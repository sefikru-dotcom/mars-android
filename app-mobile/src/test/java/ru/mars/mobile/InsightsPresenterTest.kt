package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.InsightsDailyTokens
import ru.mars.network.model.InsightsModelUsage
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.repository.InsightsResult

/**
 * Чистые тесты переходов [InsightsPresenter] (порт fetch инжектится):
 * loading/ready/empty/error/no-auth, offline-safe сохранение строк, whitelist
 * периодов и защита от гонок — устаревшие результаты конкурентных загрузок
 * отбрасываются, отмена корутины пробрасывается, а не глотается как ошибка.
 */
private fun usageSnapshot(sessions: Int = 3) = InsightsSnapshot(
    periodDays = 30,
    totalSessions = sessions,
    totalMessages = 40,
    totalInputTokens = 1_000L,
    totalOutputTokens = 500L,
    totalTokens = 1_500L,
    totalCost = 0.5,
    models = listOf(
        InsightsModelUsage(model = "gpt-5.5", sessions = sessions, totalTokens = 1_500L, cost = 0.5),
    ),
    dailyTokens = listOf(
        InsightsDailyTokens(
            date = "2026-10-12", inputTokens = 1_000L, outputTokens = 500L,
            sessions = sessions, cost = 0.5,
        ),
    ),
)

class InsightsPresenterTest {

    private class Harness(
        result: InsightsResult = InsightsResult.Snapshot(usageSnapshot()),
        throws: Boolean = false,
        credential: Boolean = true,
        offline: Boolean = false,
    ) {
        var lastDays: Int? = null
        var fetchCalls = 0
        val presenter = InsightsPresenter(
            hasCredential = { credential },
            fetch = { days ->
                fetchCalls++
                lastDays = days
                if (throws) throw RuntimeException("boom")
                result
            },
            isOffline = { offline },
        )
    }

    @Test
    fun initialStateIsLoadingWithServerDefaultPeriod() {
        val h = Harness()
        assertEquals(InsightsPhase.LOADING, h.presenter.state.phase)
        assertEquals(InsightsPolicy.MSG_LOADING, h.presenter.state.message)
        assertEquals(30, InsightsPolicy.DEFAULT_DAYS)
        assertEquals(InsightsPolicy.DEFAULT_DAYS, h.presenter.state.periodDays)
    }

    @Test
    fun subscribeReplaysCurrentState() {
        val h = Harness()
        val seen = mutableListOf<InsightsUiState>()
        h.presenter.subscribe { seen += it }
        assertEquals(1, seen.size)
        assertEquals(h.presenter.state, seen[0])
    }

    @Test
    fun noCredentialGivesNoAuthWithoutFetch() = runBlocking {
        val h = Harness(credential = false)
        h.presenter.load()
        assertEquals(InsightsPhase.NO_AUTH, h.presenter.state.phase)
        assertEquals(InsightsPolicy.MSG_NO_AUTH, h.presenter.state.message)
        assertEquals(0, h.fetchCalls)
    }

    @Test
    fun snapshotSuccessGivesReadyWithRows() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(InsightsPhase.READY, state.phase)
        assertTrue(state.rows.isNotEmpty())
        assertNull(state.message)
        assertFalse(state.canRetry)
        assertEquals(InsightsPolicy.DEFAULT_DAYS, h.lastDays)
    }

    @Test
    fun emptySnapshotGivesEmptyPhase() = runBlocking {
        val h = Harness(result = InsightsResult.Snapshot(InsightsSnapshot()))
        h.presenter.load()
        assertEquals(InsightsPhase.EMPTY, h.presenter.state.phase)
        assertEquals(InsightsPolicy.MSG_EMPTY, h.presenter.state.message)
        assertTrue(h.presenter.state.rows.isEmpty())
    }

    @Test
    fun transportFailureGivesErrorWithRetry() = runBlocking {
        val h = Harness(result = InsightsResult.Failed("connect refused"))
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(InsightsPhase.ERROR, state.phase)
        assertEquals(InsightsPolicy.MSG_ERROR, state.message)
        assertTrue(state.canRetry)
        assertNull(state.notice)
    }

    @Test
    fun thrownFetchIsTreatedAsFailure() = runBlocking {
        val h = Harness(throws = true)
        h.presenter.load()
        assertEquals(InsightsPhase.ERROR, h.presenter.state.phase)
        assertTrue(h.presenter.state.canRetry)
    }

    @Test
    fun offlineFailureUsesOfflineMessage() = runBlocking {
        val h = Harness(result = InsightsResult.Failed("io"), offline = true)
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(InsightsPolicy.MSG_ERROR_OFFLINE, state.message)
        assertEquals(InsightsPolicy.NOTICE_OFFLINE, state.notice)
    }

    @Test
    fun staleRowsSurviveFailureAsOfflineSafe() = runBlocking {
        // Первый load успешен, второй (refresh) падает в офлайне:
        // строки остаются, ошибка уходит в notice, центрального message нет.
        var result: InsightsResult = InsightsResult.Snapshot(usageSnapshot())
        var offline = false
        val presenter = InsightsPresenter(
            hasCredential = { true },
            fetch = { result },
            isOffline = { offline },
        )
        presenter.load()
        assertEquals(InsightsPhase.READY, presenter.state.phase)

        result = InsightsResult.Failed("lost")
        offline = true
        presenter.load()
        val state = presenter.state
        assertEquals(InsightsPhase.ERROR, state.phase)
        assertTrue(state.rows.isNotEmpty())
        assertNull(state.message)
        assertEquals(InsightsPolicy.NOTICE_OFFLINE_STALE, state.notice)
        assertTrue(state.canRetry)
    }

    @Test
    fun staleFailureFromConcurrentLoadDoesNotOverrideFreshSuccess() = runBlocking {
        // Гонка двух load(): первый запрос завис, второй (refresh/чип) уже
        // отрисовал READY. Запоздалый ответ первого не должен затереть состояние.
        val gate = CompletableDeferred<InsightsResult>()
        var calls = 0
        val presenter = InsightsPresenter(
            hasCredential = { true },
            fetch = {
                calls += 1
                if (calls == 1) gate.await() else InsightsResult.Snapshot(usageSnapshot())
            },
        )
        val stale = launch { presenter.load() }
        while (calls == 0) yield()
        launch { presenter.load() }.join()
        assertEquals(InsightsPhase.READY, presenter.state.phase)

        gate.complete(InsightsResult.Failed("stale transport"))
        stale.join()
        assertEquals(InsightsPhase.READY, presenter.state.phase)
        assertNull(presenter.state.message)
        assertFalse(presenter.state.canRetry)
        assertEquals(2, calls)
    }

    @Test
    fun staleSuccessFromConcurrentLoadDoesNotOverrideFreshFailure() = runBlocking {
        // Обратная направленность гонки: свежая загрузка упала первой,
        // устаревший успех не должен «воскресить» ERROR в READY.
        val gate = CompletableDeferred<InsightsResult>()
        var calls = 0
        val presenter = InsightsPresenter(
            hasCredential = { true },
            fetch = {
                calls += 1
                if (calls == 1) gate.await() else InsightsResult.Failed("fresh failure")
            },
        )
        val stale = launch { presenter.load() }
        while (calls == 0) yield()
        launch { presenter.load() }.join()
        assertEquals(InsightsPhase.ERROR, presenter.state.phase)

        gate.complete(InsightsResult.Snapshot(usageSnapshot()))
        stale.join()
        assertEquals(InsightsPhase.ERROR, presenter.state.phase)
        assertTrue(presenter.state.canRetry)
    }

    @Test
    fun cancellationIsRethrownAndNotSwallowedAsFailure() = runBlocking {
        val presenter = InsightsPresenter(
            hasCredential = { true },
            fetch = { throw CancellationException("screen closed") },
        )
        val job = launch { presenter.load() }
        job.join()
        // runCatching проглотил бы отмену и job завершился бы нормально;
        // корректное поведение — проброс: job отменён, состояние не мутировано.
        assertTrue(job.isCancelled)
        assertEquals(InsightsPhase.LOADING, presenter.state.phase)
        assertEquals(InsightsPolicy.MSG_LOADING, presenter.state.message)
    }

    @Test
    fun selectPeriodWhitelistsAndRefetches() = runBlocking {
        val h = Harness()
        h.presenter.load()
        assertEquals(30, h.lastDays)

        val applied = h.presenter.selectPeriod(90)
        assertEquals(90, applied)
        assertEquals(90, h.lastDays)
        assertEquals(90, h.presenter.state.periodDays)

        // Не-whitelisted период нормализуется и не уходит в запрос сырым.
        val sanitized = h.presenter.selectPeriod(5)
        assertEquals(InsightsPolicy.DEFAULT_DAYS, sanitized)
        assertEquals(InsightsPolicy.DEFAULT_DAYS, h.lastDays)
    }

    @Test
    fun loadingPhaseKeepsStaleRowsAndDropsCentralMessage() = runBlocking {
        var result: InsightsResult = InsightsResult.Snapshot(usageSnapshot())
        val presenter = InsightsPresenter(hasCredential = { true }, fetch = { result })
        presenter.load()
        val readyRows = presenter.state.rows

        result = InsightsResult.Failed("slow")
        val loading = mutableListOf<InsightsUiState>()
        presenter.subscribe { loading += it }
        presenter.load()
        val loadingState = loading.first { it.phase == InsightsPhase.LOADING }
        assertEquals(readyRows, loadingState.rows)
        assertNull(loadingState.message)
        assertEquals(InsightsPhase.ERROR, presenter.state.phase)
    }
}
