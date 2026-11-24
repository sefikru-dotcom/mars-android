package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Семантика [VoiceReport.substituted] — то, ради чего затевалась наблюдаемость.
 *
 * Владелец жалуется «выбрал, а звучит не тот». Флаг обязан подниматься в каждом таком случае
 * и НЕ подниматься, когда всё честно: иначе строка в logcat либо кричит волком, либо молчит
 * о реальной подмене — и оба варианта хуже отсутствия лога.
 */
class VoiceChannelReportTest {

    private fun report(
        wanted: String? = "plusvibe-alice",
        channel: VoiceOutcomeChannel = VoiceOutcomeChannel.CLOUD_PAID,
        actual: String? = "plusvibe-alice",
        reason: String? = null,
    ) = VoiceReport(wanted, channel, actual, reason, 1_700_000_000_000L)

    // --- Выбранный прозвучал им же: подмены нет ---

    @Test fun sameProfileWithoutReasonIsNotSubstitution() {
        assertFalse(report().substituted)
    }

    // --- Причина есть: подмена (или отказ) зафиксированы ---

    @Test fun anyReasonMeansSubstitution() {
        assertTrue(report(reason = "no_api_key").substituted)
        assertTrue(report(reason = "no_plusvibe_profile").substituted)
        assertTrue(report(reason = "timeout").substituted)
        assertTrue(report(reason = "transport").substituted)
    }

    @Test fun reasonWinsEvenWhenProfileMatches() {
        // Тот же id, но звук пришёл резервным путём: для слуха это другой голос,
        // поэтому подмена, а не «всё хорошо».
        assertTrue(
            report(
                wanted = "plusvibe-alice",
                channel = VoiceOutcomeChannel.OFFLINE_PIPER,
                actual = "plusvibe-alice",
                reason = "paid_unavailable",
            ).substituted
        )
    }

    // --- Профиль другой: подмена даже без причины ---

    @Test fun differentProfileWithoutReasonIsSubstitution() {
        assertTrue(
            report(
                wanted = "plusvibe-alice",
                channel = VoiceOutcomeChannel.CLOUD_SERVER,
                actual = "natural-svetlana",
            ).substituted
        )
    }

    @Test fun silentFallbackToNoVoiceIsSubstitution() {
        // Реплика не прозвучала вообще: человек выбрал голос, а получил тишину.
        assertTrue(
            report(wanted = "plusvibe-alice", channel = VoiceOutcomeChannel.NONE, actual = null).substituted
        )
    }

    @Test fun anyChannelChangeIsStillJudgedByProfile() {
        // Канал сам по себе флаг не поднимает — только профиль и причина:
        // один и тот же профиль может прозвучать и с сервера, и напрямую.
        assertFalse(report(channel = VoiceOutcomeChannel.CLOUD_SERVER).substituted)
        assertTrue(report(channel = VoiceOutcomeChannel.CLOUD_SERVER, actual = "natural-dmitry").substituted)
    }

    // --- Выбора не было: подменять нечего ---

    @Test fun noSelectionAndNoReasonIsNotSubstitution() {
        // Профиль не выбран (человек не заходил в настройки голоса) — сравнивать не с чем,
        // и обвинять приложение в подмене нечестно. Отсутствие звука здесь ловит reason.
        assertFalse(report(wanted = null, channel = VoiceOutcomeChannel.SYSTEM_TTS, actual = "system").substituted)
    }

    @Test fun noSelectionButReasonIsSubstitution() {
        assertTrue(report(wanted = null, channel = VoiceOutcomeChannel.NONE, actual = null, reason = "timeout").substituted)
    }

    // --- Контракт имён каналов ---

    @Test fun channelsCoverEveryDegradationStepInOrder() {
        // Имена читаются в logcat как есть, порядок — путь деградации от лучшего к немому.
        // Тест сторожит переименование: на эти имена ссылаются строки лога и чужие воркеры.
        assertEquals(
            listOf("CLOUD_PAID", "CLOUD_SERVER", "OFFLINE_PIPER", "SYSTEM_TTS", "NONE"),
            VoiceOutcomeChannel.values().map { it.name }
        )
    }
}
