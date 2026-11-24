package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.MusicPlaylistImportResult

class MusicPlaylistImportStateTest {

    @Test
    fun `success state reports exact track count`() {
        val state = MusicPlaylistImportStateMapper.from(
            MusicPlaylistImportResult.Imported("abc12345", "Street", 7),
        )
        assertTrue(state is MusicPlaylistImportUiState.Done)
        assertEquals("Street", (state as MusicPlaylistImportUiState.Done).title)
        assertEquals(7, state.count)
        assertEquals("Импортировано треков: 7", state.label)
    }

    @Test
    fun `server error text is preserved verbatim and spinner is over`() {
        val state = MusicPlaylistImportStateMapper.from(
            MusicPlaylistImportResult.Failed("Failed to fetch playlist: timeout"),
        )
        assertEquals(
            MusicPlaylistImportUiState.Error("Failed to fetch playlist: timeout"),
            state,
        )
        assertEquals("Failed to fetch playlist: timeout", state.label)
    }

    @Test
    fun `importing is a terminal-spinner state only until result arrives`() {
        assertEquals("Импортирую…", MusicPlaylistImportUiState.Importing.label)
        assertEquals("", MusicPlaylistImportUiState.Idle.label)
    }
}
