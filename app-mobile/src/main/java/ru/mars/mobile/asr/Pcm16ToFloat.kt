package ru.mars.mobile.asr

/**
 * Перевод PCM 16-bit little-endian (то, что отдаёт `AudioRecord`) в float32 -1..1,
 * который ждёт sherpa-onnx.
 *
 * ГРАБЛИ (проверено на сервере): `accept_waveform` принимает ТОЛЬКО float32 в -1..1.
 * Если скормить int16 как есть, распознаватель молча возвращает пустую строку — без
 * исключения и без лога, поэтому ошибку легко не заметить и списать на «модель не слышит».
 *
 * Класс хранит непарный младший байт между вызовами: буфер `AudioRecord` может разрезать
 * 16-битный отсчёт на два вызова `acceptPcm`, и без переноса каждый такой стык давал бы
 * щелчок и сдвиг всей дальнейшей ленты на байт.
 */
class Pcm16ToFloat {
    /** Младший байт отсчёта, ожидающий старшего байта из следующего куска. -1 — нет. */
    private var pendingLow: Int = -1

    /** true, если между вызовами остался «половинчатый» отсчёт. */
    val hasPendingByte: Boolean get() = pendingLow >= 0

    /**
     * @param pcm сырые байты; читаются только первые [length] байт (остальное — мусор
     *   буфера записи).
     * @param length фактическое число валидных байт; отрицательное или больше размера
     *   массива обрезается, а не падает.
     * @return сэмплы в -1..1; может быть пустым (нечётный единственный байт).
     */
    fun convert(pcm: ByteArray, length: Int): FloatArray {
        val n = minOf(length, pcm.size)
        if (n <= 0) return EMPTY

        val hasCarry = pendingLow >= 0
        val out = FloatArray((n + if (hasCarry) 1 else 0) / 2)
        var written = 0
        var i = 0

        if (hasCarry) {
            out[written++] = sample(pendingLow, pcm[0].toInt() and 0xFF)
            pendingLow = -1
            i = 1
        }
        while (i + 1 < n) {
            out[written++] = sample(pcm[i].toInt() and 0xFF, pcm[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < n) pendingLow = pcm[i].toInt() and 0xFF

        return if (written == out.size) out else out.copyOf(written)
    }

    /** Сбросить перенос — при смене реплики/источника, чтобы не тянуть байт из прошлой ленты. */
    fun reset() {
        pendingLow = -1
    }

    private fun sample(low: Int, high: Int): Float {
        val value = ((low or (high shl 8)).toShort()).toInt()
        // 32768, а не 32767: диапазон int16 симметричен по модулю, и -32768 должен дать ровно -1.
        return value / 32768.0f
    }

    companion object {
        private val EMPTY = FloatArray(0)

        /** Разовая конвертация без сохранения переноса (для тестов и offline-путей). */
        fun toFloat(pcm: ByteArray, length: Int = pcm.size): FloatArray =
            Pcm16ToFloat().convert(pcm, length)
    }
}
