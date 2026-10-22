package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация отказа серверного бэкенда в голосовом ходе (`409 unsupported_voice_mode_backend`).
 *
 * Требование контракта (`SERVER-CONTRACT.md` §1): `legacy-direct`/`legacy-journal` применяют
 * `voice_mode`, `runtime-runner` отвечает 409 `{"code":"unsupported_voice_mode_backend"}` ДО
 * side effects. Приложение обязано (а) узнать этот отказ, (б) не считать его транспортом,
 * (в) не повторять ход автоматически и (г) сказать человеку правду по-русски.
 *
 * Проверяется именно пара «код + маркер причины»: один 409 — это ещё и «поток занят» /
 * «stale clarify», а один маркер без кода мог бы прийти из текста ответа модели.
 */
class VoiceModeRejectionPolicyTest {

    @Test
    fun `server code from the contract is recognised in the described http error`() {
        // Ровно то, что отдаёт describeHttpError для тела {"code": "..."}.
        assertTrue(
            VoiceModeRejectionPolicy.isVoiceModeRejected(
                "HTTP 409: unsupported_voice_mode_backend"
            )
        )
        // Регистр не важен: строка проходит через lowercase в политике.
        assertTrue(
            VoiceModeRejectionPolicy.isVoiceModeRejected(
                "http 409: Unsupported_Voice_Mode_Backend"
            )
        )
    }

    @Test
    fun `other conflicts and other voice failures are not this rejection`() {
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected(null))
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected(""))
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected("HTTP 409"))
        // 409 по другой причине (занятый поток, stale clarify) — не отказ голосовому режиму.
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected("HTTP 409: stream already active"))
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected("HTTP 409: stale clarify"))
        // Маркер без 409 — это не отказ бэкенда (например, текст ответа модели).
        assertFalse(
            VoiceModeRejectionPolicy.isVoiceModeRejected(
                "Модель упомянула unsupported_voice_mode_backend в объяснении"
            )
        )
        // Голосовой канал/ASR падает иначе — это не повод выключать режим навсегда.
        assertFalse(VoiceModeRejectionPolicy.isVoiceModeRejected("HTTP 503: voice gateway down"))
    }

    @Test
    fun `rejection is never retryable`() {
        val rejected = "HTTP 409: unsupported_voice_mode_backend"
        assertFalse(VoiceModeRejectionPolicy.isRetryable(rejected))
        // Всё остальное политика ретраев не запрещает: этим занимаются другие правила.
        assertTrue(VoiceModeRejectionPolicy.isRetryable("HTTP 502: bad gateway"))
        assertTrue(VoiceModeRejectionPolicy.isRetryable(null))
    }

    @Test
    fun `notice is a russian explanation with the reason and a next step`() {
        val notice = VoiceModeRejectionPolicy.NOTICE
        // Причина названа, а не спрятана за «ошибка сети».
        assertTrue(notice.contains(VoiceModeRejectionPolicy.CODE))
        assertTrue(notice.contains("409"))
        assertTrue("человеку нужен следующий шаг", notice.contains("текст"))
        // Никаких английских «retry»/«try again» — тон интерфейса русский.
        assertFalse(notice.contains("retry", ignoreCase = true))
        assertFalse(notice.contains("try again", ignoreCase = true))
        // Короткий статус — про то же, без кода ошибки (место в шапке ограничено).
        assertTrue(VoiceModeRejectionPolicy.STATUS.contains("голосов", ignoreCase = true))
        assertFalse(VoiceModeRejectionPolicy.STATUS.contains("409"))
        assertEquals("unsupported_voice_mode_backend", VoiceModeRejectionPolicy.CODE)
    }
}
