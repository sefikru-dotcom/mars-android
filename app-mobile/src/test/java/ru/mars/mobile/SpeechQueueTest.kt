package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult

/**
 * Порядок, предсинтез, остановка и отказы очереди фраз.
 *
 * Фейки без Android и без реального времени: синтез либо отвечает сразу, либо висит на
 * [CompletableDeferred] до явного `release` — тест сам решает, в какой момент «сервер
 * ответил», поэтому гонки проверяются детерминированно. Плеер сообщает о конце только по
 * команде [FakePlayback.finishCurrent], как настоящий ExoPlayer на STATE_ENDED.
 */
class SpeechQueueTest {

    @Test
    fun `играет по порядку и синтезирует на фразу вперёд`() {
        val client = FakeClient()
        val playback = FakePlayback()
        val queue = newQueue(client, playback)

        queue.speak("раз")
        assertEquals(listOf("раз"), playback.played)
        assertEquals(listOf("раз"), client.requests)

        queue.speak("два")
        queue.speak("три")
        // Пока играет первая, синтезируется только следующая: дальше lookahead не пускает.
        assertEquals(listOf("раз", "два"), client.requests)
        assertEquals(listOf("раз"), playback.played)

        playback.finishCurrent()
        assertEquals(listOf("раз", "два"), playback.played)

        playback.finishCurrent()
        assertEquals(listOf("раз", "два", "три"), playback.played)
    }

    @Test
    fun `одновременных синтезов не больше двух`() {
        val client = FakeClient(gated = true)
        val playback = FakePlayback()
        val queue = newQueue(client, playback)

        queue.speakAll(listOf("один", "два", "три", "четыре"))

        assertEquals(listOf("один", "два"), client.requests)
        assertEquals(2, client.maxActive)
        assertEquals(2, queue.inFlightSynthesis)
    }

    @Test
    fun `stopAll даёт тишину, отменяет синтезы и не играет поздний ответ`() {
        val client = FakeClient(gated = true)
        val playback = FakePlayback()
        val queue = newQueue(client, playback)

        queue.speak("старая фраза")
        assertEquals(1, client.requests.size)

        queue.stopAll()

        assertEquals(1, playback.stopCount)
        assertEquals(0, queue.pendingCount)
        assertFalse(queue.isSpeaking)

        // Сервер ответил уже ПОСЛЕ перебивания: звук не должен появиться.
        client.release("старая фраза")
        assertTrue(playback.played.isEmpty())

        // Новая фраза после остановки звучит нормально.
        queue.speak("новая фраза")
        client.release("новая фраза")
        assertEquals(listOf("новая фраза"), playback.played)
    }

    @Test
    fun `отказ синтеза одной фразы пропускает её и очередь продолжается`() {
        val client = FakeClient(failures = setOf("плохая"))
        val playback = FakePlayback()
        val failed = ArrayList<Pair<String, String>>()
        val queue = newQueue(client, playback, onPhraseFailed = { text, reason -> failed += text to reason })

        queue.speak("хорошая")
        queue.speak("плохая")
        queue.speak("третья")

        assertEquals(listOf("хорошая"), playback.played)
        assertEquals(listOf("плохая" to "server_error"), failed)
        // Повтор ограничен политикой: фраза ушла в синтез ровно дважды, а не бесконечно.
        // (В requests уже есть «третья»: очередь синтезирует следующую фразу, пока играет текущая.)
        assertEquals(2, client.requests.count { it == "плохая" })

        playback.finishCurrent()
        assertEquals(listOf("хорошая", "третья"), playback.played)
    }

    @Test
    fun `отказ плеера пропускает фразу, а не останавливает очередь`() {
        val client = FakeClient()
        val playback = FakePlayback().apply { rejectRemaining = 1 }
        val failed = ArrayList<String>()
        val queue = newQueue(client, playback, onPhraseFailed = { text, _ -> failed += text })

        queue.speak("первая")
        queue.speak("вторая")

        assertEquals(listOf("вторая"), playback.played)
        assertEquals(listOf("первая"), failed)
    }

    @Test
    fun `первое аудио и опустошение очереди сообщаются по одному разу`() {
        val client = FakeClient()
        val playback = FakePlayback()
        var firstAudio = 0
        var drained = 0
        val queue = newQueue(
            client,
            playback,
            onFirstAudio = { firstAudio++ },
            onDrained = { drained++ },
        )

        queue.speak("раз")
        queue.speak("два")
        assertEquals(1, firstAudio)
        assertEquals(0, drained)

        playback.finishCurrent()
        assertEquals(1, firstAudio)
        assertEquals(0, drained)

        playback.finishCurrent()
        assertEquals(1, firstAudio)
        assertEquals(1, drained)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `пустые фразы игнорируются, release закрывает очередь`() {
        val client = FakeClient()
        val playback = FakePlayback()
        val queue = newQueue(client, playback)

        assertFalse(queue.speak("   "))
        assertTrue(queue.speak("фраза"))

        queue.release()
        assertTrue(playback.released)
        assertFalse(queue.speak("после release"))
    }

    private fun newQueue(
        client: FakeClient,
        playback: FakePlayback,
        onFirstAudio: () -> Unit = {},
        onDrained: () -> Unit = {},
        onPhraseFailed: (String, String) -> Unit = { _, _ -> },
        // Без реального ожидания: Unconfined + delay сделали бы исход теста гонкой с таймером.
        retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(sleeper = {}),
        metrics: SpeechLatencyMetrics? = null,
    ): SpeechQueue = SpeechQueue(
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onFirstAudio = onFirstAudio,
        onDrained = onDrained,
        onPhraseFailed = onPhraseFailed,
        retryPolicy = retryPolicy,
        metrics = metrics,
    )

    /** Синтез: сразу готовое аудио, отказ по списку или ожидание внешнего «ответа сервера». */
    private class FakeClient(
        private val failures: Set<String> = emptySet(),
        private val gated: Boolean = false,
    ) : ServerTtsClient {

        val requests: MutableList<String> = ArrayList()
        private val gates = HashMap<String, CompletableDeferred<ServerTtsResult>>()

        var active: Int = 0
        var maxActive: Int = 0

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests += text
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (gated) {
                    val gate = CompletableDeferred<ServerTtsResult>()
                    gates[text] = gate
                    // NonCancellable: проверяем именно защиту поколением, а не отмену job.
                    return withContext(NonCancellable) { gate.await() }
                }
                return if (text in failures) {
                    ServerTtsResult.Unavailable("server_error")
                } else {
                    audio(text)
                }
            } finally {
                active--
            }
        }

        fun release(text: String, result: ServerTtsResult = audio(text)) {
            gates.remove(text)?.complete(result)
        }
    }

    /** Плеер: запоминает принятые фразы и ждёт команды «доиграло». */
    private class FakePlayback : ServerTtsPlayback {

        val played: MutableList<String> = ArrayList()
        var stopCount: Int = 0
        var released: Boolean = false
        var rejectRemaining: Int = 0

        private var finish: (() -> Unit)? = null

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean =
            playToEnd(bytes, contentType, {}, onFailed)

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            if (rejectRemaining > 0) {
                rejectRemaining--
                return false
            }
            played += bytes.decodeToString()
            finish = onFinished
            return true
        }

        /** Реплика доиграла до конца (STATE_ENDED). */
        fun finishCurrent() {
            val callback = finish
            finish = null
            callback?.invoke()
        }

        override fun stop() {
            stopCount++
            finish = null
        }

        override fun release() {
            released = true
        }
    }

    private companion object {
        fun audio(text: String): ServerTtsResult =
            ServerTtsResult.Audio(text.toByteArray(), "audio/mpeg")
    }
}
