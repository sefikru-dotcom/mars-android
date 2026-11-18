package ru.mars.mobile.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.ServerTtsClient
import ru.mars.network.repository.ServerTtsResult

/**
 * Резерв Piper на уровне клиента синтеза: облако → локальная модель → отказ.
 *
 * Проверяется ровно то, что видит очередь фраз:
 * - успех облака не трогает локальный движок (резерв не «звучит заодно»);
 * - отказ облака отдаёт WAV с `audio/wav` и объявляет подмену хосту;
 * - отсутствие/поломка резерва не бросает исключение, а возвращает отказ с причиной;
 * - отмена корутины прекращает локальный синтез, а не превращается в «тихий отказ»
 *   (иначе отменённая фраза продолжила бы занимать поток движка).
 */
class PiperFallbackTtsClientTest {

    @Test
    fun `облако ответило — резерв не задействован`() = runBlocking {
        val piper = FakePiper()
        val substitutions = ArrayList<String>()
        val failures = ArrayList<String>()
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = audio("облако", "audio/mpeg")),
            piper = piper,
            onLocalFallback = { substitutions += it },
            onLocalFailure = { failures += it },
        )

        val result = client.synthesize("фраза")
        val audio = result as ServerTtsResult.Audio

        assertEquals("audio/mpeg", audio.contentType)
        assertEquals("облако", audio.bytes.decodeToString())
        assertTrue("локальный движок не должен синтезировать", piper.requests.isEmpty())
        assertTrue(substitutions.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `отказ облака читает локальная модель и об этом сообщается`() = runBlocking {
        val piper = FakePiper(wav = "локально".toByteArray())
        val substitutions = ArrayList<String>()
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("rate_limited", 429)),
            piper = piper,
            onLocalFallback = { substitutions += it },
        )

        val result = client.synthesize("фраза") as ServerTtsResult.Audio

        assertEquals("локально", result.bytes.decodeToString())
        // Плеер выбирает расширение временного файла по Content-Type: без `wav` файл получил
        // бы нейтральное `.audio`, и разбор контейнера зависел бы от догадки ExoPlayer.
        assertEquals(PiperFallbackTtsClient.CONTENT_TYPE_WAV, result.contentType)
        assertEquals(listOf("фраза"), piper.requests)
        assertEquals(listOf("rate_limited"), substitutions)
    }

    @Test
    fun `нет модели — отказ честный, без исключения и без подмены`() = runBlocking {
        val piper = FakePiper(ready = false)
        val failures = ArrayList<String>()
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("unauthorized", 401)),
            piper = piper,
            onLocalFailure = { failures += it },
        )

        val result = client.synthesize("фраза")

        assertEquals("unauthorized", (result as ServerTtsResult.Unavailable).reason)
        assertTrue("неготовый движок не должен получать текст", piper.requests.isEmpty())
        assertEquals(listOf("unauthorized"), failures)
    }

    @Test
    fun `порта нет вовсе — тот же отказ, а не падение`() = runBlocking {
        val failures = ArrayList<String>()
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("transport")),
            piper = null,
            onLocalFailure = { failures += it },
        )

        val result = client.synthesize("фраза")

        assertEquals("transport", (result as ServerTtsResult.Unavailable).reason)
        assertEquals(listOf("transport"), failures)
    }

    @Test
    fun `локальный синтез упал — отказ облака сохраняется для повтора`() = runBlocking {
        val failures = ArrayList<String>()
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("rate_limited", 429)),
            piper = FakePiper(error = IllegalStateException("native")),
            onLocalFailure = { failures += it },
        )

        val result = client.synthesize("фраза")

        // Причина именно облачная: повторяемая причина даёт очереди право на один повтор.
        assertEquals("rate_limited", (result as ServerTtsResult.Unavailable).reason)
        assertEquals(listOf("rate_limited"), failures)
    }

    @Test
    fun `серверный порт бросил — это транспортный отказ и повод для резерва`() = runBlocking {
        val piper = FakePiper(wav = "локально".toByteArray())
        val client = PiperFallbackTtsClient(
            server = FakeServer(error = IllegalStateException("boom")),
            piper = piper,
        )

        val result = client.synthesize("фраза") as ServerTtsResult.Audio

        assertEquals("локально", result.bytes.decodeToString())
    }

    @Test
    fun `пустое аудио сервера не считается звуком`() = runBlocking {
        val piper = FakePiper(wav = "локально".toByteArray())
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Audio(ByteArray(0), "audio/mpeg")),
            piper = piper,
        )

        val result = client.synthesize("фраза") as ServerTtsResult.Audio

        assertEquals("локально", result.bytes.decodeToString())
        assertEquals(1, piper.requests.size)
    }

    @Test
    fun `падение колбэка хоста не ломает синтез`() = runBlocking {
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("server_error", 503)),
            piper = FakePiper(wav = "локально".toByteArray()),
            onLocalFallback = { error("хост упал") },
        )

        val result = client.synthesize("фраза")

        assertEquals("локально", (result as ServerTtsResult.Audio).bytes.decodeToString())
    }

    @Test
    fun `отмена прекращает локальный синтез и не превращается в отказ`() = runBlocking {
        val piper = FakePiper(hold = CompletableDeferred())
        val client = PiperFallbackTtsClient(
            server = FakeServer(result = ServerTtsResult.Unavailable("transport")),
            piper = piper,
        )

        val job = launch { client.synthesize("фраза") }
        piper.entered.await()
        job.cancel()
        job.join()

        assertTrue("отмена обязана дойти до корутины синтеза", job.isCancelled)
    }

    // --- фейки -------------------------------------------------------------------------------

    private class FakeServer(
        private val result: ServerTtsResult = ServerTtsResult.Audio(ByteArray(0), "audio/mpeg"),
        private val error: Throwable? = null,
    ) : ServerTtsClient {
        override suspend fun synthesize(text: String): ServerTtsResult {
            error?.let { throw it }
            return result
        }
    }

    /** Офлайн-порт: отдаёт заранее заданные байты, умеет «зависнуть» и «упасть». */
    private class FakePiper(
        var ready: Boolean = true,
        private var wav: ByteArray? = "wav".toByteArray(),
        private var error: Throwable? = null,
        private var hold: CompletableDeferred<Unit>? = null,
    ) : OfflineTtsPort {

        val requests: MutableList<String> = ArrayList()

        /** Синтез начался: тесту нужно дождаться этого, прежде чем отменять. */
        val entered: CompletableDeferred<Unit> = CompletableDeferred()

        override suspend fun prepare(): Boolean = ready

        override fun speak(text: String): Boolean = false

        override fun stop() = Unit

        override fun isReady(): Boolean = ready

        override fun release() = Unit

        override suspend fun synthesizeToWav(text: String): ByteArray? {
            requests += text
            entered.complete(Unit)
            hold?.await()
            error?.let { throw it }
            return wav
        }
    }

    private companion object {
        fun audio(text: String, contentType: String): ServerTtsResult =
            ServerTtsResult.Audio(text.toByteArray(), contentType)
    }
}
