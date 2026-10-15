package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A16: свёртка набора вызовов инструментов в одну строку-сводку.
 *
 * Правила, которые здесь закреплены:
 *  - один вызов и пустой список группой НЕ считаются (карточка рисуется как раньше);
 *  - два и более — группа; в свёрнутом виде [ToolGroupModel.cards] ПУСТ, чтобы
 *    Activity не строила дочерние вью впустую;
 *  - ручное переопределение перебивает режим активности в обе стороны;
 *  - текст сводки согласуется по-русски, включая ловушку 11-14.
 */
class ToolGroupPresenterTest {

    private fun tools(count: Int, withResult: Boolean = true): List<ToolExecution> =
        (1..count).map { i ->
            ToolExecution(
                id = "t$i",
                name = "run_command_$i",
                arguments = "{\"cmd\":\"echo $i\"}",
                result = if (withResult) "ok" else null,
            )
        }

    @Test
    fun `empty list is not a group`() {
        val model = ToolGroupPresenter.model(emptyList())
        assertEquals(ToolGroupState.NONE, model.state)
        assertFalse(model.showsSummary)
        assertFalse(model.showsCards)
        assertNull(model.iconToolName)
    }

    @Test
    fun `single tool is not a group and keeps its card`() {
        val model = ToolGroupPresenter.model(tools(1))
        assertEquals(ToolGroupState.NONE, model.state)
        assertFalse(model.showsSummary)
        assertTrue(model.showsCards)
        assertEquals(1, model.cards.size)
    }

    @Test
    fun `two tools collapse by default outside stream full`() {
        val model = ToolGroupPresenter.model(
            tools = tools(2),
            mode = ActivityDisplayMode.STREAM_QUIET,
        )
        assertEquals(ToolGroupState.COLLAPSED, model.state)
        assertTrue(model.showsSummary)
        assertFalse(model.showsCards)
        assertTrue("свёрнутая группа не должна нести карточки", model.cards.isEmpty())
    }

    @Test
    fun `stream full expands the group by itself`() {
        val model = ToolGroupPresenter.model(
            tools = tools(6),
            mode = ActivityDisplayMode.STREAM_FULL,
        )
        assertEquals(ToolGroupState.EXPANDED, model.state)
        assertEquals(6, model.cards.size)
    }

    @Test
    fun `manual collapse overrides stream full`() {
        val model = ToolGroupPresenter.model(
            tools = tools(6),
            mode = ActivityDisplayMode.STREAM_FULL,
            manualExpanded = false,
        )
        assertEquals(ToolGroupState.COLLAPSED, model.state)
        assertTrue(model.cards.isEmpty())
    }

    @Test
    fun `manual expand overrides quiet mode`() {
        val model = ToolGroupPresenter.model(
            tools = tools(6),
            mode = ActivityDisplayMode.STREAM_QUIET,
            manualExpanded = true,
        )
        assertEquals(ToolGroupState.EXPANDED, model.state)
        assertEquals(6, model.cards.size)
    }

    @Test
    fun `unfinished tool marks the group as running`() {
        val model = ToolGroupPresenter.model(tools(3, withResult = false))
        assertTrue(model.summary.contains(ToolGroupPresenter.RUNNING_SUFFIX))
    }

    @Test
    fun `finished group reports done`() {
        val model = ToolGroupPresenter.model(tools(3))
        assertTrue(model.summary.contains(ToolGroupPresenter.DONE_SUFFIX))
    }

    @Test
    fun `running turn keeps suffix even when results exist`() {
        val model = ToolGroupPresenter.model(tools(2), turnRunning = true)
        assertTrue(model.summary.contains(ToolGroupPresenter.RUNNING_SUFFIX))
    }

    @Test
    fun `summary and description carry the count`() {
        val model = ToolGroupPresenter.model(tools(12))
        assertTrue(model.summary.startsWith("12 "))
        assertTrue(model.summaryDescription.contains("12"))
    }

    @Test
    fun `plural handles the 11 to 14 trap`() {
        assertEquals("инструмент", ToolGroupPresenter.plural(1))
        assertEquals("инструмента", ToolGroupPresenter.plural(3))
        assertEquals("инструментов", ToolGroupPresenter.plural(6))
        assertEquals("инструментов", ToolGroupPresenter.plural(11))
        assertEquals("инструментов", ToolGroupPresenter.plural(12))
        assertEquals("инструментов", ToolGroupPresenter.plural(14))
        assertEquals("инструмент", ToolGroupPresenter.plural(21))
        assertEquals("инструмента", ToolGroupPresenter.plural(22))
    }

    @Test
    fun `summary carries no emoji or typographic glyphs`() {
        val text = ToolGroupPresenter.summaryText(3, running = false)
        // Разрешены: ASCII, кириллица, пробел и разделитель «·» из макета сводки.
        val offenders = text.filter { ch ->
            ch.code >= 128 &&
                ch !in 'А'..'я' && ch != 'ё' && ch != 'Ё' && ch != '·'
        }
        assertTrue("в сводке не должно быть эмодзи и глифов, найдено: $offenders", offenders.isEmpty())
    }

    @Test
    fun `collapsed group does not expose cards`() {
        val model = ToolGroupPresenter.model(tools(10), mode = ActivityDisplayMode.STREAM_QUIET)
        assertTrue(model.cards.isEmpty())
        assertFalse(model.chevronExpanded)
    }
}
