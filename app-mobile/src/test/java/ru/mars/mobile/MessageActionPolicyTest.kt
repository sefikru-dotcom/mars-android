package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionPolicyTest {

    @Test
    fun `blank message offers no actions`() {
        assertTrue(MessageActionPolicy.actionsFor("").isEmpty())
        assertTrue(MessageActionPolicy.actionsFor("   \n ").isEmpty())
    }

    @Test
    fun `non-blank message offers copy quote share in order`() {
        val actions = MessageActionPolicy.actionsFor("привет")
        assertEquals(
            listOf(MessageActionKind.COPY, MessageActionKind.QUOTE, MessageActionKind.SHARE),
            actions,
        )
    }

    @Test
    fun `action labels are user-facing Russian`() {
        assertEquals("Копировать текст", MessageActionKind.COPY.label)
        assertEquals("Цитировать", MessageActionKind.QUOTE.label)
        assertEquals("Поделиться", MessageActionKind.SHARE.label)
    }

    @Test
    fun `single line quote follows the webui block format`() {
        assertEquals("> привет\n\n", MessageActionPolicy.formatQuote("привет"))
    }

    @Test
    fun `multi-line quote prefixes every line and keeps inner blank lines quoted`() {
        val text = "первая\n\nвторая"
        assertEquals("> первая\n>\n> вторая\n\n", MessageActionPolicy.formatQuote(text))
    }

    @Test
    fun `quote of blank text is an empty block`() {
        assertEquals("", MessageActionPolicy.formatQuote(""))
        assertEquals("", MessageActionPolicy.formatQuote("   "))
    }

    @Test
    fun `excerpt caps long messages by lines with ellipsis`() {
        val long = (1..30).joinToString("\n") { "строка $it" }
        val excerpt = MessageActionPolicy.quoteExcerpt(long)
        assertEquals(MessageActionPolicy.QUOTE_MAX_LINES, excerpt.lines().size)
        assertTrue(excerpt.endsWith("…"))
        assertTrue(excerpt.startsWith("строка 1"))
    }

    @Test
    fun `excerpt caps by characters too`() {
        val long = "абв".repeat(400) // 1200 chars, one line
        val excerpt = MessageActionPolicy.quoteExcerpt(long)
        assertEquals(MessageActionPolicy.QUOTE_MAX_CHARS + 1, excerpt.length) // cap + «…»
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun `short message excerpt is verbatim and trimmed`() {
        assertEquals("короткое", MessageActionPolicy.quoteExcerpt("  короткое \n"))
    }

    @Test
    fun `quote block combines excerpt and formatting`() {
        assertEquals("> ответ марса\n\n", MessageActionPolicy.quoteBlock("ответ марса"))
    }

    @Test
    fun `composeQuote into empty composer puts cursor after the block`() {
        val quote = MessageActionPolicy.composeQuote("", "текст")
        assertEquals("> текст\n\n", quote.text)
        assertEquals("> текст\n\n".length, quote.cursor)
    }

    @Test
    fun `composeQuote keeps the existing draft below the quote`() {
        val quote = MessageActionPolicy.composeQuote("черновик", "текст")
        assertEquals("> текст\n\nчерновик", quote.text)
        assertEquals("> текст\n\n".length, quote.cursor)
    }

    @Test
    fun `composeQuote of a blank message leaves the composer untouched`() {
        val quote = MessageActionPolicy.composeQuote("черновик", "   ")
        assertEquals("черновик", quote.text)
        assertEquals("черновик".length, quote.cursor)
    }

    @Test
    fun `share body is the message text verbatim`() {
        val text = "дословный > текст\nвторая строка"
        assertEquals(text, MessageActionPolicy.shareBody(text))
    }

    @Test
    fun `share subject and copy toast are stable strings`() {
        assertEquals("Сообщение из Марса", MessageActionPolicy.SHARE_SUBJECT)
        assertEquals("Скопировано", MessageActionPolicy.COPY_TOAST)
    }
}
