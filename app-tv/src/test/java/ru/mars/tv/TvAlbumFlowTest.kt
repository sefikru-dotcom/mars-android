package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.MusicAlbum
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvAlbumFlow] — чистого автомата диалога альбома (без Android: gateway,
 * executor и ui-dispatcher — fakes, [TvLyricsPanelFlowTest] parity).
 *
 * Покрыто ровно то, что ломается на живом пульте: Loading → Loaded/NotFound/Error,
 * невалидный browseId не уходит в сеть (сервер отвечает 500 на чужой префикс),
 * повторный open во время загрузки не дублирует запрос, ответ устаревшего запроса и
 * ответ после закрытия/уничтожения в UI не попадают, крах gateway не роняет поток,
 * «Повторить» повторяет только сетевой отказ.
 */
class TvAlbumFlowTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class QueueUi : TvUiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class FakeAlbumGateway : TvAlbumGateway {
        val results = ArrayDeque<TvAlbumResult>()
        var fallback: TvAlbumResult = TvAlbumResult.NotFound
        var calls = 0
        val requested = mutableListOf<String>()
        var throwOnFetch: Throwable? = null

        override fun fetchAlbum(browseId: String): TvAlbumResult {
            calls++
            requested += browseId
            throwOnFetch?.let { throw it }
            return if (results.isNotEmpty()) results.removeFirst() else fallback
        }
    }

    private val browseId = "MPREb_4KOifxmY0Kc"

    private fun track(id: String = "F9P_fIBfenk") = MusicTrack(
        id = id,
        title = "станция конечная",
        artist = "не меняет",
        duration = "1:31",
        albumTitle = "двадцать два",
        albumBrowseId = browseId,
    )

    private fun album() = MusicAlbum(
        browseId = browseId,
        title = "двадцать два",
        artist = "не меняет",
        year = "2024",
        tracks = listOf(track("a"), track("b")),
    )

    private class Harness {
        val gateway = FakeAlbumGateway()
        val executor = QueueExecutor()
        val ui = QueueUi()
        val states = mutableListOf<TvAlbumPanelState>()
        val flow = TvAlbumFlow(gateway, executor, ui).apply {
            onStateChanged = { states += it }
        }

        fun drain() {
            executor.runAll()
            ui.runAll()
        }
    }

    @Test
    fun `open loads the album and publishes Loading then Loaded`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Loaded(album())

        h.flow.open(browseId)
        assertEquals(TvAlbumPanelState.Loading(browseId), h.flow.state)

        h.drain()
        val loaded = h.flow.state as TvAlbumPanelState.Loaded
        assertEquals("двадцать два", loaded.album.title)
        assertEquals(2, loaded.album.tracks.size)
        assertEquals(listOf(browseId), h.gateway.requested)
        assertEquals(
            listOf(TvAlbumPanelState.Loading(browseId), TvAlbumPanelState.Loaded(album())),
            h.states,
        )
    }

    @Test
    fun `invalid browseId never reaches the network`() {
        val h = Harness()
        h.flow.open("F9P_fIBfenk")

        assertEquals(0, h.gateway.calls)
        assertTrue(h.flow.state is TvAlbumPanelState.NotFound)
        // Ни одного Loading: диалог сразу знает, что открывать нечего.
        assertEquals(1, h.states.size)
    }

    @Test
    fun `null browseId is NotFound and blank id is not fetched`() {
        val h = Harness()
        h.flow.open(null)
        assertTrue(h.flow.state is TvAlbumPanelState.NotFound)
        h.flow.open("   ")
        assertTrue(h.flow.state is TvAlbumPanelState.NotFound)
        assertEquals(0, h.gateway.calls)
    }

    @Test
    fun `repeated open while loading does not duplicate the request`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Loaded(album())

        h.flow.open(browseId)
        h.flow.open(browseId)
        h.flow.open("  $browseId ")
        h.drain()

        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun `server not found keeps the dialog on a terminal state`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.NotFound

        h.flow.open(browseId)
        h.drain()

        assertEquals(TvAlbumPanelState.NotFound(browseId), h.flow.state)
    }

    @Test
    fun `network failure surfaces as Error with a non-empty message`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Failed("timeout")

        h.flow.open(browseId)
        h.drain()

        val error = h.flow.state as TvAlbumPanelState.Error
        assertEquals(browseId, error.browseId)
        assertEquals("timeout", error.message)
    }

    @Test
    fun `gateway crash is contained as Error`() {
        val h = Harness()
        h.gateway.throwOnFetch = IllegalStateException("boom")

        h.flow.open(browseId)
        h.drain()

        val error = h.flow.state as TvAlbumPanelState.Error
        assertEquals("boom", error.message)
    }

    @Test
    fun `close invalidates the in-flight response`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Loaded(album())

        h.flow.open(browseId)
        h.flow.close()
        h.drain()

        // Ответ пришёл после закрытия — окно уже Idle, старый альбом не всплывает.
        assertEquals(TvAlbumPanelState.Idle, h.flow.state)
        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun `stale response of a previous album does not overwrite the fresh one`() {
        val h = Harness()
        val otherId = "MPREb_OTHER00000"
        h.gateway.results += TvAlbumResult.Loaded(album())
        h.gateway.results += TvAlbumResult.Loaded(album().copy(browseId = otherId, title = "второй"))

        h.flow.open(browseId)
        h.flow.open(otherId) // предыдущий запрос ещё в очереди
        h.drain()

        val loaded = h.flow.state as TvAlbumPanelState.Loaded
        assertEquals("второй", loaded.album.title)
        assertEquals(listOf(browseId, otherId), h.gateway.requested)
    }

    @Test
    fun `retry repeats a failed request but not a not-found`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Failed("timeout")
        h.flow.open(browseId)
        h.drain()
        assertEquals(1, h.gateway.calls)

        h.gateway.results += TvAlbumResult.Loaded(album())
        h.flow.retry()
        h.drain()
        assertEquals(2, h.gateway.calls)
        assertTrue(h.flow.state is TvAlbumPanelState.Loaded)

        // «Не найден» повторять нечего: запрос не уходит.
        h.flow.open("F9P_fIBfenk")
        val callsBefore = h.gateway.calls
        h.flow.retry()
        h.drain()
        assertEquals(callsBefore, h.gateway.calls)
    }

    @Test
    fun `destroy silences the flow`() {
        val h = Harness()
        h.gateway.results += TvAlbumResult.Loaded(album())
        h.flow.open(browseId)
        h.flow.destroy()
        h.drain()

        // Состояние не опубликовано после destroy: подписчик окна уже мёртв.
        assertTrue(h.states.none { it is TvAlbumPanelState.Loaded })
        h.flow.open(browseId)
        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun `idle flow has no state change hook installed by default`() {
        val flow = TvAlbumFlow(FakeAlbumGateway(), QueueExecutor(), QueueUi())
        assertNull(flow.onStateChanged)
        assertEquals(TvAlbumPanelState.Idle, flow.state)
    }
}
