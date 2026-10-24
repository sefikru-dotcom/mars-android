package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

private fun catalog() = WorkspacesResponse(
    workspaces = listOf(
        WorkspaceItem("Home", "/root/workspace"),
        WorkspaceItem("Квартал", "/opt/kvartal-git"),
        WorkspaceItem("hermes-webui", "/opt/hermes-webui"),
    ),
    last = "/opt/kvartal-git",
)

/**
 * Оркестрация экрана «Пространства» на fake-шве [SpacesGateway]: ни одного
 * HTTP-вызова, все переходы состояний проверяются на JVM.
 */
class SpacesPresenterTest {

    private class FakeStorage(var saved: WorkspaceItem? = null) : WorkspaceChoiceStorage {
        override fun load(): WorkspaceItem? = saved
        override fun save(item: WorkspaceItem) {
            saved = item
        }

        override fun clear() {
            saved = null
        }
    }

    private class FakeGateway(var result: SpacesFetchResult) : SpacesGateway {
        var calls = 0

        override suspend fun fetchSpaces(): SpacesFetchResult {
            calls++
            return result
        }
    }

    private class Harness(
        var result: SpacesFetchResult = SpacesFetchResult.Success(catalog()),
        saved: WorkspaceItem? = null,
        var offline: Boolean = false,
        var now: Long = 1_000_000L,
    ) {
        val storage = FakeStorage(saved)
        val selection = WorkspaceSelection(storage)
        val gateway = FakeGateway(result)
        val presenter = SpacesPresenter(
            selection = selection,
            gateway = gateway,
            isOffline = { offline },
            clock = { now },
        )
    }

    @Test
    fun `initial state is loading with a message`() {
        val h = Harness()
        assertEquals(SpacesPhase.LOADING, h.presenter.state.phase)
        assertEquals(SpacesPolicy.MSG_LOADING, h.presenter.state.message)
    }

    @Test
    fun `subscribe replays the current state`() {
        val h = Harness()
        val seen = mutableListOf<SpacesUiState>()
        h.presenter.subscribe { seen += it }
        assertEquals(1, seen.size)
        assertEquals(SpacesPhase.LOADING, seen.single().phase)
    }

    @Test
    fun `missing pairing becomes no auth`() = runBlocking {
        val h = Harness(result = SpacesFetchResult.NotPaired)
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.NO_AUTH, state.phase)
        assertEquals(SpacesPolicy.MSG_NO_AUTH, state.message)
        assertTrue(state.canRetry)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `thrown gateway becomes an error, not a crash`() = runBlocking {
        val h = Harness()
        val throwing = SpacesPresenter(
            selection = h.selection,
            gateway = object : SpacesGateway {
                override suspend fun fetchSpaces(): SpacesFetchResult = error("boom")
            },
        )
        throwing.load()
        assertEquals(SpacesPhase.ERROR, throwing.state.phase)
        assertEquals(SpacesPolicy.MSG_ERROR, throwing.state.message)
        assertTrue(throwing.state.canRetry)
    }

    @Test
    fun `success is ready with the auto row first and active`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.READY, state.phase)
        assertEquals(4, state.rows.size) // «Авто» + три пространства
        assertTrue(state.rows[0].isAuto)
        assertTrue(state.rows[0].isActive) // явного пика нет — активно «Авто»
        assertFalse(state.rows.drop(1).any { it.isActive })
        assertTrue(state.rows[2].isServerDefault) // /opt/kvartal-git == last
        assertNull(state.message)
        assertEquals("Пространств: 3", state.summary)
    }

    @Test
    fun `success with a persisted pick marks that row active`() = runBlocking {
        val h = Harness(saved = WorkspaceItem("hermes-webui", "/opt/hermes-webui"))
        h.presenter.load()
        val state = h.presenter.state
        assertFalse(state.rows[0].isActive) // «Авто» больше не активно
        assertEquals(listOf(false, false, true), state.rows.drop(1).map { it.isActive })
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), h.selection.current)
    }

    @Test
    fun `empty catalog is the empty phase, not an error`() = runBlocking {
        val h = Harness(result = SpacesFetchResult.Success(WorkspacesResponse(emptyList(), last = null)))
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.EMPTY, state.phase)
        assertEquals(SpacesPolicy.MSG_EMPTY, state.message)
        assertEquals("Пространств: 0", state.summary)
        assertTrue(state.rows.isEmpty())
        assertFalse(state.canRetry)
    }

    @Test
    fun `failure while offline says so and offers a retry`() = runBlocking {
        val h = Harness(result = SpacesFetchResult.Failure, offline = true)
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.ERROR, state.phase)
        assertEquals(SpacesPolicy.MSG_ERROR_OFFLINE, state.message)
        assertEquals(SpacesPolicy.NOTICE_OFFLINE, state.notice)
        assertTrue(state.canRetry)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `failure without saved pick keeps the plain error text`() = runBlocking {
        val h = Harness(result = SpacesFetchResult.Failure)
        h.presenter.load()
        assertEquals(SpacesPolicy.MSG_ERROR, h.presenter.state.message)
        assertNull(h.presenter.state.notice)
    }

    @Test
    fun `offline with a saved pick shows the local row instead of an empty screen`() = runBlocking {
        val h = Harness(
            result = SpacesFetchResult.Failure,
            offline = true,
            saved = WorkspaceItem("Home", "/root/workspace"),
        )
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.ERROR, state.phase)
        val row = state.rows.single()
        assertEquals("/root/workspace", row.key)
        assertTrue(row.isActive)
        assertEquals(SpacesPolicy.NOTICE_OFFLINE_SAVED, state.notice)
        assertNull(state.message)
        assertTrue(state.canRetry)
    }

    @Test
    fun `server default is not passed off as a saved pick`() = runBlocking {
        // Каталог уже был получен, пика нет: «Активно» на серверном значении по
        // умолчанию не значит, что пользователь что-то выбирал.
        val h = Harness()
        h.presenter.load()
        h.gateway.result = SpacesFetchResult.Failure
        h.now += 25L * 60 * 60 * 1000 // кэш просрочен
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.ERROR, state.phase)
        assertTrue(state.rows.isEmpty())
        assertEquals(SpacesPolicy.MSG_ERROR, state.message)
    }

    @Test
    fun `select persists the pick and moves the active mark`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val effective = h.presenter.select("/opt/hermes-webui")
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), effective)
        assertEquals("/opt/hermes-webui", h.storage.saved?.path) // переживает перезапуск
        val state = h.presenter.state
        assertEquals(SpacesPhase.READY, state.phase)
        assertFalse(state.rows[0].isActive)
        assertEquals(listOf(false, false, true), state.rows.drop(1).map { it.isActive })
        // Тот же выбор уходит в исходящее сообщение чата (общее хранилище).
        assertEquals(
            "[Workspace::v1: /opt/hermes-webui]\nпривет",
            h.selection.outgoingMessage("привет"),
        )
    }

    @Test
    fun `select auto returns the screen to the auto mode`() = runBlocking {
        val h = Harness(saved = WorkspaceItem("Home", "/root/workspace"))
        h.presenter.load()
        h.presenter.select(SpacesPolicy.AUTO_KEY)
        // Контракт WorkspaceSelection.select(null): при известном каталоге принятое
        // серверное значение по умолчанию персистится (как в чипе чата), но
        // состояние экрана — снова «Авто».
        assertEquals("/opt/kvartal-git", h.storage.saved?.path)
        val state = h.presenter.state
        assertTrue(state.rows[0].isActive)
        assertFalse(state.rows.drop(1).any { it.isActive })
        assertEquals("/opt/kvartal-git", h.selection.current?.path)
    }

    @Test
    fun `select with an unknown key keeps the state stable`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val before = h.presenter.state.rows
        // Неизвестный ключ не создаёт пик: эффективный выбор остаётся прежним
        // (серверное значение по умолчанию), storage не трогается.
        assertEquals("/opt/kvartal-git", h.presenter.select("/no/such/path")?.path)
        assertEquals(before, h.presenter.state.rows)
        assertNull(h.storage.saved)
    }

    @Test
    fun `reload after an error recovers to ready`() = runBlocking {
        val h = Harness(result = SpacesFetchResult.Failure)
        h.presenter.load()
        assertEquals(SpacesPhase.ERROR, h.presenter.state.phase)
        h.gateway.result = SpacesFetchResult.Success(catalog())
        h.presenter.load()
        assertEquals(SpacesPhase.READY, h.presenter.state.phase)
        assertEquals(4, h.presenter.state.rows.size)
    }

    @Test
    fun `refresh keeps the shown rows and does not blink the screen`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val seen = mutableListOf<SpacesUiState>()
        h.presenter.subscribe { seen += it }
        h.presenter.load()
        val refreshing = seen[1]
        assertEquals(SpacesPhase.READY, refreshing.phase)
        assertTrue(refreshing.refreshing)
        assertEquals(4, refreshing.rows.size)
        assertNull(refreshing.message)
        // Итоговое состояние — тот же каталог без флага обновления.
        assertFalse(seen.last().refreshing)
    }

    @Test
    fun `failure after a success shows the session catalog as stale`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        h.gateway.result = SpacesFetchResult.Failure
        h.offline = true
        h.now += 60_000
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.READY, state.phase)
        assertTrue(state.stale)
        assertEquals(SpacesPolicy.NOTICE_OFFLINE_CACHE, state.notice)
        assertEquals(4, state.rows.size)
        assertTrue(state.canRetry)
    }

    @Test
    fun `stale catalog older than the cache limit falls back to an error`() = runBlocking {
        val h = Harness()
        h.presenter.load()
        h.gateway.result = SpacesFetchResult.Failure
        h.now += SpacesPolicy.CACHE_MAX_AGE_MS + 1
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.ERROR, state.phase)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `pick that vanished from the server falls back to auto`() = runBlocking {
        // Держать явный пик на несуществующем пути нельзя: следующее сообщение ушло
        // бы с сентинелом каталога, которого на сервере нет.
        val h = Harness(
            result = SpacesFetchResult.Success(
                WorkspacesResponse(workspaces = listOf(WorkspaceItem("Home", "/root/workspace")), last = null)
            ),
            saved = WorkspaceItem("Квартал", "/opt/kvartal-git"),
        )
        h.presenter.load()
        val state = h.presenter.state
        assertEquals(SpacesPhase.READY, state.phase)
        assertTrue(state.rows[0].isActive)
        assertFalse(state.rows.drop(1).any { it.isActive })
        // Пик заменён серверным значением по умолчанию (в каталоге его нет — пика нет).
        assertNull(h.storage.saved)
    }

    @Test
    fun `offline local row selection still persists`() = runBlocking {
        val h = Harness(
            result = SpacesFetchResult.Failure,
            offline = true,
            saved = WorkspaceItem("Home", "/root/workspace"),
        )
        h.presenter.load()
        h.presenter.select("/root/workspace")
        assertEquals("/root/workspace", h.storage.saved?.path)
        assertTrue(h.presenter.state.rows.single().isActive)
    }
}
