package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.repository.ApprovalChoice

/**
 * UI-state mapping of the approval/clarify card ([InteractiveCardPresenter]): option roles and
 * ordering, the locked options of the in-flight phase, the progress row, and the retry action
 * that carries the retained choice. Pure JVM — no Android framework involved.
 */
class InteractiveCardStateTest {

    private fun approval(
        allowSession: Boolean = true,
        allowPermanent: Boolean = false,
    ) = ApprovalPayload(
        approvalId = "appr-1",
        command = "rm -rf /tmp/build",
        description = "Удалить каталог сборки",
        allowSession = allowSession,
        allowPermanent = allowPermanent,
    )

    private fun clarify(vararg choices: String) = ClarifyPayload(
        clarifyId = "clr-1",
        question = "Какой профиль сборки?",
        choices = choices.toList(),
    )

    // --- approval -------------------------------------------------------------------------------

    @Test
    fun approvalIdleMapsEveryOptionWithItsRole() {
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = false,
            phase = InteractiveCardPhase.Idle,
            retained = null,
        )

        assertEquals(
            listOf(
                "Разрешить один раз" to InteractiveOptionKind.PRIMARY,
                "Разрешить для этой сессии" to InteractiveOptionKind.SECONDARY,
                InteractiveDialogSpec.AUTONOMY_ENABLE_LABEL to InteractiveOptionKind.SECONDARY,
                "Отклонить" to InteractiveOptionKind.DESTRUCTIVE,
            ),
            state.options.map { it.label to it.kind },
        )
        assertTrue(state.options.all { it.enabled })
        assertTrue(state.optionsEnabled)
        assertFalse(state.sending)
        assertNull(state.sendingLabel)
        assertNull(state.errorMessage)
        assertFalse(state.retryVisible)
    }

    @Test
    fun approvalFlagsDriveWhichOptionsExist() {
        val state = InteractiveCardPresenter.approval(
            approval(allowSession = false, allowPermanent = true),
            autonomyEnabled = true,
            phase = InteractiveCardPhase.Idle,
            retained = null,
        )

        // No session grant, permanent grant present, autonomy grant hidden (already enabled).
        assertEquals(
            listOf("Разрешить один раз", "Разрешать всегда", "Отклонить"),
            state.options.map { it.label },
        )
        assertEquals(InteractiveOptionKind.DESTRUCTIVE, state.options.last().kind)
    }

    @Test
    fun sendingPhaseLocksEveryOptionAndShowsProgress() {
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = false,
            phase = InteractiveCardPhase.Sending,
            retained = ApprovalAttempt(ApprovalChoice.ONCE),
        )

        assertTrue(state.sending)
        assertFalse(state.optionsEnabled)
        assertTrue(state.options.none { it.enabled })
        assertEquals(InteractiveDialogSpec.SENDING_LABEL, state.sendingLabel)
        assertNull(state.errorMessage)
        // The retry action never coexists with the in-flight progress row.
        assertFalse(state.retryVisible)
    }

    @Test
    fun retryableFailureOffersTheRetainedChoiceVerbatim() {
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = false,
            phase = InteractiveCardPhase.Failed("Сеть недоступна — попробуйте ещё раз.", retryable = true),
            retained = ApprovalAttempt(ApprovalChoice.SESSION),
        )

        assertEquals("Сеть недоступна — попробуйте ещё раз.", state.errorMessage)
        assertTrue(state.retryVisible)
        assertEquals("Повторить: Разрешить для этой сессии", state.retryLabel)
        assertFalse(state.sending)
        assertTrue(state.optionsEnabled) // the user may still pick a different option
    }

    @Test
    fun terminalFailureShowsTheErrorWithoutRetry() {
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = false,
            phase = InteractiveCardPhase.Failed("Invalid choice", retryable = false),
            retained = ApprovalAttempt(ApprovalChoice.ONCE),
        )

        assertEquals("Invalid choice", state.errorMessage)
        assertFalse(state.retryVisible)
        assertNull(state.retryLabel)
    }

    @Test
    fun autonomyGrantRetryLabelKeepsTheGrantFlag() {
        val attempt = ApprovalAttempt(ApprovalChoice.ONCE, grantsAutonomy = true)
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = false,
            phase = InteractiveCardPhase.Failed("offline", retryable = true),
            retained = attempt,
        )

        // The grant is a distinct option from the plain "once": the retry must name THAT one.
        assertEquals("Повторить: ${InteractiveDialogSpec.AUTONOMY_ENABLE_LABEL}", state.retryLabel)
    }

    @Test
    fun retryLabelDisappearsWhenTheRetainedOptionIsNoLongerOffered() {
        // Autonomy got enabled meanwhile → the grant option is hidden, so nothing to re-send.
        val attempt = ApprovalAttempt(ApprovalChoice.ONCE, grantsAutonomy = true)
        val state = InteractiveCardPresenter.approval(
            approval(),
            autonomyEnabled = true,
            phase = InteractiveCardPhase.Failed("offline", retryable = true),
            retained = attempt,
        )

        assertFalse(state.retryVisible)
    }

    // --- clarify --------------------------------------------------------------------------------

    @Test
    fun clarifyIdleMapsPresetsPlusOwnAnswer() {
        val state = InteractiveCardPresenter.clarify(
            clarify("Кратко", "Подробно"),
            phase = InteractiveCardPhase.Idle,
            retainedAnswer = null,
        )

        assertEquals(
            listOf("Кратко", "Подробно", InteractiveDialogSpec.OWN_ANSWER_LABEL),
            state.options.map { it.label },
        )
        assertEquals(InteractiveOptionKind.PRESET, state.options.first().kind)
        assertEquals(InteractiveOptionKind.OWN_ANSWER, state.options.last().kind)
        assertFalse(state.retryVisible)
    }

    @Test
    fun clarifyWithoutChoicesStillOffersTheFreeTextEntry() {
        val state = InteractiveCardPresenter.clarify(
            clarify(),
            phase = InteractiveCardPhase.Idle,
            retainedAnswer = null,
        )

        assertEquals(listOf(InteractiveDialogSpec.OWN_ANSWER_LABEL), state.options.map { it.label })
    }

    @Test
    fun clarifyRetryEchoesTheRetainedAnswer() {
        val state = InteractiveCardPresenter.clarify(
            clarify("Кратко"),
            phase = InteractiveCardPhase.Failed("Сеть недоступна — попробуйте ещё раз.", retryable = true),
            retainedAnswer = "Подробно, с примерами",
        )

        assertEquals("Повторить: «Подробно, с примерами»", state.retryLabel)
        assertTrue(state.retryVisible)
    }

    @Test
    fun longClarifyAnswerIsClippedInTheRetryLabel() {
        val long = "а".repeat(InteractiveCardPresenter.RETRY_ANSWER_MAX_CHARS + 10)
        val label = InteractiveCardPresenter.clarifyRetryLabel(long)

        assertEquals(
            "Повторить: «${"а".repeat(InteractiveCardPresenter.RETRY_ANSWER_MAX_CHARS)}…»",
            label,
        )
    }

    @Test
    fun blankRetainedAnswerNeverProducesARetryLabel() {
        assertNull(InteractiveCardPresenter.clarifyRetryLabel("   "))
        assertNull(InteractiveCardPresenter.clarifyRetryLabel(null))
        assertNull(InteractiveCardPresenter.approvalRetryLabel(approval(), false, null))
        assertNull(InteractiveCardPresenter.approvalRetryLabel(null, false, ApprovalAttempt(ApprovalChoice.ONCE)))
    }
}
