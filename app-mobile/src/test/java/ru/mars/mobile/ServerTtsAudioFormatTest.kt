package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Расширение временного файла по Content-Type ответа синтеза.
 *
 * Сервер `api/tts` отдаёт то MP3, то WAV, а файл играет ExoPlayer из кеша: расширение
 * должно совпадать с форматом, иначе логи и внешние потребители файла видят не тот тип.
 */
class ServerTtsAudioFormatTest {

    @Test
    fun `wav получает своё расширение`() {
        assertEquals(".wav", ServerTtsAudioFormat.extensionFor("audio/wav"))
        assertEquals(".wav", ServerTtsAudioFormat.extensionFor("audio/x-wav"))
        assertEquals(".wav", ServerTtsAudioFormat.extensionFor("audio/wave"))
    }

    @Test
    fun `mp3 и mpeg получают своё расширение`() {
        assertEquals(".mp3", ServerTtsAudioFormat.extensionFor("audio/mpeg"))
        assertEquals(".mp3", ServerTtsAudioFormat.extensionFor("audio/mp3"))
    }

    @Test
    fun `регистр и параметры Content-Type не мешают`() {
        assertEquals(".wav", ServerTtsAudioFormat.extensionFor("AUDIO/WAV; charset=binary"))
        assertEquals(".mp3", ServerTtsAudioFormat.extensionFor(" Audio/MPEG ; rate=24000 "))
    }

    @Test
    fun `прочие форматы и неизвестный тип`() {
        assertEquals(".ogg", ServerTtsAudioFormat.extensionFor("audio/ogg"))
        assertEquals(".m4a", ServerTtsAudioFormat.extensionFor("audio/mp4"))
        assertEquals(ServerTtsAudioFormat.FALLBACK, ServerTtsAudioFormat.extensionFor(null))
        assertEquals(ServerTtsAudioFormat.FALLBACK, ServerTtsAudioFormat.extensionFor(""))
        assertEquals(ServerTtsAudioFormat.FALLBACK, ServerTtsAudioFormat.extensionFor("application/octet-stream"))
    }
}
