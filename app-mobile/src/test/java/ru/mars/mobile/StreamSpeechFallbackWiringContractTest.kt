package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки резерва Piper в потоковую озвучку серверного чата.
 *
 * Instrumented-слоя в проекте нет, поэтому проводка закреплена как source-контракт (тот же
 * стиль, что [ChatVoiceUiWiringContractTest]): тест читает РЕАЛЬНЫЙ production-исходник.
 * Без него возможен самый дорогой вид регресса — «резерв написан, тесты зелёные, а на экране
 * он не подключён»: `PiperFallbackTtsClient` существует, но очередь получает голый серверный
 * клиент, и фраза снова пропадает молча.
 *
 * Защищается:
 * 1. потоковая очередь собирается С декоратором резерва и получает общий порт процесса;
 * 2. отказ фразы уходит хосту (а не в никуда) и превращается в текст для человека;
 * 3. порт офлайн-голоса берётся ОДИН раз: второй экземпляр означал бы второй движок и два
 *    голоса одновременно.
 */
class StreamSpeechFallbackWiringContractTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val init by lazy { source("src/main/java/ru/mars/mobile/ChatInitSection.kt") }
    private val speechSection by lazy { source("src/main/java/ru/mars/mobile/ChatVoiceSpeechSection.kt") }

    @Test
    fun `потоковая очередь собирается с резервом Piper на общем порте`() {
        assertTrue(
            "очередь обязана получать декоратор резерва",
            init.contains("PiperFallbackTtsClient("),
        )
        assertTrue(
            "резерв должен получать порт процесса, а не создавать свой",
            init.contains("piper = offlinePort"),
        )
        assertEquals(
            "порт офлайн-голоса берётся один раз на экран",
            1,
            Regex("OfflineVoice\\.port\\(").findAll(init).count(),
        )
    }

    @Test
    fun `подмена голоса и пропущенная фраза доходят до человека`() {
        assertTrue("подмена голоса объявляется хосту", init.contains("onLocalFallback = {"))
        assertTrue("отказ резерва объявляется хосту", init.contains("onLocalFailure = {"))
        assertTrue(
            "очередь сообщает о пропущенной фразе, а не глотает её",
            init.contains("onPhraseFailed = { _, reason ->"),
        )
        // Причина отказа превращается в текст политикой, а не сырым кодом на экране.
        assertTrue(speechSection.contains("StreamSpeechNotice.phraseFailed(reason)"))
        assertTrue(speechSection.contains("StreamSpeechNotice.LOCAL_FAILED"))
    }
}
