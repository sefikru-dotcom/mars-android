package ru.mars.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.SessionCreator

/**
 * Профиль сессии TV-чата: переход с экрана «Профили» передаёт имя профиля extra'ом
 * ([ru.mars.tv.profiles.TvProfilesRouting.EXTRA_PROFILE]), а [TvChatSessionFlow]
 * кладёт его в `POST /api/session/new` как `profile`.
 *
 * Контракт:
 *  - обычный чат (без extra) создаёт сессию с пустым телом — поведение не меняется;
 *  - названный профиль уходит в тело создания сессии;
 *  - пустое имя игнорируется;
 *  - уже привязанная сессия профиль не меняет: смена профиля живого WebUI — мутация.
 */
class TvChatSessionProfileArgTest {

    private val credential = ConsumedDeviceCredential("t", "Bearer", "d", emptyList(), 1)

    private class RecordingCreator : SessionCreator {
        var lastArgs: Map<String, Any?>? = null
        var calls = 0
        override suspend fun createSession(
            baseUrl: String,
            credential: ConsumedDeviceCredential,
            args: Map<String, Any?>,
        ): CreateSessionOutcome {
            calls++
            lastArgs = args
            return CreateSessionOutcome.Success(SessionDetail("minted"))
        }
    }

    private fun flow(): Pair<TvChatSessionFlow, RecordingCreator> {
        val creator = RecordingCreator()
        return TvChatSessionFlow(creator, "https://base") to creator
    }

    @Test
    fun `a plain chat still creates the session with an empty body`() = runBlocking {
        val (chat, creator) = flow()
        chat.ensureSession(credential)
        assertEquals(emptyMap<String, Any?>(), creator.lastArgs)
        assertNull(chat.profile)
    }

    @Test
    fun `the profile from the profiles screen travels into session creation`() = runBlocking {
        val (chat, creator) = flow()
        chat.bindProfile("office")
        chat.ensureSession(credential)
        assertEquals(mapOf("profile" to "office"), creator.lastArgs)
        assertEquals("office", chat.profile)
    }

    @Test
    fun `blank profile names are ignored`() = runBlocking {
        val (chat, creator) = flow()
        chat.bindProfile("   ")
        chat.bindProfile(null)
        chat.ensureSession(credential)
        assertEquals(emptyMap<String, Any?>(), creator.lastArgs)
    }

    @Test
    fun `profile name is trimmed before it reaches the wire`() = runBlocking {
        val (chat, creator) = flow()
        chat.bindProfile("  office  ")
        chat.ensureSession(credential)
        assertEquals(mapOf("profile" to "office"), creator.lastArgs)
    }

    @Test
    fun `an already bound session keeps its profile`() = runBlocking {
        val (chat, creator) = flow()
        chat.onEvent(ru.mars.network.model.ChatEvent.SessionBound("bound-1"))
        chat.bindProfile("office")
        chat.ensureSession(credential)
        assertNull("привязанная сессия профиль не меняет", chat.profile)
        assertEquals(0, creator.calls)
    }
}
