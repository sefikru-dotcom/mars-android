package ru.mars.mobile

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Темп и высота речи — то, что человек реально крутит в настройках голоса.
 *
 * Зачем отдельный тип, а не два `Float` в настройках: темп живёт в ТРЁХ разных
 * представлениях, и каждое требует своей арифметики.
 * - серверный `api/tts` принимает `rate` строкой `[+-]N%` (`+15%`) и `pitch` — `[+-]NHz`;
 * - системный Android TTS принимает МНОЖИТЕЛЬ (`setSpeechRate(1.15f)`), причём у разных
 *   движков этот множитель означает разное;
 * - sherpa-onnx (piper) принимает `speed` во float и внутри делает
 *   `lengthScale / speed`.
 *
 * Один множитель на входе и три метода на выходе — единственный способ не развести
 * «1.15×» по трём местам и не получить голос, который ускоряется на сервере и не
 * ускоряется локально.
 *
 * `null` в [voiceId] — не «голоса нет», а «как в вебе»: выбранный там голос. Пустой
 * список голосов — не повод угадывать.
 */
data class VoiceProsody(
    val speed: Float = DEFAULT_SPEED,
    val pitch: Float = DEFAULT_PITCH,
    val voiceId: String? = null,
) {

    /** Темп и высота по умолчанию и голос не выбран — настройку можно не хранить. */
    val isDefault: Boolean
        get() = speed == DEFAULT_SPEED && pitch == DEFAULT_PITCH && voiceId == null

    fun withSpeed(value: Float): VoiceProsody = copy(speed = clampSpeed(value))

    fun withPitch(value: Float): VoiceProsody = copy(pitch = clampPitch(value))

    fun withVoice(id: String?): VoiceProsody = copy(voiceId = id)

    /**
     * Подпись темпа для шапки шторки: «1.15×» либо «обычный темп».
     *
     * Сравнение с эталоном через [isDefaultSpeed], а не `==`: значение могло прийти
     * из хранилища с погрешностью float, и «1.0000001×» в подписи выглядело бы поломкой.
     */
    fun speedLabel(): String =
        if (isDefaultSpeed) DEFAULT_SPEED_LABEL else "${formatMultiplier(speed)}×"

    /**
     * `rate` для `POST /api/tts`: `+15%` из 1.15; `null` при обычном темпе.
     *
     * Смысл null — «не отправляем поле»: сервер подставит свой дефолт, а присылать
     * `+0%` каждый раз значило бы перебивать настройку веба без причины.
     */
    fun serverRate(): String? = percentDelta(speed)?.let { delta ->
        val sign = if (delta >= 0) "+" else ""
        "$sign$delta%"
    }

    /** `pitch` для `POST /api/tts`: `+10Hz` из 1.10; `null` при обычной высоте. */
    fun serverPitch(): String? {
        if (isDefaultPitch) return null
        val delta = ((pitch - DEFAULT_PITCH) * HZ_PER_UNIT_PITCH).roundToInt()
        val sign = if (delta >= 0) "+" else ""
        return "$sign${delta}Hz"
    }

    /** Множитель для `TextToSpeech.setSpeechRate`; уже зажат в допустимые границы. */
    fun systemSpeechRate(): Float = clampSpeed(speed)

    /** Множитель для `TextToSpeech.setPitch`; уже зажат. */
    fun systemPitch(): Float = clampPitch(pitch)

    /** Множитель для `OfflineTtsBackend.speed` (там `lengthScale / speed`). */
    fun offlineSpeed(): Float = clampSpeed(speed)

    /**
     * Зажат ли темп в границы провайдера.
     *
     * Нужно шторке, чтобы не показывать «1.9×», если движок умеет только до 1.6:
     * человек должен увидеть предел, а не узнать о нём по звуку.
     */
    fun speedWithinLimits(): Boolean = speed in MIN_SPEED..MAX_SPEED

    private val isDefaultSpeed: Boolean
        get() = abs(speed - DEFAULT_SPEED) < EPSILON

    private val isDefaultPitch: Boolean
        get() = abs(pitch - DEFAULT_PITCH) < EPSILON

    companion object {
        /** Границы осмысленного темпа: 0.5× слышно как «замедленно», 2.0× — ещё разборчиво. */
        const val MIN_SPEED: Float = 0.5f
        const val MAX_SPEED: Float = 2.0f

        /** Границы высоты: за ними голос превращается в «бурундука» или в «робота». */
        const val MIN_PITCH: Float = 0.6f
        const val MAX_PITCH: Float = 1.6f

        const val DEFAULT_SPEED: Float = 1.0f
        const val DEFAULT_PITCH: Float = 1.0f

        /** Пресеты темпа для шторки: не произвольный ползунок, а осмысленные ступени. */
        val STEP_CHOICES: List<Float> = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)

        const val DEFAULT_SPEED_LABEL: String = "обычный темп"

        private const val HZ_PER_UNIT_PITCH: Float = 100f
        private const val EPSILON: Float = 0.001f
        /** Проценты для сервера целые: `_normalize_tts_prosody` ждёт `\d{1,3}`. */
        private const val PERCENT_PER_UNIT: Float = 100f

        fun clampSpeed(value: Float): Float =
            if (value.isNaN()) DEFAULT_SPEED else value.coerceIn(MIN_SPEED, MAX_SPEED)

        fun clampPitch(value: Float): Float =
            if (value.isNaN()) DEFAULT_PITCH else value.coerceIn(MIN_PITCH, MAX_PITCH)

        /** Процент отклонения темпа от обычного; null, если отклонения нет. */
        private fun percentDelta(speed: Float): Int? {
            if (abs(speed - DEFAULT_SPEED) < EPSILON) return null
            return ((speed - DEFAULT_SPEED) * PERCENT_PER_UNIT).roundToInt()
        }

        private fun formatMultiplier(value: Float): String {
            val hundredths = (value * 100f).roundToInt()
            val whole = hundredths / 100
            val rest = hundredths % 100
            return when {
                rest == 0 -> "$whole.0"
                rest % 10 == 0 -> "$whole.${rest / 10}"
                rest < 10 -> "$whole.0$rest"
                else -> "$whole.$rest"
            }
        }
    }
}

/** Сохранение темпа между запусками. Порт, а не SharedPreferences: чистая JVM, тестируется фейком. */
interface VoiceProsodyStore {
    fun load(): VoiceProsody
    fun save(prosody: VoiceProsody)
}
