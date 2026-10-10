package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты [OfflineQueuePresenter] — презентационного слоя офлайн-очереди:
 * бейдж (счётчик/тон/потолок), панель строк (FIFO, статусы, фильтр по диалогу),
 * ретрай недоставленных ходов с double-trigger guard и двухшаговый discard-поток.
 */
class OfflineQueuePresenterTest {

    private var now = 1_700_000_000_000L
    private val presenter = OfflineQueuePresenter(clock = { now })

    private var seq = 0
    private fun queued(
        text: String,
        sessionId: String? = "s1",
        attempts: Int = 0,
    ) = QueuedMessage(
        id = "q-${seq++}",
        text = text,
        sessionId = sessionId,
        enqueuedAtEpochMillis = now,
        attempts = attempts,
    )

    private fun drop(text: String = "провал", sessionId: String? = "s1"): QueuedMessage {
        val message = queued(text, sessionId)
        presenter.onTurnFailed(message)
        return message
    }

    // --- бейдж -----------------------------------------------------------------------------------

    @Test
    fun `badge hidden when queue empty and nothing failed`() {
        assertSame(OfflineQueueBadgeUi.Hidden, presenter.badgeFor(0, NetworkState.OFFLINE))
        assertFalse(OfflineQueueBadgeUi.Hidden.visible)
        assertEquals(0, OfflineQueueBadgeUi.Hidden.count)
    }

    @Test
    fun `badge counts queued plus failed with warning tone offline`() {
        drop("не доставлено")
        val badge = presenter.badgeFor(queueSize = 2, network = NetworkState.OFFLINE)
        assertTrue(badge.visible)
        assertEquals(3, badge.count)
        assertEquals("3", badge.label)
        // Недоставленные перекрашивают бейдж в ERROR независимо от сети.
        assertEquals(OfflineQueueBadgeUi.Tone.ERROR, badge.tone)
        assertEquals("Неотправленные сообщения: 3 сообщения", badge.contentDescription)
    }

    @Test
    fun `badge tone is warning offline and ok while auto-flushing without failures`() {
        assertEquals(
            OfflineQueueBadgeUi.Tone.WARNING,
            presenter.badgeFor(1, NetworkState.LOSING).tone,
        )
        assertEquals(
            OfflineQueueBadgeUi.Tone.WARNING,
            presenter.badgeFor(1, NetworkState.OFFLINE).tone,
        )
        assertEquals(
            OfflineQueueBadgeUi.Tone.OK,
            presenter.badgeFor(1, NetworkState.ONLINE).tone,
        )
        assertEquals(
            OfflineQueueBadgeUi.Tone.OK,
            presenter.badgeFor(1, NetworkState.UNVALIDATED).tone,
        )
    }

    @Test
    fun `badge label caps at 99 plus but count stays exact`() {
        val badge = presenter.badgeFor(queueSize = 150, network = NetworkState.OFFLINE)
        assertEquals("99+", badge.label)
        assertEquals(150, badge.count)
        assertEquals(99, OfflineQueuePresenter.BADGE_MAX_COUNT)
    }

    // --- панель ----------------------------------------------------------------------------------

    @Test
    fun `panel hidden for session without queued or failed messages but keeps global badge`() {
        presenter.onSendStarted("x") // шум не по теме
        val other = queued("чужой", sessionId = "s2")
        val panel = presenter.panelFor(listOf(other), NetworkState.OFFLINE, sessionId = "s1")
        assertFalse(panel.visible)
        assertTrue(panel.rows.isEmpty())
        assertEquals(1, panel.badge.count)
        assertFalse(panel.discardAllVisible)
        assertNull(panel.discardPrompt)
    }

    @Test
    fun `panel rows follow fifo order of the session snapshot`() {
        val a = queued("первое")
        val b = queued("второе")
        val c = queued("чужое", sessionId = "s2")
        val panel = presenter.panelFor(listOf(a, b, c), NetworkState.OFFLINE, sessionId = "s1")
        assertTrue(panel.visible)
        assertEquals(OfflineQueuePresenter.PANEL_TITLE, panel.title)
        assertEquals(listOf(a.id, b.id), panel.rows.map { it.id })
        assertEquals(listOf("первое", "второе"), panel.rows.map { it.preview })
        assertTrue(panel.rows.all { it.status == OfflineQueueRowStatus.WAITING })
        // Бейдж глобальный: чужой диалог тоже посчитан.
        assertEquals(3, panel.badge.count)
    }

    @Test
    fun `waiting row label follows network state and never offers retry or discard`() {
        val message = queued("ждун")
        val offline = presenter.panelFor(listOf(message), NetworkState.OFFLINE, "s1").rows.single()
        assertEquals(OfflineQueuePresenter.LABEL_WAITING, offline.statusLabel)
        assertFalse(offline.retryAvailable)
        assertFalse(offline.discardAvailable)

        val flushing = presenter.panelFor(listOf(message), NetworkState.ONLINE, "s1").rows.single()
        assertEquals(OfflineQueuePresenter.LABEL_AUTO_SEND, flushing.statusLabel)
    }

    @Test
    fun `in-flight message renders as sending row`() {
        val message = queued("улетаю")
        presenter.onSendStarted(message.id)
        assertTrue(presenter.isSending(message.id))
        val row = presenter.panelFor(listOf(message), NetworkState.ONLINE, "s1").rows.single()
        assertEquals(OfflineQueueRowStatus.SENDING, row.status)
        assertEquals(OfflineQueuePresenter.LABEL_SENDING, row.statusLabel)
        assertFalse(row.retryAvailable)
        presenter.onSendSucceeded(message.id)
        assertFalse(presenter.isSending(message.id))
        val after = presenter.panelFor(listOf(message), NetworkState.ONLINE, "s1").rows.single()
        assertEquals(OfflineQueueRowStatus.WAITING, after.status)
    }

    @Test
    fun `failed turn appends after queued rows with retry and discard available`() {
        val waiting = queued("ещё в очереди")
        val failed = drop("сгорело")
        val panel = presenter.panelFor(listOf(waiting), NetworkState.OFFLINE, "s1")
        assertEquals(listOf(waiting.id, failed.id), panel.rows.map { it.id })
        val row = panel.rows.last()
        assertEquals(OfflineQueueRowStatus.FAILED, row.status)
        assertEquals(OfflineQueuePresenter.LABEL_FAILED, row.statusLabel)
        assertTrue(row.retryAvailable)
        assertTrue(row.discardAvailable)
        assertEquals("Не доставлено. сгорело", row.contentDescription)
    }

    @Test
    fun `failed turn is scoped to its session in rows`() {
        val foreign = drop("чужой провал", sessionId = "s2")
        val panel = presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1")
        assertFalse(panel.visible)
        val panelS2 = presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s2")
        assertEquals(listOf(foreign.id), panelS2.rows.map { it.id })
    }

    @Test
    fun `queue snapshot entry already marked failed is not duplicated in rows`() {
        val message = drop("дубль?")
        // Хост мог передать снимок, где сообщение ещё лежит (гонка записи) — строка одна, FAILED.
        val panel = presenter.panelFor(listOf(message), NetworkState.OFFLINE, "s1")
        assertEquals(1, panel.rows.size)
        assertEquals(OfflineQueueRowStatus.FAILED, panel.rows.single().status)
    }

    @Test
    fun `preview collapses newlines and caps long text with ellipsis`() {
        assertEquals("раз два три", OfflineQueuePresenter.previewOf("раз\nдва\n\n  три  "))
        assertEquals("", OfflineQueuePresenter.previewOf("   \n "))
        val long = "ы".repeat(OfflineQueuePresenter.PREVIEW_MAX_CHARS + 10)
        val capped = OfflineQueuePresenter.previewOf(long)
        assertEquals(OfflineQueuePresenter.PREVIEW_MAX_CHARS + 1, capped.length)
        assertTrue(capped.endsWith("…"))
        // Ровно в пределе — без отсечения.
        assertEquals("ы".repeat(80), OfflineQueuePresenter.previewOf("ы".repeat(80)))
    }

    // --- onTurnFailed ------------------------------------------------------------------------------

    @Test
    fun `turn failure records text session attempts and timestamp and replaces duplicates`() {
        now = 1_700_000_123_456L
        val message = queued("важное", sessionId = null, attempts = 3)
        presenter.onSendStarted(message.id)
        presenter.onTurnFailed(message)
        assertFalse(presenter.isSending(message.id))
        val turn = presenter.failed.single()
        assertEquals(message.id, turn.id)
        assertEquals("важное", turn.text)
        assertNull(turn.sessionId)
        assertEquals(3, turn.attempts)
        assertEquals(now, turn.failedAtEpochMillis)

        // Повторный провал того же id заменяет запись, а не плодит дубли.
        now += 1000
        presenter.onTurnFailed(message.copy(attempts = 4))
        assertEquals(1, presenter.failed.size)
        assertEquals(4, presenter.failed.single().attempts)
    }

    @Test
    fun `successful send clears in-flight mark and stale failure of same id`() {
        val message = drop("сначала провал")
        presenter.onSendStarted(message.id)
        presenter.onSendSucceeded(message.id)
        assertTrue(presenter.failed.isEmpty())
        assertFalse(presenter.isSending(message.id))
    }

    // --- ретрай ----------------------------------------------------------------------------------

    @Test
    fun `retry of failed turn triggers send with original payload and guards double tap`() {
        val message = drop("ретрай меня", sessionId = "s7")
        val outcome = presenter.requestRetry(message.id)
        assertTrue(outcome is RetryOutcome.TriggerSend)
        outcome as RetryOutcome.TriggerSend
        assertEquals(message.id, outcome.messageId)
        assertEquals("ретрай меня", outcome.text)
        assertEquals("s7", outcome.sessionId)
        // Ход изъят из FAILED и отмечен in-flight.
        assertTrue(presenter.failed.isEmpty())
        assertTrue(presenter.isSending(message.id))
        // Double-trigger guard: второй тап до завершения отправки отклонён.
        val second = presenter.requestRetry(message.id)
        assertTrue(second is RetryOutcome.Rejected)
        assertEquals(RetryRejectReason.ALREADY_IN_FLIGHT, (second as RetryOutcome.Rejected).reason)
    }

    @Test
    fun `retry unknown or still queued id is rejected as unknown turn`() {
        val rejected = presenter.requestRetry("нет-такого")
        assertTrue(rejected is RetryOutcome.Rejected)
        assertEquals(RetryRejectReason.UNKNOWN_TURN, (rejected as RetryOutcome.Rejected).reason)

        val waiting = queued("я ещё в очереди")
        val outcome = presenter.requestRetry(waiting.id)
        assertEquals(RetryRejectReason.UNKNOWN_TURN, (outcome as RetryOutcome.Rejected).reason)
    }

    @Test
    fun `retried turn may fail again and returns to failed list`() {
        val message = drop("невезучее")
        presenter.requestRetry(message.id)
        presenter.onTurnFailed(message.copy(attempts = 1))
        assertEquals(listOf(message.id), presenter.failed.map { it.id })
        assertTrue(
            presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1")
                .rows.single().retryAvailable,
        )
    }

    @Test
    fun `retried turn success clears in-flight and leaves panel clean`() {
        val message = drop("одноразовое")
        presenter.requestRetry(message.id)
        presenter.onSendSucceeded(message.id)
        assertFalse(presenter.isSending(message.id))
        assertTrue(presenter.failed.isEmpty())
        assertFalse(presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1").visible)
    }

    // --- discard-поток ----------------------------------------------------------------------------

    @Test
    fun `discard requires failed turn and returns confirmation prompt`() {
        assertNull(presenter.requestDiscard("неизвестный"))
        val waiting = queued("живой")
        assertNull(presenter.requestDiscard(waiting.id))
        assertNull(presenter.discardPrompt)

        val failed = drop("прощай")
        val prompt = presenter.requestDiscard(failed.id)
        assertNotNull(prompt)
        prompt!!
        assertEquals(DiscardTarget.Single(failed.id), prompt.target)
        assertEquals("Удалить сообщение?", prompt.title)
        assertTrue(prompt.message.contains("прощай"))
        assertEquals("Удалить", prompt.confirmLabel)
        assertEquals("Отмена", prompt.cancelLabel)
        assertSame(prompt, presenter.discardPrompt)
        // Промпт виден и в панели, строка помечена ожидающей подтверждения.
        val panel = presenter.panelFor(listOf(waiting), NetworkState.OFFLINE, "s1")
        assertSame(prompt, panel.discardPrompt)
        val row = panel.rows.first { it.id == failed.id }
        assertTrue(row.awaitingDiscardConfirm)
        assertFalse(panel.rows.first { it.id == waiting.id }.awaitingDiscardConfirm)
    }

    @Test
    fun `confirmed discard removes failed turn and hands it to the host`() {
        val failed = drop("удали меня")
        presenter.requestDiscard(failed.id)
        val outcome = presenter.confirmDiscard()
        assertTrue(outcome is DiscardOutcome.Discard)
        val turn = (outcome as DiscardOutcome.Discard).turn
        assertEquals(failed.id, turn.id)
        assertEquals("удали меня", turn.text)
        assertTrue(presenter.failed.isEmpty())
        assertNull(presenter.discardPrompt)
        // Повторное подтверждение без промпта — нечего удалять.
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
    }

    @Test
    fun `cancel keeps the failed turn and clears the prompt`() {
        val failed = drop("останусь")
        presenter.requestDiscard(failed.id)
        presenter.cancelDiscard()
        assertNull(presenter.discardPrompt)
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
        assertEquals(listOf(failed.id), presenter.failed.map { it.id })
    }

    @Test
    fun `confirm without request is a no-op`() {
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
    }

    @Test
    fun `discard of vanished turn yields nothing to discard`() {
        val failed = drop("испарюсь")
        presenter.requestDiscard(failed.id)
        // Хост параллельно доставил сообщение — промпт сброшен, подтверждение безопасно.
        presenter.onSendSucceeded(failed.id)
        assertNull(presenter.discardPrompt)
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
    }

    @Test
    fun `discard all prompts with pluralized count and clears every failed turn`() {
        val a = drop("первое")
        val b = drop("второе", sessionId = "s2")
        val prompt = presenter.requestDiscardAll()
        assertNotNull(prompt)
        assertEquals(DiscardTarget.AllFailed, prompt!!.target)
        assertEquals("Удалить недоставленные?", prompt.title)
        assertTrue(prompt.message.contains("2 сообщения"))
        assertEquals("Удалить все", prompt.confirmLabel)

        val outcome = presenter.confirmDiscard()
        assertTrue(outcome is DiscardOutcome.DiscardAll)
        assertEquals(listOf(a.id, b.id), (outcome as DiscardOutcome.DiscardAll).turns.map { it.id })
        assertTrue(presenter.failed.isEmpty())
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
    }

    @Test
    fun `discard all without failures returns null prompt`() {
        assertNull(presenter.requestDiscardAll())
        assertNull(presenter.discardPrompt)
    }

    @Test
    fun `discard all visible only from two failed turns of the session`() {
        val a = drop("одно")
        assertFalse(presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1").discardAllVisible)
        presenter.requestDiscardAll()
        presenter.cancelDiscard()
        drop("два", sessionId = "s1")
        assertTrue(presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1").discardAllVisible)
        // Провалы s1 вычищены целиком — панель s1 больше не предлагает «удалить все».
        presenter.removeFailedFor(a.sessionId!!)
        assertFalse(presenter.panelFor(emptyList(), NetworkState.OFFLINE, "s1").discardAllVisible)
    }

    @Test
    fun `discard all request supersedes pending single discard`() {
        val a = drop("первое")
        drop("второе")
        presenter.requestDiscard(a.id)
        val all = presenter.requestDiscardAll()
        assertSame(all, presenter.discardPrompt)
        val outcome = presenter.confirmDiscard()
        assertTrue(outcome is DiscardOutcome.DiscardAll)
    }

    // --- очистка по сессии и сброс ----------------------------------------------------------------

    @Test
    fun `removeFailedFor drops only target session and its pending prompt`() {
        val a = drop("провал s1", sessionId = "s1")
        val b = drop("провал s2", sessionId = "s2")
        presenter.requestDiscard(a.id)
        presenter.removeFailedFor("s1")
        assertEquals(listOf(b.id), presenter.failed.map { it.id })
        assertNull(presenter.discardPrompt)
        // Удаление сессии без провалов — no-op.
        presenter.removeFailedFor("s3")
        assertEquals(listOf(b.id), presenter.failed.map { it.id })
    }

    @Test
    fun `removeFailedFor clears discard-all prompt when no failures left`() {
        drop("единственный")
        presenter.requestDiscardAll()
        presenter.removeFailedFor("s1")
        assertTrue(presenter.failed.isEmpty())
        assertNull(presenter.discardPrompt)
    }

    @Test
    fun `reset clears failed turns in-flight marks and prompt`() {
        val message = drop("всё")
        presenter.onSendStarted("live-id")
        presenter.requestDiscard(message.id)
        presenter.reset()
        assertTrue(presenter.failed.isEmpty())
        assertFalse(presenter.isSending("live-id"))
        assertNull(presenter.discardPrompt)
        assertSame(DiscardOutcome.NothingToDiscard, presenter.confirmDiscard())
    }

    // --- плюрализация ------------------------------------------------------------------------------

    @Test
    fun `messages label pluralizes russian forms correctly`() {
        assertEquals("1 сообщение", OfflineQueuePresenter.messagesLabel(1))
        assertEquals("2 сообщения", OfflineQueuePresenter.messagesLabel(2))
        assertEquals("5 сообщений", OfflineQueuePresenter.messagesLabel(5))
        assertEquals("11 сообщений", OfflineQueuePresenter.messagesLabel(11))
        assertEquals("21 сообщение", OfflineQueuePresenter.messagesLabel(21))
        assertEquals("104 сообщения", OfflineQueuePresenter.messagesLabel(104))
    }
}
