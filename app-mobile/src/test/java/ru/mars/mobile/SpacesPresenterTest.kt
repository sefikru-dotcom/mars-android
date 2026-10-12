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

class SpacesPresenterTest {

    private class FakeStorage : WorkspaceChoiceStorage {
        var saved: WorkspaceItem? = null
        override fun load(): WorkspaceItem? = saved
        override fun save(item: WorkspaceItem) { saved = item }
        override fun clear() { saved = null }
    }

    private class Harness(
        response: WorkspacesResponse? = catalog(),
        throws: Boolean = false,
        credential: Boolean = true,
        offline: Boolean = false,
        saved: WorkspaceItem? = null,
    ) {
        val storage = FakeStorage().apply { this.saved = saved }
        val selection = WorkspaceSelection(storage)
        val presenter = SpacesPresenter(
            selection = selection,
            explicitPickPath = { storage.load()?.path },
            hasCredential = { credential },
            fetch = { if (throws) throw RuntimeException("boom") else response },
            isOffline = { offline },
        )
    }

    @Test
    fun initialStateIsLoadingWithMessage() {
        val h = Harness()
        assertEquals(SpacesPhase.LOADING, h.presenter.state.phase)
        assertEquals(SpacesPolicy.MSG_LOADING, h.presenter.state.message)
    }

    @Test
    fun subscribeReplaysCurrentState() {
        val h = Harness()
        val seen = mutableListOf<SpacesUiState>()
        h.presenter.subscribe { seen += it }
        assertEquals(1, seen.size)
        assertEquals(SpacesPhase.LOADING, seen[0].phase)
    }

    @Test
    fun noCredentialBecomesNoAuthWithoutFetching() {
        val h = Harness(credential = false)
        runBlocking { h.presenter.load() }
        val s = h.presenter.state
        assertEquals(SpacesPhase.NO_AUTH, s.phase)
        assertEquals(SpacesPolicy.MSG_NO_AUTH, s.message)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun successBecomesReadyWithAutoRowFirstAndAutoActive() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val s = h.presenter.state
        assertEquals(SpacesPhase.READY, s.phase)
        assertEquals(4, s.rows.size) // «Авто» + 3 пространства
        assertTrue(s.rows[0].isAuto)
        assertTrue(s.rows[0].isActive) // явного пика нет — активно «Авто»
        assertFalse(s.rows.drop(1).any { it.isActive })
        assertTrue(s.rows[2].isServerDefault) // /opt/kvartal-git == last
        assertNull(s.message)
    }

    @Test
    fun successWithPersistedPickMarksThatRowActive() = runBlocking {
        val h = Harness(saved = WorkspaceItem("hermes-webui", "/opt/hermes-webui"))
        h.presenter.load()
        val s = h.presenter.state
        assertFalse(s.rows[0].isActive) // «Авто» больше не активно
        assertEquals(listOf(false, false, true), s.rows.drop(1).map { it.isActive })
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), h.selection.current)
    }

    @Test
    fun emptyCatalogBecomesEmptyPhase() = runBlocking {
        val h = Harness(response = WorkspacesResponse(workspaces = emptyList(), last = null))
        h.presenter.load()
        val s = h.presenter.state
        assertEquals(SpacesPhase.EMPTY, s.phase)
        assertEquals(SpacesPolicy.MSG_EMPTY, s.message)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun nullResponseOfflineBecomesErrorWithOfflineMessage() = runBlocking {
        val h = Harness(response = null, offline = true)
        h.presenter.load()
        val s = h.presenter.state
        assertEquals(SpacesPhase.ERROR, s.phase)
        assertEquals(SpacesPolicy.MSG_ERROR_OFFLINE, s.message)
        assertEquals(SpacesPolicy.NOTICE_OFFLINE, s.notice)
        assertTrue(s.canRetry)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun thrownFetchBecomesErrorNotCrash() = runBlocking {
        val h = Harness(throws = true)
        h.presenter.load()
        assertEquals(SpacesPhase.ERROR, h.presenter.state.phase)
        assertEquals(SpacesPolicy.MSG_ERROR, h.presenter.state.message)
        assertTrue(h.presenter.state.canRetry)
    }

    @Test
    fun offlineWithSavedPickShowsLocalRowInsteadOfEmptyScreen() = runBlocking {
        val h = Harness(response = null, offline = true, saved = WorkspaceItem("Home", "/root/workspace"))
        h.presenter.load()
        val s = h.presenter.state
        assertEquals(SpacesPhase.ERROR, s.phase)
        assertEquals(1, s.rows.size)
        val row = s.rows.single()
        assertEquals("/root/workspace", row.key)
        assertTrue(row.isActive)
        assertEquals(SpacesPolicy.NOTICE_OFFLINE_SAVED, s.notice)
        assertNull(s.message)
        assertTrue(s.canRetry)
    }

    @Test
    fun selectItemPersistsPickAndMovesActiveMark() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val effective = h.presenter.select("/opt/hermes-webui")
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), effective)
        assertEquals("/opt/hermes-webui", h.storage.saved?.path) // переживает перезапуск
        val s = h.presenter.state
        assertEquals(SpacesPhase.READY, s.phase)
        assertFalse(s.rows[0].isActive)
        assertEquals(listOf(false, false, true), s.rows.drop(1).map { it.isActive })
    }

    @Test
    fun selectAutoClearsPersistedPick() = runBlocking {
        val h = Harness(saved = WorkspaceItem("Home", "/root/workspace"))
        h.presenter.load()
        h.presenter.select(SpacesPolicy.AUTO_KEY)
        // Контракт WorkspaceSelection.select(null): при известном каталоге
        // принятый серверный default ПЕРСИСТИТСЯ (как в чипе ChatActivity),
        // но UI-состояние экрана — снова «Авто».
        assertEquals("/opt/kvartal-git", h.storage.saved?.path)
        val s = h.presenter.state
        assertTrue(s.rows[0].isActive)
        assertFalse(s.rows.drop(1).any { it.isActive })
        // Эффективный выбор — серверный default из каталога (WebUI-паритет).
        assertEquals("/opt/kvartal-git", h.selection.current?.path)
    }

    @Test
    fun selectUnknownKeyKeepsStateStable() = runBlocking {
        val h = Harness()
        h.presenter.load()
        val before = h.presenter.state.rows
        h.presenter.select("/no/such/path")
        assertEquals(before, h.presenter.state.rows)
    }

    @Test
    fun reloadAfterErrorRecoversToReady() = runBlocking {
        var fail = true
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        val presenter = SpacesPresenter(
            selection = selection,
            explicitPickPath = { storage.load()?.path },
            hasCredential = { true },
            fetch = { if (fail) null else catalog() },
        )
        presenter.load()
        assertEquals(SpacesPhase.ERROR, presenter.state.phase)
        fail = false
        presenter.load()
        assertEquals(SpacesPhase.READY, presenter.state.phase)
        assertEquals(4, presenter.state.rows.size)
    }

    @Test
    fun loadingKeepsPreviousRowsWithoutCenterMessage() = runBlocking {
        val h = Harness()
        h.presenter.load()
        // Повторный load: старые строки остаются (refresh без мигания пустотой).
        val seen = mutableListOf<SpacesUiState>()
        h.presenter.subscribe { seen += it }
        h.presenter.load()
        val loadingState = seen[1]
        assertEquals(SpacesPhase.LOADING, loadingState.phase)
        assertEquals(4, loadingState.rows.size)
        assertNull(loadingState.message)
    }

    @Test
    fun offlineLocalRowSelectionStillPersists() = runBlocking {
        val h = Harness(response = null, offline = true, saved = WorkspaceItem("Home", "/root/workspace"))
        h.presenter.load()
        h.presenter.select("/root/workspace")
        assertEquals("/root/workspace", h.storage.saved?.path)
        assertTrue(h.presenter.state.rows.single().isActive)
    }
}
