package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.tv.chat.TvTranscriptRetentionPolicy.Kind

/**
 * Политика удержания транскрипта: срез истории под рендер, cap строк активности,
 * план подрезки общего числа View и счётчики маркера «…ранее скрыто».
 */
class TvTranscriptRetentionPolicyTest {

    // --- historySlice ------------------------------------------------------------------

    @Test
    fun `history slice caps a long transcript to the newest N rows`() {
        val slice = TvTranscriptRetentionPolicy.historySlice(500, maxRendered = 200)
        assertEquals(300, slice.firstRowIndex)
        assertEquals(200, slice.renderedRows)
        assertEquals(300, slice.hiddenMessages)
    }

    @Test
    fun `history slice keeps a short transcript whole`() {
        val slice = TvTranscriptRetentionPolicy.historySlice(50, maxRendered = 200)
        assertEquals(0, slice.firstRowIndex)
        assertEquals(50, slice.renderedRows)
        assertEquals(0, slice.hiddenMessages)
    }

    @Test
    fun `history slice handles empty and negative totals`() {
        val empty = TvTranscriptRetentionPolicy.historySlice(0)
        assertEquals(0, empty.firstRowIndex)
        assertEquals(0, empty.renderedRows)
        assertEquals(0, empty.hiddenMessages)
        val negative = TvTranscriptRetentionPolicy.historySlice(-5)
        assertEquals(0, negative.renderedRows)
        assertEquals(0, negative.hiddenMessages)
    }

    @Test
    fun `history slice renders the TAIL - newest rows are never dropped`() {
        val slice = TvTranscriptRetentionPolicy.historySlice(1000, maxRendered = 200)
        // Индекс первой рисуемой строки + число рисуемых == общее число: срез — хвост.
        assertEquals(1000, slice.firstRowIndex + slice.renderedRows)
        assertEquals(800, slice.hiddenMessages)
    }

    // --- activityOverflow ---------------------------------------------------------------

    @Test
    fun `activity overflow trims only the excess over the cap`() {
        assertEquals(50, TvTranscriptRetentionPolicy.activityOverflow(250, cap = 200))
        assertEquals(1, TvTranscriptRetentionPolicy.activityOverflow(201, cap = 200))
        assertEquals(0, TvTranscriptRetentionPolicy.activityOverflow(200, cap = 200))
        assertEquals(0, TvTranscriptRetentionPolicy.activityOverflow(0, cap = 200))
    }

    @Test
    fun `activity cap keeps converging under a long 'everything' turn`() {
        // Симуляция: строки приходят без остановки; после каждой — trim. Видимых строк
        // никогда не больше cap, счётчик скрытых растёт монотонно.
        var visible = 0
        var hidden = 0
        repeat(5000) {
            visible++
            val overflow = TvTranscriptRetentionPolicy.activityOverflow(visible)
            if (overflow > 0) {
                visible -= overflow
                hidden += overflow
            }
            assertTrue(visible <= TvTranscriptRetentionPolicy.ACTIVITY_LINE_CAP)
        }
        assertEquals(5000 - TvTranscriptRetentionPolicy.ACTIVITY_LINE_CAP, hidden)
    }

    // --- trimPlan -------------------------------------------------------------------------

    @Test
    fun `trim plan is empty while under the cap`() {
        val kinds = List(10) { Kind.MESSAGE }
        assertTrue(TvTranscriptRetentionPolicy.trimPlan(kinds, cap = 450).isEmpty())
        assertTrue(TvTranscriptRetentionPolicy.trimPlan(kinds, cap = 10).isEmpty())
    }

    @Test
    fun `trim plan removes the oldest entries down to the cap`() {
        val kinds = List(10) { Kind.MESSAGE }
        val plan = TvTranscriptRetentionPolicy.trimPlan(kinds, cap = 6)
        assertEquals(listOf(0, 1, 2, 3), plan)
    }

    @Test
    fun `trim plan never removes the newest entry even over the cap`() {
        val plan = TvTranscriptRetentionPolicy.trimPlan(listOf(Kind.MESSAGE, Kind.MESSAGE), cap = 1)
        assertEquals(listOf(0), plan)
    }

    @Test
    fun `trim plan skips protected entries - cards, live bubble and the hidden marker survive`() {
        val kinds = listOf(Kind.PROTECTED, Kind.MESSAGE, Kind.ACTIVITY_LINE, Kind.MESSAGE, Kind.PROTECTED)
        val plan = TvTranscriptRetentionPolicy.trimPlan(kinds, cap = 3)
        assertEquals(listOf(1, 2), plan)
    }

    @Test
    fun `trim plan is best effort when protected entries alone exceed the cap`() {
        val kinds = List(6) { Kind.PROTECTED }
        assertTrue(TvTranscriptRetentionPolicy.trimPlan(kinds, cap = 2).isEmpty())
    }

    @Test
    fun `trim plan keeps the container within cap in a mixed live transcript`() {
        // Лента как в жизни: сообщения, строки активности, защищённые карточки и пузырь.
        val kinds = buildList {
            add(Kind.PROTECTED) // маркер «…ранее скрыто» в начале
            repeat(300) { add(Kind.MESSAGE) }
            repeat(200) { add(Kind.ACTIVITY_LINE) }
            add(Kind.PROTECTED) // интерактивная карточка
            add(Kind.PROTECTED) // живой пузырь — последний ребёнок
        }
        val plan = TvTranscriptRetentionPolicy.trimPlan(kinds, cap = TvTranscriptRetentionPolicy.MAX_TRANSCRIPT_VIEWS)
        val remaining = kinds.size - plan.size
        assertTrue(remaining <= TvTranscriptRetentionPolicy.MAX_TRANSCRIPT_VIEWS)
        // Подрезка идёт строго с начала: удалённые индексы — префикс незащищённых записей.
        assertEquals(plan.sorted(), plan)
        assertTrue(plan.all { kinds[it] != Kind.PROTECTED })
        // Самые свежие записи (хвост) выживают полностью.
        val maxRemoved = plan.max()
        assertTrue(maxRemoved < kinds.lastIndex)
        assertTrue((maxRemoved + 1 until kinds.size).none { it in plan.toSet() })
    }

    // --- hiddenMarkerText -------------------------------------------------------------------

    @Test
    fun `hidden marker is null when nothing is hidden`() {
        assertNull(TvTranscriptRetentionPolicy.hiddenMarkerText(0, 0))
    }

    @Test
    fun `hidden marker counts messages and activity lines with russian plurals`() {
        assertEquals(
            "…ранее скрыто: 12 сообщений",
            TvTranscriptRetentionPolicy.hiddenMarkerText(12, 0),
        )
        assertEquals(
            "…ранее скрыто: 1 сообщение",
            TvTranscriptRetentionPolicy.hiddenMarkerText(1, 0),
        )
        assertEquals(
            "…ранее скрыто: 2 сообщения",
            TvTranscriptRetentionPolicy.hiddenMarkerText(2, 0),
        )
        assertEquals(
            "…ранее скрыто: 340 строк активности",
            TvTranscriptRetentionPolicy.hiddenMarkerText(0, 340),
        )
        assertEquals(
            "…ранее скрыто: 21 строка активности",
            TvTranscriptRetentionPolicy.hiddenMarkerText(0, 21),
        )
        assertEquals(
            "…ранее скрыто: 3 сообщения и 45 строк активности",
            TvTranscriptRetentionPolicy.hiddenMarkerText(3, 45),
        )
    }

    @Test
    fun `hidden marker ignores non-positive counters`() {
        assertNull(TvTranscriptRetentionPolicy.hiddenMarkerText(-3, 0))
        assertEquals(
            "…ранее скрыто: 5 сообщений",
            TvTranscriptRetentionPolicy.hiddenMarkerText(5, -2),
        )
    }

    // --- пагинация головы истории (D-pad «Показать ещё») --------------------------------

    @Test
    fun `the pager adds one page to the render window`() {
        assertEquals(
            TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES + TvTranscriptRetentionPolicy.PAGE_SIZE,
            TvTranscriptRetentionPolicy.nextWindow(
                TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES,
                totalRows = 1_000,
            ),
        )
    }

    @Test
    fun `the pager never grows past the whole history`() {
        assertEquals(210, TvTranscriptRetentionPolicy.nextWindow(200, totalRows = 210))
    }

    @Test
    fun `the pager is idempotent when everything is already rendered`() {
        assertEquals(200, TvTranscriptRetentionPolicy.nextWindow(200, totalRows = 200))
        assertEquals(0, TvTranscriptRetentionPolicy.nextWindow(0, totalRows = 0))
    }

    @Test
    fun `a zero page still advances by one row - the action never becomes a no-op`() {
        assertEquals(201, TvTranscriptRetentionPolicy.nextWindow(200, totalRows = 500, page = 0))
    }

    @Test
    fun `load more is offered only while something is hidden`() {
        assertTrue(TvTranscriptRetentionPolicy.canLoadMore(1))
        assertTrue(!TvTranscriptRetentionPolicy.canLoadMore(0))
        assertTrue(!TvTranscriptRetentionPolicy.canLoadMore(-5))
    }

    @Test
    fun `the pager text tells the user the marker is pressable`() {
        assertEquals(
            "…ранее скрыто: 300 сообщений — нажмите, чтобы показать",
            TvTranscriptRetentionPolicy.pagerText(300, 0),
        )
    }

    @Test
    fun `the pager text stays plain when only activity lines are hidden`() {
        assertEquals(
            "…ранее скрыто: 12 строк активности",
            TvTranscriptRetentionPolicy.pagerText(0, 12),
        )
        assertNull(TvTranscriptRetentionPolicy.pagerText(0, 0))
    }

    @Test
    fun `a second page reveals the head of the history`() {
        val total = 450
        val first = TvTranscriptRetentionPolicy.historySlice(total, TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES)
        assertEquals(250, first.firstRowIndex)
        val window = TvTranscriptRetentionPolicy.nextWindow(first.renderedRows, total)
        val second = TvTranscriptRetentionPolicy.historySlice(total, window)
        assertEquals(50, second.firstRowIndex)
        assertEquals(400, second.renderedRows)
        assertTrue(second.hiddenMessages < first.hiddenMessages)
    }
}
