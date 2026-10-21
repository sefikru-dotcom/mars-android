package ru.mars.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsResult

/**
 * Регрессия по блокеру «echo-эталон берётся снимком, а не по реально озвучиваемым фразам».
 *
 * Хосту нужен не «текст ответа целиком» (там ещё не произнесённые фразы), а ровно та фраза,
 * которую очередь отдала в воспроизведение. Иначе человек не смог бы перебить словами, которых
 * Марс ещё не сказал, а эхо уже сказанных фраз не опознавалось бы как эхо.
 *
 * Проверяется поведение очереди, а не строка исходника: какие фразы и в каком порядке попадают
 * в колбэк при реальном проигрывании, и что пропущенная фраза в эталон не попадает.
 */
class SpeechQueuePhraseStartedTest {

    private class Client(private val fail: Set<String> = emptySet()) : ServerTtsClient {
        override suspend fun synthesize(text: String): ServerTtsResult =
            if (text in fail) ServerTtsResult.Unavailable("server_error")
            else ServerTtsResult.Audio(text.toByteArray(), "audio/mpeg")
    }

    private class Playback(private val reject: Set<String> = emptySet()) : ServerTtsPlayback {
        private var finish: (() -> Unit)? = null
        var played: MutableList<String> = ArrayList()

        override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean =
            playToEnd(bytes, contentType, {}, onFailed)

        override fun playToEnd(
            bytes: ByteArray,
            contentType: String,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            val text = bytes.decodeToString()
            if (text in reject) return false
            played += text
            finish = onFinished
            return true
        }

        fun finishCurrent() {
            val callback = finish
            finish = null
            callback?.invoke()
        }

        override fun stop() {
            finish = null
        }

        override fun release() = Unit
    }

    private fun queue(
        client: Client,
        playback: Playback,
        onPhraseStarted: (String) -> Unit,
    ): SpeechQueue = SpeechQueue(
        client = client,
        playback = playback,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onPhraseStarted = onPhraseStarted,
        retryPolicy = SpeechRetryPolicy(sleeper = {}),
    )

    @Test
    fun `эталон обновляется по одной фразе в порядке звучания`() {
        val playback = Playback()
        val started = mutableListOf<String>()
        val queue = queue(Client(), playback) { started += it }

        queue.speakAll(listOf("Первая фраза.", "Вторая фраза.", "Третья фраза."))

        // Зазвучала только первая: будущий текст в эталон не попадает.
        assertEquals(listOf("Первая фраза."), started)

        playback.finishCurrent()
        assertEquals(listOf("Первая фраза.", "Вторая фраза."), started)

        playback.finishCurrent()
        assertEquals(listOf("Первая фраза.", "Вторая фраза.", "Третья фраза."), started)

        playback.finishCurrent()
        assertEquals(3, started.size)
    }

    @Test
    fun `фраза, которую плеер не принял, в эталон не попадает`() {
        val playback = Playback(reject = setOf("Вторая фраза."))
        val started = mutableListOf<String>()
        val queue = queue(Client(), playback) { started += it }

        queue.speakAll(listOf("Первая фраза.", "Вторая фраза.", "Третья фраза."))
        assertEquals(listOf("Первая фраза."), started)

        // Вторая отклонена плеером → пропуск; третья звучит и только она попадает в эталон.
        playback.finishCurrent()
        assertEquals(listOf("Первая фраза.", "Третья фраза."), started)
    }

    @Test
    fun `фраза с упавшим синтезом не считается озвученной`() {
        val playback = Playback()
        val started = mutableListOf<String>()
        val queue = queue(Client(fail = setOf("Вторая фраза.")), playback) { started += it }

        queue.speakAll(listOf("Первая фраза.", "Вторая фраза.", "Третья фраза."))
        assertEquals(listOf("Первая фраза."), started)

        playback.finishCurrent()
        assertEquals(
            "незвучавшая фраза не должна становиться эталоном эха",
            listOf("Первая фраза.", "Третья фраза."),
            started,
        )
        assertTrue(queue.pendingCount <= 1)
    }
}
