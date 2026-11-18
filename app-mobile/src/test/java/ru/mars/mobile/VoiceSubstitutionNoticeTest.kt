package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Текст сообщения о подмене голоса: что человек прочитает, услышав не тот голос.
 *
 * Проверяется не «красота формулировок», а три свойства, без которых сообщение бесполезно:
 * 1. каждый канал ([VoiceChannel]) имеет СВОЙ текст — иначе «робот вместо Алисы» и
 *    «Piper вместо облака» описывались бы одинаково;
 * 2. текст говорит, ЧЕМ говорят сейчас (человек должен понимать, что звук всё-таки будет);
 * 3. причина подмены не утекает наружу кодами (`401`, `media_max_price_exceeded`) — она
 *    остаётся в диагностике, а не в статусной строке.
 */
class VoiceSubstitutionNoticeTest {

    @Test
    fun `each channel has its own full text`() {
        val cloudFailed = VoiceSubstitutionNotice.text(
            VoiceSubstitution(VoiceChannel.OFFLINE, VoiceSubstitutionReason.CLOUD_FAILED),
        )
        val offlineFailed = VoiceSubstitutionNotice.text(
            VoiceSubstitution(VoiceChannel.CLOUD, VoiceSubstitutionReason.OFFLINE_FAILED),
        )
        val systemReserve = VoiceSubstitutionNotice.text(
            VoiceSubstitution(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE),
        )

        assertEquals(VoiceSubstitutionNotice.CLOUD_FAILED, cloudFailed)
        assertEquals(VoiceSubstitutionNotice.OFFLINE_FAILED, offlineFailed)
        assertEquals(VoiceSubstitutionNotice.SYSTEM_RESERVE, systemReserve)
        assertNotEquals(cloudFailed, offlineFailed)
        assertNotEquals(offlineFailed, systemReserve)
        assertNotEquals(cloudFailed, systemReserve)
    }

    @Test
    fun `text says which voice is speaking now`() {
        assertTrue(
            VoiceSubstitutionNotice.CLOUD_FAILED.contains("локальным", ignoreCase = true),
        )
        assertTrue(
            VoiceSubstitutionNotice.OFFLINE_FAILED.contains("облачным", ignoreCase = true),
        )
        assertTrue(
            VoiceSubstitutionNotice.SYSTEM_RESERVE.contains("системным", ignoreCase = true),
        )
    }

    @Test
    fun `short form names the channel without repeating the explanation`() {
        assertEquals(
            "Говорю локальным голосом",
            VoiceSubstitutionNotice.shortText(
                VoiceSubstitution(VoiceChannel.OFFLINE, VoiceSubstitutionReason.CLOUD_FAILED),
            ),
        )
        assertEquals(
            "Говорю облачным голосом",
            VoiceSubstitutionNotice.shortText(
                VoiceSubstitution(VoiceChannel.CLOUD, VoiceSubstitutionReason.OFFLINE_FAILED),
            ),
        )
        assertEquals(
            "Говорю системным голосом",
            VoiceSubstitutionNotice.shortText(
                VoiceSubstitution(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE),
            ),
        )
    }

    @Test
    fun `text never leaks internal codes or http statuses`() {
        val all = listOf(
            VoiceSubstitutionNotice.CLOUD_FAILED,
            VoiceSubstitutionNotice.OFFLINE_FAILED,
            VoiceSubstitutionNotice.SYSTEM_RESERVE,
        )
        for (text in all) {
            for (code in listOf("401", "429", "500", "http", "media_max_price_exceeded", "transport")) {
                assertTrue(
                    "в тексте не должно быть кода «$code»: $text",
                    !text.contains(code, ignoreCase = true),
                )
            }
        }
    }
}
