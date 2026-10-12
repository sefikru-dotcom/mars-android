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
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import ru.mars.network.repository.ServerLogsResult

/** Чистые тесты переходов [LogsPresenter] (порт fetch инжектится). */
private fun window(
    lines: List<String> = listOf("line-1", "line-2"),
    truncated: Boolean = false,
    hint: String = "",
    totalBytes: Long = 2048,
    mtime: Double? = 1760000000.0,
) = ServerLogWindow(
    file = "agent",
    tail = 200,
    lines = lines,
    truncated = truncated,
    totalBytes = totalBytes,
    mtime = mtime,
    hint = hint,
)

class LogsPresenterTest {

    private class Harness(
        result: ServerLogsResult = ServerLogsResult.Window(window()),
        throws: Boolean = false,
        credential: Boolean = true,
        offline: Boolean = false,
    ) {
        var lastFile: ServerLogFile? = null
        var lastTail: ServerLogTail? = null
        var fetchCalls = 0
        val presenter = LogsPresenter(
            hasCredential = { credential },
            fetch = { file, tail ->
                fetchCalls++
                lastFile = file
                lastTail = tail
                if (throws) throw RuntimeException("boom")
                result
            },
            isOffline = { offline },
        )
    }

    @Test
    fun initialStateIsLoadingWithDefaults() {
        val h = Harness()
        assertEquals(LogsPhase.LOADING, h.presenter.state.phase)
        assertEquals(LogsPolicy.MSG_LOADING, h.presenter.state.message)
        assertEquals(LogsPolicy.DEFAULT_FILE, h.presenter.state.file)
        assertEquals(LogsPolicy.DEFAULT_TAIL, h.presenter.state.tail)
    }

    @Test
    fun subscribeReplaysCurrentState() {
        val h = Harness()
        val seen = mutableListOf<LogsUiState>()
        h.presenter.subscribe { seen += it }
        assertEquals(1, seen.size)
    }

    @Test
    fun noCredentialGivesNoAuthWithoutFetch() = runBlocking {
        val h = Harness(credential = false)
        h.presenter.load()
        assertEquals(LogsPhase.NO_AUTH, h.presenter.state.phase)
        assertEquals(LogsPolicy.MSG_NO_AUTH, h.presenter.state.message)
        assertEquals(0, h.fetchCalls)
    }

    @Test
    fun windowSuccessGivesReadyWithLinesAndMeta() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(LogsPhase.READY, state.phase)
        assertEquals(listOf("line-1", "line-2"), state.lines)
        assertEquals(LogsPolicy.meta(window()), state.meta)
        assertNull(state.notice)
        assertFalse(state.canRetry)
        assertEquals(ServerLogFile.AGENT, h.lastFile)
        assertEquals(ServerLogTail.TAIL_200, h.lastTail)
    }

    @Test
    fun truncatedWindowRaisesNotice() = runBlocking {
        val h = Harness(result = ServerLogsResult.Window(window(truncated = true)))
        h.presenter.load()
        assertEquals(LogsPolicy.NOTICE_TRUNCATED, h.presenter.state.notice)
        assertEquals(LogsPhase.READY, h.presenter.state.phase)
    }

    @Test
    fun missingFileIsSettledEmptyWithServerHint() = runBlocking {
        // Валидный 200 «файла ещё нет»: пустые строки, нулевой размер, null mtime.
        val missing = window(lines = emptyList(), hint = "Log file for agent not found yet.", totalBytes = 0, mtime = null)
        assertTrue(missing.missingOnServer)
        val h = Harness(result = ServerLogsResult.Window(missing))
        h.presenter.load()
        assertEquals(LogsPhase.EMPTY, h.presenter.state.phase)
        assertEquals("Log file for agent not found yet.", h.presenter.state.message)
        assertFalse(h.presenter.state.canRetry)
    }

    @Test
    fun emptyWindowWithoutHintFallsBackToPolicyText() = runBlocking {
        val h = Harness(result = ServerLogsResult.Window(window(lines = emptyList())))
        h.presenter.load()
        assertEquals(LogsPhase.EMPTY, h.presenter.state.phase)
        assertEquals(LogsPolicy.MSG_EMPTY, h.presenter.state.message)
    }

    @Test
    fun invalidRequestSurfacesServerError() = runBlocking {
        val h = Harness(result = ServerLogsResult.InvalidRequest("Unknown log file"))
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(LogsPhase.ERROR, state.phase)
        assertEquals(LogsPolicy.msgInvalidRequest("Unknown log file"), state.message)
        assertTrue(state.canRetry)
    }

    @Test
    fun transportFailureGivesErrorWithRetry() = runBlocking {
        val h = Harness(result = ServerLogsResult.Failed("timeout"))
        h.presenter.load()
        assertEquals(LogsPhase.ERROR, h.presenter.state.phase)
        assertEquals(LogsPolicy.MSG_ERROR, h.presenter.state.message)
        assertTrue(h.presenter.state.canRetry)
    }

    @Test
    fun thrownFetchIsTreatedAsFailure() = runBlocking {
        val h = Harness(throws = true)
        h.presenter.load()
        assertEquals(LogsPhase.ERROR, h.presenter.state.phase)
    }

    @Test
    fun offlineFailureUsesOfflineMessage() = runBlocking {
        val h = Harness(result = ServerLogsResult.Failed("io"), offline = true)
        h.presenter.load()
        assertEquals(LogsPolicy.MSG_ERROR_OFFLINE, h.presenter.state.message)
        assertEquals(LogsPolicy.NOTICE_OFFLINE, h.presenter.state.notice)
    }

    @Test
    fun staleLinesSurviveFailureAsOfflineSafe() = runBlocking {
        var result: ServerLogsResult = ServerLogsResult.Window(window())
        var offline = false
        val presenter = LogsPresenter(hasCredential = { true }, fetch = { _, _ -> result }, isOffline = { offline })
        presenter.load()
        assertEquals(LogsPhase.READY, presenter.state.phase)

        result = ServerLogsResult.Failed("lost")
        offline = true
        presenter.load()
        val state = presenter.state
        assertEquals(LogsPhase.ERROR, state.phase)
        assertEquals(listOf("line-1", "line-2"), state.lines)
        assertNull(state.message)
        assertEquals(LogsPolicy.NOTICE_OFFLINE_STALE, state.notice)
        assertTrue(state.canRetry)
    }

    @Test
    fun selectFileAndTailStayInsideWhitelistAndRefetch() = runBlocking {
        val h = Harness()
        h.presenter.load()

        h.presenter.selectFile(ServerLogFile.GATEWAY)
        assertEquals(ServerLogFile.GATEWAY, h.lastFile)
        assertEquals(ServerLogFile.GATEWAY, h.presenter.state.file)

        h.presenter.selectTail(ServerLogTail.TAIL_1000)
        assertEquals(ServerLogTail.TAIL_1000, h.lastTail)
        assertEquals(ServerLogTail.TAIL_1000, h.presenter.state.tail)

        // Каждый выбор — ровно один новый fetch; всего load + file + tail.
        assertEquals(3, h.fetchCalls)
    }

    @Test
    fun staleFailureFromConcurrentLoadDoesNotOverrideFreshWindow() = runBlocking {
        // Гонка двух load() (быстрое переключение файла/tail): запоздалый
        // ответ первого запроса не должен затереть состояние второго.
        val gate = CompletableDeferred<ServerLogsResult>()
        var calls = 0
        val presenter = LogsPresenter(
            hasCredential = { true },
            fetch = { _, _ ->
                calls += 1
                if (calls == 1) gate.await() else ServerLogsResult.Window(window())
            },
        )
        val stale = launch { presenter.load() }
        while (calls == 0) yield()
        launch { presenter.load() }.join()
        assertEquals(LogsPhase.READY, presenter.state.phase)

        gate.complete(ServerLogsResult.Failed("stale transport"))
        stale.join()
        assertEquals(LogsPhase.READY, presenter.state.phase)
        assertEquals(listOf("line-1", "line-2"), presenter.state.lines)
        assertFalse(presenter.state.canRetry)
        assertEquals(2, calls)
    }

    @Test
    fun staleWindowFromConcurrentLoadDoesNotOverrideFreshFailure() = runBlocking {
        // Обратная направленность: свежая загрузка упала первой —
        // устаревшее окно не должно «воскресить» ERROR в READY.
        val gate = CompletableDeferred<ServerLogsResult>()
        var calls = 0
        val presenter = LogsPresenter(
            hasCredential = { true },
            fetch = { _, _ ->
                calls += 1
                if (calls == 1) gate.await() else ServerLogsResult.Failed("fresh failure")
            },
        )
        val stale = launch { presenter.load() }
        while (calls == 0) yield()
        launch { presenter.load() }.join()
        assertEquals(LogsPhase.ERROR, presenter.state.phase)

        gate.complete(ServerLogsResult.Window(window()))
        stale.join()
        assertEquals(LogsPhase.ERROR, presenter.state.phase)
        assertTrue(presenter.state.canRetry)
    }

    @Test
    fun cancellationIsRethrownAndNotSwallowedAsFailure() = runBlocking {
        val presenter = LogsPresenter(
            hasCredential = { true },
            fetch = { _, _ -> throw CancellationException("screen closed") },
        )
        val job = launch { presenter.load() }
        job.join()
        // runCatching проглотил бы отмену и job завершился бы нормально;
        // корректное поведение — проброс: job отменён, состояние не мутировано.
        assertTrue(job.isCancelled)
        assertEquals(LogsPhase.LOADING, presenter.state.phase)
        assertEquals(LogsPolicy.MSG_LOADING, presenter.state.message)
    }
}
