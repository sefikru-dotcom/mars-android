package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

/**
 * Контракты персональной ленты GET /api/home и живых подсказок GET /api/suggestions
 * (server.py handle_home / build_personal_shelf / handle_suggestions). Живой сервер
 * держит полки в ключе `shelf` и дублирует кураторские миксы в верхнеуровневом
 * `sections`; документированный контракт описывает полки прямо в `sections` —
 * разбор обязан принимать обе формы, отдавая приоритет `shelf`.
 */
class MusicHomeSuggestionsRepositoryTest {

    private fun repo(server: MockWebServer) =
        MusicRepository(server.url("/music").toString().removeSuffix("/"))

    private fun track(id: String) =
        """{"id":"$id","title":"Song $id","artist":"Artist $id","duration":"3:24","thumbnail":"/music/api/thumb?v=$id"}"""

    // ---------------------------------------------------------------- /api/home

    @Test
    fun homeParsesShelfSectionsAndPrefersThemOverLegacySections() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"hero":{"id":"h1","title":"Hero","artist":"A"},
                       |"folders":[],"recent":[],
                       |"sections":[{"title":"Быстрый микс","items":[${track("legacy1")}]}],
                       |"shelf":{"sections":[
                       |  {"id":"continue","title":"Продолжить слушать","items":[${track("c1")},${track("c2")}]},
                       |  {"id":"new","title":"Новинки","items":[${track("n1")}]},
                       |  {"id":"for_you","title":"Для тебя","items":[${track("f1")}]},
                       |  {"id":"similar","title":"Похожие на «Song»","anchor":${track("a1")},"items":[${track("s1")}]}
                       |],"generatedAt":1700000000}}""".trimMargin().replace("\n", ""),
                ),
            )
            val shelves = repo(server).getHome()
            assertEquals(listOf("continue", "new", "for_you", "similar"), shelves.map { it.id })
            assertEquals(
                listOf("Продолжить слушать", "Новинки", "Для тебя", "Похожие на «Song»"),
                shelves.map { it.title },
            )
            assertEquals(listOf("c1", "c2"), shelves[0].items.map { it.id })
            assertEquals(
                MusicTrack("c1", "Song c1", "Artist c1", "3:24", "/music/api/thumb?v=c1"),
                shelves[0].items[0],
            )
            assertEquals(listOf("s1"), shelves[3].items.map { it.id })
            assertEquals("/music/api/home", server.takeRequest().path)
        }
    }

    /**
     * Трек на полке — не только `TrackDto`: `ShelfTrackDto` разбирает `album` тем же
     * `AlbumRefDto`. Без этого лента «Для тебя» показывала треки без альбома, хотя
     * чарты и поиск его уже отдавали.
     */
    @Test
    fun homeShelfTrackCarriesAlbumFromTheLiveContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"shelf":{"sections":[{"id":"for_you","title":"Для тебя","items":[
                       |{"id":"F9P_fIBfenk","title":"станция конечная","artist":"не меняет","duration":"1:31",
                       |"thumbnail":"/music/api/thumb?v=F9P_fIBfenk",
                       |"album":{"name":"двадцать два","browseId":"MPREb_4KOifxmY0Kc"}}
                       |]}]}}""".trimMargin().replace("\n", ""),
                ),
            )
            val track = repo(server).getHome().single().items.single()
            assertEquals("двадцать два", track.albumTitle)
            assertEquals("MPREb_4KOifxmY0Kc", track.albumBrowseId)
        }
    }

    @Test
    fun homeParsesTopLevelSectionsWhenShelfMissing() = runBlocking {
        // Форма из документированного контракта: полки прямо в верхнеуровневом `sections`.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"sections":[{"id":"continue","title":"Продолжить слушать","items":[${track("c1")}]}],"generatedAt":1700000000}""",
                ),
            )
            val shelves = repo(server).getHome()
            assertEquals(listOf(MusicShelf("continue", "Продолжить слушать", listOf(
                MusicTrack("c1", "Song c1", "Artist c1", "3:24", "/music/api/thumb?v=c1"),
            ))), shelves)
        }
    }

    @Test
    fun homeEmptyShelvesAreANormalStateNotAnError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"shelf":{"sections":[
                       |{"id":"continue","title":"Продолжить слушать","items":[]},
                       |{"id":"new","title":"Новинки","items":[]},
                       |{"id":"for_you","title":"Для тебя","items":[]},
                       |{"id":"similar","title":"Похожие","anchor":null,"items":[]}
                       |],"generatedAt":1700000000}}""".trimMargin().replace("\n", ""),
                ),
            )
            val shelves = repo(server).getHome()
            assertEquals(4, shelves.size)
            assertTrue(shelves.all { it.items.isEmpty() })
        }
    }

    @Test
    fun homeWithoutSectionsIsEmptyListWithoutCrash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"hero":{"id":"h1","title":"Hero","artist":"A"},"folders":[],"recent":[]}""",
                ),
            )
            assertEquals(emptyList<MusicShelf>(), repo(server).getHome())
        }
    }

    @Test
    fun homeShelfRowsTolerateMissingFieldsAndDropIdLessRows() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"shelf":{"sections":[{"id":"continue","title":"Продолжить слушать","items":[
                       |{"id":"ok","title":"","artist":null,"duration":"","thumbnail":""},
                       |{"title":"Без id","artist":"A"}
                       |]}],"generatedAt":1}}""".trimMargin().replace("\n", ""),
                ),
            )
            val shelves = repo(server).getHome()
            val items = shelves.single().items
            // Строка без id неиграбельна и отбрасывается; пустые поля получают фолбэки.
            assertEquals(listOf("ok"), items.map { it.id })
            assertEquals("Без названия", items[0].title)
            assertEquals("Неизвестный артист", items[0].artist)
            assertEquals("--:--", items[0].duration)
            assertNull(items[0].thumbnail)
        }
    }

    @Test
    fun homeLegacySectionsWithoutIdFallBackToTitleKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"sections":[{"title":"Быстрый микс","items":[${track("q1")}]}]}""",
                ),
            )
            val shelves = repo(server).getHome()
            assertEquals("Быстрый микс", shelves.single().id)
            assertEquals(listOf("q1"), shelves.single().items.map { it.id })
        }
    }

    @Test
    fun homeSuccessFalseSurfacesAsFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"error":"upstream down"}"""))
            val failure = runCatching { repo(server).getHome() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("upstream down"))
        }
    }

    @Test
    fun homeHttpFailureSurfaces() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"error":"boom"}"""))
            val failure = runCatching { repo(server).getHome() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("500"))
        }
    }

    // ---------------------------------------------------------- /api/suggestions

    @Test
    fun suggestionsMapsArrayAndSendsEncodedQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"suggestions":["rock live","рок живьём"]}"""))
            val suggestions = repo(server).getSuggestions("rock & roll")
            assertEquals(listOf("rock live", "рок живьём"), suggestions)
            assertEquals("/music/api/suggestions?q=rock%20%26%20roll", server.takeRequest().path)
        }
    }

    @Test
    fun suggestionsEmptyArrayIsANormalEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"suggestions":[]}"""))
            assertEquals(emptyList<String>(), repo(server).getSuggestions("запрос"))
        }
    }

    @Test
    fun suggestionsTrimsDropsBlanksAndCapsAtEight() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"suggestions":[" one ","","   ","two","3","4","5","6","7","8","9"]}""",
                ),
            )
            val suggestions = repo(server).getSuggestions("ro")
            assertEquals(listOf("one", "two", "3", "4", "5", "6", "7", "8"), suggestions)
        }
    }

    @Test
    fun suggestionsServerFailureYieldsEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
            assertEquals(emptyList<String>(), repo(server).getSuggestions("запрос"))
        }
    }

    @Test
    fun suggestionsMalformedBodyYieldsEmptyList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"suggestions":"not-an-array"}"""))
            assertEquals(emptyList<String>(), repo(server).getSuggestions("запрос"))
        }
    }

    @Test
    fun suggestionsShortQueryNeverHitsServer() = runBlocking {
        MockWebServer().use { server ->
            // Сервер отвечает пустым массивом на q короче 2 символов — клиент не спамит.
            assertEquals(emptyList<String>(), repo(server).getSuggestions("р"))
            assertEquals(emptyList<String>(), repo(server).getSuggestions("  "))
            assertEquals(0, server.requestCount)
        }
    }
}
