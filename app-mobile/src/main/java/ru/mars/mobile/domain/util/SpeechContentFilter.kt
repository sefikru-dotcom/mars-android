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
 * Pure JVM class: no Android imports, fully unit-testable.
 */
class SpeechContentFilter {

    fun filterForSpeech(markdown: String): String {
        if (markdown.isBlank()) return ""
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')

        // 1. Fenced code blocks -> natural placeholder.
        text = CODE_FENCE.replace(text, CODE_PLACEHOLDER)
        // An unclosed fence (streaming answer) still must not be read verbatim.
        text = Regex("```[^\n]*\n?[\\s\\S]*$").replace(text) { CODE_PLACEHOLDER }
        // Collapse repeated placeholders (e.g. several fences in a row).
        text = Regex("(?:$ESCAPED_PLACEHOLDER\\s*)+").replace(text, "$CODE_PLACEHOLDER\n")

        // 2. Images before links (both use brackets); keep nothing from the image syntax.
        text = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(text, " ")
        // 3. Markdown links -> anchor text only.
        text = Regex("\\[([^\\]]*)]\\([^)]*\\)").replace(text, "$1")
        // Reference-style links [text][ref] -> text.
        text = Regex("\\[([^\\]]*)]\\[[^\\]]*]").replace(text, "$1")
        // Reference definition lines ([ref]: url) are metadata — never spoken.
        text = Regex("(?m)^[ \\t]*\\[[^\\]]+]:[ \\t]*\\S+[ \\t]*$").replace(text, "")
        // Autolinks <https://…> and bare URLs -> «ссылка».
        text = Regex("<https?://[^>\\s]+>").replace(text, " ссылка ")
        text = Regex("https?://\\S+").replace(text, " ссылка ")

        // 4. Inline code: keep the clean name, drop backticks.
        text = text.replace("`", "")

        // 5. Line-level markdown decoration.
        text = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*").replace(text, "")          // headings
        text = Regex("(?m)^[ \\t]*>[ \\t]?").replace(text, "")                    // blockquotes
        text = Regex("(?m)^[ \\t]*[-*+][ \\t]+").replace(text, "")                // unordered list bullets
        text = Regex("(?m)^[ \\t]*(?:---+|\\*\\*\\*+|___+)[ \\t]*$").replace(text, "") // horizontal rules
        text = Regex("(?m)^[ \\t]*\\|?[ \\t]*:?-{2,}[\\s|:-]*$").replace(text, "") // table separator rows
        text = text.replace('|', ' ')                                             // table cell pipes
        text = Regex("</?[a-zA-Z][^>]*>").replace(text, " ")                      // residual HTML tags

        // 6. Inline markers.
        text = EMOJI.replace(text, " ")
        text = text.replace("~~", "")
        text = Regex("\\*+").replace(text, "")
        // Underscore emphasis: strip only edges not glued to a word character
        // (snake_case identifiers keep their underscores).
        text = Regex("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])").replace(text, "")

        // 7. Whitespace normalization.
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("[ ]*\\n[ ]*"), "\n")
        text = text.replace(Regex("\\n{2,}"), "\n")
        text = text.trim()

        // 8. Whole answer was code only -> dedicated natural sentence.
        if (text.isEmpty()) return ""
        val withoutPlaceholders = text.replace(CODE_PLACEHOLDER, "").trim()
        return if (withoutPlaceholders.isEmpty()) CODE_ONLY_REPLY else text
    }

    companion object {
        /** Phrase spoken instead of every code block. */
        const val CODE_PLACEHOLDER = "(Блок кода выведен на экран)"

        /** Spoken when the answer contained nothing but code. */
        const val CODE_ONLY_REPLY = "Марс предоставил решение в виде программного кода на экране."

        /** Fenced block: ``` with optional language tag … closing ``` (DOTALL, non-greedy). */
        private val CODE_FENCE = Regex("```[^\\n]*\\n?[\\s\\S]*?```")

        private const val ESCAPED_PLACEHOLDER = "\\(Блок кода выведен на экран\\)"

        /** Emoji / pictographic glyphs incl. variation selectors and ZWJ — never speakable. */
        private val EMOJI = Regex(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{2190}-\\x{21FF}]"
        )
    }
}
