package ru.mars.mobile

import android.content.Context
import android.media.audiofx.Visualizer
import kotlin.math.sqrt

/**
 * Источник громкости ИГРАЮЩЕГО аудио (не микрофона).
 *
 * ## Зачем отдельный порт
 * До этой волны орб во время речи Марса управлялся синтетической синусоидой
 * ([LocalVoiceController] → `envelopeAt`): три несоизмеримые частоты «похожие на фразы». Реальный
 * RMS снимался ТОЛЬКО с микрофона, то есть пока говорил человек. Из-за этого планета шла ровным
 * ритмом и на громком слове, и на паузе — «не как в ChatGPT», где орб захлёбывается звуком и
 * притухает в тишине.
 *
 * Порт, а не прямой вызов `Visualizer` из контроллера, по двум причинам:
 * - контроллер обязан оставаться проверяемым на JVM: тест подставляет фейк и доказывает политику
 *   «чей уровень рисуем», не поднимая Android;
 * - у платформы несколько разных отказов (эффект занят другим приложением, сессии ещё нет, нет
 *   `RECORD_AUDIO`), и все они обязаны свестись к одному сигналу — `start()` вернул `false`.
 *
 * Порт публичный (не `internal`, как в контракте волны): его принимает конструктор публичного
 * [LocalVoiceController], а Kotlin запрещает публичному конструктору раскрывать `internal`-тип.
 * Остальные порты проекта (`AsrEnginePort`, `ServerTtsClient`, `OfflineTtsPort`) публичны по той
 * же причине.
 */
interface SpeechLevelSource {

    /**
     * Подписаться на громкость конкретной сессии вывода.
     *
     * @param sessionId сессия играющего плеера/трека (`0` — сессии нет, эффект привязывать не к чему);
     * @param onLevel колбэк уровня 0..1; зовётся с чужого потока, подписчик сам решает, куда его
     *   переложить. Значения обязаны быть уже зажаты в 0..1.
     * @return `false` — источник недоступен, вызывающий обязан остаться на резерве.
     */
    fun start(sessionId: Int, onLevel: (Float) -> Unit): Boolean

    /** Отпустить эффект. Обязан быть идемпотентным: зовётся и на путях без активного старта. */
    fun stop()
}

/**
 * Арифметика уровня вывода — без Android, поэтому проверяется JVM-тестом.
 *
 * Почему отдельным объектом. Вся опасность здесь в двух местах, которые на телефоне видны только
 * как «орб дёрнулся и погас»:
 * - `waveform` у [Visualizer] — это 8-бит БЕЗ знака с центром 128, а не PCM16 со знаком. Взять
 *   байты как есть значит получить постоянную составляющую 128 и «уровень» ~1.0 в полной тишине;
 * - `Float.coerceIn` НЕ зажимает `NaN` (сравнения с `NaN` всегда ложны), а `NaN`, доехавший до
 *   `setLevel`, ломает кадр орба целиком. Поэтому `NaN` приводится к нулю явно, а не «границей».
 */
internal object SpeechLevelMath {

    /** Центр 8-битной огибающей: тишина — это 128, а не 0. */
    const val WAVEFORM_CENTER: Int = 128

    /** Полная шкала после снятия центра: 127/128 отклонения. */
    const val WAVEFORM_FULL_SCALE: Double = 128.0

    /**
     * Усиление RMS вывода.
     *
     * Речь из динамика на телефоне (и синтез, и серверный MP3) даёт RMS 0.04–0.20 от полной шкалы:
     * без усиления орб почти не двигался бы. 4.5 подобран так, чтобы тихая речь (0.05–0.09) давала
     * 0.22–0.40, а громкая (0.18+) — 0.8 и выше. Число проверено арифметикой в тесте
     * ([SpeechLevelTapTest]) и НЕ проверено живым замером на устройстве — точную подстройку даёт
     * приёмка на POCO.
     */
    const val GAIN: Float = 4.5f

    /**
     * RMS 8-битной огибающей, нормированный в 0..1.
     *
     * @param length сколько байт буфера реально заполнено (хвост буфера — не звук).
     */
    fun rmsOfWaveform(waveform: ByteArray, length: Int = waveform.size): Float {
        val valid = minOf(length, waveform.size)
        if (valid <= 0) return 0f
        var sum = 0.0
        var index = 0
        while (index < valid) {
            // Беззнаковый байт: `and 0xFF` обязателен, иначе 200 превратится в -56 и центр 128
            // перестанет быть центром.
            val sample = (waveform[index].toInt() and 0xFF) - WAVEFORM_CENTER
            sum += sample.toDouble() * sample
            index++
        }
        return (sqrt(sum / valid) / WAVEFORM_FULL_SCALE).toFloat()
    }

    /** RMS → уровень 0..1 с усилением [GAIN]. */
    fun levelOf(rms: Float, gain: Float = GAIN): Float = sanitize(rms * gain)

    /**
     * Привести любой пришедший уровень к 0..1.
     *
     * `NaN` → 0 (тишина): «не знаю» не должно превращаться в «нет кадра». `+∞` → 1, `-∞` → 0 —
     * это как раз то, чего ждёт `coerceIn`, и он с бесконечностями справляется.
     */
    fun sanitize(value: Float): Float {
        if (value.isNaN()) return 0f
        return value.coerceIn(0f, 1f)
    }
}

/**
 * Громкость вывода через [Visualizer] — единственный доступный в Android способ «услышать»
 * собственный звук приложения, не открывая микрофон.
 *
 * ## Почему не микрофон
 * На время озвучки микрофон закрывается намеренно (иначе в захват попадает динамик, и ASR
 * отправляет ответ Марса в чат новым вопросом). Значит громкость ответа можно взять только из
 * тракта ВЫВОДА, а для этого платформа даёт ровно один публичный инструмент — `Visualizer`,
 * привязанный к сессии.
 *
 * ## Отказы: всегда `false`, никогда исключение
 * `Visualizer(sessionId)` и `enabled = true` бросают `RuntimeException`, когда эффект уже занят
 * другим приложением (частая история на MIUI), когда сессия мертва или когда у процесса нет
 * `RECORD_AUDIO` (да, `Visualizer` требует именно его). Падать из-за этого контроллер не имеет
 * права: он обязан молча остаться на синтетической огибающей, а не уронить разговор.
 *
 * ## Уборка
 * `release()` зовётся ВСЕГДА — и в [stop], и на любом отказе настройки. Эффект живёт в
 * аудио-сервисе системы: забытый `Visualizer` продолжает получать чужой звук и мешает следующему
 * приложению включить свой.
 *
 * @param context контекст приложения. Самому `Visualizer` он не нужен — параметр оставлен
 *   сигнатурой порта из контракта и держит место для проверок перед привязкой к сессии (например,
 *   `AudioManager`), чтобы такие проверки не пришлось добавлять сменой конструктора у вызывающих.
 */
internal class VisualizerLevelSource(context: Context) : SpeechLevelSource {

    /** Эффект трогают и главный поток, и колбэк Visualizer: ссылка под замком. */
    private val lock = Any()

    private var visualizer: Visualizer? = null

    override fun start(sessionId: Int, onLevel: (Float) -> Unit): Boolean {
        // Прошлый эффект (например от предыдущей фразы) обязан умереть до нового: два Visualizer
        // на одну сессию платформа не даёт, и второй `start` молча вернул бы `false`.
        stop()
        if (sessionId == NO_SESSION) return false
        val effect = try {
            Visualizer(sessionId)
        } catch (e: RuntimeException) {
            // Эффект занят другим приложением / сессия не наша / нет RECORD_AUDIO.
            return false
        }
        val configured = try {
            effect.captureSize = captureSize()
            effect.setDataCaptureListener(
                LevelListener(onLevel),
                // Половина максимальной частоты: 10–20 кадров в секунду хватает кадру орба,
                // а полный поток грузил бы главный поток вдвое чаще без пользы.
                Visualizer.getMaxCaptureRate() / 2,
                /* onWaveFormDataCapture = */ true,
                /* onFftDataCapture = */ false,
            )
            effect.enabled = true
            true
        } catch (e: RuntimeException) {
            false
        }
        if (!configured) {
            releaseQuietly(effect)
            return false
        }
        synchronized(lock) { visualizer = effect }
        return true
    }

    override fun stop() {
        val effect = synchronized(lock) { visualizer.also { visualizer = null } } ?: return
        releaseQuietly(effect)
    }

    /**
     * Размер захвата — максимум из [Visualizer.getCaptureSizeRange].
     *
     * Почему максимум: RMS по 128 байтам на громком слове врёт (окно короче периода основного тона),
     * а 1024 байта при 44.1 кГц — это ~23 мс, уже похоже на «громкость слога». Значение вне
     * диапазона платформа отвергает исключением, поэтому берём ровно границу, а не своё число.
     */
    private fun captureSize(): Int {
        val range = runCatching { Visualizer.getCaptureSizeRange() }.getOrNull()
        val max = range?.lastOrNull() ?: 0
        return if (max > 0) max else DEFAULT_CAPTURE_SIZE
    }

    private fun releaseQuietly(effect: Visualizer) {
        runCatching { effect.setEnabled(false) }
        runCatching { effect.release() }
    }

    /**
     * Колбэк платформы. Приходит на поток аудио-сервиса, поэтому здесь только арифметика:
     * перекладывать на главный поток обязан подписчик ([LocalVoiceController.publishLevel]).
     */
    private class LevelListener(private val onLevel: (Float) -> Unit) :
        Visualizer.OnDataCaptureListener {

        override fun onWaveFormDataCapture(
            visualizer: Visualizer,
            waveform: ByteArray,
            samplingRate: Int,
        ) {
            onLevel(SpeechLevelMath.levelOf(SpeechLevelMath.rmsOfWaveform(waveform)))
        }

        /** Фурье-спектр не нужен: орбу нужна громкость, а не тембр. */
        override fun onFftDataCapture(
            visualizer: Visualizer,
            fft: ByteArray,
            samplingRate: Int,
        ) = Unit
    }

    private companion object {
        /** `AudioTrack`/ExoPlayer отдают `0`, пока сессии нет: привязывать эффект не к чему. */
        const val NO_SESSION = 0

        /** Если платформа не назвала диапазон: 1024 — документированный максимум Visualizer. */
        const val DEFAULT_CAPTURE_SIZE = 1024
    }
}
