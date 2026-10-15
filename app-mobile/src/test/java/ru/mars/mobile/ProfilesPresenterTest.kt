package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/** Переходы состояний экрана «Профили»: сеть/детектор credential инжектятся, Android не нужен. */
class ProfilesPresenterTest {

    private val payload = ProfilesResponse(
        profiles = listOf(MarsProfile(name = "default", isDefault = true), MarsProfile(name = "office", isActive = true)),
        active = "office",
    )

    private fun presenter(
        fetch: suspend () -> ProfilesResponse?,
        hasCredential: () -> Boolean = { true },
        activeName: suspend () -> String? = { null },
    ) = ProfilesPresenter(hasCredential, fetch, activeName)

    @Test
    fun `no credential never touches the network`() = runBlocking {
        var fetched = false
        val presenter = presenter(fetch = { fetched = true; payload }, hasCredential = { false })

        presenter.load()

        assertEquals(ProfilesScreenState.NoAuth, presenter.state)
        assertFalse("без пары запрос не уходит", fetched)
    }

    @Test
    fun `load publishes loading then the parsed list`() = runBlocking {
        val presenter = presenter(fetch = { payload })
        val seen = mutableListOf<ProfilesScreenState>()
        presenter.subscribe { seen += it }

        presenter.load()

        assertEquals(ProfilesScreenState.Loading, seen.first())
        val body = presenter.state as ProfilesScreenState.Body
        assertEquals(listOf("default", "office"), body.rows.map { it.name })
        assertEquals("office", body.activeName)
        assertFalse(body.stale)
        assertFalse(body.refreshing)
    }

    @Test
    fun `failed fetch is a failure state, not a crash`() = runBlocking {
        val presenter = presenter(fetch = { null })
        presenter.load()
        assertEquals(ProfilesScreenState.Failure, presenter.state)

        val throwing = presenter(fetch = { throw IllegalStateException("boom") })
        throwing.load()
        assertEquals(ProfilesScreenState.Failure, throwing.state)
    }

    @Test
    fun `empty list state is reached`() = runBlocking {
        val presenter = presenter(fetch = { ProfilesResponse(profiles = emptyList(), active = "default") })
        presenter.load()
        assertEquals(ProfilesScreenState.Empty, presenter.state)
    }

    @Test
    fun `refresh keeps the shown list and marks it stale on failure`() = runBlocking {
        var answer: ProfilesResponse? = payload
        val presenter = presenter(fetch = { answer })

        presenter.load()
        answer = null
        presenter.load()

        val body = presenter.state as ProfilesScreenState.Body
        assertTrue("прошлый список остался на экране", body.stale)
        assertFalse(body.refreshing)
        assertEquals(2, body.rows.size)
    }

    @Test
    fun `active profile endpoint is used only when the list does not name the active profile`() = runBlocking {
        var activeCalls = 0
        val named = presenter(
            fetch = { payload },
            activeName = { activeCalls++; "default" },
        )
        named.load()
        assertEquals("поле active ответа сильнее отдельной ручки", 0, activeCalls)
        assertEquals("office", (named.state as ProfilesScreenState.Body).activeName)

        var fallbackCalls = 0
        val unnamed = presenter(
            fetch = { ProfilesResponse(profiles = listOf(MarsProfile(name = "default"), MarsProfile(name = "work"))) },
            activeName = { fallbackCalls++; "work" },
        )
        unnamed.load()
        assertEquals(1, fallbackCalls)
        assertEquals("work", (unnamed.state as ProfilesScreenState.Body).activeName)
        assertTrue((unnamed.state as ProfilesScreenState.Body).rows.single { it.name == "work" }.showActiveBadge)
    }

    @Test
    fun `stale race guard drops the reply of the superseded load`() = runBlocking {
        val first = CompletableDeferred<ProfilesResponse?>()
        val second = CompletableDeferred<ProfilesResponse?>()
        var call = 0
        val presenter = presenter(fetch = { if (call++ == 0) first.await() else second.await() })

        val slow = async { presenter.load() }
        while (call == 0) yield()          // первый запрос ушёл в сеть и висит
        val fast = async { presenter.load() }
        while (call < 2) yield()           // второй запрос стартовал поверх первого

        second.complete(ProfilesResponse(profiles = listOf(MarsProfile(name = "new", isActive = true)), active = "new"))
        fast.join()
        first.complete(ProfilesResponse(profiles = listOf(MarsProfile(name = "old")), active = "old"))
        slow.join()

        val body = presenter.state as ProfilesScreenState.Body
        assertEquals("устаревший ответ не затирает свежий", listOf("new"), body.rows.map { it.name })
        assertEquals("new", body.activeName)
    }

    @Test
    fun `subscription replays the current state`() = runBlocking {
        val presenter = presenter(fetch = { payload })
        val seen = mutableListOf<ProfilesScreenState>()
        presenter.subscribe { seen += it }
        assertEquals(listOf(ProfilesScreenState.Loading), seen)
    }
}
