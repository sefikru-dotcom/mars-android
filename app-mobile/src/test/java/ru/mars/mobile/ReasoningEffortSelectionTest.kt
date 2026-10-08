package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ReasoningStatus

/**
 * Domain-state tests for the reasoning effort selector (P1 slice). The ladder is
 * server-driven (`supported_efforts` from GET /api/reasoning): the client never
 * invents levels, hides the control on unknown/failed state and records only
 * explicit user picks.
 */
class ReasoningEffortSelectionTest {

    private class FakeStorage : ReasoningChoiceStorage {
        var saved: ReasoningChoice? = null
        var saveCount = 0
        override fun load(): ReasoningChoice? = saved
        override fun save(choice: ReasoningChoice) { saved = choice; saveCount++ }
        override fun clear() { saved = null }
    }

    private fun status(
        effort: String? = "high",
        supported: List<String> = listOf("low", "high"),
    ) = ReasoningStatus(
        showReasoning = true,
        reasoningEffort = effort,
        source = "default",
        supportedEfforts = supported,
        supportsReasoningEffort = supported.isNotEmpty(),
        supportsThinkingToggle = supported.isNotEmpty(),
    )

    // --- unknown / failed state is safe -----------------------------------------------------------

    @Test
    fun hiddenBeforeAnyServerStatus() {
        val selection = ReasoningEffortSelection(FakeStorage())
        assertNull(selection.buttonLabel())
        assertTrue(selection.options().isEmpty())
        assertNull(selection.effectiveEffort())
    }

    @Test
    fun hiddenAfterFailedStatusFetch() {
        val selection = ReasoningEffortSelection(FakeStorage())
        selection.applyStatus(status())
        selection.applyStatus(null) // Failed → unknown state again
        assertNull(selection.buttonLabel())
        assertTrue(selection.options().isEmpty())
    }

    @Test
    fun hiddenForThinkingToggleOnlyModelWithoutLadder() {
        val selection = ReasoningEffortSelection(FakeStorage())
        selection.applyStatus(status(effort = null, supported = emptyList()))
        assertTrue(selection.options().isEmpty())
        assertNull(selection.buttonLabel())
    }

    // --- server-driven ladder ---------------------------------------------------------------------

    @Test
    fun optionsComeFromServerSupportedEffortsWithAutoFirst() {
        val selection = ReasoningEffortSelection(FakeStorage())
        selection.applyStatus(status(supported = listOf("low", "medium", "high")))

        assertEquals(
            listOf(
                ReasoningOption(null, "Авто"),
                ReasoningOption("low", "Low"),
                ReasoningOption("medium", "Medium"),
                ReasoningOption("high", "High"),
            ),
            selection.options(),
        )
    }

    @Test
    fun optionsNormalizeAndDedupeServerValues() {
        val selection = ReasoningEffortSelection(FakeStorage())
        selection.applyStatus(status(supported = listOf(" High ", "high", "")))

        assertEquals(listOf(ReasoningOption(null, "Авто"), ReasoningOption("high", "High")), selection.options())
    }

    @Test
    fun labelShowsServerEffectiveEffort() {
        val selection = ReasoningEffortSelection(FakeStorage())
        selection.applyStatus(status(effort = "high"))
        assertEquals("High", selection.buttonLabel())
        assertEquals("high", selection.effectiveEffort())

        selection.applyStatus(status(effort = null))
        assertEquals("Авто", selection.buttonLabel())
        assertNull(selection.effectiveEffort())
    }

    // --- explicit pick ------------------------------------------------------------------------------

    @Test
    fun selectRecordsExplicitPickNormalized() {
        val storage = FakeStorage()
        val selection = ReasoningEffortSelection(storage)
        selection.applyStatus(status())

        val choice = selection.select(" High ")
        assertEquals(ReasoningChoice("high", explicitPick = true), choice)
        assertEquals(choice, storage.saved)
        assertEquals(1, storage.saveCount)
    }

    @Test
    fun selectNullRecordsExplicitAuto() {
        val storage = FakeStorage()
        val selection = ReasoningEffortSelection(storage)
        selection.select(null)

        assertEquals(ReasoningChoice(null, explicitPick = true), storage.saved)
        assertEquals(ReasoningChoice(null, explicitPick = true), selection.current)
    }

    @Test
    fun persistedPickRestoresAcrossInstancesAndFallsBackToServerEffort() {
        val storage = FakeStorage().apply { saved = ReasoningChoice("low", explicitPick = true) }
        val selection = ReasoningEffortSelection(storage)
        assertEquals(ReasoningChoice("low", explicitPick = true), selection.current)

        // Before any status the restored pick labels the chip.
        assertEquals("Low", selection.buttonLabel())
        assertEquals("low", selection.effectiveEffort())

        // Server status wins once fetched (it reports the coerced effective effort).
        selection.applyStatus(status(effort = "high"))
        assertEquals("high", selection.effectiveEffort())
    }
}
