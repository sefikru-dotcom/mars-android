package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvLyricsPanelFlow] — чистого конечного автомата панели «Текст песни»
 * (без Android: гейтвей, executor и ui-dispatcher — fakes, TvMusicScreenControllerTest
 * parity). Покрыто: Loading → Text/Missing/Error, обязательная передача title/artist
 * в гейтвей, защита от двойного тапа (повторный open того же трека в Loading не
 * дублирует запрос), generation-guard (ответ устаревшего трека и запрос, закрытый
 * до ответа, в UI не попадают), изоляция падения гейтвея и destroy-семантика.
 */
class TvLyricsPanelFlowTest {

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

    private class FakeLyricsGateway : TvLyricsGateway {
        val results = ArrayDeque<TvLyricsResult>()
        var fallback: TvLyricsResult = TvLyricsResult.Loaded(null)
        var calls = 0
        val requested = mutableListOf<MusicTrack>()
        var throwOnFetch: Throwable? = null

        override fun fetchLyrics(track: MusicTrack): TvLyricsResult {
            calls++
            requested += track
            throwOnFetch?.let { throw it }
            return if (results.isNotEmpty()) results.removeFirst() else fallback
        }
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        duration = "3:20",
        thumbnail = "/music/api/thumb?v=$id",
    )

    private class Harness {
        val gateway = FakeLyricsGateway()
        val executor = QueueExecutor()
        val ui = QueueUi()
        val states = mutableListOf<TvLyricsPanelState>()
        val flow = TvLyricsPanelFlow(gateway, executor, ui).apply {
            onStateChanged = { states += it }
        }

        fun drain() {
            executor.runAll()
            ui.runAll()
        }
    }

    /** Реальная форма живого ответа /api/lyrics (fixtures/lyrics_kino.json). */
    private val kinoLyrics = LyricsResult(
        lyrics = "Теплое место, но улицы ждут отпечатков наших ног.",
        synced = null,
        source = "youtube",
    )

    @Test
    fun `open publishes Loading immediately and Text after the fetch lands`() {
        val h = Harness()
        h.gateway.fallback = TvLyricsResult.Loaded(kinoLyrics)
        val t = track("kino1")

        h.flow.open(t)
        assertEquals(listOf<TvLyricsPanelState>(TvLyricsPanelState.Loading(t)), h.states)

        h.drain()
        assertEquals(2, h.states.size)
        assertEquals(
            TvLyricsPanelState.Text(t, body = kinoLyrics.lyrics!!, synced = false, source = "youtube"),
            h.states.last(),
        )
    }

    @Test
    fun `gateway receives the track with title and artist for the query params`() {
        val h = Harness()
        val t = track("vSA7yMrTETg")
        h.flow.open(t)
        h.drain()
        assertEquals(listOf(t), h.gateway.requested)
        // title/artist обязательны живому серверу — проверяем, что ушли непустыми.
        assertTrue(h.gateway.requested.single().title.isNotBlank())
        assertTrue(h.gateway.requested.single().artist.isNotBlank())
    }

    @Test
    fun `server not-found stub becomes Missing`() {
        val h = Harness()
        h.gateway.fallback = TvLyricsResult.Loaded(
            LyricsResult(lyrics = "Текст не найден.", synced = null, source = "youtube"),
        )
        val t = track("stub1")
        h.flow.open(t)
        h.drain()
        assertEquals(TvLyricsPanelState.Missing(t), h.states.last())
    }

    @Test
    fun `failed result becomes Error and blank message falls back`() {
        val h = Harness()
        h.gateway.results.addLast(TvLyricsResult.Failed("timeout"))
        h.flow.open(track("a"))
        h.drain()
        assertTrue(h.states.last() is TvLyricsPanelState.Error)
        assertEquals("timeout", (h.states.last() as TvLyricsPanelState.Error).message)

        val h2 = Harness()
        h2.gateway.fallback = TvLyricsResult.Failed("   ")
        h2.flow.open(track("b"))
        h2.drain()
        assertEquals(TvLyricsPanelFlow.FETCH_FAILURE, (h2.states.last() as TvLyricsPanelState.Error).message)
    }

    @Test
    fun `gateway crash is contained as Error and never escapes`() {
        val h = Harness()
        h.gateway.throwOnFetch = IllegalStateException("socket closed")
        h.flow.open(track("crash"))
        h.drain()
        val last = h.states.last()
        assertTrue(last is TvLyricsPanelState.Error)
        assertTrue((last as TvLyricsPanelState.Error).message.contains("socket closed"))
    }

    @Test
    fun `double tap on the same track while loading does not duplicate the request`() {
        val h = Harness()
        val t = track("dbl")
        h.flow.open(t)
        h.flow.open(t)
        h.flow.open(t)
        // Повторные open того же трека в Loading глушатся: один запрос в очереди, один Loading.
        assertEquals(1, h.executor.queue.size)
        assertEquals(1, h.states.size)
        h.drain()
        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun `reopen after close fetches again`() {
        val h = Harness()
        val t = track("re")
        h.flow.open(t)
        h.drain()
        assertEquals(1, h.gateway.calls)
        h.flow.close()
        h.flow.open(t)
        h.drain()
        assertEquals(2, h.gateway.calls)
    }

    @Test
    fun `latest request wins - stale answer of a superseded track is dropped`() {
        val h = Harness()
        h.gateway.results.addLast(TvLyricsResult.Loaded(LyricsResult("старый", null, null)))
        h.gateway.results.addLast(TvLyricsResult.Loaded(LyricsResult("новый", null, null)))
        val a = track("a")
        val b = track("b")

        h.flow.open(a)
        h.flow.open(b)
        h.drain()

        val last = h.states.last()
        assertEquals(TvLyricsPanelState.Text(b, "новый", synced = false, source = null), last)
        // Ответ трека A (первый в очереди) отброшен generation-guard'ом — его нет вовсе.
        assertTrue(h.states.none { it is TvLyricsPanelState.Text && it.track == a })
    }

    @Test
    fun `close before the answer lands leaves Idle and drops the late result`() {
        val h = Harness()
        h.gateway.fallback = TvLyricsResult.Loaded(kinoLyrics)
        h.flow.open(track("gone"))
        h.flow.close()
        assertEquals(TvLyricsPanelState.Idle, h.states.last())
        h.drain()
        assertEquals(TvLyricsPanelState.Idle, h.states.last())
    }

    @Test
    fun `destroy silences everything - no fetch, no publish`() {
        val h = Harness()
        h.flow.destroy()
        h.flow.open(track("dead"))
        assertEquals(0, h.gateway.calls)
        assertTrue(h.states.isEmpty())
        h.flow.close()
        assertTrue(h.states.isEmpty())
    }
}
