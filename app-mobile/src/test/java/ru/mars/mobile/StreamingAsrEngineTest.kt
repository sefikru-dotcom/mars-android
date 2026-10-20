package ru.mars.mobile

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.asr.AsrBackend
import ru.mars.mobile.asr.AsrConfig
import ru.mars.mobile.asr.AsrModelSource

/**
 * JVM-тесты логики реплики в [StreamingAsrEngine] с фейковым native-слоем.
 *
 * Сам sherpa-onnx на JVM не поднимается (Android .so + System.loadLibrary), поэтому
 * здесь проверяется всё, что движок решает сам, а не отдаёт в native:
 * - порядок и состав событий (SpeechStarted -> Partial... -> Final);
 * - граница реплики по endpoint и повторный старт речи после неё;
 * - finishUtterance («стоп»): финальный текст и откат на последний partial;
 * - идемпотентность load() (модель не качается и распознаватель не пересоздаётся);
 * - откат на работу без hotwords, если распознаватель с ними не поднялся;
 * - перенос непарного байта PCM между вызовами и вызовы до load()/после release().
 */
class StreamingAsrEngineTest {

    // --- fakes ---------------------------------------------------------------------------------

    private class FakeBackend : AsrBackend {
        var initCalls = 0
        var failInit = false
        var failInitWithHotwords = false
        var lastConfig: AsrConfig? = null

        var streamCount = 0
        var lastHotwords: List<String> = emptyList()

        val samples = ArrayList<Float>()
        var text = ""
        var endpoint = false
        var readySteps = 0
        var decodeCalls = 0
        var finished = false
        var released = false

        override fun initialize(modelDir: File, config: AsrConfig): Boolean {
            initCalls++
            lastConfig = config
            if (failInit) return false
            if (failInitWithHotwords && config.hotwords.isNotEmpty()) return false
            return true
        }

        override fun newStream(hotwords: List<String>) {
            streamCount++
            lastHotwords = hotwords
            text = ""
            endpoint = false
            finished = false
        }

        override fun acceptSamples(samples: FloatArray) {
            this.samples.addAll(samples.toList())
        }

        override fun isReady(): Boolean {
            if (readySteps > 0) {
                readySteps--
                return true
            }
            return false
        }

        override fun decode() {
            decodeCalls++
        }

        override fun isEndpoint(): Boolean = endpoint

        override fun result(): String = text

        override fun finishStream() {
            finished = true
        }

        override fun release() {
            released = true
        }
    }

    private class FakeModelSource : AsrModelSource {
        override val modelDir: File = File("/tmp/mars-fake-model")
        var available = true
        var ensureResult = true
        var ensureCalls = 0

        override fun isAvailable(): Boolean = available

        override fun ensure(): Boolean {
            ensureCalls++
            if (ensureResult) available = true
            return ensureResult
        }
    }

    private val noHotwords = AsrConfig(hotwords = emptyList())

    private fun silence(samples: Int = 160): ByteArray = ByteArray(samples * 2)

    private fun engine(
        backend: FakeBackend,
        source: FakeModelSource = FakeModelSource(),
        config: AsrConfig = noHotwords,
        events: MutableList<AsrEvent> = ArrayList(),
        listener: (AsrEvent) -> Unit = { events += it },
    ): StreamingAsrEngine = StreamingAsrEngine(source, config, listener) { backend }

    // --- события и реплики ---------------------------------------------------------------------

    @Test
    fun partialThenFinalOnEndpoint() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        assertTrue(engine.load())
        // stream создаётся заранее: фейк, как и настоящий распознаватель, сбрасывает
        // текст при новом stream — иначе первый accept стирал бы подготовленный ответ.
        engine.startUtterance()

        backend.text = "восемь"
        backend.readySteps = 1
        engine.acceptPcm(silence(), 320)
        assertEquals(listOf<AsrEvent>(AsrEvent.SpeechStarted, AsrEvent.Partial("восемь")), events)
        assertEquals(1, backend.decodeCalls)

        events.clear()
        backend.text = "восемь тысяч"
        engine.acceptPcm(silence(), 320)
        assertEquals(listOf<AsrEvent>(AsrEvent.Partial("восемь тысяч")), events)

        events.clear()
        backend.endpoint = true
        engine.acceptPcm(silence(), 320)
        assertEquals(listOf<AsrEvent>(AsrEvent.Final("восемь тысяч")), events)
        assertEquals("после endpoint должен быть свежий stream", 2, backend.streamCount)
    }

    @Test
    fun speechStartedIsEmittedOncePerUtterance() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.text = "порт"
        engine.acceptPcm(silence(), 320)
        engine.acceptPcm(silence(), 320)
        assertEquals(listOf<AsrEvent>(AsrEvent.SpeechStarted, AsrEvent.Partial("порт")), events)
    }

    @Test
    fun samePartialIsNotRepeated() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.text = "пятьсот два"
        engine.acceptPcm(silence(), 320)
        engine.acceptPcm(silence(), 320)
        engine.acceptPcm(silence(), 320)
        assertEquals(1, events.count { it is AsrEvent.Partial })
    }

    @Test
    fun speechStartsAgainAfterEndpoint() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.text = "альпен"
        backend.endpoint = true
        engine.acceptPcm(silence(), 320)
        assertEquals(
            listOf<AsrEvent>(AsrEvent.SpeechStarted, AsrEvent.Partial("альпен"), AsrEvent.Final("альпен")),
            events,
        )

        events.clear()
        backend.text = "квартал"
        engine.acceptPcm(silence(), 320)
        assertEquals(listOf<AsrEvent>(AsrEvent.SpeechStarted, AsrEvent.Partial("квартал")), events)
    }

    @Test
    fun endpointWithoutTextEmitsNothing() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.endpoint = true
        engine.acceptPcm(silence(), 320)
        assertTrue("тишина не должна давать событий", events.isEmpty())
        assertEquals(2, backend.streamCount)
    }

    @Test
    fun startUtteranceResetsStream() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load()

        engine.startUtterance()
        engine.startUtterance()
        assertEquals(2, backend.streamCount)
    }

    // --- finishUtterance -----------------------------------------------------------------------

    @Test
    fun finishReturnsFinalTextAndEmitsFinal() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.text = "ошибка пятьсот два"
        engine.acceptPcm(silence(), 320)
        events.clear()

        val text = engine.finishUtterance()
        assertEquals("ошибка пятьсот два", text)
        assertEquals(listOf<AsrEvent>(AsrEvent.Final("ошибка пятьсот два")), events)
        assertTrue("finishStream должен сообщить native-слою конец аудио", backend.finished)
    }

    @Test
    fun finishFallsBackToLastPartialWhenResultIsEmpty() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()
        engine.startUtterance()

        backend.text = "кады"
        engine.acceptPcm(silence(), 320)
        events.clear()

        // После inputFinished native может вернуть пустую строку — текст реплики не теряем.
        backend.text = ""
        val text = engine.finishUtterance()
        assertEquals("кады", text)
        assertEquals(listOf<AsrEvent>(AsrEvent.Final("кады")), events)
    }

    @Test
    fun finishWithoutUtteranceReturnsEmpty() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()

        assertEquals("", engine.finishUtterance())
        assertTrue(events.isEmpty())
    }

    @Test
    fun acceptAfterFinishStartsNewUtterance() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load()
        engine.startUtterance()

        backend.text = "раз"
        engine.acceptPcm(silence(), 320)
        engine.finishUtterance()
        assertEquals(1, backend.streamCount)

        backend.text = "два"
        engine.acceptPcm(silence(), 320)
        assertEquals("микрофон продолжает литься — реплика начинается сама", 2, backend.streamCount)
    }

    // --- load / release ------------------------------------------------------------------------

    @Test
    fun loadIsIdempotentAndDoesNotRedownload() {
        val backend = FakeBackend()
        val source = FakeModelSource()
        val engine = engine(backend, source)

        assertTrue(engine.load())
        assertTrue(engine.load())
        assertTrue(engine.load())
        assertTrue(engine.isReady)
        assertEquals(1, backend.initCalls)
        assertEquals("модель уже на диске — качать нечего", 0, source.ensureCalls)
    }

    @Test
    fun loadDownloadsWhenModelMissing() {
        val backend = FakeBackend()
        val source = FakeModelSource().apply { available = false }
        val engine = engine(backend, source)

        assertTrue(engine.load())
        assertEquals(1, source.ensureCalls)
        assertTrue(engine.isReady)
    }

    @Test
    fun loadFailsWhenModelCannotBeFetched() {
        val backend = FakeBackend()
        val source = FakeModelSource().apply {
            available = false
            ensureResult = false
        }
        val engine = engine(backend, source)

        assertFalse(engine.load())
        assertFalse(engine.isReady)
        assertEquals(0, backend.initCalls)
    }

    @Test
    fun loadFailsWhenBackendInitFails() {
        val backend = FakeBackend().apply { failInit = true }
        val engine = engine(backend)

        assertFalse(engine.load())
        assertFalse(engine.isReady)
    }

    @Test
    fun loadFallsBackToNoHotwords() {
        val backend = FakeBackend().apply { failInitWithHotwords = true }
        val engine = engine(backend, config = AsrConfig(hotwords = listOf("марс")))

        assertTrue(engine.load())
        assertFalse("подсказки не поднялись — движок честно работает без них", engine.isHotwordsActive)
        assertEquals(2, backend.initCalls)
        assertEquals(emptyList<String>(), backend.lastConfig?.hotwords)
    }

    @Test
    fun hotwordsAreActiveAndPassedToStream() {
        val backend = FakeBackend()
        val engine = engine(backend, config = AsrConfig(hotwords = listOf("марс", "502")))

        assertTrue(engine.load())
        assertTrue(engine.isHotwordsActive)
        engine.startUtterance()
        assertEquals(listOf("марс", "502"), backend.lastHotwords)
    }

    @Test
    fun acceptBeforeLoadIsIgnored() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)

        engine.acceptPcm(silence(), 320)
        assertTrue(events.isEmpty())
        assertEquals(0, backend.streamCount)
        assertTrue(backend.samples.isEmpty())
    }

    @Test
    fun releaseStopsProcessingAndReleasesBackend() {
        val backend = FakeBackend()
        val events = ArrayList<AsrEvent>()
        val engine = engine(backend, events = events)
        engine.load()

        backend.text = "марс"
        engine.acceptPcm(silence(), 320)
        events.clear()

        engine.release()
        assertTrue(backend.released)
        assertFalse(engine.isReady)

        engine.acceptPcm(silence(), 320)
        assertTrue(events.isEmpty())
        // Повторный release не должен падать.
        engine.release()
    }

    // --- PCM -----------------------------------------------------------------------------------

    @Test
    fun oddByteIsCarriedBetweenCalls() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load()

        // 0x00 0x01 -> 256; 0x02 остаётся в переносе и склеивается со следующим 0x00 -> 2
        engine.acceptPcm(byteArrayOf(0x00, 0x01, 0x02), 3)
        engine.acceptPcm(byteArrayOf(0x00), 1)

        assertEquals(2, backend.samples.size)
        assertEquals(256f / 32768f, backend.samples[0], 0f)
        assertEquals(2f / 32768f, backend.samples[1], 0f)
    }

    @Test
    fun emptyPcmDoesNotCreateStream() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load()

        engine.acceptPcm(ByteArray(0), 0)
        assertEquals(0, backend.streamCount)
    }

    // --- устойчивость ---------------------------------------------------------------------------

    @Test
    fun listenerExceptionDoesNotBreakAudioLoop() {
        val backend = FakeBackend()
        val received = ArrayList<AsrEvent>()
        val engine = StreamingAsrEngine(FakeModelSource(), noHotwords, { event ->
            received += event
            if (received.size == 1) throw IllegalStateException("UI упал")
        }) { backend }
        engine.load()
        engine.startUtterance()

        backend.text = "марс"
        engine.acceptPcm(silence(), 320)
        backend.text = "марс порт"
        engine.acceptPcm(silence(), 320)
        assertEquals(3, received.size)
    }

    @Test
    fun concurrentAcceptDoesNotCorruptState() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load()

        val threads = 4
        val start = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(50) {
                    backend.text = "марс"
                    engine.acceptPcm(silence(80), 160)
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        assertEquals("все потоки должны писать в одну реплику", 1, backend.streamCount)
        assertEquals(threads * 50 * 80, backend.samples.size)
    }
}
