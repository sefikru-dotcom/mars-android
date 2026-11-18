package ru.mars.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.tts.OfflineTtsPort
import ru.mars.mobile.tts.PiperFallbackTtsClient
import ru.mars.network.repository.ServerTtsResult

/**
 * Очередь фраз с резервом Piper: порядок, отмена и явная индикация отказа.
 *
 * Почему проверка на уровне ОЧЕРЕДИ, а не только клиента: резерв легко сделать так, что он
 * «звучит», но ломает главное — порядок и перебивание. Очередь — единственный владелец
 * последовательности и поколения, поэтому именно здесь доказывается, что:
 * - фразы с резервом звучат строго в порядке постановки (и играет их плеер очереди, а не
 *   офлайн-движок сам по себе);
 * - `stopAll` во время локального синтеза не даёт позднему звуку зазвучать;
 * - фраза, которую не смог ни один канал, пропускается С ПРИЧИНОЙ (это и есть индикация), а
 *   следующая всё равно звучит.
 */
class SpeechQueuePiperFallbackTest {

    @Test
    fun `облако отказало — фразы резерва звучат по порядку`() {
        val piper = FakePiper()
        val playback = FakePlayback()
        val queue = newQueue(CloudDown(), piper, playback)

        queue.speak("раз")
        assertEquals(listOf("wav:раз"), playback.played)
        // Резерв обязан отдавать байты, а не играть сам: иначе звук шёл бы мимо очереди.
        assertEquals(listOf("audio/wav"), playback.contentTypes)
        assertEquals(listOf("раз"), piper.requests)

        queue.speak("два")
        queue.speak("три")
        assertEquals("пока играет первая, готовится только следующая", listOf("wav:раз"), playback.played)

        playback.finishCurrent()
        assertEquals(listOf("wav:раз", "wav:два"), playback.played)

        playback.finishCurrent()
        assertEquals(listOf("wav:раз", "wav:два", "wav:три"), playback.played)
    }

    @Test
    fun `успех облака не включает резерв`() {
        val piper = FakePiper()
        val playback = FakePlayback()
        val queue = newQueue(CloudUp(), piper, playback)

        queue.speak("раз")

        assertEquals(listOf("srv:раз"), playback.played)
        assertEquals(listOf("audio/mpeg"), playback.contentTypes)
        assertTrue("локальный движок не должен работать", piper.requests.isEmpty())
    }

    @Test
    fun `stopAll во время локального синтеза не даёт позднему звуку зазвучать`() {
        val piper = FakePiper(gated = true)
        val playback = FakePlayback()
        val queue = newQueue(CloudDown(), piper, playback)

        queue.speak("старая фраза")
        assertEquals(1, piper.requests.size)

        queue.stopAll()
        // Локальный синтез «досчитал» уже после перебивания: звук не должен появиться.
        piper.release("старая фраза")
        assertTrue(playback.played.isEmpty())

        queue.speak("новая фраза")
        piper.release("новая фраза")
        assertEquals(listOf("wav:новая фраза"), playback.played)
    }

    @Test
    fun `фраза без звука пропускается с причиной, а очередь продолжается`() {
        val piper = FakePiper(failTexts = setOf("плохая"))
        val playback = FakePlayback()
        val failed = ArrayList<Pair<String, String>>()
        val queue = newQueue(
            CloudDown(),
            piper,
            playback,
            onPhraseFailed = { text, reason -> failed += text to reason },
        )

        queue.speak("хорошая")
        queue.speak("плохая")
        queue.speak("третья")

        assertEquals(listOf("wav:хорошая"), playback.played)
        // Причина — не «тишина вообще», а конкретный отказ: по ней хост строит текст для
        // человека (см. StreamSpeechNotice.phraseFailed).
        assertEquals(listOf("плохая" to "rate_limited"), failed)

        playback.finishCurrent()
        assertEquals(listOf("wav:хорошая", "wav:третья"), playback.played)
    }

    // --- фейки -------------------------------------------------------------------------------

    private fun newQueue(
        server: ServerTtsClient,
        piper: OfflineTtsPort,
        playback: FakePlayback,
        onPhraseFailed: (String, String) -> Unit = { _, _ -> },
    ): SpeechQueue = SpeechQueue(
        client = PiperFallbackTtsClient(server = server, piper = piper),
        playback = playback,
        // Без реального ожидания: Unconfined + delay сделали бы исход теста гонкой с таймером.
        scope = CoroutineScope(Dispatchers.Unconfined),
        onPhraseFailed = onPhraseFailed,
        retryPolicy = SpeechRetryPolicy(sleeper = {}),
    )

    /** Облако всегда отказывает так, как это делает лимитер `api/tts`. */
    private class CloudDown(
        private val reason: String = "rate_limited",
    ) : ServerTtsClient {
        override suspend fun synthesize(text: String): ServerTtsResult =
            ServerTtsResult.Unavailable(reason, 429, retryAfterMs = 1_000L)
    }

    /** Облако работает: резерв не должен задействоваться вовсе. */
    private class CloudUp : ServerTtsClient {
        override suspend fun synthesize(text: String): ServerTtsResult =
            ServerTtsResult.Audio("srv:$text".toByteArray(), "audio/mpeg")
    }

    /**
     * Офлайн-порт: «синтезирует» предсказуемые байты, умеет зависнуть до команды теста и
     * отказать для выбранных фраз.
     */
    private class FakePiper(
        private val gated: Boolean = false,
        private val failTexts: Set<String> = emptySet(),
    ) : OfflineTtsPort {

        val requests: MutableList<String> = ArrayList()

        private val gates = HashMap<String, CompletableDeferred<ByteArray?>>()

        override suspend fun prepare(): Boolean = true

        override fun speak(text: String): Boolean = false

        override fun stop() = Unit

        override fun isReady(): Boolean = true

        override fun release() = Unit

        override suspend fun synthesizeToWav(text: String): ByteArray? {
            requests += text
            if (gated) {
                val gate = CompletableDeferred<ByteArray?>()
                gates[text] = gate
                // Без NonCancellable: отмена должна доходить до синтеза — это и проверяем.
                return gate.await()
            }
            return if (text in failTexts) null else "wav:$text".toByteArray()
        }

        /** «Синтез доехал»: тест сам решает, в какой момент появился звук. */
        fun release(text: String) {
            gates.remove(text)?.complete("wav:$text".toByteArray())
        }
    }

    /** Плеер: запоминает принятые фразы и ждёт команды «доиграло». */
    private class FakePlayback : ServerTtsPlayback {

        val played: MutableList<String> = ArrayList()
        val contentTypes: MutableList<String> = ArrayList()

        private var finish: (() -> Unit)? = null

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean =
            playToEnd(bytes, contentType, {}, onFailed)

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            played += bytes.decodeToString()
            contentTypes += contentType
            finish = onFinished
            return true
        }

        /** Реплика доиграла до конца (STATE_ENDED у ExoPlayer). */
        fun finishCurrent() {
            val callback = finish
            finish = null
            callback?.invoke()
        }

        override fun stop() {
            finish = null
        }

        override fun release() {
            finish = null
        }
    }
}
