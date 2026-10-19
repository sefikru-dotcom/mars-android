package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate

/**
 * Goal-loop (долг O14): карточка статуса цели и локальная часть команды `/goal`.
 *
 * Карточка — чистое представление кадров `goal`/`goal_continue`: она ничего не
 * запрашивает у сервера. Управление целью (`/api/goal`) device-токену недоступно,
 * поэтому управляющие подкоманды обязаны отвечать честно, а не молчать.
 */
class GoalLoopPolicyTest {

    @Test
    fun evaluatingFrameShowsProgressLine() {
        val card = GoalLoopPolicy.card(GoalUpdate(sessionId = "s1", state = "evaluating"))
        assertTrue(card.visible)
        assertEquals(GoalLoopPolicy.EVALUATING_MESSAGE, card.title)
        assertEquals(GoalCardTone.ACTIVE, card.tone)
        assertEquals(null, card.detail)
    }

    @Test
    fun messageBecomesTitleAndRestBecomesDetail() {
        val card = GoalLoopPolicy.card(
            GoalUpdate(
                sessionId = "s1",
                state = "active",
                message = "Цель: сдать отчёт\nОсталось 3 шага",
                messageKey = "goal_set",
            )
        )
        assertTrue(card.visible)
        assertEquals("Цель: сдать отчёт", card.title)
        assertEquals("Осталось 3 шага", card.detail)
        assertEquals(GoalCardTone.ACTIVE, card.tone)
    }

    @Test
    fun pausedGoalGetsPausedTone() {
        val card = GoalLoopPolicy.card(
            GoalUpdate(sessionId = "s1", state = "paused", message = "Цель на паузе", messageKey = "goal_paused")
        )
        assertEquals(GoalCardTone.PAUSED, card.tone)
    }

    @Test
    fun clearedGoalIsShownAsDoneAndNotActive() {
        val card = GoalLoopPolicy.card(
            GoalUpdate(sessionId = "s1", state = "idle", message = "Цель закрыта", messageKey = "goal_cleared")
        )
        assertTrue(card.visible)
        assertEquals(GoalCardTone.DONE, card.tone)
    }

    @Test
    fun frameWithoutTextHidesCard() {
        // Сервер шлёт `goal` и без текста (например, «цели нет»): пустая карточка
        // над композером хуже, чем её отсутствие.
        assertFalse(GoalLoopPolicy.card(GoalUpdate(sessionId = "s1", state = "idle")).visible)
        assertFalse(GoalLoopPolicy.card(GoalUpdate(sessionId = "s1", state = "active", message = "   ")).visible)
    }

    @Test
    fun continuationCardCarriesNextStepPrompt() {
        val card = GoalLoopPolicy.continuationCard(
            GoalContinuation(
                sessionId = "s1",
                continuationPrompt = "Проверь пункт 2 отчёта",
                message = "Цель не закрыта",
            )
        )
        assertTrue(card.visible)
        assertEquals("Цель не закрыта", card.title)
        assertTrue(card.detail.orEmpty().contains("Проверь пункт 2 отчёта"))
        assertEquals(GoalCardTone.ACTIVE, card.tone)
    }

    @Test
    fun continuationWithoutMessageFallsBackToOwnTitle() {
        val card = GoalLoopPolicy.continuationCard(
            GoalContinuation(sessionId = null, continuationPrompt = "Дальше")
        )
        assertTrue(card.title.isNotBlank())
        assertTrue(card.detail.orEmpty().contains("Дальше"))
    }

    @Test
    fun goalCommandIsRecognisedButLookalikesAreNot() {
        assertTrue(GoalLoopPolicy.isCommand("/goal"))
        assertTrue(GoalLoopPolicy.isCommand("  /GOAL status "))
        assertTrue(GoalLoopPolicy.isCommand("/goal Сдать отчёт"))
        // Не команда: другой префикс/слово с тем же началом/обычный текст.
        assertFalse(GoalLoopPolicy.isCommand("/goalkeeper"))
        assertFalse(GoalLoopPolicy.isCommand("/goals"))
        assertFalse(GoalLoopPolicy.isCommand("goal сдать отчёт"))
        assertFalse(GoalLoopPolicy.isCommand(""))
    }

    @Test
    fun commandArgsDropsPrefixAndKeepsSubcommand() {
        assertEquals("", GoalLoopPolicy.commandArgs("/goal"))
        assertEquals("status", GoalLoopPolicy.commandArgs("/goal   status"))
        assertEquals("сдать отчёт", GoalLoopPolicy.commandArgs("/goal сдать отчёт"))
        assertEquals("", GoalLoopPolicy.commandArgs("/goalkeeper"))
    }

    @Test
    fun statusCommandIsLocalOnly() {
        assertTrue(GoalLoopPolicy.isStatusCommand(""))
        assertTrue(GoalLoopPolicy.isStatusCommand("STATUS"))
        assertFalse(GoalLoopPolicy.isStatusCommand("pause"))
        assertFalse(GoalLoopPolicy.isStatusCommand("сдать отчёт"))
        assertTrue(GoalLoopPolicy.isControlCommand("clear"))
    }

    @Test
    fun statusReplyWithoutGoalExplainsHowToStart() {
        val reply = GoalLoopPolicy.statusReply(GoalLoopPolicy.hidden())
        assertEquals(GoalLoopPolicy.NO_GOAL, reply)
        assertTrue(reply.contains("/goal"))
    }

    @Test
    fun statusReplyEchoesVisibleCard() {
        val card = GoalLoopPolicy.card(
            GoalUpdate(sessionId = "s1", state = "active", message = "Цель: отчёт\nШаг 2 из 5")
        )
        val reply = GoalLoopPolicy.statusReply(card)
        assertTrue(reply.contains("Цель: отчёт"))
        assertTrue(reply.contains("Шаг 2 из 5"))
    }

    @Test
    fun controlReplyNamesTheMissingServerPath() {
        // Обещание «поставил цель» было бы ложью: POST /api/goal не в allowlist
        // device-токена. Ответ обязан сказать это прямо.
        val pause = GoalLoopPolicy.controlReply("pause")
        assertTrue(pause.startsWith("Пауза:"))
        assertTrue(pause.contains("/api/goal"))
        val setGoal = GoalLoopPolicy.controlReply("сдать отчёт")
        assertTrue(setGoal.startsWith("Постановка цели:"))
        assertTrue(setGoal.contains("/api/goal"))
    }
}
