package ru.mars.mobile.asr

import java.io.File

/**
 * Настройки wake word «Марс, слушай»: фразы, порог/скор и защита от повторных срабатываний.
 *
 * Значения по умолчанию — из замера на сервере (Python sherpa-onnx 1.13.8, та же модель
 * `zipformer-small-ru-vosk-int8`, тот же AAR): синтезированное «Марс, слушай» ловилось за 0.7 с,
 * ложных срабатываний на посторонней русской речи не было.
 */
data class WakeWordConfig(
    /**
     * Фразы-триггеры в человеческом виде: слова через пробел, регистр не важен.
     *
     * «Марс» отдельно, потому что человек в шуме часто глотает «слушай»; «марш» — потому что
     * модель слышит обращение как «марш» ([AsrFinalCorrector] правит это же в финальном тексте).
     * «эй марс» не добавлено: эта форма на замерах не проверялась, а непроверенная фраза —
     * лишние ложные срабатывания на батарейном режиме.
     */
    val phrases: List<String> = DEFAULT_PHRASES,

    /**
     * Порог срабатывания KeywordSpotter.
     *
     * 0.10 — НЕ дефолт sherpa-onnx (там 0.25), а результат замера: на 0.25 детектор не срабатывал
     * вообще ни на одном файле, на 0.10 срабатывал стабильно. Порог подобран на синтезированной
     * речи и требует подбора на устройстве в шуме: чем выше, тем меньше ложных срабатываний и
     * больше пропусков.
     */
    val threshold: Float = DEFAULT_THRESHOLD,

    /** Вес ключевой фразы относительно обычного декодирования (дефолт sherpa-onnx). */
    val score: Float = DEFAULT_SCORE,

    /**
     * Потоки ONNX Runtime для поиска фразы.
     *
     * Один, а не два: основной ASR держит два потока, и wake word не должен отнимать у него CPU —
     * иначе распознавание реплики проседает ровно тогда, когда человек говорит сразу после «Марс».
     */
    val numThreads: Int = 1,

    /**
     * Минимальный интервал между срабатываниями, мс.
     *
     * Нужен потому, что KeywordSpotter после детекции продолжает слышать ту же фразу в хвосте
     * аудио: без паузы одно «Марс, слушай» открыло бы голосовой диалог дважды.
     */
    val repeatSuppressMs: Long = REPEAT_SUPPRESS_MS,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.10f
        const val DEFAULT_SCORE = 1.0f
        const val REPEAT_SUPPRESS_MS = 1_500L

        /** Фразы по умолчанию — только проверенные на замере формы. */
        val DEFAULT_PHRASES: List<String> = listOf("марс", "марс слушай", "марш")
    }
}

/**
 * Политика wake word: сборка keywords-файла под словарь модели и решение «срабатывание принято».
 *
 * Чистый JVM (никакого Android и никакого native): и BPE-кодирование, и дебаунс проверяются
 * юнит-тестами, а нативная часть живёт за портом [WakeWordPort].
 *
 * ПОЧЕМУ BPE, А НЕ ПОСИМВОЛЬНО. KeywordSpotter ищет в потоке последовательность ТОКЕНОВ словаря,
 * а не буквы: `марс` — это `▁ма р с`, и запись `▁м а р с` падает с `Cannot find ID for token`
 * (токена `▁м` в словаре нет). Поэтому фраза кодируется тем же greedy longest-match, каким её
 * закодировал бы BPE-словарь модели, и файл keywords собирается уже из токенов.
 *
 * @param tokens словарь модели (первая колонка `tokens.txt`).
 * @param config фразы, порог, скор и дебаунс.
 */
class WakeWordPolicy(
    tokens: Collection<String>,
    val config: WakeWordConfig = WakeWordConfig(),
) {
    private val vocabulary: Set<String> = tokens.filter { it.isNotBlank() }.toHashSet()

    /** Время последнего принятого срабатывания; `null` — срабатываний ещё не было. */
    private var lastAcceptedAtMs: Long? = null

    val phrases: List<String> get() = config.phrases

    val threshold: Float get() = config.threshold

    /**
     * Слово → последовательность BPE-токенов модели.
     *
     * Маркер начала слова (`▁`, U+2581) добавляется к слову, а не к каждому токену: так словарь
     * сам решает, где начинается слово, и «слушай» после «марс» кодируется как `▁с лу ша й`.
     *
     * @throws WakeWordEncodingException если словарь не содержит токена для очередного фрагмента:
     *   молча пропустить фразу нельзя — детектор искал бы в потоке не то, что написано в настройке.
     */
    fun encodeWord(word: String): List<String> {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) {
            throw WakeWordEncodingException("Пустое слово в фразе wake word: кодировать нечего")
        }
        val text = WORD_MARK + normalized
        val encoded = ArrayList<String>(text.length)
        var start = 0
        while (start < text.length) {
            var end = text.length
            var matched: String? = null
            // Greedy longest-match: самый длинный токен словаря, начинающийся здесь. Именно так
            // словарь модели режет текст при обучении — иначе последовательность не совпадёт
            // с той, что native ждёт на входе.
            while (end > start) {
                val candidate = text.substring(start, end)
                if (candidate in vocabulary) {
                    matched = candidate
                    break
                }
                end--
            }
            if (matched == null) {
                throw WakeWordEncodingException(
                    "Нет BPE-токена для фрагмента «${text.substring(start, minOf(text.length, start + 1))}» " +
                        "в слове «$normalized» (позиция $start, остаток «${text.substring(start)}»). " +
                        "Keywords собираются настоящей BPE-последовательностью из tokens.txt модели, " +
                        "а не посимвольно: проверьте, что tokens.txt соответствует версии модели.",
                )
            }
            encoded += matched
            start += matched.length
        }
        return encoded
    }

    /** Фраза целиком (слова через пробел) → плоская последовательность токенов. */
    fun encodePhrase(phrase: String): List<String> =
        phrase.split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { encodeWord(it) }

    /**
     * Содержимое файла `keywords.txt` для KeywordSpotter: одна фраза на строку, токены через
     * пробел. Формат native-слоя — именно такой; посимвольная или человеческая запись не годится.
     */
    fun keywordsFileContent(): String {
        if (phrases.isEmpty()) {
            throw WakeWordEncodingException("Список фраз wake word пуст: keywords-файл был бы пустым")
        }
        return phrases.joinToString(separator = "\n", postfix = "\n") { phrase ->
            encodePhrase(phrase).joinToString(" ")
        }
    }

    /**
     * Принять ли срабатывание в момент [atMs] (монотонные миллисекунды).
     *
     * Дебаунс: после принятого срабатывания следующие [WakeWordConfig.repeatSuppressMs] мс
     * отбрасываются. Отрицательная разница времени (часы ушли назад) тоже считается повтором —
     * лучше пропустить срабатывание, чем открыть микрофон второй раз на той же фразе.
     */
    fun acceptDetection(atMs: Long): Boolean {
        val last = lastAcceptedAtMs
        if (last != null && atMs - last < config.repeatSuppressMs) return false
        lastAcceptedAtMs = atMs
        return true
    }

    /** Сброс дебаунса: новая сессия прослушивания не должна наследовать паузу прошлой. */
    fun reset() {
        lastAcceptedAtMs = null
    }

    companion object {
        /** `▁` (U+2581) — маркер начала слова в BPE-словаре sherpa-onnx. */
        const val WORD_MARK = "\u2581"

        const val TOKENS_FILE = "tokens.txt"

        /** Словарь модели из содержимого `tokens.txt` (первая колонка каждой строки). */
        fun vocabularyOf(tokensTxt: String): List<String> = tokensTxt.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') }
            .toList()

        /**
         * Политика поверх реального каталога модели.
         *
         * @throws java.io.IOException если `tokens.txt` не читается — детектор в этом случае
         *   переходит в состояние «недоступно», а не качает модель молча.
         */
        fun forModelDir(modelDir: File, config: WakeWordConfig = WakeWordConfig()): WakeWordPolicy =
            WakeWordPolicy(vocabularyOf(File(modelDir, TOKENS_FILE).readText()), config)
    }
}

/**
 * Словарь модели не позволил закодировать фразу.
 *
 * Отдельный тип, а не `IllegalArgumentException`: вызывающая сторона (детектор) различает
 * «фраза не кодируется» и «модель не читается», и в первом случае это дефект конфигурации,
 * который виден в логе целиком.
 */
class WakeWordEncodingException(message: String) : IllegalArgumentException(message)
