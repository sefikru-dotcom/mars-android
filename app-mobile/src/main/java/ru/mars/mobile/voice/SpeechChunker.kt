package ru.mars.mobile.voice

/**
 * Нарезка живого текста ответа на куски для синтеза речи.
 *
 * ## Зачем это отдельный объект без Android
 * Задержка «ответ уже пошёл, а Марс молчит» складывается не из скорости синтеза, а из
 * ОЖИДАНИЯ текста: пока не набралось достаточно, отдавать в движок нечего. Первый кусок
 * обязан появляться как можно раньше — но не ценой обрыва на первом слове.
 *
 * ## Почему первый кусок — ПРЕДЛОЖЕНИЕ, а не клауз (волна 21)
 * Раньше первым куском уходил первый клауз: `firstCut` резал на первой же запятой. Модель
 * отвечает `"Конечно, сейчас расскажу подробно…"` — и в синтез уезжало `"Конечно,"`:
 * одно слово, **0.5 с** звука. Следующий кусок (`batchCut`) в это время ждал накопления
 * `BATCH_CHARS = 220` символов, поэтому после этого полсекундного слова наступала тишина
 * **5–10 секунд** — ровно жалоба владельца «доходит до запятой и останавливается».
 *
 * Теперь границы разделены по смыслу:
 * - [SENTENCE_MARKS] — конец предложения (`.` `;` `:` `!` `?` `…` `\n`): лучшая граница,
 *   первый кусок звучит осмысленной фразой на 2–4 с речи;
 * - [CLAUSE_MARKS] — мягкая пауза внутри предложения (запятая, тире): годится только
 *   ПОСЛЕ [FIRST_CLAUSE_MIN_CHARS] символов. Короткое вводное слово (`"Привет,"`,
 *   `"Конечно,"`) отдельным куском не отрезается — оно едет вместе с продолжением фразы.
 *
 * Дальше — пачки около [BATCH_CHARS] символов, но законченное предложение длиной от
 * [MIN_SENTENCE_BATCH_CHARS] отдаётся СРАЗУ, не дожидаясь полной пачки: очередь синтеза
 * наполняется предложение за предложением, и предсинтез (`prefetch`) успевает подготовить
 * следующий кусок, пока звучит текущий.
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
     * Сколько символов нужно накопить, прежде чем резать по МЯГКОЙ границе (запятая/тире)
     * или по границе слова.
     *
     * Волна 21: было 60 и это был порог «нет клауза — режь по слову». Теперь это ещё и
     * защита от обрыва на вводном слове: `"Конечно,"` — 9 символов, до порога не дотягивает,
     * поэтому запятая после него границей НЕ считается, и первый кусок продолжается до
     * конца предложения. 48 символов ≈ 1.5–2 с речи — уже не «одно слово».
     */
    const val FIRST_CLAUSE_MIN_CHARS: Int = 48

    /**
     * Жёсткий потолок ПЕРВОГО куска (волна 21: было 120).
     *
     * Зачем потолок, если есть конец предложения: модель регулярно пишет первое предложение
     * на 300–500 символов («Сейчас я проверил все три сервера, посмотрел логи, перезапустил
     * сервис и вижу, что …»). Резать строго по предложению значило бы отдать в синтез
     * пол-абзаца и молчать, пока он синтезируется. 110 символов ≈ 2–3 с речи: синтез такого
     * куска укладывается в 300–500 мс, то есть первый звук слышен почти мгновенно.
     */
    const val FIRST_MAX_CHARS: Int = 110

    /**
     * Минимальная длина первого куска, закрытого концом предложения.
     *
     * `"Да."`, `"Нет!"` отдельной репликой звучат как заикание: такой короткий кусок
     * отдаётся только когда других концов предложений в окне нет, а продолжение уже
     * накопилось ([FIRST_CLAUSE_MIN_CHARS]).
     */
    const val MIN_FIRST_SENTENCE_CHARS: Int = 12

    /**
     * Жёсткий потолок последующих пачек (волна 21: было 220).
     *
     * Между кусками слышна микропауза (создание `AudioTrack`, старт синтеза), поэтому пачка
     * не должна быть слишком короткой; но и длинная пачка (220+) синтезируется дольше, чем
     * звучит предыдущая, и очередь пустеет — на слух это «задумался посреди ответа». 160
     * символов ≈ 3–4 с речи: предсинтез следующего куска гарантированно успевает.
     */
    const val REST_MAX_CHARS: Int = 160

    /** Размер последующих пачек: компромисс «реже паузы между кусками» ↔ «короче запрос синтеза». */
    const val BATCH_CHARS: Int = REST_MAX_CHARS

    /**
     * Минимальная длина ЗАКОНЧЕННОГО предложения, которое отдаётся сразу, не дожидаясь
     * полной пачки [BATCH_CHARS].
     *
     * Зачем: пока LLM генерирует текст, буфер редко доходит до 160 символов. Ждать полную
     * пачку — значит держать в очереди уже готовое предложение и оставлять предсинтез без
     * работы; на слух это та же пауза, только короче. Предложение от 45 символов (≈ 1.5–2 с
     * речи) уже стоит того, чтобы уйти в синтез немедленно.
     */
    const val MIN_SENTENCE_BATCH_CHARS: Int = 45

    /**
     * Знаки конца предложения: после них пауза в речи есть всегда, поэтому это лучшая
     * граница куска. Точка/двоеточие внутри числа (`1.5`, `14:30`) концом не считаются.
     */
    private const val SENTENCE_MARKS: String = ".;:!?…\n"

    /**
     * Мягкие паузы ВНУТРИ предложения: запятая и тире.
     *
     * Дефиса-минуса здесь нет намеренно: «кто-то», «Марс-1», «по-русски» — одно слово, и резка
     * по нему дала бы в синтез обрывок «кто-» с паузой посреди слова. Дефис считается границей
     * только когда он окружён пробелами (тире, набранное дефисом) — см. [isSpacedDash].
     */
    private const val CLAUSE_MARKS: String = ",—–"

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
     * @param first это первый кусок текущего ответа: режем по концу предложения и не ждём пачку;
     * @param endOfStream текст ответа закончился — отдаём остаток целиком, даже короткий.
     * @return `null` — отдавать пока нечего (для первого куска меньше [FIRST_CLAUSE_MIN_CHARS]
     *   и нет конца предложения; для пачки меньше [MIN_SENTENCE_BATCH_CHARS] и нет законченного
     *   предложения); иначе кусок и остаток.
     */
    fun nextChunk(buffer: String, first: Boolean, endOfStream: Boolean = false): Chunk? {
        if (buffer.isEmpty()) return null
        // Ведущие пробелы не считаются содержимым: «   Привет» и «Привет» — один и тот же кусок.
        val from = buffer.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return null
        val cut = if (first) firstCut(buffer, from, endOfStream) else batchCut(buffer, from, endOfStream)
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

    /**
     * Порезать ГОТОВЫЙ текст на куски для синтеза (путь «Озвучить» на карточке сообщения).
     *
     * Отличие от потокового [nextChunk]: текст уже целиком в руках, ждать дельт не нужно, но
     * первое слово обязано звучать мгновенно. Поэтому первый кусок — предложение в пределах
     * [FIRST_MAX_CHARS] (или клауз/слово-граница после [FIRST_CLAUSE_MIN_CHARS]), а остальное
     * режется пачками по [REST_MAX_CHARS] с приоритетом конца предложения.
     *
     * Раньше `LocalVoiceTts.speak` отдавал весь текст ОДНИМ куском: сообщение на 800–2500
     * символов синтезировалось 10–20 секунд в полной тишине. Это и была жалоба «долго
     * анализирует сообщение из чата, большая задержка перед началом».
     *
     * @return куски в порядке произнесения; пустой список — говорить нечего.
     */
    fun split(text: String): List<String> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        // Первый кусок — как в потоке. Короткий текст без конца предложения (например
        // «Привет») не набирает FIRST_CLAUSE_MIN_CHARS, и nextChunk вернёт null: тогда отдаём
        // всё целиком.
        val head = nextChunk(body, first = true) ?: return flush(body)
        val chunks = ArrayList<String>()
        chunks += head.text
        chunks += flush(head.rest)
        return chunks
    }

    /**
     * Граница ПЕРВОГО куска.
     *
     * Порядок правил — от лучшей границы к худшей:
     * 1. конец предложения в окне [FIRST_MAX_CHARS] — если кусок дотянул до
     *    [MIN_FIRST_SENTENCE_CHARS]. Короткий `"Да."` пропускаем и ищем следующий конец
     *    предложения: два огрызка подряд звучат хуже одной нормальной фразы;
     * 2. мягкая пауза (запятая/тире) — ТОЛЬКО после [FIRST_CLAUSE_MIN_CHARS] символов от
     *    начала куска. Именно это правило убивает обрыв на `"Конечно,"`: вводное слово
     *    короче 48 символов границей не считается, и фраза едет дальше целиком;
     * 3. граница слова на потолке окна — когда модель пишет предложение без единой запятой
     *    длиннее [FIRST_MAX_CHARS];
     * 4. иначе `-1`: ждём следующие дельты (на конце потока отдаём всё, что есть).
     */
    private fun firstCut(buffer: String, from: Int, endOfStream: Boolean): Int {
        val available = buffer.length - from
        // Окно ограничено: длинное первое предложение не должно откладывать звук.
        val windowEnd = from + minOf(available, FIRST_MAX_CHARS)

        // 1. Конец предложения — лучшая граница первого куска.
        val sentence = sentenceMarkAt(buffer, from, windowEnd)
        if (sentence > 0) {
            var candidate = sentence
            var lastShort = -1
            while (candidate > 0 && candidate - from < MIN_FIRST_SENTENCE_CHARS) {
                // «Да.» / «Нет!» отдельным куском — заикание: идём к следующему концу
                // предложения, а короткие подряд склеиваем («Да. Нет.» — уже фраза).
                lastShort = candidate
                candidate = sentenceMarkAt(buffer, candidate, windowEnd)
            }
            if (candidate > 0) return candidate
            // Дальше в окне концов предложений нет: склеенные короткие отдаём, только когда
            // продолжение уже накопилось и ждать его бессмысленно.
            if (lastShort > 0 && available >= FIRST_CLAUSE_MIN_CHARS) return lastShort
        }

        if (available < FIRST_CLAUSE_MIN_CHARS) {
            // Ни конца предложения, ни порога для мягкой границы: ждём дельты дальше.
            return if (endOfStream) buffer.length else -1
        }

        // 2. Мягкая пауза — только после порога (см. KDoc правила).
        val clause = clauseMarkAt(buffer, from + FIRST_CLAUSE_MIN_CHARS, windowEnd)
        if (clause > 0) return clause

        // 3. Предложение без запятых длиннее окна: режем по границе слова у потолка.
        if (available >= FIRST_MAX_CHARS || endOfStream) {
            return lastBoundary(buffer, from, windowEnd) ?: windowEnd
        }
        return -1
    }

    /**
     * Граница пачки: не больше [BATCH_CHARS] (или конец потока), по предложению или слову.
     *
     * Волна 21: законченное предложение от [MIN_SENTENCE_BATCH_CHARS] отдаётся СРАЗУ, не
     * дожидаясь полной пачки. Раньше при `available < BATCH_CHARS` возвращался `-1` безусловно,
     * и готовое предложение лежало в буфере, пока LLM не догенерирует ещё сотню символов, —
     * та же пауза, только короче.
     */
    private fun batchCut(buffer: String, from: Int, endOfStream: Boolean): Int {
        val available = buffer.length - from
        if (endOfStream && available <= BATCH_CHARS) return buffer.length
        if (!endOfStream && available < BATCH_CHARS) {
            // Пачку набрать нечем, но законченное предложение уже есть — отдаём его сразу.
            if (available >= MIN_SENTENCE_BATCH_CHARS) {
                val early = sentenceMarkAt(buffer, from + MIN_SENTENCE_BATCH_CHARS, buffer.length)
                if (early > 0) return early
            }
            return -1
        }
        val limit = from + BATCH_CHARS
        // Приоритет — концу предложения: пауза между кусками тогда совпадает с паузой,
        // которую человек и так делает. Слишком короткий «огрызок предложения» не берём.
        val sentence = lastSentenceEnd(buffer, from + MIN_SENTENCE_BATCH_CHARS, limit)
        if (sentence > 0) return sentence
        // Второй приоритет — мягкая пауза во второй половине окна: кусок остаётся длинным,
        // но кончается на запятой/тире, а не посреди мысли.
        val clause = lastClauseMark(buffer, from + BATCH_CHARS / 2, limit)
        if (clause > 0) return clause
        return lastBoundary(buffer, from, limit) ?: limit
    }

    /**
     * Индекс ЗА первым знаком конца предложения в окне `[start, until)`; `-1` — концов нет.
     *
     * @param start с чего искать (включая): первому куску нельзя закрываться на самом начале,
     *   пачке — на огрызке короче [MIN_SENTENCE_BATCH_CHARS].
     */
    private fun sentenceMarkAt(buffer: String, start: Int, until: Int): Int {
        val limit = minOf(until, buffer.length)
        var index = maxOf(start, 0)
        while (index < limit) {
            if (isSentenceMarkAt(buffer, index)) return index + 1
            index++
        }
        return -1
    }

    /**
     * Индекс ЗА первым знаком мягкой паузы в окне `[start, until)`; `-1` — пауз нет.
     *
     * @param start с чего искать (включая): первый кусок ищет запятую только после
     *   [FIRST_CLAUSE_MIN_CHARS], иначе вводное слово отрезалось бы отдельно.
     */
    private fun clauseMarkAt(buffer: String, start: Int, until: Int): Int {
        val limit = minOf(until, buffer.length)
        var index = maxOf(start, 0)
        while (index < limit) {
            if (isClauseMarkAt(buffer, index)) return index + 1
            index++
        }
        return -1
    }

    /**
     * Индекс ЗА последней мягкой паузой в окне `[start, until)`; `-1` — пауз нет.
     *
     * Нужен пачкам: длинный кусок лучше закрыть на запятой/тире, чем посреди слова.
     */
    private fun lastClauseMark(buffer: String, start: Int, until: Int): Int {
        val limit = minOf(until, buffer.length)
        var index = limit - 1
        val floor = maxOf(start, 0)
        while (index >= floor) {
            if (isClauseMarkAt(buffer, index)) return index + 1
            index--
        }
        return -1
    }

    /**
     * Индекс ЗА последним концом предложения в окне `[from, until)`; `-1` — концов нет.
     *
     * Нужен только пачкам: закончить кусок на точке естественнее, чем посреди фразы.
     * Точка внутри числа (`1.5`) концом предложения не считается.
     */
    private fun lastSentenceEnd(buffer: String, from: Int, until: Int): Int {
        val limit = minOf(until, buffer.length)
        var index = limit - 1
        val floor = maxOf(from, 0)
        while (index >= floor) {
            if (isSentenceMarkAt(buffer, index)) return index + 1
            index--
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
        var index = limit - 1
        while (index > from) {
            if (isBoundaryAt(buffer, index)) return index + 1
            index--
        }
        return null
    }

    /**
     * Знак конца предложения в позиции [index].
     *
     * Точка и двоеточие внутри числа (`1.5`, `14:30`) концом предложения не считаются: резка
     * по ним произнесла бы «один точка» с паузой посреди числа.
     */
    private fun isSentenceMarkAt(buffer: String, index: Int): Boolean {
        val char = buffer[index]
        if (char !in SENTENCE_MARKS) return false
        if ((char == '.' || char == ':') && isBetweenDigits(buffer, index)) return false
        return true
    }

    /**
     * Знак мягкой паузы в позиции [index]: запятая, длинное тире или дефис, окружённый
     * пробелами. Точка/двоеточие сюда НЕ входят — это границы предложения ([isSentenceMarkAt]),
     * а не пауза внутри фразы.
     */
    private fun isClauseMarkAt(buffer: String, index: Int): Boolean {
        val char = buffer[index]
        if (char == '-') return isSpacedDash(buffer, index)
        return char in CLAUSE_MARKS
    }

    /** Пробел — такая же законная граница пачки, как знак препинания. */
    private fun isBoundaryAt(buffer: String, index: Int): Boolean {
        val char = buffer[index]
        if (char.isWhitespace()) return true
        return isClauseMarkAt(buffer, index) || isSentenceMarkAt(buffer, index)
    }

    /** Символ [index] зажат между цифрами: «1.5», «14:30» — одно число, а не пауза. */
    private fun isBetweenDigits(buffer: String, index: Int): Boolean {
        if (index <= 0 || index >= buffer.lastIndex) return false
        return buffer[index - 1].isDigit() && buffer[index + 1].isDigit()
    }

    /** Дефис-тире: « — » и « - » режут, «кто-то» и «Марс-1» — нет. */
    private fun isSpacedDash(buffer: String, index: Int): Boolean {
        val before = index == 0 || buffer[index - 1].isWhitespace()
        val after = index == buffer.lastIndex || buffer[index + 1].isWhitespace()
        return before && after
    }
}
