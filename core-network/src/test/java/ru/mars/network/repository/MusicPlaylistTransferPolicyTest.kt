package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM-тесты валидации ввода импорта плейлиста YouTube Music. */
class MusicPlaylistTransferPolicyTest {

    private fun accept(raw: String): String {
        val result = MusicPlaylistImportPolicy.validate(raw)
        assert(result is MusicPlaylistImportPolicy.Validation.Accepted) { "ожидался accept для [$raw], получено $result" }
        return (result as MusicPlaylistImportPolicy.Validation.Accepted).urlOrId
    }

    @Test
    fun `ordinary youtu be music playlist link yields the list id`() {
        assertEquals(
            "PL123abc",
            accept("https://music.youtube.com/playlist?list=PL123abc&si=abcd"),
        )
        assertEquals("PL123abc", accept("https://www.youtube.com/watch?v=xyz&list=PL123abc"))
    }

    @Test
    fun `youtu be short link is accepted`() {
        assertEquals("PL123abc", accept("https://youtu.be/PL123abc?si=xyz"))
        assertEquals("PL123abc", accept("youtu.be/PL123abc"))
    }

    @Test
    fun `VL-prefixed and bare ids pass through`() {
        assertEquals("VLPL123abc", accept("VLPL123abc"))
        assertEquals("PL123abc", accept("PL123abc"))
    }

    @Test
    fun `garbage and empty input are rejected`() {
        for (raw in listOf("", "   ", "just some words", "https://example.com/", "PL 123abc", "bad!!id")) {
            assertEquals(
                MusicPlaylistImportPolicy.Validation.Invalid,
                MusicPlaylistImportPolicy.validate(raw),
            )
        }
    }
}
