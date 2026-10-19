package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkerStatusInfo

/**
 * Строка субагента в списке диалогов: тема работы из `worker_status.goal`
 * вместо безликой роли. Правила — паритет с WebUI `sessions.js`
 * (`childLabelFor`), но без глифов: дизайн-система мобильного их банит,
 * статус передаётся словом + тоном.
 */
class SubagentWorkLabelPolicyTest {

    private val now = 1_800_000_000.0

    private fun status(
        goal: String? = "Починить парсер сессий",
        rawStatus: String? = "running",
        duration: Double? = null,
        startedAt: Double? = null,
        lastAction: String? = null,
        failureReason: String? = null,
    ) = WorkerStatusInfo(
        goal = goal,
        status = rawStatus,
        durationSeconds = duration,
        startedAtEpochSeconds = startedAt,
        lastAction = lastAction,
        failureReason = failureReason,
    )

    @Test
    fun `running shows goal and elapsed time from started_at`() {
        val label = SubagentWorkLabelPolicy.label(
            status(startedAt = now - 134.0),
            now,
        )
        assertEquals("работает 2м 14с · Починить парсер сессий", label.text)
        assertEquals(SubagentWorkTone.RUNNING, label.tone)
        assertTrue(label.active)
    }

    @Test
    fun `running falls back to duration when started_at is absent`() {
        val label = SubagentWorkLabelPolicy.label(status(duration = 5.0), now)
        assertEquals("работает 5с · Починить парсер сессий", label.text)
    }

    @Test
    fun `completed carries the duration phrase`() {
        val label = SubagentWorkLabelPolicy.label(
            status(rawStatus = "completed", duration = 134.0),
            now,
        )
        assertEquals("выполнено за 2м 14с · Починить парсер сессий", label.text)
        assertEquals(SubagentWorkTone.DONE, label.tone)
        assertFalse(label.active)
    }

    @Test
    fun `failed stopped interrupted and unknown map to their tones`() {
        val cases = mapOf(
            "failed" to SubagentWorkTone.ERROR,
            "stopped" to SubagentWorkTone.STOPPED,
            "interrupted" to SubagentWorkTone.STOPPED,
            "unknown" to SubagentWorkTone.UNKNOWN,
        )
        for ((raw, tone) in cases) {
            val label = SubagentWorkLabelPolicy.label(status(rawStatus = raw), now)
            assertEquals("статус $raw", tone, label.tone)
            assertFalse("статус $raw не активен", label.active)
            assertTrue("тема в подписи $raw", label.text.endsWith(" · Починить парсер сессий"))
        }
        assertEquals("провал · Починить парсер сессий", SubagentWorkLabelPolicy.label(status(rawStatus = "failed"), now).text)
        assertEquals("остановлен · Починить парсер сессий", SubagentWorkLabelPolicy.label(status(rawStatus = "stopped"), now).text)
        assertEquals("остановлен · Починить парсер сессий", SubagentWorkLabelPolicy.label(status(rawStatus = "interrupted"), now).text)
        assertEquals("статус неизвестен · Починить парсер сессий", SubagentWorkLabelPolicy.label(status(rawStatus = "unknown"), now).text)
    }

    @Test
    fun `status matching ignores case and surrounding spaces`() {
        assertEquals(SubagentWorkTone.RUNNING, SubagentWorkLabelPolicy.toneOf(" Running "))
        assertEquals(SubagentWorkTone.DONE, SubagentWorkLabelPolicy.toneOf("COMPLETED"))
        assertEquals(SubagentWorkTone.UNKNOWN, SubagentWorkLabelPolicy.toneOf("что-то новое"))
        assertEquals(SubagentWorkTone.UNKNOWN, SubagentWorkLabelPolicy.toneOf(null))
    }

    @Test
    fun `empty or missing goal gives the neutral fallback instead of untitled`() {
        for (goal in listOf(null, "", "   ", "\n\t ")) {
            val label = SubagentWorkLabelPolicy.label(status(goal = goal, rawStatus = "completed"), now)
            assertEquals("Субагент", SubagentWorkLabelPolicy.FALLBACK_GOAL)
            assertEquals("выполнено · Субагент", label.text)
            assertFalse("фолбэк не содержит Untitled", label.text.contains("Untitled"))
        }
    }

    @Test
    fun `missing worker_status is a neutral subagent label`() {
        val label = SubagentWorkLabelPolicy.label(null, now)
        assertEquals("статус неизвестен · Субагент", label.text)
        assertEquals(SubagentWorkTone.UNKNOWN, label.tone)
        assertFalse(label.active)
    }

    @Test
    fun `goal is truncated to sixty four characters`() {
        val long = "а".repeat(100)
        val label = SubagentWorkLabelPolicy.goalLabel(long)
        assertEquals(SubagentWorkLabelPolicy.MAX_GOAL_CHARS, label.length)
        assertEquals(64, SubagentWorkLabelPolicy.MAX_GOAL_CHARS)
        assertEquals("а".repeat(64), label)
    }

    @Test
    fun `goal shorter than the limit survives untouched and whitespace collapses`() {
        assertEquals("Короткая тема", SubagentWorkLabelPolicy.goalLabel("  Короткая   тема "))
        assertEquals("Тема в две строки", SubagentWorkLabelPolicy.goalLabel("Тема в\nдве\tстроки"))
        val exact = "б".repeat(64)
        assertEquals(exact, SubagentWorkLabelPolicy.goalLabel(exact))
    }

    @Test
    fun `duration is omitted when null or zero`() {
        val runningNull = SubagentWorkLabelPolicy.label(status(rawStatus = "running", duration = null), now)
        assertEquals("работает · Починить парсер сессий", runningNull.text)
        val runningZero = SubagentWorkLabelPolicy.label(status(rawStatus = "running", duration = 0.0), now)
        assertEquals("работает · Починить парсер сессий", runningZero.text)
        val doneNull = SubagentWorkLabelPolicy.label(status(rawStatus = "completed", duration = null), now)
        assertEquals("выполнено · Починить парсер сессий", doneNull.text)
        val doneZero = SubagentWorkLabelPolicy.label(status(rawStatus = "completed", duration = 0.0), now)
        assertEquals("выполнено · Починить парсер сессий", doneZero.text)
        val negative = SubagentWorkLabelPolicy.label(status(rawStatus = "completed", duration = -3.0), now)
        assertEquals("выполнено · Починить парсер сессий", negative.text)
    }

    @Test
    fun `duration formatting switches from seconds to minutes and seconds`() {
        assertEquals("59с", SubagentWorkLabelPolicy.formatDuration(59.4))
        assertEquals("1м 0с", SubagentWorkLabelPolicy.formatDuration(60.0))
        assertEquals("2м 14с", SubagentWorkLabelPolicy.formatDuration(134.0))
        assertNull(SubagentWorkLabelPolicy.formatDuration(null))
        assertNull(SubagentWorkLabelPolicy.formatDuration(0.0))
        assertNull(SubagentWorkLabelPolicy.formatDuration(Double.NaN))
    }

    @Test
    fun `running clock never goes negative on skewed server time`() {
        val label = SubagentWorkLabelPolicy.label(status(startedAt = now + 500.0), now)
        assertEquals("работает · Починить парсер сессий", label.text)
    }

    @Test
    fun `label carries no banned design glyphs`() {
        val banned = setOf(
            '\u2713', '\u2714', '\u2717', '\u2715', '\u00d7', '\u25CF',
            '\u25A0', '\u25A1', '\u26A0', '\u2B50', '\u2192', '\u200D', '\uFE0F',
        )
        val samples = listOf("running", "completed", "failed", "stopped", "interrupted", "unknown", null)
            .map { SubagentWorkLabelPolicy.label(status(rawStatus = it, duration = 134.0), now).text } +
            SubagentWorkLabelPolicy.label(null, now).text
        for (sample in samples) {
            for (glyph in banned) {
                assertFalse("Глиф '$glyph' запрещён дизайн-системой: $sample", sample.contains(glyph))
            }
        }
    }
}
