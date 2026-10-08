package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice
import ru.mars.tv.TvInteractiveCardSpec.ClarifyAction
import ru.mars.tv.TvInteractiveCardSpec.ClarifyMode
import ru.mars.tv.TvInteractiveCardSpec.ClarifyOption

/**
 * Pure-JVM contract of the TV interactive card presentation ([TvInteractiveCardSpec]):
 * option gating by payload flags, YOLO grant visibility, deny/primary classification, queue
 * titles, code-block fallbacks and the clarify modes — including the EMPTY-choices degradation
 * to the free-text editor (an open question must still be answerable on TV).
 */
class TvInteractiveCardSpecTest {

    private fun payload(
        allowSession: Boolean = true,
        allowPermanent: Boolean = false,
        pendingCount: Int = 1,
        command: String = "rm -rf /tmp/x",
        description: String = "",
        tool: String? = null,
        risk: String? = null,
        patternKeys: List<String> = emptyList(),
    ) = ApprovalPayload(
        approvalId = "a1", command = command, description = description,
        patternKeys = patternKeys, allowPermanent = allowPermanent, allowSession = allowSession,
        pendingCount = pendingCount, tool = tool, riskLevel = risk,
    )

    @Test
    fun approvalOptionsGatedByPayloadFlagsWithTrailingDeny() {
        val options = TvInteractiveCardSpec.approvalOptions(payload())
        assertEquals(
            listOf("Разрешить один раз", "Разрешить для этой сессии", "Больше не спрашивать (YOLO)", "Отклонить"),
            options.map { it.label },
        )
        assertEquals(ApprovalChoice.ONCE, options[0].choice)
        assertEquals(ApprovalChoice.SESSION, options[1].choice)
        assertTrue("YOLO grant submits ONCE with yolo=true", options[2].yolo)
        assertEquals(ApprovalChoice.DENY, options.last().choice)
        assertTrue("deny is always last", TvInteractiveCardSpec.isDeny(options.last()))
    }

    @Test
    fun approvalOptionsIncludeAlwaysOnlyWhenAllowedAndHideYoloWhenActive() {
        val full = TvInteractiveCardSpec.approvalOptions(payload(allowPermanent = true))
        assertTrue(full.any { it.choice == ApprovalChoice.ALWAYS })
        val noSession = TvInteractiveCardSpec.approvalOptions(payload(allowSession = false))
        assertFalse(noSession.any { it.choice == ApprovalChoice.SESSION })
        val yoloOn = TvInteractiveCardSpec.approvalOptions(payload(), yoloEnabled = true)
        assertFalse("nothing left to grant when YOLO is already active", yoloOn.any { it.yolo })
    }

    @Test
    fun primaryIsOnceGrantDenyIsDestructiveYoloGrantIsNeither() {
        val options = TvInteractiveCardSpec.approvalOptions(payload(allowPermanent = true))
        assertTrue(TvInteractiveCardSpec.isPrimary(options.first { it.choice == ApprovalChoice.ONCE && !it.yolo }))
        assertTrue(TvInteractiveCardSpec.isDeny(options.first { it.choice == ApprovalChoice.DENY }))
        val yolo = options.first { it.yolo }
        assertFalse(TvInteractiveCardSpec.isPrimary(yolo))
        assertFalse(TvInteractiveCardSpec.isDeny(yolo))
    }

    @Test
    fun approvalTitleCarriesQueueIndicatorOnlyWhenPending() {
        assertEquals("Требуется подтверждение", TvInteractiveCardSpec.approvalTitle(1))
        assertEquals("Требуется подтверждение · 1 из 3", TvInteractiveCardSpec.approvalTitle(3))
        assertEquals("1 из 3", TvInteractiveCardSpec.pendingBadge(3))
        assertNull(TvInteractiveCardSpec.pendingBadge(1))
    }

    @Test
    fun codeBlockPrefersCommandThenToolThenNothing() {
        assertEquals("rm -rf /tmp/x", TvInteractiveCardSpec.approvalCodeBlock(payload()))
        assertEquals("terminal", TvInteractiveCardSpec.approvalCodeBlock(payload(command = "", tool = "terminal")))
        assertEquals("", TvInteractiveCardSpec.approvalCodeBlock(payload(command = "  ", tool = null)))
    }

    @Test
    fun codeLabelJoinsToolAndRisk() {
        assertEquals("terminal · high", TvInteractiveCardSpec.approvalCodeLabel(payload(tool = "terminal", risk = "HIGH")))
        assertEquals("terminal", TvInteractiveCardSpec.approvalCodeLabel(payload(tool = "terminal")))
        assertNull(TvInteractiveCardSpec.approvalCodeLabel(payload()))
    }

    @Test
    fun patternKeysTrimmedAndBlanksDropped() {
        assertEquals(listOf("a", "b"), TvInteractiveCardSpec.approvalPatternKeys(payload(patternKeys = listOf(" a ", "", "b"))))
    }

    @Test
    fun clarifyWithChoicesBecomesPresetsPlusOwnAnswer() {
        val mode = TvInteractiveCardSpec.clarifyMode(ClarifyPayload(clarifyId = "c1", question = "Q?", choices = listOf(" да ", "нет")))
        val presets = mode as ClarifyMode.Presets
        assertEquals(3, presets.options.size)
        assertEquals("да", (presets.options[0] as ClarifyOption.Preset).label)
        assertEquals(ClarifyOption.OwnAnswer, presets.options.last())
        assertEquals("Свой ответ", presets.options.last().label)
    }

    @Test
    fun clarifyWithEmptyOrAllBlankChoicesDegradesToFreeText() {
        // P0: an open question (no choices offered) must still be answerable — free-text editor.
        val empty = TvInteractiveCardSpec.clarifyMode(ClarifyPayload(clarifyId = "c1", question = "Q?"))
        assertTrue(empty is ClarifyMode.FreeText)
        assertEquals(emptyList<String>(), (empty as ClarifyMode.FreeText).presets)
        val blanks = TvInteractiveCardSpec.clarifyMode(ClarifyPayload(clarifyId = "c1", question = "Q?", choices = listOf("  ", "")))
        assertTrue(blanks is ClarifyMode.FreeText)
    }

    @Test
    fun clarifyTapRoutingPresetRespondsOwnAnswerSwitchesEditor() {
        val mode = TvInteractiveCardSpec.clarifyMode(ClarifyPayload(clarifyId = "c1", question = "Q?", choices = listOf("да", "нет"))) as ClarifyMode.Presets
        val respond = TvInteractiveCardSpec.onClarifyOptionActivated(mode, mode.options[0]) as ClarifyAction.Respond
        assertEquals("да", respond.text)
        val swap = TvInteractiveCardSpec.onClarifyOptionActivated(mode, ClarifyOption.OwnAnswer) as ClarifyAction.SwitchToFreeText
        assertEquals(listOf("да", "нет"), swap.presets)
    }

    @Test
    fun clarifyFreeTextSubmitTrimsAndRejectsBlanks() {
        assertEquals("привет", TvInteractiveCardSpec.clarifyFreeTextSubmit("  привет  "))
        assertNull(TvInteractiveCardSpec.clarifyFreeTextSubmit("   "))
        assertNull(TvInteractiveCardSpec.clarifyFreeTextSubmit(""))
    }
}
