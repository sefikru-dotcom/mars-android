package ru.mars.mobile.voice

/**
 * Нарезка живого текста ответа на куски для синтеза речи.
 *
 * ## Зачем это отдельный объект без Android
 * Задержка «ответ уже пошёл, а Марс молчит» складывается не из скорости синтеза, а из
 * ОЖИДАНИЯ текста: пока не набралось достаточно, отдавать в движок нечего. Первый кусок
 * обязан появляться как можно раньше, поэтому первым режем клауз — кусок до запятой, точки,
 * двоеточия, тире или перевода строки. Речевой движок на клаузе звучит естественно (запятая —
 * тоже пауза), а первое слово человек слышит на секунды раньше, чем при ожидании целого
 * предложения или всего ответа.
 *
 * Дальше — пачки около [BATCH_CHARS] символов: у серверного синтеза и у Piper стоимость
 * запроса растёт с длиной текста, а слишком мелкая нарезка даёт слышимые паузы между кусками.
 *
 * ## Почему нельзя просто `chunked(300)`
 * `chunked` режет по счётчику и разрывает слова пополам — движок получает половину слова,
 * произносит её невнятно, а первый кусок ещё и ждёт полные [BATCH_CHARS] символов. Поэтому
 * граница всегда ищется по последнему пробелу/знаку препинания внутри окна, и только если их
 * нет вовсе (текст без пробелов) — режется по счётчику.
 *
 * ## Инвариант
 * Ни один символ не теряется: для любого текста, прокачанного через [nextChunk] до
 * `endOfStream`, конкатенация всех `text` равна исходному тексту, кроме пробелов на стыках
 * кусков (их съедает `trim`). Содержимое — никогда. Это и есть смысл `rest`: вызывающий
 * обязан продолжить именно с ним, а не с «хвостом», который он посчитал сам.
 *
 * Класс чистый (JVM, без `android.*`): нарезка проверяется юнит-тестом без устройства, а
 * ошибка «кусок потерял слово» ловится до телефона.
 */
object SpeechChunker {

    /**
     * Сколько символов нужно накопить, если клаузы (запятой/точки/…) в потоке ещё нет.
     *
     * Меньше — и в синтез уходит огрызок из двух слов, который звучит как заикание; больше — и
     * ожидание первого звука снова становится заметным, что и было жалобой.
     */
    const val FIRST_CLAUSE_MIN_CHARS: Int = 60

    /** Размер последующих пачек: компромисс «реже паузы между кусками» ↔ «короче запрос синтеза». */
    const val BATCH_CHARS: Int = 300

    /**
     * Знаки, на которых кончается клауз.
     *
     * Дефиса-минуса здесь нет намеренно: «кто-то», «Марс-1», «по-русски» — одно слово, и резка
     * по нему дала бы в синтез обрывок «кто-» с паузой посреди слова. Дефис считается границей
     * только когда он окружён пробелами (тире, набранное дефисом).
     */
    private const val CLAUSE_MARKS: String = ",.;:!?—–\n"

    /**
     * Готовый кусок и остаток буфера.
     *
     * @param text что отдавать в синтез (без ведущих/хвостовых пробелов, непусто);
     * @param rest хвост исходного буфера, который вызывающий ОБЯЗАН сохранить и передать в
     *   следующий [nextChunk] — потерять его значит потерять слова ответа.
     */
    data class Chunk(val text: String, val rest: String) {

        /** Текст кончился: следующий вызов на [rest] даст `null`. */
        val drained: Boolean get() = rest.isBlank()
    }

    /**
     * Взять следующий кусок из [buffer].
     *
     * @param buffer накопленный, ещё не озвученный текст (может быть частичным: поток отдаёт
     *   дельты по нескольку символов);
     * @param first это первый кусок текущего ответа: режем по клаузу и не ждём пачку;
     * @param endOfStream текст ответа закончился — отдаём остаток целиком, даже короткий.
     * @return `null` — отдавать пока нечего (нет клаузы и не набралось [FIRST_CLAUSE_MIN_CHARS]
     *   для первого куска либо меньше [BATCH_CHARS] для пачки); иначе кусок и остаток.
     */
    fun nextChunk(buffer: String, first: Boolean, endOfStream: Boolean = false): Chunk? {
        if (buffer.isEmpty()) return null
        // Ведущие пробелы не считаются содержимым: «   Привет» и «Привет» — один и тот же кусок.
        val from = buffer.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return null
        val cut = if (first) firstCut(buffer, from) else batchCut(buffer, from, endOfStream)
        if (cut <= from) return null
        val text = buffer.substring(0, cut).trim()
        if (text.isEmpty()) return null
        return Chunk(text = text, rest = buffer.substring(cut))
    }

    /**
     * Отдать ВСЁ, что осталось в буфере, на конце потока.
     *
     * Может вернуть несколько кусков: остаток длиннее [BATCH_CHARS] всё равно режется по
     * границам слов, иначе серверный синтез получил бы один огромный запрос. Остаток короче
     * пачки уходит целиком — это хвост ответа, ждать больше нечего.
     */
    fun flush(buffer: String): List<String> {
        val chunks = ArrayList<String>()
        var rest = buffer
        while (true) {
            val chunk = nextChunk(rest, first = false, endOfStream = true) ?: break
            chunks += chunk.text
            rest = chunk.rest
        }
        return chunks
    }

    /** Граница первого куска: клауз, а если его нет — [FIRST_CLAUSE_MIN_CHARS] символов. */
    private fun firstCut(buffer: String, from: Int): Int {
        val mark = clauseMarkAt(buffer, from)
        if (mark > 0) return mark
        // Клаузы нет (модель пишет одним длинным предложением): ждём порог, но режем по границе
        // слова внутри него — «половина слова» звучит хуже паузы.
        if (buffer.length - from < FIRST_CLAUSE_MIN_CHARS) return -1
        val limit = from + FIRST_CLAUSE_MIN_CHARS
        return lastBoundary(buffer, from, limit) ?: limit
    }

    /** Граница пачки: ровно [BATCH_CHARS] (или конец потока), но по слову/препинанию. */
    private fun batchCut(buffer: String, from: Int, endOfStream: Boolean): Int {
        val available = buffer.length - from
        if (!endOfStream && available < BATCH_CHARS) return -1
        if (available <= BATCH_CHARS) return buffer.length
        val limit = from + BATCH_CHARS
        return lastBoundary(buffer, from, limit) ?: limit
    }

    /**
     * Индекс ЗА первым знаком клаузы, начиная с `from + 1` (сам первый символ не считается:
     * клауз из одного знака препинания говорить нечего). `-1` — клаузы нет.
     */
    private fun clauseMarkAt(buffer: String, from: Int): Int {
        for (index in (from + 1) until buffer.length) {
            if (!isClauseMarkAt(buffer, index)) continue
            return index + 1
        }
        return -1
    }

    /**
     * Последняя граница в окне `[from, until)`, за которой можно резать.
     *
     * Возвращает индекс ЗА границей; `null` — границ в окне нет (текст без пробелов).
     */
    private fun lastBoundary(buffer: String, from: Int, until: Int): Int? {
        val limit = minOf(until, buffer.length)
        // С конца, но не в самом начале: кусок нулевой длины бессмыслен.
        for (index in (limit - 1) downTo (from + 1)) {
            if (isBoundaryAt(buffer, index)) return index + 1
        }
        return null
    }

    /** Знак клаузы в позиции [index]; точка внутри числа клаузом не считается. */
    private fun isClauseMarkAt(buffer: String, index: Int): Boolean {
        val char = buffer[index]
        if (char == '-') return isSpacedDash(buffer, index)
        if (char !in CLAUSE_MARKS) return false
        // «1.5», «2.0» — одно слово: резка по такой точке произнесла бы «один точка» с паузой.
        if (char == '.' && index > 0 && buffer[index - 1].isDigit()) return false
        return true
    }

    /** Пробел — такая же законная граница пачки, как знак препинания. */
    private fun isBoundaryAt(buffer: String, index: Int): Boolean {
        val char = buffer[index]
        if (char.isWhitespace()) return true
        return isClauseMarkAt(buffer, index)
    }

    /** Дефис-тире: « — » и « - » режут, «кто-то» и «Марс-1» — нет. */
    private fun isSpacedDash(buffer: String, index: Int): Boolean {
        val before = index == 0 || buffer[index - 1].isWhitespace()
        val after = index == buffer.lastIndex || buffer[index + 1].isWhitespace()
        return before && after
    }
}
