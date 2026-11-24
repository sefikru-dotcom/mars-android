package ru.mars.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Формат строк [VoiceTrace] и запрет на лишнее в них.
 *
 * `android.util.Log` в JVM-тестах — заглушка, поэтому строки ловятся через [VoiceTrace.sink]:
 * проверяется ровно то, что человек увидит в `adb logcat -s MarsVoice`.
 */
class VoiceTraceTest {

    private val lines = mutableListOf<String>()

    @Before
    fun hookSink() {
        lines.clear()
        VoiceTrace.sink = { lines += it }
    }

    @After
    fun releaseSink() {
        // Объект живёт всё время тестов JVM: без сброса подмена утечёт в соседний тест.
        VoiceTrace.sink = null
    }

    private fun sample() = VoiceReport(
        wantedProfileId = "plusvibe-alice",
        actualChannel = VoiceOutcomeChannel.CLOUD_SERVER,
        actualProfileId = "natural-svetlana",
        reason = "no_api_key",
        atMs = 1_700_000_000_000L,
    )

    // --- Формат реплики ---

    @Test fun reportLineHasContractShape() {
        assertEquals(
            "voice wanted=plusvibe-alice actual=CLOUD_SERVER(profile=natural-svetlana) " +
                "reason=no_api_key substituted=true at=1700000000000",
            VoiceTrace.line(sample())
        )
    }

    @Test fun reportWritesExactlyOneLineWithKeyFields() {
        VoiceTrace.report(sample())
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line, line.startsWith("voice wanted="))
        assertTrue(line, line.contains("actual=CLOUD_SERVER"))
        assertTrue(line, line.contains("reason=no_api_key"))
        assertTrue(line, line.contains("substituted=true"))
        assertTrue(line, line.contains("at=1700000000000"))
    }

    @Test fun honestReplyIsMarkedAsNotSubstituted() {
        VoiceTrace.report(
            VoiceReport("plusvibe-alice", VoiceOutcomeChannel.CLOUD_PAID, "plusvibe-alice", null, 42L)
        )
        assertTrue(lines.single().endsWith("substituted=false at=42"))
    }

    @Test fun emptyFieldsReadAsNoneInsteadOfNull() {
        // «null» в логе читается как сбой, «none» — как отсутствие значения по делу.
        val line = VoiceTrace.line(
            VoiceReport(null, VoiceOutcomeChannel.NONE, null, null, 7L)
        )
        assertEquals("voice wanted=none actual=NONE(profile=none) reason=none substituted=false at=7", line)
    }

    // --- В строке не должно быть ни текста реплики, ни секретов ---

    @Test fun utteranceTextNeverReachesTheLine() {
        val marker = "реплика владельца про голос целиком"
        VoiceTrace.report(
            VoiceReport(
                wantedProfileId = marker,
                actualChannel = VoiceOutcomeChannel.NONE,
                actualProfileId = marker,
                reason = marker,
                atMs = 0L,
            )
        )
        val line = lines.single()
        assertFalse(line, line.contains("реплика"))
        assertFalse(line, line.contains("владельца"))
        assertFalse(line, line.contains("голос"))
    }

    @Test fun apiKeyAndBearerAreRedacted() {
        // Заголовок авторизации целиком: в лог он не должен попасть ни в каком виде.
        val header = "Bearer dummy-marker-42"
        VoiceTrace.report(
            VoiceReport(
                wantedProfileId = "plusvibe-alice",
                actualChannel = VoiceOutcomeChannel.NONE,
                actualProfileId = null,
                reason = header,
                atMs = 1L,
            )
        )
        val line = lines.single()
        assertFalse(line, line.contains("Bearer", ignoreCase = true))
        assertFalse(line, line.contains("dummy-marker"))
        assertFalse(line, line.contains("apiKey", ignoreCase = true))
        assertTrue(line, line.contains("reason=redacted"))
        // Причина подмены остаётся видимой — иначе строка бесполезна.
        assertTrue(line, line.contains("substituted=true"))
    }

    @Test fun apiKeyAssignmentIsRedacted() {
        val line = VoiceTrace.line(
            VoiceReport("apiKey=deadbeef", VoiceOutcomeChannel.CLOUD_PAID, "api_key=deadbeef", null, 3L)
        )
        assertFalse(line, line.contains("deadbeef"))
        assertTrue(line, line.contains("wanted=redacted"))
        assertTrue(line, line.contains("profile=redacted"))
    }

    @Test fun reasonCodeIsNotMistakenForAKey() {
        // `no_api_key` — штатный код причины и главный диагноз: его прятать нельзя.
        val line = VoiceTrace.line(
            VoiceReport("plusvibe-alice", VoiceOutcomeChannel.CLOUD_SERVER, "natural-svetlana", "no_api_key", 4L)
        )
        assertTrue(line, line.contains("reason=no_api_key"))
    }

    @Test fun lineBreaksCannotSplitTheRecord() {
        // Одна реплика — одна строка: иначе `adb logcat` рвёт запись и разбор ломается.
        VoiceTrace.report(
            VoiceReport("alice\nactual=FAKE", VoiceOutcomeChannel.NONE, null, "bad\nreason", 5L)
        )
        val line = lines.single()
        assertFalse(line, line.contains("\n"))
        assertFalse(line, line.contains("FAKE"))
    }

    @Test fun longValueIsCapped() {
        val line = VoiceTrace.line(
            VoiceReport("a".repeat(200), VoiceOutcomeChannel.CLOUD_PAID, null, null, 9L)
        )
        assertTrue(line, line.contains("wanted=" + "a".repeat(48) + "~"))
        assertFalse(line, line.contains("a".repeat(49)))
    }

    @Test fun cleanIdentifiersPassThroughUntouched() {
        val line = VoiceTrace.line(
            VoiceReport("plusvibe-larisa", VoiceOutcomeChannel.OFFLINE_PIPER, "local-irina", "transport", 11L)
        )
        assertTrue(line, line.contains("wanted=plusvibe-larisa"))
        assertTrue(line, line.contains("profile=local-irina"))
        assertTrue(line, line.contains("reason=transport"))
    }

    // --- Готовность цепочки ---

    @Test fun readinessLineHasContractShape() {
        assertEquals(
            "voice ready paid=true piper=false server=true",
            VoiceTrace.readinessLine(paidReady = true, piperReady = false, serverReady = true)
        )
    }

    @Test fun readinessWritesOneLine() {
        VoiceTrace.readiness(paidReady = false, piperReady = true, serverReady = false)
        assertEquals(listOf("voice ready paid=false piper=true server=false"), lines)
    }

    // --- Тег и подмена sink ---

    @Test fun tagMatchesLogcatFilter() {
        // Координатор снимает приёмку командой `adb logcat -s MarsVoice` — тег менять нельзя.
        assertEquals("MarsVoice", VoiceTrace.TAG)
    }

    @Test fun sinkIsUsedOnlyWhileSet() {
        VoiceTrace.report(sample())
        VoiceTrace.sink = null
        // Без подмены вызов уходит в заглушку Log и не падает: приложение не должно
        // ломаться из-за диагностики.
        VoiceTrace.report(sample())
        assertEquals(1, lines.size)
    }
}
