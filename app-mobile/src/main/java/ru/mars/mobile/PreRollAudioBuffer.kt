package ru.mars.mobile

/**
 * Кольцевой предбуфер микрофона: держит последние [maxBytes] байт PCM16.
 *
 * ## Зачем он нужен
 * Офлайн-движок распознавания (sherpa-onnx, 23 МиБ) поднимается не мгновенно: пока
 * `StreamingAsrEngine.load()` читает ONNX в фоновом потоке, микрофон уже можно открыть —
 * человек начинает говорить в ту же секунду, как увидел орб. Без предбуфера эти первые
 * 300–800 мс просто исчезали: `StreamingStt` принимает PCM только внутри открытой реплики,
 * а реплику нельзя открыть до готовности движка. В результате терялось ровно первое слово.
 *
 * Поэтому поток чтения микрофона складывает PCM сюда, а в момент готовности движка
 * [drain] отдаёт накопленное одним куском — оно уходит в стрим ПЕРЕД живыми байтами,
 * и речь звучит целиком и в правильном порядке.
 *
 * ## Почему кольцо, а не растущий массив
 * Пока модель грузится, человек может молчать минуту, а может говорить всё это время.
 * Растущий буфер означал бы неограниченную память на «микрофон открыт, движок думает».
 * Кольцо ограничено [maxBytes] и при переполнении выбрасывает САМОЕ СТАРОЕ: перед стартом
 * распознавания ценнее последние две секунды, чем первые (модель догружается, и к моменту
 * готовности свежая речь — это и есть начало фразы).
 *
 * ## Границы отсчётов
 * PCM16 — два байта на отсчёт, и разрезанный посередине отсчёт сдвинул бы весь дальнейший
 * поток на полбайта (на выходе — шипение вместо речи). Поэтому ёмкость округляется до
 * целого числа отсчётов, а при выбросе старых байт начало всегда попадает на границу
 * отсчёта.
 *
 * Потокобезопасен: [append] зовётся с потока чтения микрофона, [drain]/[clear] — с главного.
 *
 * @param maxBytes ёмкость в байтах; по умолчанию [DEFAULT_MAX_BYTES] — две секунды
 *   (16 кГц × 2 байта × 2 с), чего с запасом хватает на загрузку модели и на первую фразу.
 */
class PreRollAudioBuffer(maxBytes: Int = DEFAULT_MAX_BYTES) {

    init {
        require(maxBytes >= BYTES_PER_SAMPLE) {
            "предбуфер меньше одного отсчёта PCM16: $maxBytes байт"
        }
    }

    private val lock = Any()

    /** Ёмкость, выровненная по 16-битному отсчёту: половина отсчёта — уже не звук. */
    private val capacity: Int = maxBytes - (maxBytes % BYTES_PER_SAMPLE)

    private val data: ByteArray = ByteArray(capacity)

    /** Куда писать следующий байт. */
    private var head: Int = 0

    /** Сколько байт реально накоплено (`<= capacity`). */
    private var stored: Int = 0

    /** Сколько байт сейчас лежит в предбуфере. */
    val size: Int
        get() = synchronized(lock) { stored }

    /** Предбуфер пуст. */
    val isEmpty: Boolean
        get() = synchronized(lock) { stored == 0 }

    /**
     * Добавить кусок PCM.
     *
     * @param pcm буфер записи; валидны первые [length] байт.
     * @param length фактическое число байт; больше [pcm] или `<= 0` — вызов ничего не делает
     *   (захват вправе отдать «сколько прочитал», а не «сколько влезло»).
     */
    fun append(pcm: ByteArray, length: Int) {
        val valid = minOf(length, pcm.size)
        if (valid <= 0) return
        synchronized(lock) {
            var src = 0
            var remaining = valid
            if (remaining > capacity) {
                // Кусок длиннее всего буфера: прежнее содержимое всё равно не влезет, а от
                // нового остаётся хвост. Начало хвоста — на границе отсчёта.
                src = remaining - capacity
                if ((src and 1) != 0) src++
                remaining = valid - src
                head = 0
                stored = 0
            }
            while (remaining > 0) {
                val space = capacity - head
                val chunk = minOf(remaining, space)
                System.arraycopy(pcm, src, data, head, chunk)
                head = (head + chunk) % capacity
                // Кольцо заполняется до ёмкости и дальше не растёт: старые байты затёрты.
                stored = minOf(capacity, stored + chunk)
                src += chunk
                remaining -= chunk
            }
        }
    }

    /**
     * Забрать накопленное и очистить предбуфер.
     *
     * @return байты в хронологическом порядке (самые старые первыми); пустой массив — копить
     *   было нечего. Возвращённый массив — копия: вызывающая сторона вправе держать его
     *   сколько нужно, а буфер продолжает работать.
     */
    fun drain(): ByteArray = synchronized(lock) {
        if (stored == 0) {
            ByteArray(0)
        } else {
            val out = ByteArray(stored)
            val start = (head - stored + capacity) % capacity
            val first = minOf(stored, capacity - start)
            System.arraycopy(data, start, out, 0, first)
            if (first < stored) System.arraycopy(data, 0, out, first, stored - first)
            head = 0
            stored = 0
            out
        }
    }

    /** Выбросить накопленное, не отдавая его (отмена реплики, уход с экрана). */
    fun clear() {
        synchronized(lock) {
            head = 0
            stored = 0
        }
    }

    companion object {
        /** Два байта на отсчёт PCM16 — минимальная единица звука. */
        private const val BYTES_PER_SAMPLE = 2

        /** Две секунды 16 кГц моно PCM16: 16 000 × 2 × 2. */
        const val DEFAULT_MAX_BYTES: Int = 64_000
    }
}
