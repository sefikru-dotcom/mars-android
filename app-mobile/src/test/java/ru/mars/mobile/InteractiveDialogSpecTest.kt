package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.InteractiveDialogSpec.ApprovalOption
import ru.mars.mobile.InteractiveDialogSpec.ClarifyAction
import ru.mars.mobile.InteractiveDialogSpec.ClarifyMode
import ru.mars.mobile.InteractiveDialogSpec.ClarifyOption
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * Unit tests for [InteractiveDialogSpec] — the pure presentation logic behind the custom Mars
 * dialogs (approval command cards, decision buttons, clarify pills and the free-text editor
 * transitions). Framework-free by design: runs on the JVM without Robolectric.
 */
class InteractiveDialogSpecTest {

    private fun approval(
        command: String = "rm -rf /tmp/x",
        description: String = "",
        patternKeys: List<String> = emptyList(),
        allowPermanent: Boolean = false,
        allowSession: Boolean = true,
        pendingCount: Int = 1,
        tool: String? = null,
        riskLevel: String? = null,
    ) = ApprovalPayload(
        approvalId = "a-1",
        command = command,
        description = description,
        patternKeys = patternKeys,
        allowPermanent = allowPermanent,
        allowSession = allowSession,
        pendingCount = pendingCount,
        tool = tool,
        riskLevel = riskLevel,
    )

    private fun clarify(
        question: String = "Какой профиль?",
        choices: List<String> = emptyList(),
        pendingCount: Int = 1,
    ) = ClarifyPayload(clarifyId = "c-1", question = question, choices = choices, pendingCount = pendingCount)

    // --- approval options -----------------------------------------------------------------

    @Test
    fun approvalOptionsDefaultsAreOnceSessionDeny() {
        val options = InteractiveDialogSpec.approvalOptions(approval())
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY),
            options.map { it.choice }
        )
        assertEquals(listOf("Разрешить один раз", "Разрешить для этой сессии", "Отклонить"), options.map { it.label })
    }

    @Test
    fun approvalOptionsGateSessionByPayloadFlag() {
        val options = InteractiveDialogSpec.approvalOptions(approval(allowSession = false))
        assertEquals(listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY), options.map { it.choice })
    }

    @Test
    fun approvalOptionsAddAlwaysOnlyWhenPermanentAllowed() {
        val options = InteractiveDialogSpec.approvalOptions(approval(allowPermanent = true))
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS, ApprovalChoice.DENY),
            options.map { it.choice }
        )
    }

    @Test
    fun denyIsAlwaysLastAndFlaggedDestructive() {
        val options = InteractiveDialogSpec.approvalOptions(approval(allowPermanent = true))
        assertTrue(InteractiveDialogSpec.isDeny(options.last()))
        assertFalse(options.dropLast(1).any { InteractiveDialogSpec.isDeny(it) })
    }

    // --- approval presentation --------------------------------------------------------------

    @Test
    fun approvalTitleHasNoCounterForSinglePrompt() {
        assertEquals(InteractiveDialogSpec.APPROVAL_TITLE, InteractiveDialogSpec.approvalTitle(1))
        assertNull(InteractiveDialogSpec.pendingBadge(1))
    }

    @Test
    fun approvalTitleShowsQueueCounterForMultiple() {
        assertEquals("Требуется подтверждение · 1 из 3", InteractiveDialogSpec.approvalTitle(3))
        assertEquals("1 из 3", InteractiveDialogSpec.pendingBadge(3))
    }

    @Test
    fun descriptionIsTrimmedAndBlankWhenAbsent() {
        assertEquals("Удалить временный каталог", InteractiveDialogSpec.approvalDescription(approval(description = "  Удалить временный каталог\n")))
        assertEquals("", InteractiveDialogSpec.approvalDescription(approval()))
    }

    @Test
    fun codeBlockPrefersCommandFallsBackToToolThenEmpty() {
        assertEquals("rm -rf /tmp/x", InteractiveDialogSpec.approvalCodeBlock(approval()))
        assertEquals("web_search", InteractiveDialogSpec.approvalCodeBlock(approval(command = "  ", tool = " web_search ")))
        assertEquals("", InteractiveDialogSpec.approvalCodeBlock(approval(command = "", tool = null)))
        assertEquals("", InteractiveDialogSpec.approvalCodeBlock(approval(command = "", tool = "   ")))
    }

    @Test
    fun codeLabelCombinesToolAndRiskLevel() {
        assertEquals("terminal", InteractiveDialogSpec.approvalCodeLabel(approval(tool = "terminal")))
        assertEquals("terminal · high", InteractiveDialogSpec.approvalCodeLabel(approval(tool = "terminal", riskLevel = "HIGH")))
        assertNull(InteractiveDialogSpec.approvalCodeLabel(approval()))
        assertNull(InteractiveDialogSpec.approvalCodeLabel(approval(tool = "  ", riskLevel = null)))
    }

    @Test
    fun patternKeysAreTrimmedAndBlankDropped() {
        assertEquals(
            listOf("terminal:rm", "terminal:mv"),
            InteractiveDialogSpec.approvalPatternKeys(approval(patternKeys = listOf(" terminal:rm ", "", "  ", "terminal:mv")))
        )
        assertTrue(InteractiveDialogSpec.approvalPatternKeys(approval()).isEmpty())
    }

    // --- clarify modes ----------------------------------------------------------------------

    @Test
    fun clarifyWithChoicesStartsInPresetsEndingWithOwnAnswer() {
        val mode = InteractiveDialogSpec.clarifyMode(clarify(choices = listOf("Прод", "Тест")))
        assertTrue(mode is ClarifyMode.Presets)
        val options = (mode as ClarifyMode.Presets).options
        assertEquals(
            listOf("Прод", "Тест", InteractiveDialogSpec.OWN_ANSWER_LABEL),
            options.map { it.label }
        )
        assertTrue(options[0] is ClarifyOption.Preset)
        assertTrue(options.last() is ClarifyOption.OwnAnswer)
    }

    @Test
    fun clarifyWithoutChoicesStartsInFreeText() {
        assertEquals(ClarifyMode.FreeText(emptyList()), InteractiveDialogSpec.clarifyMode(clarify()))
    }

    @Test
    fun clarifyOptionsTrimAndDropBlanks() {
        val options = InteractiveDialogSpec.clarifyOptions(clarify(choices = listOf("  Прод  ", "", "Тест")))
        assertEquals(
            listOf(ClarifyOption.Preset("Прод"), ClarifyOption.Preset("Тест"), ClarifyOption.OwnAnswer),
            options
        )
    }

    @Test
    fun tappingPresetRespondsWithItsLabelVerbatim() {
        val mode = ClarifyMode.Presets(InteractiveDialogSpec.clarifyOptions(clarify(choices = listOf("Прод", "Тест"))))
        val action = InteractiveDialogSpec.onClarifyOptionTapped(mode, ClarifyOption.Preset("Прод"))
        assertEquals(ClarifyAction.Respond("Прод"), action)
    }

    @Test
    fun tappingOwnAnswerSwitchesToFreeTextCarryingPresets() {
        val mode = ClarifyMode.Presets(InteractiveDialogSpec.clarifyOptions(clarify(choices = listOf("Прод", "Тест"))))
        val action = InteractiveDialogSpec.onClarifyOptionTapped(mode, ClarifyOption.OwnAnswer)
        assertEquals(ClarifyAction.SwitchToFreeText(listOf("Прод", "Тест")), action)
    }

    @Test
    fun tappingOwnAnswerInFreeTextIsIgnored() {
        val action = InteractiveDialogSpec.onClarifyOptionTapped(ClarifyMode.FreeText(listOf("Прод")), ClarifyOption.OwnAnswer)
        assertEquals(ClarifyAction.None, action)
    }

    @Test
    fun freeTextSubmitTrimsAndRejectsBlank() {
        assertEquals("развернуть прод", InteractiveDialogSpec.clarifyFreeTextSubmit("  развернуть прод\n"))
        assertNull(InteractiveDialogSpec.clarifyFreeTextSubmit("   "))
        assertNull(InteractiveDialogSpec.clarifyFreeTextSubmit(""))
    }

    @Test
    fun clarifyQuestionIsTrimmed() {
        assertEquals("Какой профиль?", InteractiveDialogSpec.clarifyQuestion(clarify(question = "  Какой профиль? ")))
        assertEquals("", InteractiveDialogSpec.clarifyQuestion(clarify(question = "  ")))
    }

    // --- labels -----------------------------------------------------------------------------

    @Test
    fun dialogLabelsContainNoEmoji() {
        val labels = listOf(
            InteractiveDialogSpec.APPROVAL_TITLE,
            InteractiveDialogSpec.CLARIFY_TITLE,
            InteractiveDialogSpec.OWN_ANSWER_LABEL,
            InteractiveDialogSpec.SUBMIT_LABEL,
            InteractiveDialogSpec.BACK_LABEL,
            InteractiveDialogSpec.CANCEL_LABEL,
            InteractiveDialogSpec.ANSWER_HINT,
            InteractiveDialogSpec.PATTERN_PREFIX,
        ) + InteractiveDialogSpec.approvalOptions(approval(allowPermanent = true)).map(ApprovalOption::label)
        val emoji = Regex("[\\u2190-\\u2BFF\\uD800-\\uDFFF\\uFE0F]")
        labels.forEach { assertFalse("emoji in label: $it", emoji.containsMatchIn(it)) }
    }
}
