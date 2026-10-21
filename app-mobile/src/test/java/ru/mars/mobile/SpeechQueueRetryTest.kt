package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult

/**
 * Очередь фраз против лимитера `api/tts` (2 с на клиента → `429`) и против позднего звука.
 *
 * Проверяется ровно то, что ломалось на живой ручке:
 * - `429` с `Retry-After` повторяется ОДИН раз и после ограниченной паузы (а не сразу и не
 *   бесконечно) — иначе в речи дырка либо отставание;
 * - пауза берётся из заголовка и обрезается потолком;
 * - `401`/`404` не повторяются (это не лимитер);
 * - перебивание во время паузы не даёт позднему аудио зазвучать;
 * - очередь доигрывает дальше после отказа одной фразы.
 *
 * Сон подменён: тест не тратит реальное время, но видит запрошенные паузы.
 */
class SpeechQueueRetryTest {

    @Test
    fun `rate limited phrase is retried once after the requested delay`() {
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("rate_limited", 429, retryAfterMs = 1_500L),
                audio("фраза"),
            ),
        )
        val playback = FakePlayback()
        val sleeps = ArrayList<Long>()
        val queue = newQueue(client, playback, retryPolicy = policy(sleeps))

        queue.speak("фраза")

        assertEquals("ровно один повтор", 2, client.requests)
        assertEquals(listOf(1_500L), sleeps)
        assertEquals(listOf("фраза"), playback.played)
    }

    @Test
    fun `retry after is capped by the policy ceiling`() {
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("rate_limited", 429, retryAfterMs = 60_000L),
                audio("фраза"),
            ),
        )
        val sleeps = ArrayList<Long>()
        val queue = newQueue(client, FakePlayback(), retryPolicy = policy(sleeps, maxDelayMs = 2_500L))

        queue.speak("фраза")

        assertEquals("сервер попросил 60 с — ждём не больше потолка", listOf(2_500L), sleeps)
    }

    @Test
    fun `without retry after the backoff is used`() {
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("server_error", 503),
                audio("фраза"),
            ),
        )
        val sleeps = ArrayList<Long>()
        val queue = newQueue(client, FakePlayback(), retryPolicy = policy(sleeps))

        queue.speak("фраза")

        assertEquals(listOf(SpeechRetryPolicy.DEFAULT_BACKOFF_MS), sleeps)
    }

    @Test
    fun `terminal failures are not retried`() {
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("unauthorized", 401),
                audio("фраза"),
            ),
        )
        val failed = ArrayList<Pair<String, String>>()
        val queue = newQueue(
            client,
            FakePlayback(),
            onPhraseFailed = { text, reason -> failed += text to reason },
            retryPolicy = policy(ArrayList()),
        )

        queue.speak("фраза")

        assertEquals("401 не повторяется", 1, client.requests)
        assertEquals(listOf("фраза" to "unauthorized"), failed)
    }

    @Test
    fun `a phrase that stays rate limited is skipped, the queue keeps going`() {
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("rate_limited", 429),
                ServerTtsResult.Unavailable("rate_limited", 429),
                audio("вторая"),
            ),
        )
        val playback = FakePlayback()
        val failed = ArrayList<String>()
        val queue = newQueue(
            client,
            playback,
            onPhraseFailed = { text, _ -> failed += text },
            retryPolicy = policy(ArrayList()),
        )

        queue.speak("первая")
        queue.speak("вторая")

        assertEquals(listOf("первая"), failed)
        assertEquals(listOf("вторая"), playback.played)
    }

    @Test
    fun `stop during the retry pause leaves no late sound`() {
        val gate = CompletableDeferred<Unit>()
        val client = ScriptedClient(
            script = mutableListOf(
                ServerTtsResult.Unavailable("rate_limited", 429),
                audio("поздняя"),
            ),
        )
        val playback = FakePlayback()
        val queue = SpeechQueue(
            client = client,
            playback = playback,
            scope = CoroutineScope(Dispatchers.Unconfined),
            retryPolicy = SpeechRetryPolicy(maxAttempts = 2, maxDelayMs = 2_500L) { gate.await() },
        )

        queue.speak("поздняя")
        assertEquals("пауза началась, второго запроса ещё нет", 1, client.requests)

        queue.stopAll()
        gate.complete(Unit)

        assertEquals(1, playback.stopCount)
        assertTrue("поздний ответ не должен зазвучать", playback.played.isEmpty())
        assertFalse(queue.isSpeaking)
    }

    // --- фейки ---------------------------------------------------------------------------

    private fun audio(text: String) = ServerTtsResult.Audio(text.toByteArray(), "audio/wav")

    private fun policy(sleeps: MutableList<Long>, maxDelayMs: Long = 2_500L) =
        SpeechRetryPolicy(maxAttempts = 2, maxDelayMs = maxDelayMs) { millis -> sleeps += millis }

    private fun newQueue(
        client: ServerTtsClient,
        playback: ServerTtsPlayback,
        onPhraseFailed: (String, String) -> Unit = { _, _ -> },
        retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(maxAttempts = 1, maxDelayMs = 0L),
    ): SpeechQueue = SpeechQueue(
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onPhraseFailed = onPhraseFailed,
        retryPolicy = retryPolicy,
    )

    /** Отвечает по сценарию: последний ответ повторяется, если сценарий исчерпан. */
    private class ScriptedClient(private val script: MutableList<ServerTtsResult>) : ServerTtsClient {

        var requests: Int = 0
            private set

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests++
            return if (script.size > 1) script.removeAt(0) else script.first()
        }
    }

    private class FakePlayback : ServerTtsPlayback {

        val played: MutableList<String> = ArrayList()
        var stopCount: Int = 0
            private set
        var released: Boolean = false
            private set

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean = false

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            played += bytes.toString(Charsets.UTF_8)
            return true
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            released = true
        }
    }
}
