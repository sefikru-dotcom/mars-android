package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionUsage

/** A14: презентация расхода сессии (пилюля, детали, формат стоимости). */
class SessionUsagePolicyTest {

    @Test
    fun `no usage hides the pill`() {
        val view = SessionUsagePolicy.view(null)
        assertFalse(view.visible)
        assertNull(view.text)
    }

    @Test
    fun `zero counters without price hide the pill instead of showing fake zeros`() {
        val view = SessionUsagePolicy.view(SessionUsage())
        assertFalse(view.visible)
    }

    @Test
    fun `tokens and cost render as one compact pill`() {
        val view = SessionUsagePolicy.view(SessionUsage(totalTokens = 12_345, cost = 0.42))
        assertTrue(view.visible)
        assertEquals("12.3k ток · $0.42", view.text)
        assertEquals("Расход сессии: 12.3k токенов, стоимость $0.42", view.contentDescription)
    }

    @Test
    fun `unknown cost is omitted from the pill but tokens still show`() {
        val view = SessionUsagePolicy.view(SessionUsage(totalTokens = 900))
        assertTrue(view.visible)
        assertEquals("900 ток", view.text)
    }

    @Test
    fun `cost alone is enough to show the pill (provider reported price, no tokens)`() {
        val view = SessionUsagePolicy.view(SessionUsage(cost = 1.5))
        assertTrue(view.visible)
        assertEquals("$1.50", view.text)
    }

    @Test
    fun `sub-cent cost keeps four decimals instead of collapsing to zero`() {
        assertEquals("$0.0003", SessionUsagePolicy.formatCost(0.0003))
    }

    @Test
    fun `zero or negative cost means unknown, not free`() {
        assertNull(SessionUsagePolicy.formatCost(0.0))
        assertNull(SessionUsagePolicy.formatCost(-1.0))
        assertNull(SessionUsagePolicy.formatCost(null))
        assertNull(SessionUsagePolicy.formatCost(Double.NaN))
    }

    @Test
    fun `details text breaks tokens down and names the missing price`() {
        val text = SessionUsagePolicy.detailsText(
            SessionUsage(totalTokens = 300, promptTokens = 200, completionTokens = 100),
        )
        assertEquals(
            "Токены: 300\nЗапрос: 200\nОтвет: 100\nСтоимость: ${SessionUsagePolicy.COST_UNAVAILABLE}",
            text,
        )
    }

    @Test
    fun `details of an unmeasured session say so instead of printing zeros`() {
        assertEquals(SessionUsagePolicy.UNAVAILABLE_DETAILS, SessionUsagePolicy.detailsText(null))
        assertEquals(SessionUsagePolicy.UNAVAILABLE_DETAILS, SessionUsagePolicy.detailsText(SessionUsage()))
    }
}
