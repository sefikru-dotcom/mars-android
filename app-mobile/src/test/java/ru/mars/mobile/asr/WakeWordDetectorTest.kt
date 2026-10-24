package ru.mars.mobile.asr

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.mars.mobile.AudioSource

/**
 * Тесты [WakeWordDetector] — чистый JVM: вместо микрофона [AudioSource] с очередью чанков,
 * вместо `KeywordSpotter` — [FakePort], вместо системных часов — управляемый счётчик.
 *
 * Проверяется ровно то, чего не видно в тестах политики: без модели микрофон не открывается;
 * native не поднялся — деградация в «недоступно», а не падение; keywords-файл лежит рядом с
 * моделью и собран из BPE-токенов; срабатывание доходит до хоста один раз (хвост фразы гасит
 * дебаунс); [WakeWordDetector.stop] освобождает и микрофон, и native.
 */
class WakeWordDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        /** Словарь, достаточный для трёх фраз по умолчанию (те же токены, что у модели). */
        val TOKENS = listOf("▁ма", "р", "с", "ш", "▁с", "лу", "ша", "й")

        const val WAIT_MS = 3_000L

        /** Кусок PCM: 20 мс 16 кГц моно; поток чтения всё равно читает своим буфером. */
        const val CHUNK_BYTES = 640

        val EXPECTED_KEYWORDS = "▁ма р с\n▁ма р с ▁с лу ша й\n▁ма р ш\n"
    }

    /** Источник с очередью: тест кладёт чанки, поток чтения их забирает. */
    private class FakeSource : AudioSource {
        private val monitor = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var closed = false

        @Volatile
        var released = false
            private set

        fun push(chunk: ByteArray = ByteArray(CHUNK_BYTES) { 1 }) {
            synchronized(monitor) {
                chunks.addLast(chunk)
                monitor.notifyAll()
            }
        }

        override fun read(buf: ByteArray): Int {
            val chunk: ByteArray
            synchronized(monitor) {
                while (chunks.isEmpty() && !closed) {
                    try {
                        monitor.wait(200)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                }
                if (chunks.isEmpty()) return -1
                chunk = chunks.removeFirst()
            }
            val copy = minOf(buf.size, chunk.size)
            System.arraycopy(chunk, 0, buf, 0, copy)
            return copy
        }

        override fun release() {
            released = true
            synchronized(monitor) {
                closed = true
                monitor.notifyAll()
            }
        }
    }

    /** Порт-фейк: помнит вызовы и отдаёт заранее заданную фразу. */
    private class FakePort(private val initResult: Boolean = true) : WakeWordPort {
        val initializeCalls = AtomicInteger()

        @Volatile
        var keywordsFile: File? = null

        @Volatile
        var config: WakeWordConfig? = null

        @Volatile
        var released = false

        val resets = AtomicInteger()
        val accepted = AtomicInteger()

        /** Что «услышал» порт на следующем чанке. */
        @Volatile
        var nextResult: String = ""

        /** Бросать ли исключение из accept (проверка деградации). */
        @Volatile
        var failOnAccept = false

        val wasInitialized: Boolean get() = initializeCalls.get() > 0

        override fun initialize(modelDir: File, keywordsFile: File, config: WakeWordConfig): Boolean {
            initializeCalls.incrementAndGet()
            this.keywordsFile = keywordsFile
            this.config = config
            return initResult
        }

        override fun accept(samples: FloatArray): String {
            accepted.incrementAndGet()
            if (failOnAccept) throw IllegalStateException("native упал")
            return nextResult
        }

        override fun reset() {
            resets.incrementAndGet()
        }

        override fun release() {
            released = true
        }
    }

    /** Часы дебаунса: неподвижные, чтобы окно подавления было заведомо активно. */
    private val clock = AtomicInteger(1_000)

    private fun modelStore(installed: Boolean): ModelStore {
        val tokens = "▁ма 0\nр 1\nс 2\n"
        val store = ModelStore(
            rootDir = tmp.root,
            expectedFiles = linkedMapOf("tokens.txt" to tokens.toByteArray().size.toLong()),
            fileSha256 = linkedMapOf("tokens.txt" to "unused"),
            modelId = "test-model",
        )
        if (installed) {
            store.modelDir.mkdirs()
            File(store.modelDir, "tokens.txt").writeText(tokens)
        }
        return store
    }

    private fun detector(
        store: ModelStore,
        port: FakePort,
        source: FakeSource,
        onSourceCreated: () -> Unit = {},
    ): WakeWordDetector = WakeWordDetector(
        modelStore = store,
        policyFactory = { WakeWordPolicy(TOKENS) },
        portFactory = { port },
        sourceFactory = {
            onSourceCreated()
            source
        },
        nowMs = { clock.get().toLong() },
    )

    // --- Состояния и деградация ---

    @Test
    fun `без модели микрофон не открывается и состояние - модель не скачана`() {
        val port = FakePort()
        val source = FakeSource()
        val states = CopyOnWriteArrayList<WakeWordDetector.State>()
        val detector = detector(modelStore(installed = false), port, source)

        val started = detector.start(onDetection = { }, onState = { states.add(it) })

        assertFalse("без модели стартовать нечему", started)
        assertEquals(WakeWordDetector.State.MODEL_MISSING, detector.state)
        assertEquals(
            "хост обязан узнать причину, а не получить молчание",
            listOf(WakeWordDetector.State.MODEL_MISSING),
            states.toList(),
        )
        assertFalse("порт не должен трогать native без модели", port.wasInitialized)
        assertFalse("микрофон не открывается без модели", source.released)
        assertFalse(detector.isListening)
    }

    @Test
    fun `native не поднялся - состояние недоступно, микрофон закрыт`() {
        val port = FakePort(initResult = false)
        val source = FakeSource()
        val states = CopyOnWriteArrayList<WakeWordDetector.State>()
        val detector = detector(modelStore(installed = true), port, source)

        assertTrue(detector.start(onDetection = { }, onState = { states.add(it) }))

        waitUntil("состояние UNAVAILABLE после неудачной инициализации") {
            detector.state == WakeWordDetector.State.UNAVAILABLE
        }
        assertTrue("порт обязан попытаться поднять native", port.wasInitialized)
        waitUntil("микрофон освобождается при отказе native") { source.released }
        assertTrue(
            "хост получает честное «недоступно»: ${states.toList()}",
            states.contains(WakeWordDetector.State.UNAVAILABLE),
        )
        assertFalse(detector.isListening)
    }

    @Test
    fun `исключение порта на аудио - деградация, а не падение приложения`() {
        val port = FakePort().apply { failOnAccept = true }
        val source = FakeSource()
        val detector = detector(modelStore(installed = true), port, source)
        detector.start(onDetection = { })

        waitUntil("порт попытался инициализироваться") { port.wasInitialized }
        source.push()

        waitUntil("состояние UNAVAILABLE после сбоя порта") {
            detector.state == WakeWordDetector.State.UNAVAILABLE
        }
        waitUntil("микрофон освобождён после сбоя") { source.released }
    }

    @Test
    fun `нечитаемый словарь модели - недоступно, а не исключение наружу`() {
        val port = FakePort()
        val source = FakeSource()
        val detector = WakeWordDetector(
            modelStore = modelStore(installed = true),
            policyFactory = { error("tokens.txt не читается") },
            portFactory = { port },
            sourceFactory = { source },
            nowMs = { clock.get().toLong() },
        )

        assertFalse(detector.start(onDetection = { }))
        assertEquals(WakeWordDetector.State.UNAVAILABLE, detector.state)
        assertFalse(port.wasInitialized)
        assertFalse(source.released)
    }

    // --- Основной путь ---

    @Test
    fun `keywords-файл собирается из BPE-токенов и лежит рядом с моделью`() {
        val port = FakePort()
        val source = FakeSource()
        val store = modelStore(installed = true)
        val detector = detector(store, port, source)

        detector.start(onDetection = { })
        waitUntil("порт инициализирован") { port.wasInitialized }

        val file = checkNotNull(port.keywordsFile) { "порт не получил keywords-файл" }
        assertTrue("файл обязан существовать до инициализации native", file.isFile)
        assertEquals("keywords живут и умирают вместе с моделью", store.rootDir.absoluteFile, file.parentFile.absoluteFile)
        assertEquals(EXPECTED_KEYWORDS, file.readText())

        val config = checkNotNull(port.config)
        assertEquals("порог уходит в native из конфига", WakeWordConfig.DEFAULT_THRESHOLD, config.threshold, 0.0001f)
        assertEquals("один поток: wake word не конкурирует с ASR", 1, config.numThreads)

        detector.stop()
    }

    @Test
    fun `срабатывание доходит до хоста один раз - хвост фразы гасит дебаунс`() {
        val port = FakePort()
        val source = FakeSource()
        val detections = CopyOnWriteArrayList<String>()
        val firstDetection = CountDownLatch(1)
        val detector = detector(modelStore(installed = true), port, source)

        detector.start(
            onDetection = { phrase ->
                detections.add(phrase)
                firstDetection.countDown()
            },
        )
        waitUntil("порт инициализирован") { port.wasInitialized }

        port.nextResult = "▁ма р с"
        source.push()
        assertTrue(
            "детекция обязана дойти до хоста",
            firstDetection.await(WAIT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals(listOf("▁ма р с"), detections.toList())

        // Тот же возглас в хвосте аудио: часы не двигались, значит окно подавления активно.
        source.push()
        waitUntil("порт обработал второй чанк") { port.accepted.get() >= 2 }
        assertEquals(
            "одна фраза не должна открывать диалог дважды",
            listOf("▁ма р с"),
            detections.toList(),
        )
        assertTrue("после срабатывания поток поиска сброшен", port.resets.get() >= 1)

        detector.stop()
        assertTrue("микрофон освобождён", source.released)
        assertTrue("native освобождён в stop", port.released)
    }

    @Test
    fun `reset снимает дебаунс - следующая фраза снова принимается`() {
        val port = FakePort()
        val source = FakeSource()
        val detections = CopyOnWriteArrayList<String>()
        val twoDetections = CountDownLatch(2)
        val detector = detector(modelStore(installed = true), port, source)

        detector.start(
            onDetection = {
                detections.add(it)
                twoDetections.countDown()
            },
        )
        waitUntil("порт инициализирован") { port.wasInitialized }

        port.nextResult = "марс"
        source.push()
        waitUntil("первая детекция") { detections.size == 1 }

        // Хост знает, что дальше был «свой» звук (озвучка/музыка), и просит начать поиск заново.
        detector.reset()
        source.push()
        assertTrue(
            "после reset та же фраза обязана приниматься снова",
            twoDetections.await(WAIT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals(listOf("марс", "марс"), detections.toList())

        detector.stop()
    }

    @Test
    fun `повторный start при уже идущей сессии не открывает второй микрофон`() {
        val port = FakePort()
        val source = FakeSource()
        val created = AtomicInteger()
        val detector = detector(modelStore(installed = true), port, source) { created.incrementAndGet() }

        assertTrue(detector.start(onDetection = { }))
        waitUntil("порт инициализирован") { port.wasInitialized }
        assertFalse("второй микрофон не открывается", detector.start(onDetection = { }))
        assertEquals("источник открыт ровно один раз", 1, created.get())

        detector.stop()
        assertFalse(detector.isListening)
    }

    @Test
    fun `stop и reset без start безопасны`() {
        val detector = detector(modelStore(installed = true), FakePort(), FakeSource())
        detector.stop()
        detector.reset()
        assertEquals(WakeWordDetector.State.IDLE, detector.state)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Не дождались: $what")
    }
}
