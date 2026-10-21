package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult

/**
 * Потоковая речь целиком: дельта → фраза → нормализация → очередь.
 *
 * Тесты держат четыре требования задачи, которые легко сломать рефакторингом:
 * - первая фраза уходит в синтез на первой границе, не дожидаясь конца хода;
 * - финал произносится РОВНО ОДИН раз, даже если хост позовёт `onTurnFinished` дважды;
 * - после `stop()` поздний ответ синтеза не звучит;
 * - «первое аудио» — это факт старта плеера, а не приход текста: метрика не подделывается
 *   первой дельтой.
 */
class StreamingSpeechControllerTest {

    @Test
    fun `first phrase is synthesized before the turn ends`() {
        val client = RecordingClient()
        val controller = controller(client)

        controller.onDelta("Привет! Как дела")

        assertEquals(listOf("Привет!"), client.requests)
    }

    @Test
    fun `text is normalized before it reaches the synthesizer`() {
        val client = RecordingClient()
        val controller = controller(client)

        controller.onDelta("Открой /root/workspace. Готово!")

        assertTrue(client.requests.isNotEmpty())
        assertTrue("путь не читается посимвольно: ${client.requests}", client.requests.none { it.contains("/") })
    }

    @Test
    fun `finish is idempotent and reads the tail once`() {
        val client = RecordingClient()
        val controller = controller(client)
        controller.onDelta("Хвост без точки")

        controller.onTurnFinished()
        controller.onTurnFinished()

        assertEquals(listOf("Хвост без точки"), client.requests)
    }

    @Test
    fun `a new delta after finish starts a new turn`() {
        val client = RecordingClient()
        val controller = controller(client)

        controller.onDelta("Первый ответ.")
        controller.onTurnFinished()
        controller.onDelta("Второй ответ.")
        controller.onTurnFinished()

        assertEquals(listOf("Первый ответ.", "Второй ответ."), client.requests)
    }

    @Test
    fun `stop drops the buffered text and silences the queue`() {
        val client = RecordingClient()
        val playback = RecordingPlayback()
        val controller = controller(client, playback)

        controller.onDelta("Недосказанная фраза без точки")
        controller.stop()
        controller.onTurnFinished()

        assertTrue("после stop хвост не должен звучать: ${client.requests}", client.requests.isEmpty())
        assertTrue(playback.played.isEmpty())
    }

    @Test
    fun `audio start is reported only when a phrase actually plays`() {
        val playback = RecordingPlayback()
        var firstAudio = 0
        val metrics = SpeechLatencyMetrics(clockMs = { clock })
        val controller = controller(
            RecordingClient(),
            playback,
            onFirstAudio = { firstAudio++ },
            metrics = metrics,
        )

        // Текст без границы предложения: фразы ещё нет, значит и звука быть не может.
        controller.onDelta("Привет")
        assertEquals("текст пришёл, но звука ещё нет", 0, firstAudio)
        assertNull("метрика не должна считаться от первой дельты", metrics.snapshot().firstDeltaToAudioMs)

        controller.onTurnFinished()

        assertEquals("хвост дочитан — звук пошёл", 1, firstAudio)
        assertEquals(1, playback.played.size)
    }

    @Test
    fun `drained is reported when the last phrase finishes`() {
        val playback = RecordingPlayback()
        var drained = 0
        val controller = controller(RecordingClient(), playback, onDrained = { drained++ })

        controller.onDelta("Привет!")
        playback.finishCurrent()

        assertEquals(1, drained)
    }

    @Test
    fun `unavailable synthesis is reported to the host with a reason`() {
        val failed = ArrayList<String>()
        val controller = controller(
            RecordingClient(result = ServerTtsResult.Unavailable("rate_limited", 429)),
            RecordingPlayback(),
            onPhraseFailed = { _, reason -> failed += reason },
        )

        controller.onDelta("Привет!")

        assertEquals(listOf("rate_limited"), failed)
    }

    @Test
    fun `metrics count the whole path from delta to audio`() {
        clock = 100
        // Плеер «готовит» звук 250 мс: так видно, что метрика аудио — отдельная точка,
        // а не момент прихода текста.
        val playback = RecordingPlayback(onPlay = { clock = 350 })
        val metrics = SpeechLatencyMetrics(clockMs = { clock })
        val controller = controller(RecordingClient(), playback, metrics = metrics)

        controller.onDelta("Привет!")

        val snapshot = controller.latencySnapshot()
        assertEquals(0L, snapshot.firstDeltaToRequestMs)
        assertEquals(250L, snapshot.requestToAudioMs)
        assertEquals(250L, snapshot.firstDeltaToAudioMs)
        assertEquals(1, snapshot.synthesisRequests)
    }

    // --- сборка ---------------------------------------------------------------------------

    private var clock: Long = 0L

    private fun controller(
        client: ServerTtsClient,
        playback: ServerTtsPlayback = RecordingPlayback(),
        onFirstAudio: () -> Unit = {},
        onDrained: () -> Unit = {},
        onPhraseFailed: (String, String) -> Unit = { _, _ -> },
        metrics: SpeechLatencyMetrics = SpeechLatencyMetrics(),
    ): StreamingSpeechController {
        val queue = SpeechQueue(
            client = client,
            playback = playback,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onFirstAudio = {
                metrics.onAudioStart()
                onFirstAudio()
            },
            onDrained = onDrained,
            onPhraseFailed = onPhraseFailed,
            retryPolicy = SpeechRetryPolicy(maxAttempts = 1, maxDelayMs = 0L),
            metrics = metrics,
        )
        return StreamingSpeechController(queue = queue, metrics = metrics)
    }

    private class RecordingClient(
        private val result: ServerTtsResult = ServerTtsResult.Audio("ok".toByteArray(), "audio/wav"),
    ) : ServerTtsClient {

        val requests: MutableList<String> = ArrayList()

        override suspend fun synthesize(text: String): ServerTtsResult {
            requests += text
            return result
        }
    }

    /** Плеер, которым управляет тест: конец — по команде, как настоящий ExoPlayer. */
    private class RecordingPlayback(private val onPlay: () -> Unit = {}) : ServerTtsPlayback {

        val played: MutableList<String> = ArrayList()
        private var onFinished: (() -> Unit)? = null
        private var onFailed: (() -> Unit)? = null
        var stopCount: Int = 0
            private set

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean = false

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            played += bytes.toString(Charsets.UTF_8)
            this.onFinished = onFinished
            this.onFailed = onFailed
            onPlay()
            return true
        }

        fun finishCurrent() {
            val callback = onFinished
            onFinished = null
            callback?.invoke()
        }

        fun failCurrent() {
            val callback = onFailed
            onFailed = null
            callback?.invoke()
        }

        override fun stop() {
            stopCount++
            onFinished = null
        }

        override fun release() {
            onFinished = null
        }
    }
}
