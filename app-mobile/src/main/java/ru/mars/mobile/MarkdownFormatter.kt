package ru.mars.mobile

sealed class InlineSpan {
    data class Normal(val text: String) : InlineSpan()
    data class Bold(val text: String) : InlineSpan()
    data class Italic(val text: String) : InlineSpan()
    data class Monospace(val text: String) : InlineSpan()
    data class Link(val title: String, val url: String) : InlineSpan()
}

sealed class RichLine {
    data class Header(val level: Int, val spans: List<InlineSpan>) : RichLine()
    data class Bullet(val spans: List<InlineSpan>) : RichLine()
    data class Quote(val spans: List<InlineSpan>) : RichLine()
    data class Numbered(val number: String, val spans: List<InlineSpan>) : RichLine()
    data class Normal(val spans: List<InlineSpan>) : RichLine()
    /** Horizontal rule (---, ***, ___). Rendered as a thin divider. */
    object Divider : RichLine()
}

sealed class ContentBlock {
    data class Text(val lines: List<RichLine>) : ContentBlock()
    data class Code(val language: String, val code: String) : ContentBlock()
}

object MarkdownFormatter {
    // Tolerates CRLF fences and keeps an unclosed fence parseable (streaming replies).
    private val CODE_BLOCK_REGEX = Regex("```(\\w*)\\r?\\n([\\s\\S]*?)```")
    private val OPEN_CODE_BLOCK_REGEX = Regex("```(\\w*)\\r?\\n([\\s\\S]*)$")
    private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")
    private val BOLD_REGEX = Regex("\\*\\*(.+?)\\*\\*")
    private val CODE_INLINE_REGEX = Regex("`([^`]+)`")
    private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER_REGEX = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val NUMBERED_REGEX = Regex("^(\\d+)\\.\\s+(.*)$")
    private val BULLET_REGEX = Regex("^[-*+]\\s+(.*)$")
    private val QUOTE_REGEX = Regex("^>\\s?(.*)$")
    private val ITALIC_AFTER_TRIM = Regex("\\*(.+?)\\*")

    fun parseBlocks(raw: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var lastIndex = 0
        for (match in CODE_BLOCK_REGEX.findAll(raw)) {
            val textBefore = raw.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(parseLines(textBefore)))
            }
            blocks.add(ContentBlock.Code(match.groupValues[1].trim(), match.groupValues[2].trimEnd('\n', '\r')))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            val remaining = raw.substring(lastIndex)
            // Unclosed fence: treat the tail as a code block so partial streams render sanely.
            val open = OPEN_CODE_BLOCK_REGEX.find(remaining)
            if (open != null) {
                val before = remaining.substring(0, open.range.first)
                if (before.isNotEmpty()) blocks.add(ContentBlock.Text(parseLines(before)))
                blocks.add(ContentBlock.Code(open.groupValues[1].trim(), open.groupValues[2].trimEnd('\n', '\r')))
            } else if (remaining.isNotEmpty()) {
                blocks.add(ContentBlock.Text(parseLines(remaining)))
            }
        }

        return blocks
    }

    fun parseLines(raw: String): List<RichLine> {
        return raw.split("\n").map { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> RichLine.Normal(emptyList())
                DIVIDER_REGEX.matches(trimmed) -> RichLine.Divider
                HEADER_REGEX.matches(trimmed) -> {
                    val m = HEADER_REGEX.find(trimmed)!!
                    RichLine.Header(m.groupValues[1].length, parseInline(m.groupValues[2]))
                }
                QUOTE_REGEX.matches(trimmed) -> RichLine.Quote(parseInline(QUOTE_REGEX.find(trimmed)!!.groupValues[1]))
                BULLET_REGEX.matches(trimmed) -> RichLine.Bullet(parseInline(BULLET_REGEX.find(trimmed)!!.groupValues[1]))
                NUMBERED_REGEX.matches(trimmed) -> {
                    val m = NUMBERED_REGEX.find(trimmed)!!
                    RichLine.Numbered("${m.groupValues[1]}.", parseInline(m.groupValues[2]))
                }
                else -> RichLine.Normal(parseInline(line))
            }
        }
    }

    fun parseInline(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        val spans = mutableListOf<InlineSpan>()

        // 1. Split by links [title](url); the text between them keeps formatting.
        var cursor = 0
        for (m in LINK_REGEX.findAll(raw)) {
            if (m.range.first > cursor) {
                spans.addAll(parseInlineFormatting(raw.substring(cursor, m.range.first)))
            }
            spans.add(InlineSpan.Link(m.groupValues[1], m.groupValues[2]))
            cursor = m.range.last + 1
        }
        if (cursor < raw.length) {
            spans.addAll(parseInlineFormatting(raw.substring(cursor)))
        }
        return spans
    }

    private fun parseInlineFormatting(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<InlineSpan>()

        data class Token(val range: IntRange, val span: InlineSpan)
        val tokens = mutableListOf<Token>()

        fun overlaps(r: IntRange) = tokens.any { t -> r.first <= t.range.last && r.last >= t.range.first }

        // Code spans win: nothing inside `…` is formatted.
        CODE_INLINE_REGEX.findAll(raw).forEach {
            if (!overlaps(it.range)) tokens.add(Token(it.range, InlineSpan.Monospace(it.groupValues[1])))
        }
        // Bold next: **…** claims its range so italic cannot split it.
        BOLD_REGEX.findAll(raw).forEach {
            if (!overlaps(it.range)) tokens.add(Token(it.range, InlineSpan.Bold(it.groupValues[1])))
        }
        // Italic last, only outside claimed ranges (code/bold win on overlap).
        ITALIC_AFTER_TRIM.findAll(raw).forEach { match ->
            val stripped = stripOuterAsterisks(match)
            if (stripped != null && !overlaps(stripped.first)) {
                tokens.add(Token(stripped.first, InlineSpan.Italic(stripped.second)))
            }
        }

        tokens.sortBy { it.range.first }

        var cursor = 0
        for (token in tokens) {
            if (token.range.first < cursor) continue
            if (token.range.first > cursor) {
                result.add(InlineSpan.Normal(raw.substring(cursor, token.range.first)))
            }
            result.add(token.span)
            cursor = token.range.last + 1
        }
        if (cursor < raw.length) {
            result.add(InlineSpan.Normal(raw.substring(cursor)))
        }

        return result
    }

    /**
     * `*text*` -> italic. The loose regex also catches stray asterisks of a bold token
     * (`**x**` matches as `*` + `*x` + `*`); shrink the extra asterisks and reject matches
     * whose payload touches whitespace (CommonMark flanking: `a * b * c` is not italic).
     * Returns null when the match carries no usable content.
     */
    private fun stripOuterAsterisks(match: MatchResult): Pair<IntRange, String>? {
        var first = match.range.first
        var last = match.range.last
        var text = match.groupValues[1]
        while (text.startsWith("*") && first + 1 < last) {
            first++
            text = text.substring(1)
        }
        while (text.endsWith("*") && last - 1 > first) {
            last--
            text = text.substring(0, text.length - 1)
        }
        if (text.isEmpty()) return null
        if (text.first().isWhitespace() || text.last().isWhitespace()) return null
        return Pair(first..last, text)
    }
}
