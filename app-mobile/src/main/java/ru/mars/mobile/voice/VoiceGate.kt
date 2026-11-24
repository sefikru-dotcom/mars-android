package ru.mars.mobile.voice

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ru.mars.mobile.AudioRoutePolicy
import ru.mars.mobile.asr.ModelStore

/**
 * Мост между Android и чистой политикой микрофона: собирает [VoiceSessionEnvironment] из
 * реальных системных сервисов и отвечает на вопрос «можно ли открыть микрофон».
 *
 * Зачем отдельный класс. [VoiceSessionPolicy] написана и покрыта JVM-тестами, но на телефоне её
 * никто не спрашивал: движок открывал микрофон сам, поэтому запись начиналась во время звонка,
 * при включённом «не беспокоить» и на 10% заряда. Политика при этом не виновата — ей просто не
 * передавали окружение. Здесь ровно эта передача: `Android → снимок значений → policy.gate/tick`.
 *
 * ## Что здесь есть и чего нет
 * - политика НЕ переписана: решения, тексты отказов ([VoiceSessionRejection.notice]) и таймауты
 *   живут в [VoiceSessionPolicy] и берутся оттуда как есть;
 * - движок не импортируется: класс не знает ни про [ru.mars.mobile.LocalVoiceEngine], ни про
 *   `HandsFreeCapture`. Он не открывает микрофон, а только разрешает или запрещает это делать —
 *   поэтому подключается к любому движку одной строкой (см. KDoc [openMic]);
 * - ничего не качается и не пишется на диск: чтение модели — это `File.isFile`, а не загрузка.
 *
 * ## Почему снимок собирается здесь, а не в политике
 * `BatteryManager`, `PowerManager`, `KeyguardManager`, `NotificationManager` и `AudioManager` —
 * это `android.*`, которого в политике быть не должно (иначе её нельзя проверить на JVM). Тот же
 * снимок для пути [VoiceSessionController] собирает `AndroidVoiceSessionEnvironment` из
 * `VoiceSessionController.kt`; здесь он собран заново намеренно: гейт подключается к сервису
 * независимо от контроллера, и падение/удаление второго пути не должно ломать первый.
 *
 * ## Правило умолчаний при сбое опроса
 * Любой системный вызов может бросить (`SecurityException` на отозванном разрешении,
 * `RuntimeException` из аудиостека при смерти `audioserver`) или вернуть мусор. Поэтому каждый
 * источник опрашивается отдельно, исключение глотается с записью в лог, и подставляется
 * значение «ничего не известно». Умолчания выбраны так же, как в [VoiceSessionEnvironment]:
 * неизвестный заряд — `-1` (политика считает его достаточным и НЕ блокирует микрофон из-за
 * сбоя чтения батареи), неизвестный экран — «включён», неизвестный маршрут — `UNKNOWN`.
 * Два исключения сделаны сознательно в сторону запрета: не подтверждённое разрешение на микрофон
 * и не подтверждённая модель считаются отсутствующими — открыть микрофон «на всякий случай»
 * хуже, чем не открыть.
 *
 * ## Потоки
 * Класс не синхронизирован: как и политика, он рассчитан на главный поток (движок зовёт его из
 * `startWakeWord`, тик — из главного `Handler`). Чтение системных сервисов дешёвое, но это всё же
 * IPC в `system_server`, поэтому звать [environment] на каждый кадр аудио нельзя — только на
 * старте и на тике (раз в ~10 с).
 *
 * @param context любой контекст: хранится `applicationContext`, утечки нет;
 * @param policy чистая политика; передаётся, если хост держит свою (например, с другими порогами);
 * @param foregroundServiceRunning поднят ли foreground-сервис с типом `microphone`. По умолчанию
 *   `true`: единственный штатный вызывающий — движок внутри [ru.mars.mobile.service.MarsVoiceService],
 *   который поднимает foreground ДО первого `startWakeWord`. Хост вне сервиса обязан передать
 *   честный поставщик, иначе политика разрешит фоновую запись, которую система всё равно отклонит;
 * @param otherCaptureActive микрофон занят ДРУГИМ захватом приложения (голосовой диалог в чате).
 *   Свою сессию сюда передавать нельзя — политика закрыла бы её саму;
 * @param modelInstalled лежит ли модель распознавания на диске. `null` — проверять через
 *   [ModelStore] в `<filesDir>/asr` (та же раскладка, что у [ru.mars.mobile.asr.WakeWordDetector]);
 * @param clock монотонные миллисекунды; в тестах и на стенде подменяется.
 */
class VoiceGate(
    context: Context,
    val policy: VoiceSessionPolicy = VoiceSessionPolicy(),
    private val foregroundServiceRunning: () -> Boolean = { true },
    private val otherCaptureActive: () -> Boolean = { false },
    modelInstalled: (() -> Boolean)? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val app: Context = context.applicationContext

    /** Проверка модели: по умолчанию — раскладка `<filesDir>/asr` (см. [ModelStore.rootForAppFilesDir]). */
    private val modelCheck: () -> Boolean = modelInstalled ?: {
        ModelStore(ModelStore.rootForAppFilesDir(app.filesDir)).isInstalled()
    }

    /** Последний собранный снимок: для диагностики и логов, когда решение кажется странным. */
    var lastEnvironment: VoiceSessionEnvironment? = null
        private set

    /** Фаза сессии — то же, что видит политика. */
    val phase: VoiceSessionPhase get() = policy.phase

    /** Последняя причина отказа (в том числе та, которой закрылась сессия). */
    val lastRejection: VoiceSessionRejection? get() = policy.lastRejection

    /** Готовый текст последнего отказа для человека; `null` — отказов не было. */
    val lastNotice: String? get() = policy.lastRejection?.notice

    // --- ворота и тик -------------------------------------------------------------------------

    /**
     * Можно ли открыть микрофон прямо сейчас. Чистый вопрос: фазу не меняет, паузу не назначает.
     *
     * Годится и для проверки перед стартом, и для UI («почему кнопка неактивна»).
     */
    fun canOpenMic(nowMs: Long): VoiceSessionDecision = policy.gate(environment(), nowMs)

    /** То же по монотонным часам класса. */
    fun canOpenMic(): VoiceSessionDecision = canOpenMic(clock())

    /**
     * Ворота + открытие сессии: политика переходит в [VoiceSessionPhase.ARMED], и с этого момента
     * её тик умеет считать лимиты длительности (без открытия фазы всегда `IDLE`, и `keepsMicOpen`
     * не появится никогда).
     *
     * @return `null`, если микрофон открывать можно; иначе причину отказа. Текст для человека —
     *   `reason.notice`, свой не придумывается.
     *
     * Подключение к движку — одна строка в начале `startWakeWord()`:
     * ```
     * gate.openMic()?.let { events?.onFailed(it.notice); return false }
     * ```
     * Она закрывает оба требования сразу: отказ возвращает `false` (микрофон не открывается) и
     * отдаёт причину текстом политики.
     */
    fun openMic(nowMs: Long): VoiceSessionRejection? {
        val decision = policy.start(environment(), nowMs)
        return (decision as? VoiceSessionDecision.Deny)?.reason
    }

    /** То же по монотонным часам класса. */
    fun openMic(): VoiceSessionRejection? = openMic(clock())

    /**
     * Полный ответ тика: окружение + лимиты длительности. Единственный метод, который стоит звать,
     * если нужны оба признака ([shouldKeepOpen] и [shouldCloseMic]) — иначе политика опрашивается
     * дважды, а тик имеет побочные эффекты (таймаут простоя сбрасывается на первом вызове).
     */
    fun tickDecision(nowMs: Long): VoiceSessionDecision = policy.tick(environment(), nowMs)

    /** То же по монотонным часам класса. */
    fun tickDecision(): VoiceSessionDecision = tickDecision(clock())

    /**
     * Микрофон ОБЯЗАН остаться открытым: политика отказала, но отказ касается реплики, а не
     * сессии (`keepsMicOpen` — «не расслышал», «слишком длинная реплика»). Так политика выражает
     * «возвращайся к ожиданию ключевой фразы, захват не гаси».
     *
     * `false` НЕ означает «закрывай микрофон»: разрешение (`Allow`) тоже даёт `false`. Закрывать —
     * по [shouldCloseMic].
     */
    fun shouldKeepOpen(nowMs: Long): Boolean {
        val decision = policy.tick(environment(), nowMs)
        return decision is VoiceSessionDecision.Deny && decision.keepsMicOpen
    }

    /** То же по монотонным часам класса. */
    fun shouldKeepOpen(): Boolean = shouldKeepOpen(clock())

    /**
     * Микрофон надо гасить прямо сейчас: окружение испортилось (звонок, «не беспокоить», разряд,
     * энергосбережение) или исчерпан лимит сессии.
     *
     * Отказ с `keepsMicOpen` сюда не попадает — по нему закрывается реплика, а захват живёт.
     */
    fun shouldCloseMic(nowMs: Long): Boolean {
        val decision = policy.tick(environment(), nowMs)
        return decision is VoiceSessionDecision.Deny && !decision.keepsMicOpen
    }

    /** То же по монотонным часам класса. */
    fun shouldCloseMic(): Boolean = shouldCloseMic(clock())

    /**
     * Причина, по которой микрофон открывать нельзя; `null` — можно.
     *
     * Отличается от [canOpenMic] только формой: здесь сразу то, что нужно для `onFailed`.
     */
    fun rejection(nowMs: Long): VoiceSessionRejection? =
        (canOpenMic(nowMs) as? VoiceSessionDecision.Deny)?.reason

    /** То же по монотонным часам класса. */
    fun rejection(): VoiceSessionRejection? = rejection(clock())

    /**
     * Закрыть сессию: фаза в [VoiceSessionPhase.IDLE], пауза назначается политикой.
     *
     * @param reason причина закрытия; `null` — человек выключил режим сам (паузы после явной
     *   остановки нет, иначе человек не смог бы включить режим обратно).
     */
    fun close(nowMs: Long = clock(), reason: VoiceSessionRejection? = null) {
        policy.stop(nowMs, reason)
    }

    // --- сборка снимка окружения --------------------------------------------------------------

    /**
     * Свежий снимок окружения из системных сервисов.
     *
     * Порядок опроса не важен, но каждый источник обёрнут отдельно: сбой одного (например,
     * `AudioManager` после падения `audioserver`) не должен обнулять остальные.
     */
    fun environment(): VoiceSessionEnvironment {
        val power = service<PowerManager>()
        val keyguard = service<KeyguardManager>()
        val audio = service<AudioManager>()
        val notifications = service<NotificationManager>()

        val (percent, charging) = battery()

        val env = VoiceSessionEnvironment(
            batteryPercent = percent,
            charging = charging,
            screenOn = screenOn(power),
            keyguardLocked = keyguardLocked(keyguard),
            audioRoute = audioRoute(audio),
            doNotDisturb = doNotDisturb(notifications),
            powerSaveMode = powerSaveMode(power),
            micPermissionGranted = micPermissionGranted(),
            modelInstalled = modelInstalled(),
            callActive = callActive(audio),
            otherCaptureActive = provider("otherCaptureActive", default = false, source = otherCaptureActive),
            foregroundServiceRunning =
                provider("foregroundServiceRunning", default = true, source = foregroundServiceRunning),
        )
        lastEnvironment = env
        return env
    }

    /**
     * Системный сервис или `null`.
     *
     * `getSystemService` может бросить на урезанных прошивках и в юнит-окружении; `null` здесь
     * означает «источник недоступен», а не «сервис выключен» — умолчание решает вызывающий.
     */
    private inline fun <reified T : Any> service(): T? = try {
        app.getSystemService(T::class.java)
    } catch (t: Throwable) {
        Log.w(TAG, "cannot get ${T::class.java.simpleName}", t)
        null
    }

    /** Экран включён. Неизвестно — считаем включённым: это не запрет, а лишь менее строгий порог. */
    private fun screenOn(power: PowerManager?): Boolean = try {
        power?.isInteractive ?: true
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read isInteractive", t)
        true
    }

    /** Устройство заблокировано. Неизвестно — «не заблокировано» (см. [screenOn]). */
    private fun keyguardLocked(keyguard: KeyguardManager?): Boolean = try {
        keyguard?.isKeyguardLocked ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read isKeyguardLocked", t)
        false
    }

    /** Режим энергосбережения. Неизвестно — выключен: иначе телефон с кривым API терял бы режим. */
    private fun powerSaveMode(power: PowerManager?): Boolean = try {
        power?.isPowerSaveMode ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read isPowerSaveMode", t)
        false
    }

    /**
     * «Не беспокоить».
     *
     * Фильтр `ALL` — уведомления разрешены; `UNKNOWN` — приложению не выдан доступ к политике
     * уведомлений, то есть про DND мы ничего не знаем (считать это запретом значило бы выключить
     * голосовой режим у всех, кто не дал доступ). Остальные фильтры (`PRIORITY`, `SILENT`,
     * `ALARMS`) — это DND в терминах политики.
     */
    private fun doNotDisturb(notifications: NotificationManager?): Boolean {
        val filter = try {
            notifications?.currentInterruptionFilter
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read currentInterruptionFilter", t)
            null
        } ?: return false
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * Маршрут вывода → [ru.mars.mobile.AudioRoute].
     *
     * Типы устройств переводит чистая [AudioRoutePolicy]; здесь только чтение списка. Пустой
     * список (сбой аудиостека) даёт `UNKNOWN`, а он политикой не блокируется — маршрут влияет на
     * barge-in, а не на право открыть микрофон.
     */
    private fun audioRoute(audio: AudioManager?): ru.mars.mobile.AudioRoute {
        val types = try {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }.orEmpty()
        } catch (t: Throwable) {
            // По контракту getDevices не бросает, но политика не должна падать из-за аудиостека.
            Log.w(TAG, "cannot read output devices", t)
            emptyList()
        }
        return AudioRoutePolicy.routeForDeviceTypes(types)
    }

    /**
     * Идёт звонок: `MODE_IN_CALL` — телефонный, `MODE_IN_COMMUNICATION` — VoIP/мессенджер.
     *
     * Другие режимы (`RINGTONE`, `IN_CALL` уведомлений, `MODE_NORMAL`) звонком не считаются:
     * входящий вызов без ответа микрофон не занимает. Неизвестно — «не звонок», потому что
     * ложный запрет выключил бы режим навсегда, а звонок политика увидит на следующем тике.
     */
    private fun callActive(audio: AudioManager?): Boolean {
        val mode = try {
            audio?.mode
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read AudioManager.mode", t)
            null
        } ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Разрешение `RECORD_AUDIO` выдано прямо сейчас (могли отозвать в настройках).
     *
     * Сбой проверки трактуется как «не выдано»: открывать микрофон, не подтвердив разрешение,
     * — единственный отказ, который здесь дороже ложного запрета.
     */
    private fun micPermissionGranted(): Boolean = try {
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        Log.w(TAG, "cannot check RECORD_AUDIO", t)
        false
    }

    /** Модель распознавания на диске. Сбой чтения — «нет модели» (искать фразу нечем). */
    private fun modelInstalled(): Boolean = try {
        modelCheck()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot check ASR model", t)
        false
    }

    /** Значение от хоста: исключение в поставщике не должно ломать сборку снимка. */
    private inline fun provider(name: String, default: Boolean, source: () -> Boolean): Boolean = try {
        source()
    } catch (t: Throwable) {
        Log.w(TAG, "$name provider failed", t)
        default
    }

    /**
     * Заряд и питание.
     *
     * `BATTERY_PROPERTY_CAPACITY` на части прошивок отдаёт мусор, поэтому есть второй путь —
     * sticky-интент `ACTION_BATTERY_CHANGED` с `EXTRA_LEVEL`/`EXTRA_SCALE`. Если оба не дали
     * процентов, возвращается `-1`: политика считает неизвестный заряд достаточным
     * ([VoiceSessionEnvironment.batteryKnown]), а не блокирует режим навсегда.
     */
    private fun battery(): Pair<Int, Boolean> {
        var percent = -1
        var charging = false
        try {
            val manager = app.getSystemService(BatteryManager::class.java)
            val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) percent = capacity
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read battery capacity", t)
        }
        try {
            val sticky: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                if (percent < 0) {
                    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) percent = level * 100 / scale
                }
                val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                charging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (t: Throwable) {
            // На Android 14+ регистрация приёмника без флага может быть отклонена: процент уже
            // прочитан первым путём, а «питание неизвестно» политика переживёт.
            Log.w(TAG, "cannot read battery intent", t)
        }
        return percent to charging
    }

    private companion object {
        const val TAG = "MarsVoiceGate"
    }
}
