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
}

sealed class ContentBlock {
    data class Text(val lines: List<RichLine>) : ContentBlock()
    data class Code(val language: String, val code: String) : ContentBlock()
}

object MarkdownFormatter {
    private val CODE_BLOCK_REGEX = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")
    private val BOLD_REGEX = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC_REGEX = Regex("(?<!\\*)\\*(.+?)(?<!\\*)\\*")
    private val CODE_INLINE_REGEX = Regex("`([^`]+)`")

    fun parseBlocks(raw: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var lastIndex = 0
        val matches = CODE_BLOCK_REGEX.findAll(raw)

        for (match in matches) {
            val textBefore = raw.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) {
                blocks.add(ContentBlock.Text(parseLines(textBefore)))
            }
            val lang = match.groupValues[1].trim()
            val code = match.groupValues[2].trimEnd('\n')
            blocks.add(ContentBlock.Code(lang, code))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            val remaining = raw.substring(lastIndex)
            if (remaining.isNotEmpty()) {
                blocks.add(ContentBlock.Text(parseLines(remaining)))
            }
        }

        return blocks
    }

    fun parseLines(raw: String): List<RichLine> {
        return raw.split("\n").map { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("# ") -> RichLine.Header(1, parseInline(trimmed.substring(2)))
                trimmed.startsWith("## ") -> RichLine.Header(2, parseInline(trimmed.substring(3)))
                trimmed.startsWith("### ") -> RichLine.Header(3, parseInline(trimmed.substring(4)))
                trimmed.startsWith("> ") -> RichLine.Quote(parseInline(trimmed.substring(2)))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> RichLine.Bullet(parseInline(trimmed.substring(2)))
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val dotIndex = trimmed.indexOf('.')
                    val num = trimmed.substring(0, dotIndex + 1)
                    val rest = trimmed.substring(dotIndex + 1).trimStart()
                    RichLine.Numbered(num, parseInline(rest))
                }
                else -> RichLine.Normal(parseInline(line))
            }
        }
    }

    fun parseInline(raw: String): List<InlineSpan> {
        if (raw.isEmpty()) return emptyList()
        val spans = mutableListOf<InlineSpan>()

        // 1. Split by links [title](url)
        var cursor = 0
        val linkMatches = LINK_REGEX.findAll(raw).toList()
        for (m in linkMatches) {
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

        // Find matches for bold, italic, code
        data class Token(val range: IntRange, val span: InlineSpan)
        val tokens = mutableListOf<Token>()

        BOLD_REGEX.findAll(raw).forEach {
            tokens.add(Token(it.range, InlineSpan.Bold(it.groupValues[1])))
        }
        CODE_INLINE_REGEX.findAll(raw).forEach {
            tokens.add(Token(it.range, InlineSpan.Monospace(it.groupValues[1])))
        }
        ITALIC_REGEX.findAll(raw).forEach {
            // Avoid overlapping with bold
            if (tokens.none { t -> it.range.first >= t.range.first && it.range.last <= t.range.last }) {
                tokens.add(Token(it.range, InlineSpan.Italic(it.groupValues[1])))
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
}
