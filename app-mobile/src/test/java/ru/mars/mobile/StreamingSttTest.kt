package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [StreamingStt] — обёртки над движком, на фейковом [AsrEnginePort].
 *
 * Нативной библиотеки в JVM нет и не должно быть: проверяется не качество распознавания, а два
 * инварианта, которые ломают потоковый путь, если их нет: (1) звук вне открытой реплики не
 * попадает в движок (иначе он «слышит» собственную озвучку и отдаёт финал по ответу Марса),
 * (2) готовность движка проверяется на каждом буфере — отвалившийся движок не должен получать
 * звук и не должен ронять цикл.
 */
class StreamingSttTest {

    private class FakePort(
        var ready: Boolean = true,
    ) : AsrEnginePort {
        override val isReady: Boolean get() = ready

        override var events: AsrEnginePort.Events? = null

        var loads = 0
        var starts = 0
        var finishes = 0
        var releases = 0
        val pcmLengths = mutableListOf<Int>()
        var finishText: String = "финальный текст"

        override fun load(): Boolean {
            loads++
            return true
        }

        override fun startUtterance() {
            starts++
        }

        override fun acceptPcm(pcm: ByteArray, length: Int) {
            pcmLengths += length
        }

        override fun finishUtterance(): String {
            finishes++
            return finishText
        }

        override fun release() {
            releases++
        }
    }

    private class Events : SpeechToText.Events {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        var speechStarts = 0

        override fun onSpeechStarted() {
            speechStarts++
        }

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onFinal(text: String) {
            finals += text
        }
    }

    private fun pcm(bytes: Int = 3_200) = ByteArray(bytes)

    @Test
    fun `без движка обёртка не готова и молчит`() {
        val stt = StreamingStt()

        assertFalse(stt.isReady)
        stt.startUtterance()
        stt.acceptPcm(pcm(), 3_200)
        assertEquals("", stt.finishUtterance())
        assertEquals("streaming-asr", stt.id)
    }

    @Test
    fun `неготовый движок не получает звук`() {
        val port = FakePort(ready = false)
        val stt = StreamingStt(port)

        assertFalse(stt.isReady)
        stt.startUtterance()
        stt.acceptPcm(pcm(), 3_200)

        assertEquals("реплика не открывалась", 0, port.starts)
        assertEquals(emptyList<Int>(), port.pcmLengths)
    }

    @Test
    fun `звук до начала реплики отбрасывается`() {
        val port = FakePort()
        val stt = StreamingStt(port)

        stt.acceptPcm(pcm(), 3_200)
        assertEquals(emptyList<Int>(), port.pcmLengths)

        stt.startUtterance()
        stt.acceptPcm(pcm(1_600), 1_600)
        stt.acceptPcm(pcm(1_600), 1_600)

        assertEquals(listOf(1_600, 1_600), port.pcmLengths)
        assertEquals(3_200L, stt.acceptedBytes)
    }

    @Test
    fun `звук после конца реплики отбрасывается`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        stt.startUtterance()
        stt.finishUtterance()

        stt.acceptPcm(pcm(), 3_200)

        assertEquals("после finish движок молчит", emptyList<Int>(), port.pcmLengths)
    }

    @Test
    fun `finishUtterance отдаёт текст движка и закрывает реплику`() {
        val port = FakePort().apply { finishText = "проверка связи" }
        val stt = StreamingStt(port)
        stt.startUtterance()

        assertEquals("проверка связи", stt.finishUtterance())
        assertEquals(1, port.finishes)
        assertEquals("повторный finish не дёргает движок", "", stt.finishUtterance())
        assertEquals(1, port.finishes)
    }

    @Test
    fun `cancelUtterance закрывает реплику и выбрасывает текст`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        stt.startUtterance()

        stt.cancelUtterance()

        assertEquals("движку сообщили о конце реплики", 1, port.finishes)
        stt.cancelUtterance()
        assertEquals("повторный cancel движок не дёргает", 1, port.finishes)
        stt.acceptPcm(pcm(), 3_200)
        assertEquals(emptyList<Int>(), port.pcmLengths)
    }

    @Test
    fun `события движка доходят до цикла`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        val events = Events()
        stt.events = events

        port.events?.onSpeechStarted()
        port.events?.onPartial("при")
        port.events?.onPartial("привет")
        port.events?.onFinal("привет")

        assertEquals(1, events.speechStarts)
        assertEquals(listOf("при", "привет"), events.partials)
        assertEquals(listOf("привет"), events.finals)
    }

    @Test
    fun `падение движка на финале не роняет обёртку`() {
        val port = object : AsrEnginePort {
            override val isReady: Boolean get() = true
            override var events: AsrEnginePort.Events? = null
            override fun load(): Boolean = true
            override fun startUtterance() = Unit
            override fun acceptPcm(pcm: ByteArray, length: Int) = Unit
            override fun finishUtterance(): String = throw IllegalStateException("нативный сбой")
            override fun release() = Unit
        }
        val stt = StreamingStt(port)
        stt.startUtterance()

        assertEquals("", stt.finishUtterance())
    }

    @Test
    fun `load прокидывается в движок и без движка даёт false`() {
        val port = FakePort(ready = false)
        val stt = StreamingStt(port)

        assertTrue("load вызван у порта", stt.load())
        assertEquals(1, port.loads)
        assertFalse("без движка грузить нечего", StreamingStt().load())
    }

    @Test
    fun `release отвязывает движок`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        stt.startUtterance()

        stt.release()

        assertFalse(stt.isReady)
        assertEquals(1, port.releases)
        assertNull("движок больше не шлёт события", port.events)
        stt.acceptPcm(pcm(), 3_200)
        assertEquals(emptyList<Int>(), port.pcmLengths)
    }

    @Test
    fun `bind подключает движок после загрузки модели`() {
        val stt = StreamingStt()
        assertFalse(stt.isReady)

        val port = FakePort()
        stt.bind(port)

        assertTrue(stt.isReady)
        stt.startUtterance()
        assertEquals(1, port.starts)
    }
}
