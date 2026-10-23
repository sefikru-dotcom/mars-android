package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.UploadOutcome

/**
 * R5 на уровне проводов (MockWebServer, без устройства): прикрепление файла в новом чате
 * идёт через [FirstSessionGate], поэтому в `POST /api/upload` уезжает `session_id`,
 * выданный `POST /api/session/new`. Раньше поле отсутствовало и сервер отвечал
 * 404 «Session not found» — файл в новом чате не прикреплялся вообще.
 */
class AttachmentBeforeSessionTest {
    private val credential = ConsumedDeviceCredential("native-token", "Bearer", "device-1", emptyList(), 3600)

    private fun gate(server: MockWebServer) =
        FirstSessionGate(FirstSessionFlow(SessionRepository(), server.url("/").toString()))

    @Test
    fun newChatAttachmentUploadsWithTheMintedSessionId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"session":{"session_id":"minted-7"}}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"filename":"notes.txt","path":"/uploads/notes.txt","size":2,"mime":"text/plain","is_image":false}"""
                )
            )
            var bound: String? = null
            val ensured = gate(server).ensure(null, credential, { bound }, { bound = it })
            assertEquals(EnsureSessionResult.Ready("minted-7"), ensured)

            val outcome = ChatRepository().uploadAttachment(
                server.url("/").toString(), credential, "notes.txt", "text/plain",
                "hi".toByteArray(), (ensured as EnsureSessionResult.Ready).sessionId,
            )
            assertTrue(outcome is UploadOutcome.Success)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/session/new", create.path)
            val upload = server.takeRequest()
            assertEquals("/api/upload", upload.path)
            val body = upload.body.readUtf8()
            // Поле, которого не хватало: без него api/upload.py ищет сессию по пустому id.
            assertTrue(body.contains("name=\"session_id\""))
            assertTrue(body.contains("minted-7"))
            assertTrue(body.contains("name=\"file\"; filename=\"notes.txt\""))
        }
    }

    @Test
    fun attachmentInAnExistingSessionSkipsSessionCreation() = runBlocking {
        // Путь «сессия уже есть» не изменился: ни одного запроса на создание сессии.
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"filename":"a.txt","path":"/uploads/a.txt","size":2,"mime":"text/plain","is_image":false}"""
                )
            )
            var bound: String? = "bound-1"
            val ensured = gate(server).ensure(null, credential, { bound }, { bound = it })
            assertEquals(EnsureSessionResult.Ready("bound-1"), ensured)

            ChatRepository().uploadAttachment(
                server.url("/").toString(), credential, "a.txt", "text/plain",
                "hi".toByteArray(), (ensured as EnsureSessionResult.Ready).sessionId,
            )
            assertEquals(1, server.requestCount)
            assertEquals("/api/upload", server.takeRequest().path)
        }
    }

    @Test
    fun deadCredentialOnMintIsRecognisedForReauthentication() = runBlocking {
        // Мятка отвергнута сервером: экран обязан увести на переавторизацию, а не
        // показывать 404 от upload. Проверяем, что причина распознаётся политикой.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))
            var bound: String? = null
            val ensured = gate(server).ensure(null, credential, { bound }, { bound = it })
            assertTrue(ensured is EnsureSessionResult.Failed)
            assertTrue(AuthRejectionPolicy.isCredentialRejected((ensured as EnsureSessionResult.Failed).message))
            assertNull(bound)
            // Файл не грузился: единственный запрос — отклонённая мятка.
            assertEquals(1, server.requestCount)
        }
    }
}
