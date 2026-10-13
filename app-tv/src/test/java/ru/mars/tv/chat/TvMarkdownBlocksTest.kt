package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистый парсер markdown для ТВ: таблицы (заголовок + разделитель + строки),
 * чек-листы (`- [ ]` / `- [x]`), code-fence дословно, inline-разметка снимается.
 */
class TvMarkdownBlocksTest {

    @Test
    fun `plain text stays a single text block`() {
        val blocks = TvMarkdownBlocks.parse("Привет!\nВторая строка.")
        assertEquals(listOf<TvBlock>(TvBlock.Text("Привет!\nВторая строка.")), blocks)
        assertFalse(TvMarkdownBlocks.isRich("Привет!\nВторая строка."))
    }

    @Test
    fun `blank input gives no blocks`() {
        assertTrue(TvMarkdownBlocks.parse("").isEmpty())
        assertTrue(TvMarkdownBlocks.parse("   \n ").isEmpty())
    }

    @Test
    fun `table with pipes and separator parses into headers and rows`() {
        val raw = """
            Итоги:
            | Город   | Цена  |
            |---------|-------|
            | Москва  | 100   |
            | Питер   | 90    |
        """.trimIndent()
        val blocks = TvMarkdownBlocks.parse(raw)
        assertEquals(2, blocks.size)
        assertEquals(TvBlock.Text("Итоги:"), blocks[0])
        val table = blocks[1] as TvBlock.Table
        assertEquals(listOf("Город", "Цена"), table.headers)
        assertEquals(listOf(listOf("Москва", "100"), listOf("Питер", "90")), table.rows)
        assertTrue(TvMarkdownBlocks.isRich(raw))
    }

    @Test
    fun `table without outer pipes and with alignment markers parses too`() {
        val raw = """
            a | b
            :-: | ---:
            1 | 2
        """.trimIndent()
        val table = TvMarkdownBlocks.parse(raw).single() as TvBlock.Table
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `ragged rows are padded and trimmed to header width`() {
        val raw = """
            | x | y | z |
            |---|---|---|
            | 1 |
            | 1 | 2 | 3 | 4 |
        """.trimIndent()
        val table = TvMarkdownBlocks.parse(raw).single() as TvBlock.Table
        assertEquals(listOf(listOf("1", "", ""), listOf("1", "2", "3")), table.rows)
    }

    @Test
    fun `pipes without separator line are NOT a table`() {
        val raw = "a | b\nc | d"
        val blocks = TvMarkdownBlocks.parse(raw)
        assertEquals(listOf<TvBlock>(TvBlock.Text(raw)), blocks)
        assertFalse(TvMarkdownBlocks.isRich(raw))
    }

    @Test
    fun `task list parses checked and unchecked items and groups consecutive lines`() {
        val raw = """
            - [ ] купить хлеб
            - [x] **починить** CI
            * [X] третий пункт
            Обычный текст
            - [ ] хвост
        """.trimIndent()
        val blocks = TvMarkdownBlocks.parse(raw)
        assertEquals(3, blocks.size)
        val first = blocks[0] as TvBlock.TaskList
        assertEquals(
            listOf(TvTask(false, "купить хлеб"), TvTask(true, "починить CI"), TvTask(true, "третий пункт")),
            first.items,
        )
        assertEquals(TvBlock.Text("Обычный текст"), blocks[1])
        assertEquals(listOf(TvTask(false, "хвост")), (blocks[2] as TvBlock.TaskList).items)
        assertTrue(TvMarkdownBlocks.isRich(raw))
    }

    @Test
    fun `code fence keeps content verbatim and hides tables inside`() {
        val raw = """
            Пример:
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
            ```
        """.trimIndent()
        val blocks = TvMarkdownBlocks.parse(raw)
        assertEquals(1, blocks.size)
        val text = (blocks.single() as TvBlock.Text).text
        assertTrue(text.contains("| a | b |"))
        assertTrue(text.contains("```"))
        assertFalse(TvMarkdownBlocks.isRich(raw))
    }

    @Test
    fun `inline markup is stripped in cells and tasks`() {
        assertEquals("жирный и код", TvMarkdownBlocks.stripInline("**жирный** и `код`"))
        assertEquals("ссылка (https://x.y)", TvMarkdownBlocks.stripInline("[ссылка](https://x.y)"))
        assertEquals("устарело актуально", TvMarkdownBlocks.stripInline("~~устарело~~ актуально"))
        val raw = "| **A** | [b](http://b) |\n|---|---|\n| `c` | d |"
        val table = TvMarkdownBlocks.parse(raw).single() as TvBlock.Table
        assertEquals(listOf("A", "b (http://b)"), table.headers)
        assertEquals(listOf(listOf("c", "d")), table.rows)
    }

    @Test
    fun `mixed answer keeps block order`() {
        val raw = """
            Вступление

            - [ ] шаг

            | k | v |
            |---|---|
            | 1 | 2 |

            Финал
        """.trimIndent()
        val kinds = TvMarkdownBlocks.parse(raw).map { it::class }
        assertEquals(
            listOf(TvBlock.Text::class, TvBlock.TaskList::class, TvBlock.Table::class, TvBlock.Text::class),
            kinds,
        )
    }
}
