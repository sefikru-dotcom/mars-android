package ru.mars.mobile

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Звуковой пресет эквалайзера (паритет с WebUI `EQ_PRESETS` из static/player.js:
 * flat / bass_boost / rock / vocal + расширенный набор pop / electronic).
 * [curveDb] — пятиконтрольная кривая усиления в дБ (низкие → высокие);
 * [AudioFxPolicy.bandLevelsMb] интерполирует её под любое число полос
 * аппаратного Equalizer и переводит в миллибелы.
 */
enum class AudioFxPreset(val title: String, val curveDb: IntArray) {
    FLAT("Обычный", intArrayOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Усиление басов", intArrayOf(9, 5, -1, 0, 2)),
    ROCK("Рок", intArrayOf(5, 3, -2, 2, 4)),
    POP("Поп", intArrayOf(-1, 2, 5, 3, -1)),
    VOCAL("Вокал", intArrayOf(-2, 1, 6, 3, 3)),
    ELECTRONIC("Электроника", intArrayOf(6, 4, -1, 2, 6)),
}

/**
 * Снимок настроек звука: выбранный пресет, включена ли обработка и уровень
 * Bass Boost (0..1000, шкала android.media.audiofx.BassBoost). Неизменяемый;
 * все переходы валидирует [AudioFxPolicy].
 */
data class AudioFxState(
    val preset: AudioFxPreset = AudioFxPreset.FLAT,
    val enabled: Boolean = false,
    val bassBoost: Int = 0,
)

/**
 * Чистая доменная логика звуковых эффектов (Android-free, для JVM-тестов):
 * валидация уровня Bass Boost, восстановление пресета по имени, расчёт
 * уровней полос эквалайзера в миллибелах под аппаратный диапазон устройства.
 */
object AudioFxPolicy {
    /** Штатный диапазон силы BassBoost (0 = выключен, 1000 = максимум). */
    const val MIN_BASS_BOOST = 0
    const val MAX_BASS_BOOST = 1000

    /** 1 дБ = 100 миллибел (единица Equalizer.setBandLevel). */
    const val DB_TO_MILLIBELS = 100

    /** Число контрольных точек в [AudioFxPreset.curveDb]. */
    const val CURVE_POINTS = 5

    /** Приводит произвольный уровень силы к штатному диапазону 0..1000. */
    fun sanitizeStrength(raw: Int): Int = raw.coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)

    /** Пресет по сохранённому имени; неизвестное/пустое имя деградирует в [AudioFxPreset.FLAT]. */
    fun presetByName(name: String?): AudioFxPreset =
        AudioFxPreset.values().firstOrNull { it.name == name } ?: AudioFxPreset.FLAT

    /** Приводит восстановленное/изменённое состояние к валидному (сила в диапазоне). */
    fun sanitizeState(state: AudioFxState): AudioFxState =
        state.copy(bassBoost = sanitizeStrength(state.bassBoost))

    /**
     * Уровни полос эквалайзера в миллибелах для [preset]: пятиконтрольная кривая
     * пресета линейно интерполируется под [numBands] аппаратных полос и
     * зажимается в диапазон устройства [minMb]..[maxMb] (bandLevelRange).
     * Пустой результат при numBands <= 0 — эффекты не применяются.
     */
    fun bandLevelsMb(preset: AudioFxPreset, numBands: Int, minMb: Int, maxMb: Int): ShortArray {
        if (numBands <= 0) return ShortArray(0)
        val curve = preset.curveDb
        val lo = minOf(minMb, maxMb)
        val hi = maxOf(minMb, maxMb)
        return ShortArray(numBands) { band ->
            val pos = if (numBands == 1) {
                (CURVE_POINTS - 1) / 2.0
            } else {
                band.toDouble() * (CURVE_POINTS - 1) / (numBands - 1)
            }
            val db = interpolateDb(curve, pos)
            (db * DB_TO_MILLIBELS).roundToInt().coerceIn(lo, hi).toShort()
        }
    }

    /** Линейная интерполяция кривой из [CURVE_POINTS] контрольных точек в позиции [pos]. */
    private fun interpolateDb(curve: IntArray, pos: Double): Double {
        val index = pos.toInt().coerceIn(0, curve.size - 2)
        val fraction = pos - index
        return curve[index] + (curve[index + 1] - curve[index]) * fraction
    }
}

/** Порт persistence настроек звука; приложение реализует его через SharedPreferences. */
interface AudioFxStorage {
    fun load(): AudioFxState
    fun save(state: AudioFxState)
}

/**
 * SharedPreferences-реализация [AudioFxStorage] для приватного файла
 * `mars-music-audiofx`: пресет по имени, флаг включения и уровень Bass Boost.
 * Повреждённые значения деградируют через [AudioFxPolicy.sanitizeState]
 * и [AudioFxPolicy.presetByName] вместо падения.
 */
class PrefsAudioFxStorage(private val prefs: SharedPreferences) : AudioFxStorage {
    override fun load(): AudioFxState = AudioFxState(
        preset = AudioFxPolicy.presetByName(prefs.getString(KEY_PRESET, null)),
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        bassBoost = AudioFxPolicy.sanitizeStrength(prefs.getInt(KEY_BASS_BOOST, 0)),
    )

    override fun save(state: AudioFxState) {
        val cleaned = AudioFxPolicy.sanitizeState(state)
        prefs.edit()
            .putString(KEY_PRESET, cleaned.preset.name)
            .putBoolean(KEY_ENABLED, cleaned.enabled)
            .putInt(KEY_BASS_BOOST, cleaned.bassBoost)
            .apply()
    }

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_ENABLED = "enabled"
        const val KEY_BASS_BOOST = "bass_boost"
    }
}

/**
 * Настройки звука поверх [storage]: каждая мутация проходит через
 * [AudioFxPolicy.sanitizeState] и сразу сохраняется, поэтому [state] всегда
 * валиден и переживает перезапуск процесса. Про Android-вьюхи ничего не знает.
 */
class AudioFxSettings(private val storage: AudioFxStorage) {
    /** Текущее состояние; уже санитизировано при восстановлении. */
    var state: AudioFxState = AudioFxPolicy.sanitizeState(storage.load())
        private set

    /** Переключение пресета эквалайзера; возвращает обновлённое состояние. */
    fun setPreset(preset: AudioFxPreset): AudioFxState = update { it.copy(preset = preset) }

    /** Включить/выключить обработку звука; возвращает обновлённое состояние. */
    fun setEnabled(enabled: Boolean): AudioFxState = update { it.copy(enabled = enabled) }

    /** Уровень Bass Boost (клампится в 0..1000); возвращает обновлённое состояние. */
    fun setBassBoost(strength: Int): AudioFxState =
        update { it.copy(bassBoost = AudioFxPolicy.sanitizeStrength(strength)) }

    private fun update(transform: (AudioFxState) -> AudioFxState): AudioFxState {
        state = AudioFxPolicy.sanitizeState(transform(state))
        storage.save(state)
        return state
    }
}
