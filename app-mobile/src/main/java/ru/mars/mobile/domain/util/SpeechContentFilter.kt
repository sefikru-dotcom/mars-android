package ru.mars.mobile.domain.util

/**
 * Intelligent speech filter for TTS: converts Mars's technical markdown answers into clean,
 * speakable plain text. Android's [android.speech.tts.TextToSpeech] would otherwise read
 * service characters, fenced code blocks, tables and long URLs verbatim — noisy and
 * unintelligible.
 *
 * Rules (order matters):
 * - fenced code blocks (` ```lang … ``` `) are replaced with the natural phrase
 *   «(Блок кода выведен на экран)»; consecutive/duplicate placeholders are collapsed into one;
 * - if the answer consisted ONLY of code, the whole reply becomes
 *   «Марс предоставил решение в виде программного кода на экране.»;
 * - inline code `` `foo` `` keeps only the clean name (backticks removed);
 * - markdown links `[текст](url)` become just «текст»; images `![alt](url)` are dropped;
 *   bare URLs (http/https) become «ссылка» so TTS never spells out a domain character-by-character;
 * - emoji and decorative picture glyphs (✅🚀⭐, variation selectors, ZWJ) are dropped:
 *   TTS engines either stay silent or spell out their names, both are noise;
 * - markdown decoration is smoothed away: heading hashes, bold/italic/strikethrough markers,
 *   blockquote `>`, list bullets, horizontal rules, table separator rows and cell pipes,
 *   residual HTML tags — leaving clean Russian/English prose.
 *
 * ## Почему отдельно чистятся отступы и пунктуация (волна 20)
 * Жалоба владельца: «тупит на знаках препинания, отступах и прочем, долгие паузы и
 * прерывания». Причина — синтезатор получал СТРУКТУРУ документа, а не речь:
 * - маркеры списков (`- `, `* `, `• `, `1. `) доезжали до движка и читались как «тире»,
 *   «звёздочка» или просто вставляли паузу в начале каждой строки;
 * - двойные переводы строк и отступы (`\n\n`, `  `) превращались в долгие неконтролируемые
 *   паузы посреди фразы, а строка без финальной точки — в «слипание» двух предложений;
 * - сдвоенная пунктуация (`..`, `,.`, `:.`, `— —`) заставляла движок замолкать или
 *   произносить знак вслух;
 * - псевдографика (`│ ┌ ─ ═`) и эмодзи (`✅ ❌ ⚠️ 🎙 ⏳`) читались по именам либо давали
 *   щелчок и паузу.
 *
 * Поэтому после снятия markdown текст проходит шаг [tidy]: строки склеиваются в сплошную
 * речь (после знака препинания — пробел, без него — точка с пробелом), тире превращается в
 * запятую-паузу, скобки и кавычки снимаются, сдвоенные знаки схлопываются.
 *
 * Числа, проценты и единицы измерения здесь НЕ трогаются: это работа
 * [ru.mars.mobile.VoiceNormalizer], которая вызывается сразу после этого фильтра в
 * `LocalVoiceTts` и `OfflineTtsBackend` (порядок важен: сначала markdown, потом числа —
 * иначе «1. `foo`» превратилось бы в «один точка …» до снятия разметки).
 *
 * Pure JVM class: no Android imports, fully unit-testable.
 */
class SpeechContentFilter {

    /**
     * Полный проход: markdown → произносимый текст.
     *
     * Оставлен как основное имя (его зовёт [ru.mars.mobile.VoiceOutputController]);
     * [stripForSpeech] — то же самое для путей, где текст режется на куски.
     */
    fun filterForSpeech(markdown: String): String = stripForSpeech(markdown)

    /**
     * Очистить [markdown] до плоской речи: без разметки, отступов, псевдографики и
     * сдвоенной пунктуации.
     *
     * Возвращает пустую строку, если говорить нечего (только эмодзи/разметка), и
     * [CODE_ONLY_REPLY], если ответ состоял целиком из блоков кода.
     */
    fun stripForSpeech(markdown: String): String {
        if (markdown.isBlank()) return ""
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')

        // 1. Fenced code blocks -> natural placeholder.
        text = CODE_FENCE.replace(text, CODE_PLACEHOLDER)
        // An unclosed fence (streaming answer) still must not be read verbatim.
        text = UNCLOSED_FENCE.replace(text) { CODE_PLACEHOLDER }
        // Collapse repeated placeholders (e.g. several fences in a row).
        text = ADJACENT_PLACEHOLDERS.replace(text, "$CODE_PLACEHOLDER\n")

        // 2. Images before links (both use brackets); keep nothing from the image syntax.
        text = IMAGE.replace(text, " ")
        // 3. Markdown links -> anchor text only.
        text = MD_LINK.replace(text, "$1")
        // Reference-style links [text][ref] -> text.
        text = REF_LINK.replace(text, "$1")
        // Reference definition lines ([ref]: url) are metadata — never spoken.
        text = REF_DEF.replace(text, "")
        // Autolinks <https://…> and bare URLs -> «ссылка».
        text = AUTOLINK.replace(text, " ссылка ")
        text = BARE_URL.replace(text, " ссылка ")

        // 4. Inline code: keep the clean name, drop backticks.
        text = text.replace("`", "")

        // 5. Line-level markdown decoration.
        text = HEADING.replace(text, "")
        text = BLOCKQUOTE.replace(text, "")
        // Маркер списка — не речь: убирается целиком, а разделение строк даст [tidy].
        text = BULLET.replace(text, "")
        // Номер пункта оставляем: «первое, второе» без номера теряет порядок.
        text = NUMBERED.replace(text, "$1. ")
        text = HRULE.replace(text, "")
        text = TABLE_SEP.replace(text, "")
        text = text.replace('|', ' ')
        text = HTML_TAG.replace(text, " ")

        // 6. Inline markers and decorative glyphs.
        text = EMOJI.replace(text, " ")
        text = DECOR.replace(text, " ")
        text = text.replace("~~", "")
        text = STAR.replace(text, "")
        // Underscore emphasis: strip only edges not glued to a word character
        // (snake_case identifiers keep their underscores).
        text = UNDERSCORE_EMPHASIS.replace(text, "")

        // 7. Отступы, переводы строк, пунктуация: структура документа -> сплошная речь.
        text = tidy(text)
        if (text.isEmpty()) return ""

        // 8. Whole answer was code only -> dedicated natural sentence.
        val withoutPlaceholders = text.replace(CODE_PLACEHOLDER, "").trim()
        return if (withoutPlaceholders.isEmpty()) CODE_ONLY_REPLY else text
    }

    // --- отступы, строки, пунктуация ------------------------------------------------------------

    /**
     * Превратить многострочный markdown-текст в ОДНУ произносимую строку.
     *
     * Правило перевода строки: если предыдущая строка уже кончилась знаком препинания —
     * ставим пробел (паузу даст сам знак), иначе точку с пробелом. Так строка без точки
     * не слипается со следующей («Готово» + «Дальше» → «Готово. Дальше»), а лишние пустые
     * строки и отступы не превращаются в многозначительное молчание.
     */
    private fun tidy(text: String): String {
        // Плейсхолдер кода содержит скобки, которые здесь снимаются: прячем его.
        val guarded = text.contains(CODE_PLACEHOLDER)
        var out = if (guarded) text.replace(CODE_PLACEHOLDER, GUARD) else text

        out = INNER_SPACES.replace(out, " ")              // табы и отступы
        out = LINE_PADDING.replace(out, "\n")             // пробелы вокруг перевода строки
        out = joinLines(out)                              // \n+ -> ". " либо " "
        out = normalizeDashes(out)                        // «—» -> пауза-запятая
        out = BRACKETS.replace(out, ", ")                 // скобки/кавычки -> пауза-запятая
        out = repairPunctuation(out)                      // «..», «,.», «: ,»
        out = INNER_SPACES.replace(out, " ")

        if (guarded) out = out.replace(GUARD, CODE_PLACEHOLDER)
        return out.trim()
    }

    /** Склеить строки: знак препинания на конце — пробел, иначе точка с пробелом. */
    private fun joinLines(text: String): String {
        if (text.indexOf('\n') < 0) return text
        val builder = StringBuilder(text.length)
        for (line in text.split('\n')) {
            val piece = line.trim()
            if (piece.isEmpty()) continue
            if (builder.isEmpty()) {
                builder.append(piece)
                continue
            }
            val last = builder[builder.length - 1]
            val separator = when {
                last == ' ' -> ""          // уже разделено
                last in SENTENCE_TAIL -> " " // паузу даёт сам знак препинания
                else -> ". "               // строки без точки не должны слипнуться
            }
            builder.append(separator).append(piece)
        }
        return builder.toString()
    }

    /**
     * Тире и дефис-тире -> запятая-пауза.
     *
     * Почему не «тире с пробелами»: espeak-фронтенд piper и часть сетевых голосов читают
     * `—` словом «тире» либо делают на нём рваную паузу. Запятая — та же пауза, но без
     * символа, который можно произнести неверно.
     */
    private fun normalizeDashes(text: String): String {
        var out = DOUBLE_DASH.replace(text, ", ")
        out = DASH.replace(out, ", ")
        // «слово - слово» (дефис, окружённый пробелами) — тоже тире, а не часть слова.
        out = SPACED_HYPHEN.replace(out, ", ")
        return out
    }

    /** Схлопнуть сдвоенные знаки и убрать пробелы перед ними. */
    private fun repairPunctuation(text: String): String {
        // Два прохода: первый снимает «..», «,,», «— —», второй — то, что получилось
        // после первого («дела, , ?» -> «дела?»). Проход идемпотентен, цена — микросекунды.
        var out = text
        repeat(2) { out = repairPass(out) }
        return out
    }

    private fun repairPass(text: String): String {
        var out = SPACE_BEFORE_PUNCT.replace(text, "$1")
        out = SEPARATOR_BEFORE_DOT.replace(out, ".")
        out = REPEATED_DOTS.replace(out, ".")
        out = REPEATED_QUESTION.replace(out, "$1")
        out = COLON_BEFORE_COMMA.replace(out, "$1")
        out = DOT_BEFORE_SEPARATOR.replace(out, "$1")
        out = SEPARATOR_BEFORE_TERMINAL.replace(out, "$1")
        out = DUPLICATE_SEPARATOR.replace(out, "$1")
        out = SPACE_BEFORE_PUNCT.replace(out, "$1")
        // Пробел после запятой/двоеточия — но не внутри «10:30» и «1,5».
        out = SEPARATOR_GLUED.replace(out, "$1 ")
        out = LEADING_PUNCT.replace(out, "")
        return out
    }

    companion object {
        /** Phrase spoken instead of every code block. */
        const val CODE_PLACEHOLDER = "(Блок кода выведен на экран)"

        /** Spoken when the answer contained nothing but code. */
        const val CODE_ONLY_REPLY = "Марс предоставил решение в виде программного кода на экране."

        /** Fenced block: ``` with optional language tag … closing ``` (DOTALL, non-greedy). */
        private val CODE_FENCE = Regex("""```[^\n]*\n?[\s\S]*?```""")

        /** Незакрытый блок кода (потоковый ответ): до конца текста. */
        private val UNCLOSED_FENCE = Regex("""```[^\n]*\n?[\s\S]*$""")

        private const val ESCAPED_PLACEHOLDER = """\(Блок кода выведен на экран\)"""

        private val ADJACENT_PLACEHOLDERS = Regex("""(?:$ESCAPED_PLACEHOLDER\s*)+""")

        /**
         * Служебный символ вместо плейсхолдера кода на время чистки: скобки плейсхолдера
         * обязаны выжить (тесты и озвучка ждут фразу целиком), а обычные скобки — нет.
         */
        private const val GUARD = "\u0001"

        private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val REF_LINK = Regex("""\[([^\]]*)]\[[^\]]*]""")
        private val REF_DEF = Regex("""(?m)^[ \t]*\[[^\]]+]:[ \t]*\S+[ \t]*$""")
        private val AUTOLINK = Regex("""<https?://[^>\s]+>""")
        private val BARE_URL = Regex("""https?://\S+""")

        private val HEADING = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]*""")
        private val BLOCKQUOTE = Regex("""(?m)^[ \t]*>[ \t]?""")
        private val BULLET = Regex("""(?m)^[ \t]*[-*+•‣][ \t]+""")
        private val NUMBERED = Regex("""(?m)^[ \t]*(\d+)[.)][ \t]+""")
        private val HRULE = Regex("""(?m)^[ \t]*(?:---+|\*\*\*+|___+)[ \t]*$""")
        private val TABLE_SEP = Regex("""(?m)^[ \t]*\|?[ \t]*:?-{2,}[\s|:-]*$""")
        private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")

        /**
         * Emoji / pictographic glyphs incl. variation selectors and ZWJ — never speakable.
         *
         * Диапазоны: пиктограммы (1F000–1FAFF), «dingbats»/галочки (2600–27BF), технические
         * символы часов и медиа (2300–23FF: ⏳ ⏸ ⏺), стрелки (2190–21FF), геометрические
         * маркеры (2B00–2BFF), обведённые цифры (2460–24FF), селекторы и ZWJ.
         */
        private val EMOJI = Regex(
            """[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{2300}-\x{23FF}""" +
                """\x{2460}-\x{24FF}\x{FE00}-\x{FE0F}\x{200D}\x{2190}-\x{21FF}]"""
        )

        /**
         * Псевдографика и декоративные маркеры (`│ ┌ ─ ═ █ •`): TTS либо молчит на них,
         * либо называет символ вслух — и в обоих случаях рвёт фразу паузой.
         */
        private val DECOR = Regex(
            """[\x{2500}-\x{257F}\x{2580}-\x{259F}\x{2022}\x{2023}\x{25AA}-\x{25CF}""" +
                """\x{25E6}\x{00B7}]"""
        )

        private val STAR = Regex("""\*+""")
        private val UNDERSCORE_EMPHASIS = Regex("""(?<![\p{L}\p{N}])_+|_+(?![\p{L}\p{N}])""")

        private val INNER_SPACES = Regex("""[ \t\u00A0\u2000-\u200A]+""")
        private val LINE_PADDING = Regex(""" *\n *""")

        /** Знаки, после которых перевод строки — уже пауза, точка не нужна. */
        private val SENTENCE_TAIL: Set<Char> =
            setOf('.', '!', '?', ':', ';', ',', '…', '—', '–', ')', '"', '»')

        private val DOUBLE_DASH = Regex("""\s*[—–]\s*[—–]+\s*""")
        private val DASH = Regex("""\s*[—–]\s*""")
        private val SPACED_HYPHEN = Regex("""\s+--?\s+""")
        private val BRACKETS = Regex("[()\\[\\]{}«»\"\"„“”‚‘’]")

        private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?…])""")
        private val SEPARATOR_BEFORE_DOT = Regex("""[,;:]\s*\.+""")
        private val REPEATED_DOTS = Regex("""\.{2,}""")
        private val REPEATED_QUESTION = Regex("""([!?])\s*[!?]+""")
        private val COLON_BEFORE_COMMA = Regex("""([:;])\s*,""")
        private val DOT_BEFORE_SEPARATOR = Regex("""([.!?…])\s*[,;:]+""")
        private val SEPARATOR_BEFORE_TERMINAL = Regex("""[,;:]+\s*([.!?…])""")
        private val DUPLICATE_SEPARATOR = Regex("""([,;:])\s*[,;:]+""")
        private val SEPARATOR_GLUED = Regex("""(?<!\d)([,;:])(?=[^\s])""")
        private val LEADING_PUNCT = Regex("""^[\s,;:.!?…]+""")
    }
}
