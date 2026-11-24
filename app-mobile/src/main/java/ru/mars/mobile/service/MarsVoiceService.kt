package ru.mars.mobile.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ru.mars.mobile.R
import ru.mars.mobile.service.VoiceNotificationPolicy.ActionType
import ru.mars.mobile.service.VoiceNotificationPolicy.Phase

/**
 * Порт к голосовому движку — ровно те действия, которые нужны циклу разговора в фоне.
 *
 * Зачем порт, а не прямой вызов захвата и TTS: сервис отвечает за жизненный цикл foreground-а
 * (пять секунд на `startForeground`, уведомление, освобождение микрофона), а не за звук.
 * Движок собирает приложение из уже существующих частей — [ru.mars.mobile.HandsFreeCapture]
 * (микрофон), [ru.mars.mobile.AsrEnginePort] / wake-word детектор (речь) и
 * [ru.mars.mobile.LocalVoiceTts] либо офлайн-синтез (озвучка), — и передаёт его сервису через
 * [MarsVoiceService.installEngineFactory]. Благодаря этому сервис не тянет нативную библиотеку
 * в тестах и не знает, какой именно движок под ним.
 *
 * Правила для реализации:
 * - все методы вызываются с ГЛАВНОГО потока, но обязаны возвращаться быстро: открытие
 *   микрофона и загрузка модели идут на своих потоках внутри реализации;
 * - события [Events] можно отдавать с любого потока — сервис сам переложит их на главный;
 * - `start*` возвращает `false`, если сделать нечего (нет модели, микрофон занят, нет
 *   разрешения). Сервис на `false` не падает, а завершает разговор с внятной причиной;
 * - [release] обязан закрыть микрофон и остановить звук. Освобождать порт синтеза он не обязан:
 *   порт обычно принадлежит приложению (общий офлайн-движок экрана), и `release()` здесь
 *   выключил бы озвучку всему процессу. Сервис зовёт его из `onDestroy`, но собственная
 *   идемпотентность реализации всё равно нужна: `onDestroy` может прийти после явного «Стоп».
 */
interface VoiceEnginePort {

    /** Куда движок отдаёт события. Сервис ставит слушателя один раз, в `onCreate`. */
    fun attach(events: Events)

    /**
     * Открыть микрофон и начать искать ключевую фразу.
     * @return `false`, если микрофон не открылся или искать нечего (нет модели).
     */
    fun startWakeWord(): Boolean

    /** Закрыть микрофон поиска фразы. Идемпотентно. */
    fun stopWakeWord()

    /**
     * Начать реплику после ключевой фразы: тот же микрофон, но звук уже идёт в ASR.
     * @return `false`, если захват не поднялся.
     */
    fun beginUtterance(): Boolean

    /** Закончить реплику и закрыть микрофон. Идемпотентно. */
    fun endUtterance()

    /**
     * Озвучить ответ приложения.
     * @return `false`, если говорить нечем (нет движка синтеза) или текст пуст после фильтрации.
     */
    fun speak(text: String): Boolean

    /** Немедленная тишина. Идемпотентно. */
    fun stopSpeaking()

    /** Освободить микрофон и синтез. Повторный вызов безопасен. */
    fun release()

    /** События движка. Все методы необязательны: реализация переопределяет нужные. */
    interface Events {
        /** Найдена ключевая фраза; дальше сервис открывает реплику. */
        fun onWakeWordDetected(phrase: String) {}

        /** Живой транскрипт реплики (уведомление на него не перерисовывается). */
        fun onPartial(text: String) {}

        /** Реплика распознана целиком — сервис отдаёт её приложению. */
        fun onUtterance(text: String) {}

        /** Звук ответа пошёл (`true`) или кончился (`false`) — фаза SPEAKING живёт по нему. */
        fun onSpeakingChanged(speaking: Boolean) {}

        /** Движок сломался: микрофон занят, модель битая, синтез отказал. */
        fun onFailed(reason: String) {}
    }
}

/**
 * Фабрика движка. Ставится приложением до [MarsVoiceService.start]; `create` зовётся из
 * `onCreate` сервиса, то есть на главном потоке — тяжёлую работу делать здесь нельзя.
 */
fun interface VoiceEngineFactory {
    fun create(context: Context): VoiceEnginePort?
}

/**
 * Куда сервис сообщает о происходящем в приложение.
 *
 * Колбэк — единственный канал, по которому сервис говорит с приложением: сам он диалог с
 * провайдером НЕ ведёт. Распознанную реплику он отдаёт в [onUtterance], приложение отправляет
 * её провайдеру и возвращает готовый ответ через [MarsVoiceService.deliverAnswer] — сервис его
 * только озвучивает.
 *
 * Все методы вызываются на главном потоке.
 */
interface VoiceCallback {

    /** Фаза изменилась: экранная кнопка и строка статуса рисуются по ней. */
    fun onPhaseChanged(phase: Phase) {}

    /** Распознанная реплика. Приложение обязано ответить [MarsVoiceService.deliverAnswer]. */
    fun onUtterance(text: String) {}

    /** Разговор не пошёл или оборвался: причина для тоста/строки статуса. */
    fun onError(reason: String) {}
}

/**
 * Foreground-сервис голосового разговора: держит микрофон и озвучку, когда экран погашен.
 *
 * ## Что делает сервис
 * Один цикл, ровно четыре фазы ([VoiceNotificationPolicy.Phase]):
 *
 * ```
 * WAKE (микрофон открыт, ищем «Марс, слушай»; в Phase это IDLE)
 *   → onWakeWordDetected → LISTENING (beginUtterance)
 *   → onUtterance        → THINKING  (текст уходит в приложение)
 *   → deliverAnswer      → SPEAKING  (engine.speak)
 *   → onSpeakingChanged(false) → снова WAKE
 * ```
 *
 * Возврат в WAKE — единственное, что делает разговор разговором. Он идёт по трём путям,
 * и все три заканчиваются в [armWakeWord]:
 * 1. движок сообщил «дочитал» (`onSpeakingChanged(false)`, он же сигнал офлайн-синтеза);
 * 2. страховка [speechWatchdog] — если «дочитал» не пришло вовсе (так и было: колбэк конца
 *    реплики у офлайн-порта никто не подписывал, и сервис навсегда застревал в SPEAKING);
 * 3. таймаут ответа приложения ([answerTimeout]).
 * Поэтому второй и следующие вопросы работают даже при полном молчании движка.
 *
 * **Диалог с провайдером сервис не ведёт.** Он не знает ни модели, ни истории, ни сети: получил
 * текст — отдал приложению через [VoiceCallback.onUtterance], получил ответ — озвучил. Поэтому
 * сервис не держит ни одного сетевого клиента и не мешает экрану вести тот же диалог.
 *
 * ## Почему foreground и почему `microphone`
 * Android 14 (targetSdk 34) требует для записи в фоне одновременно:
 * 1) разрешение `FOREGROUND_SERVICE_MICROPHONE` в манифесте;
 * 2) `android:foregroundServiceType="microphone"` у `<service>`;
 * 3) вызов [ServiceCompat.startForeground] с `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
 *
 * Без (1) и (2) `startForeground` бросает `SecurityException`, и процесс убивается — поэтому
 * объявление в манифесте обязательно, а сервис на отказ не падает, а честно завершается
 * ([fail] → [VoiceCallback.onError]).
 *
 * ## Когда сервис можно стартовать (Android 12+/14+)
 * `startForegroundService` из фона запрещён: система бросает
 * `ForegroundServiceStartNotAllowedException` (это `IllegalStateException`), а на Android 14+
 * запуск типа `microphone` дополнительно требует «использования в момент запуска» — то есть
 * приложение должно быть на переднем плане. Практическое правило:
 *
 * - стартовать ТОЛЬКО из активного экрана по явному действию человека — кнопка «Разговор в фоне»
 *   в голосовой панели; [start] возвращает `false`, если система отказала, и экран показывает
 *   это как «не удалось», а не падает;
 * - НЕ стартовать из `BroadcastReceiver`, `JobService`, push-сообщения, `AlarmManager` и
 *   `BOOT_COMPLETED` — это фон, и запуск будет отвергнут;
 * - НЕ стартовать из `onPause`/`onStop` экрана: к этому моменту приложение уже не на переднем
 *   плане, и Android 14 откажет;
 * - при повторном `onStartCommand` (кнопки уведомления) сервис уже в foreground, поэтому
 *   ограничение не действует.
 *
 * ## Освобождение ресурсов
 * `onDestroy` освобождает микрофон и TTS ([VoiceEnginePort.release]) и снимает уведомление.
 * Явное «Стоп» идёт тем же путём ([shutdown]), так что после завершения разговора индикатор
 * записи гаснет независимо от того, как сервис закончился.
 *
 * ## Что сервис НЕ делает
 * - не решает, можно ли вообще держать микрофон (заряд, «не беспокоить», маршрут аудио) —
 *   это политика сессии в приложении;
 * - не качает и не грузит модели — это движок;
 * - не запускает wake-word в обход разрешения: без `RECORD_AUDIO` старт отклоняется.
 */
class MarsVoiceService : Service() {

    private val main: Handler = Handler(Looper.getMainLooper())

    /** Движок текущей сессии. `null` — фабрики нет или она отказала. */
    private var engine: VoiceEnginePort? = null

    /** Текущая фаза; меняется только на главном потоке. */
    private var phase: Phase = Phase.IDLE

    /**
     * Причина, по которой цикл не идёт (нет разрешения, микрофон занят). `null` — всё штатно.
     * Живёт отдельно от [phase], потому что причина переживает смену фазы и сбрасывается только
     * успешным действием.
     */
    private var notice: String? = null

    /**
     * `true`, пока [ServiceCompat.startForeground] не подтвердил foreground. До этого момента
     * событий движка не обрабатываем: `startForegroundService` ещё может быть отменён системой,
     * а уведомления без foreground не существует.
     */
    private var startedForeground: Boolean = false

    /** Микрофон открыт на поиск ключевой фразы: повторный `start` не должен его переоткрывать. */
    private var wakeWordArmed: Boolean = false

    /** Ответ пришёл (или разговор свернулся): таймаут ожидания больше не нужен. */
    private val answerTimeout = Runnable { armWakeWord("answer timeout") }

    /**
     * Страховка конца озвучки.
     *
     * Штатный путь возврата — `onSpeakingChanged(false)` от движка. Но полагаться на одно
     * событие нельзя: у офлайн-порта синтеза этот колбэк живёт в конструкторе бэкенда и никем
     * не подписан, поэтому «дочитал» не приходило НИКОГДА, и сервис навсегда оставался в
     * SPEAKING — микрофон открыт, а фразу он не ищет. Второй вопрос в такой сессии был
     * невозможен. Поэтому по расчётной длительности реплики фаза возвращается в WAKE сама.
     */
    private val speechWatchdog = Runnable {
        if (phase != Phase.SPEAKING) return@Runnable
        Log.w(TAG, "phase=IDLE (speech end was not reported, watchdog)")
        armWakeWord("speech watchdog")
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Фабрика может бросить (нет модели, битые нативные библиотеки) — сервис обязан
        // подняться в foreground даже без движка, иначе система убьёт процесс.
        engine = runCatching { engineFactory?.create(this) }.getOrNull()
        engine?.attach(engineEvents)
        if (engine == null) Log.w(TAG, "voice: engine factory is not installed")
        Log.i(TAG, "service created, engine=${engine != null}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Первое и безусловное действие: обещание startForegroundService() живёт ~5 секунд,
        // после чего Android убивает процесс. Поэтому foreground поднимается ДО разбора action.
        if (!promoteToForeground()) return START_NOT_STICKY

        when {
            VoiceNotificationPolicy.isStop(intent?.action) -> shutdown()

            VoiceNotificationPolicy.isAnswer(intent?.action) ->
                speakAnswer(intent?.getStringExtra(VoiceNotificationPolicy.EXTRA_ANSWER))

            else -> beginConversation()
        }
        // Никакого sticky-перезапуска: разговор — это живой микрофон и живой человек рядом.
        // Восстановленный системой сервис открыл бы микрофон без ведома владельца.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Смахивание приложения из недавних НЕ останавливает разговор: ровно ради этого случая
     * сервис и существует (человек заблокировал экран и убрал приложение). Процесс живёт,
     * колбэк приложения — тоже, поэтому диалог продолжается.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Микрофон и TTS освобождаются здесь при любом сценарии: явный «Стоп», отказ системы,
        // kill задачи, падение движка. Повторный вызов безопасен.
        releaseVoice()
        main.removeCallbacksAndMessages(null)
        removeNotification()
        startedForeground = false
        running = false
        // Микрофон свободен: экранная кнопка снова вправе открывать свой захват.
        conversationActive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ голосовой цикл

    /**
     * Начать разговор: открыть микрофон на поиск фразы.
     *
     * Повторный вызов (например, второй `startForegroundService` по кнопке) не переоткрывает
     * микрофон и не сбрасывает текущую фазу.
     */
    private fun beginConversation(attempt: Int = 0) {
        val current = engine
        if (current == null) {
            fail(NOTICE_NO_ENGINE)
            return
        }
        if (!hasMicPermission()) {
            fail(NOTICE_NO_PERMISSION)
            return
        }
        if (wakeWordArmed || phase != Phase.IDLE) {
            updateNotification()
            return
        }
        if (!runCatching { current.startWakeWord() }.getOrDefault(false)) {
            // Микрофон мог ещё не отпустить экранный захват: в процессе разрешён только один
            // `AudioRecord`, и второй отвергается сразу. Пара попыток вместо мгновенного
            // «микрофон недоступен» — иначе включение фона сразу после экранного ввода
            // выглядело бы поломкой.
            if (attempt < WAKE_RETRY_ATTEMPTS) {
                Log.w(TAG, "WAKE start retry ${attempt + 1}/$WAKE_RETRY_ATTEMPTS")
                main.postDelayed({ beginConversation(attempt + 1) }, WAKE_RETRY_MS)
                return
            }
            fail(NOTICE_MIC_UNAVAILABLE)
            return
        }
        wakeWordArmed = true
        // Микрофон в руках сервиса: экранный голосовой ввод обязан уступить, иначе второй
        // AudioRecord в том же процессе отберёт звук у фонового поиска фразы.
        conversationActive = true
        setPhase(Phase.IDLE)
        Log.i(TAG, "phase=WAKE (conversation started)")
    }

    /** Ключевая фраза найдена: тот же микрофон переключается на запись реплики. */
    private fun beginUtterance(phrase: String) {
        val current = engine ?: return
        wakeWordArmed = false
        Log.i(TAG, "wake word detected: $phrase")
        if (!runCatching { current.beginUtterance() }.getOrDefault(false)) {
            fail(NOTICE_MIC_UNAVAILABLE)
            return
        }
        setPhase(Phase.LISTENING)
    }

    /** Реплика распознана: отдаём приложению и ждём ответ. Микрофон при этом закрыт. */
    private fun onUtteranceReady(text: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            // «Не расслышал» — не ошибка сервиса: просто возвращаемся к поиску фразы.
            Log.i(TAG, "phase=WAKE (empty utterance)")
            armWakeWord("empty utterance")
            return
        }
        Log.i(TAG, "heard phrase: $body")
        setPhase(Phase.THINKING)
        main.removeCallbacks(answerTimeout)
        // Ответа может не быть вовсе (приложение упало, сеть отвалилась): тогда разговор
        // возвращается к поиску фразы, а не висит в «Думаю» с живым foreground-сервисом.
        main.postDelayed(answerTimeout, ANSWER_TIMEOUT_MS)
        val target = callback
        if (target == null) {
            Log.w(TAG, "phase=IDLE (no callback registered)")
            armWakeWord("no callback")
            return
        }
        runCatching { target.onUtterance(body) }
    }

    /** Приложение вернуло ответ: озвучиваем. Пустой ответ — сразу назад к поиску фразы. */
    private fun speakAnswer(text: String?) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) {
            Log.i(TAG, "phase=IDLE (empty answer)")
            armWakeWord("empty answer")
            return
        }
        main.removeCallbacks(answerTimeout)
        val current = engine ?: run {
            fail(NOTICE_NO_ENGINE)
            return
        }
        // Опоздавший ответ (пришёл уже после таймаута): в этот момент сервис снова ищет фразу,
        // и звук ответа попал бы прямо в детектор. Гасим поиск на время озвучки — после неё
        // [armWakeWord] поднимет его заново.
        if (wakeWordArmed) {
            wakeWordArmed = false
            runCatching { current.stopWakeWord() }
        }
        if (!runCatching { current.speak(body) }.getOrDefault(false)) {
            // Сказать нечем — это НЕ повод выключать сервис: владелец включил прослушивание,
            // и следующая фраза должна быть услышана даже если синтез не поднялся. Раньше
            // здесь был fail() → shutdown(), и один отказ TTS убивал весь фоновый режим.
            Log.w(TAG, "phase=IDLE ($NOTICE_TTS_UNAVAILABLE), still listening")
            runCatching { callback?.onError(NOTICE_TTS_UNAVAILABLE) }
            armWakeWord("tts unavailable")
            return
        }
        Log.i(TAG, "speaking (${body.length} chars)")
        setPhase(Phase.SPEAKING)
        // Страховка: если движок не пришлёт «дочитал», фаза всё равно вернётся в WAKE.
        main.removeCallbacks(speechWatchdog)
        main.postDelayed(speechWatchdog, speechWatchdogMs(body))
    }

    /** Звук кончился (дочитал, заглушили, движок упал): снова слушаем ключевую фразу. */
    private fun onSpeakingFinished() {
        main.removeCallbacks(speechWatchdog)
        if (phase == Phase.SPEAKING) {
            Log.i(TAG, "speaking finished")
            armWakeWord("answer spoken")
        }
    }

    /**
     * Вернуться к ожиданию ключевой фразы.
     *
     * Микрофон открывается заново (а не «продолжает работать»): между репликой и озвучкой захват
     * закрывался, иначе собственный динамик попадал бы в ASR.
     *
     * @param reason почему вернулись — уходит в лог, чтобы прогон читался без догадок.
     */
    private fun armWakeWord(reason: String, attempt: Int = 0) {
        main.removeCallbacks(answerTimeout)
        main.removeCallbacks(speechWatchdog)
        val current = engine ?: run {
            shutdown()
            return
        }
        if (!runCatching { current.startWakeWord() }.getOrDefault(false)) {
            // Микрофон отпускается не мгновенно (закрытие AudioRecord, чужая сессия записи), а
            // один отказ не должен выключать сервис: владелец ждёт, что после ответа Марс снова
            // слушает. Поэтому несколько коротких попыток, и только потом честная ошибка.
            if (attempt < WAKE_RETRY_ATTEMPTS) {
                Log.w(TAG, "WAKE retry ${attempt + 1}/$WAKE_RETRY_ATTEMPTS ($reason)")
                main.postDelayed({ armWakeWord(reason, attempt + 1) }, WAKE_RETRY_MS)
                return
            }
            fail(NOTICE_MIC_UNAVAILABLE)
            return
        }
        wakeWordArmed = true
        setPhase(Phase.IDLE)
        Log.i(TAG, "back to WAKE ($reason)")
    }

    /**
     * Страховка длительности озвучки: 170 мс на символ плюс запас на запуск синтеза.
     *
     * Штатный путь конца реплики — `onSpeakingChanged(false)` от движка (сигнал
     * [ru.mars.mobile.OfflineVoiceSignal]). Таймер — только страховка, и он обязан быть заведомо
     * длиннее реальной озвучки: было 110 мс на символ (~9 символов в секунду), а Piper при
     * `lengthScale=1.08` говорит медленнее — ответ обрывался на середине. Теперь оценка
     * завышена примерно вдвое, и обрыв по таймеру возможен только при зависшем движке.
     */
    private fun speechWatchdogMs(text: String): Long =
        (SPEECH_WATCHDOG_BASE_MS + text.length * SPEECH_MS_PER_CHAR).coerceAtMost(MAX_SPEECH_WATCHDOG_MS)

    // ------------------------------------------------------------------ состояние и колбэки

    private fun setPhase(next: Phase, detail: String? = null) {
        val changed = phase != next || notice != detail
        phase = next
        notice = detail
        if (!changed) return
        updateNotification()
        runCatching { callback?.onPhaseChanged(next) }
    }

    /**
     * Разговор не пошёл: сообщаем приложению и уходим. Уведомление при этом снимается —
     * показывать «нет микрофона» в шторке бессмысленно, объяснить это должен экран.
     */
    private fun fail(reason: String) {
        Log.w(TAG, "phase=IDLE ($reason)")
        runCatching { callback?.onError(reason) }
        shutdown()
    }

    /** Единая точка завершения: снять уведомление, освободить звук, остановить сервис. */
    private fun shutdown() {
        main.removeCallbacksAndMessages(null)
        releaseVoice()
        removeNotification()
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        running = false
        // Сервис уходит: микрофон освобождён, флаг снимается здесь же, не дожидаясь onDestroy
        // (stopSelf асинхронен, а экранная кнопка не должна ждать лишний кадр).
        conversationActive = false
        stopSelf()
    }

    /** Освободить микрофон и синтез ровно один раз. */
    private fun releaseVoice() {
        // Микрофон свободен — флаг снимается здесь, в единственной точке освобождения:
        // и `shutdown`, и `onDestroy` проходят через неё, поэтому залипнуть в `true` он не может.
        conversationActive = false
        val current = engine ?: return
        engine = null
        wakeWordArmed = false
        // Порядок важен: сначала закрыть захват (индикатор записи гаснет сразу), потом звук,
        // и только потом отдать движок целиком — release добьёт всё, что осталось.
        runCatching { current.stopWakeWord() }
        runCatching { current.endUtterance() }
        runCatching { current.stopSpeaking() }
        runCatching { current.release() }
    }

    private val engineEvents = object : VoiceEnginePort.Events {
        override fun onWakeWordDetected(phrase: String) = post {
            // Событие могло прийти с потока чтения микрофона и опоздать: фаза уже другая.
            if (!startedForeground || phase != Phase.IDLE) return@post
            beginUtterance(phrase)
        }

        /** Живой транскрипт не нужен: уведомление не перерисовывается на каждый токен. */
        override fun onPartial(text: String) = Unit

        override fun onUtterance(text: String) = post {
            if (!startedForeground) return@post
            onUtteranceReady(text)
        }

        override fun onSpeakingChanged(speaking: Boolean) = post {
            if (!startedForeground || speaking) return@post
            onSpeakingFinished()
        }

        override fun onFailed(reason: String) = post {
            if (!startedForeground) return@post
            fail(reason)
        }
    }

    // ------------------------------------------------------------------ уведомление

    /**
     * Поднять foreground. Возвращает `false`, если система отказала (нет разрешения
     * `FOREGROUND_SERVICE_MICROPHONE`, тип не объявлен в манифесте, приложение не на переднем
     * плане) — в этом случае сервис обязан уйти, а не падать.
     */
    private fun promoteToForeground(): Boolean {
        if (startedForeground) return true
        return try {
            // ServiceCompat сам решает, как вызвать платформу: на API 34+ передаёт тип
            // (обязателен для microphone), ниже — обычный startForeground. minSdk 28,
            // поэтому один вызов закрывает все версии.
            ServiceCompat.startForeground(
                this,
                VoiceNotificationPolicy.NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            startedForeground = true
            running = true
            // Флаг держится ровно столько, сколько жив сервис: пока он в foreground, микрофон
            // считает своим он, а не экран. Раньше флаг ставился только после удачного
            // `startWakeWord`, и любой сбой между двумя этими точками оставлял экранную кнопку
            // в уверенности, что микрофон свободен.
            conversationActive = true
            true
        } catch (denied: SecurityException) {
            // Android 14+: тип microphone без разрешения или без «использования в момент
            // запуска». Процесс при этом ещё жив — уходим тихо и говорим об этом приложению.
            Log.e(TAG, "voice: startForeground(microphone) denied", denied)
            runCatching { callback?.onError(NOTICE_FOREGROUND_DENIED) }
            running = false
            stopSelf()
            false
        } catch (notAllowed: IllegalStateException) {
            Log.e(TAG, "voice: startForeground rejected", notAllowed)
            runCatching { callback?.onError(NOTICE_FOREGROUND_DENIED) }
            running = false
            stopSelf()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (!startedForeground) return
        if (!canPostNotifications()) return // Android 13+ без POST_NOTIFICATIONS: шторка молчит
        try {
            NotificationManagerCompat.from(this)
                .notify(VoiceNotificationPolicy.NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Разрешение отозвали в рантайме — уведомление не критично для работы сервиса.
        }
    }

    private fun removeNotification() {
        runCatching {
            NotificationManagerCompat.from(this)
                .cancel(VoiceNotificationPolicy.NOTIFICATION_ID)
        }
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, VoiceNotificationPolicy.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(VoiceNotificationPolicy.title(phase))
            .setContentText(VoiceNotificationPolicy.text(phase, notice))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        openIntent()?.let(builder::setContentIntent)

        val actions = VoiceNotificationPolicy.actions(phase)
        actions.forEach { action ->
            val intent = when (action.type) {
                ActionType.STOP -> stopIntent()
                ActionType.OPEN -> openIntent()
            } ?: return@forEach
            // Подпись действия — она же его accessibility-метка: отдельного contentDescription
            // у действия уведомления платформа не даёт.
            builder.addAction(iconFor(action.type), action.label, intent)
        }
        return builder.build()
    }

    /** Кнопка «Стоп»: action-строка, которую разберёт [onStartCommand]. */
    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, MarsVoiceService::class.java).setAction(VoiceNotificationPolicy.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Кнопка «Открыть»: launch-Intent приложения, а не конкретная Activity.
     *
     * Так сервис не знает про навигацию экранов: приложение само решит, куда вернуть человека
     * (экран разговора или список диалогов), и не потеряет стек, если Activity уже открыта.
     */
    private fun openIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun iconFor(type: ActionType): Int = when (type) {
        ActionType.STOP -> R.drawable.ic_stop
        ActionType.OPEN -> 0 // «Открыть» без иконки: шторка не любит лишних значков
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(VoiceNotificationPolicy.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                VoiceNotificationPolicy.CHANNEL_ID,
                VoiceNotificationPolicy.CHANNEL_NAME,
                // LOW: канал не должен звенеть на каждую смену фазы — разговор идёт молча.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = VoiceNotificationPolicy.CHANNEL_DESCRIPTION
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean = hasMicPermission(this)

    /** Колбэк движка может прийти с потока чтения микрофона: состояние меняем только на главном. */
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /**
         * Тег ровно `MarsVoice`: по нему фильтруется лог жизненного цикла
         * (`phase=WAKE`, `heard phrase`, `speaking`, `back to WAKE`). Раньше строки шли с
         * префиксом `voice:` и тегом класса, и прогон с устройства приходилось собирать
         * из разных мест.
         */
        private const val TAG = "MarsVoice"

        /** Ожидание ответа приложения: дольше двух минут «Думаю» — это уже зависший разговор. */
        private const val ANSWER_TIMEOUT_MS = 120_000L

        /** Верхняя граница страховки озвучки: длинный ответ всё равно не читается дольше. */
        private const val MAX_SPEECH_WATCHDOG_MS = 240_000L

        /** Запас страховки на запуск синтеза (загрузка модели, первый кадр). */
        private const val SPEECH_WATCHDOG_BASE_MS = 4_000L

        /**
         * Оценка темпа Piper: 170 мс на символ. 110 мс (прежнее значение) — это ~9 символов в
         * секунду, быстрее реальной русской речи при `lengthScale=1.08`, из-за чего страховка
         * срабатывала раньше конца реплики и обрывала ответ.
         */
        private const val SPEECH_MS_PER_CHAR = 170L

        /** Попыток вернуться в прослушивание после ответа, прежде чем сообщить об ошибке. */
        private const val WAKE_RETRY_ATTEMPTS = 5

        /** Пауза между попытками вернуться в прослушивание. */
        private const val WAKE_RETRY_MS = 1_200L

        private const val REQUEST_OPEN = 0x4D5601
        private const val REQUEST_STOP = 0x4D5602

        /** Движка нет: приложение не установило фабрику или она отказала. */
        const val NOTICE_NO_ENGINE: String = "Голосовой движок недоступен"

        /** Нет `RECORD_AUDIO`: запись в фоне без него запрещена и на уровне системы. */
        const val NOTICE_NO_PERMISSION: String = "Нет разрешения на микрофон"

        /** Микрофон занят другим приложением или не открылся. */
        const val NOTICE_MIC_UNAVAILABLE: String = "Микрофон недоступен"

        /** Нечем говорить: движок синтеза отказал. */
        const val NOTICE_TTS_UNAVAILABLE: String = "Озвучка недоступна на этом устройстве"

        /** Система не разрешила foreground с типом microphone (см. KDoc класса). */
        const val NOTICE_FOREGROUND_DENIED: String =
            "Система не разрешила фоновый микрофон — запустите разговор из приложения"

        /** Фабрика движка; ставится приложением до [start]. */
        @Volatile
        private var engineFactory: VoiceEngineFactory? = null

        /** Колбэк в приложение; `null` — приложение не подписано (сервис всё равно работает). */
        @Volatile
        var callback: VoiceCallback? = null
            private set

        @Volatile
        private var running: Boolean = false

        /** Сервис живёт в foreground прямо сейчас. */
        val isRunning: Boolean get() = running

        /**
         * Сервис держит микрофон и ведёт разговор прямо сейчас.
         *
         * Единственная причина существования флага: экранный голосовой ввод
         * ([ru.mars.mobile.LocalVoiceController]) не должен открывать ВТОРОЙ захват в том же
         * процессе. Два `AudioRecord` (сервисный и экранный) делят микрофон так, что фоновый
         * поиск фразы получает тишину, — поэтому экран обязан уступить.
         *
         * Ставится `true` вместе с успешным `startForeground` в `promoteToForeground` и снимается
         * `false` в `releaseVoice` — единственной точке освобождения микрофона (через неё идут
         * и `shutdown`, и `onDestroy`). Так флаг не может залипнуть: он живёт ровно столько,
         * сколько жив сервис, а не «пока не сломается что-то посередине».
         * Снаружи только чтение: менять состояние вправе только сам сервис.
         */
        @Volatile
        var conversationActive: Boolean = false
            private set

        /**
         * Поставить фабрику движка. Зовётся один раз при старте приложения (или при входе на
         * экран разговора) — до [start], иначе сервис поднимется без микрофона.
         */
        fun installEngineFactory(factory: VoiceEngineFactory?) {
            engineFactory = factory
        }

        /**
         * Подписать приложение на события. Хранится в процессе, поэтому экран может
         * пересоздаваться: колбэк переустанавливается в `onStart`/`onResume` и снимается в
         * `onDestroy` экрана ([setCallback] с `null`).
         */
        fun setCallback(callback: VoiceCallback?) {
            this.callback = callback
        }

        /**
         * Начать разговор в фоне.
         *
         * ВАЖНО (Android 12+, жёстче на 14+): звать только из активного экрана по действию
         * человека — кнопка. Из фона (`onPause`, `BroadcastReceiver`, `JobService`, push)
         * система бросит `ForegroundServiceStartNotAllowedException`, а на 14+ ещё и откажет в
         * типе `microphone` без «использования в момент запуска».
         *
         * @return `false`, если старт заведомо невозможен: нет фабрики движка или нет
         *   `RECORD_AUDIO`; либо если система отказала в запуске. Экран обязан показать это как
         *   «не удалось», а не молчать.
         */
        fun start(context: Context): Boolean {
            if (engineFactory == null) {
                Log.w(TAG, "voice: start ignored, engine factory is not installed")
                return false
            }
            if (!hasMicPermission(context)) {
                Log.w(TAG, "voice: start ignored, RECORD_AUDIO is not granted")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MarsVoiceService::class.java),
                )
                true
            } catch (notAllowed: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) — старт из фона.
                Log.e(TAG, "voice: foreground start not allowed", notAllowed)
                false
            } catch (denied: SecurityException) {
                Log.e(TAG, "voice: foreground start denied", denied)
                false
            }
        }

        /** Явное завершение разговора: `onDestroy` освободит микрофон и снимет уведомление. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MarsVoiceService::class.java))
            }
        }

        /**
         * Ответ приложения на распознанную реплику — сервис его озвучит.
         *
         * Зовётся из [VoiceCallback.onUtterance] после того, как диалог с провайдером завершён.
         * Сервис уже в foreground, поэтому `startService` (а не `startForegroundService`)
         * разрешён и не требует новых причин запуска.
         *
         * @return `false`, если сервис не запущен: ответ озвучивать некому.
         */
        fun deliverAnswer(context: Context, text: String): Boolean {
            if (!running) return false
            val intent = Intent(context, MarsVoiceService::class.java)
                .setAction(VoiceNotificationPolicy.ACTION_ANSWER)
                .putExtra(VoiceNotificationPolicy.EXTRA_ANSWER, text)
            return runCatching { context.startService(intent) }.isSuccess
        }

        private fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
