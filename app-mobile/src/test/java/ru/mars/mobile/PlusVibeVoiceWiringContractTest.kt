package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки озвучки в ОБОИХ чатах.
 *
 * ## Зачем тест по исходникам
 * Дефекты этой волны были не в логике, а в ПРОВОДКЕ, и юнит-тесты движков их не видели:
 * - `LocalVoiceTts` умел сообщать о подмене голоса, но `LocalVoiceController` не передавал
 *   колбэк — событие умирало, и человек, выбравший «Алису», слышал робота без объяснения;
 * - серверный чат вообще не имел ни платного клиента, ни офлайн-резерва: `ChatInitSection`
 *   собирал `RepositoryServerTtsClient` без `paidClient`, а резервом оставался платформенный
 *   `TextToSpeech`;
 * - офлайн-синтез собирался в двух местах, и экран освобождал нативный движок при закрытии.
 *
 * Такую проводку проверяет либо приёмка на телефоне, либо контракт по тексту production-кода.
 * Приёмка дороже и не автоматизируется, поэтому здесь зафиксированы ровно те строки, без
 * которых интеграция снова развалится молча.
 */
class PlusVibeVoiceWiringContractTest {

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

    private val initSection by lazy { source("src/main/java/ru/mars/mobile/ChatInitSection.kt") }
    private val localChat by lazy { source("src/main/java/ru/mars/mobile/LocalChatActivity.kt") }
    private val localController by lazy { source("src/main/java/ru/mars/mobile/LocalVoiceController.kt") }
    private val speechSection by lazy { source("src/main/java/ru/mars/mobile/ChatVoiceSpeechSection.kt") }

    // --- PlusVibe: реальный клиент в обоих чатах ---------------------------------------------

    @Test
    fun `server chat wires the async plusvibe client into both synthesis paths`() {
        assertTrue(
            "ChatInitSection обязан собирать платный клиент PlusVibe",
            initSection.contains("PlusVibeTtsAccess(paidVoiceStore).client()"),
        )
        // Два пути: обычная озвучка ответа и потоковая очередь фраз. Клиент должен быть
        // передан ОБОИМ, иначе стрим продолжит синтезировать без платного голоса.
        val paidWiring = Regex("""paidClient\s*=\s*paidVoiceClient""").findAll(initSection).count()
        assertTrue("платный клиент передан обоим путям (найдено $paidWiring)", paidWiring >= 2)
        // Ключ и база берутся у НАСТРОЕННОГО профиля PlusVibe из хранилища провайдеров, а не
        // у активного: активным может быть чужой провайдер, и его хост/ключ к PlusVibe
        // отношения не имеют.
        assertTrue(initSection.contains("LocalProviderPrefsStore(this)"))
        assertFalse(
            "адрес платного голоса не должен браться у активного профиля",
            initSection.contains("state().active?.baseUrl"),
        )
    }

    @Test
    fun `local chat wires the async plusvibe client instead of the sync endpoint`() {
        assertTrue(
            "LocalChatActivity обязан собирать платный клиент PlusVibe",
            localChat.contains("PlusVibeTtsAccess(providerStore).client()"),
        )
        assertFalse(
            "адрес платного голоса не должен браться у активного профиля",
            localChat.contains("state().active?.baseUrl"),
        )
        // Синхронный `/v1/audio/speech` возвращал обрезанное аудио — в боевой проводке его нет.
        assertFalse(
            "синхронный клиент не должен участвовать в озвучке",
            localChat.contains("OpenAiSpeechTtsClient("),
        )
    }

    @Test
    fun `paid voice substitution reaches the person in both chats`() {
        // Отказ платного клиента не доходит до движка: RepositoryServerTtsClient сам уходит
        // на нейро-резерв, поэтому единственный шанс сказать человеку правду — этот колбэк.
        val hooks = Regex(
            """onPaidUnavailable\s*=\s*\{\s*reason\s*->\s*showPaidVoiceUnavailable\(reason\)\s*\}""",
        ).findAll(initSection).count()
        assertTrue("серверный чат обязан объявлять отказ платного голоса (найдено $hooks)", hooks >= 2)
        assertTrue(
            "локальный чат обязан объявлять отказ платного голоса",
            localChat.contains("onPaidUnavailable = { reason ->"),
        )
        assertTrue(
            "текст берётся из единой политики, а не пишется по месту",
            localChat.contains("VoiceSubstitutionNotice.paidUnavailable(reason)"),
        )
        assertTrue(
            "точка показа обязана существовать",
            speechSection.contains("fun ChatActivity.showPaidVoiceUnavailable(reason: String?)"),
        )
    }

    @Test
    fun `sync openai client is not constructed by any production wiring`() {
        val initUses = Regex("""OpenAiSpeechTtsClient\s*\(""").findAll(initSection).count()
        val localUses = Regex("""OpenAiSpeechTtsClient\s*\(""").findAll(localChat).count()
        assertEquals("серверный чат не должен создавать синхронный клиент", 0, initUses)
        assertEquals("локальный чат не должен создавать синхронный клиент", 0, localUses)
    }

    // --- Подмена голоса: событие обязано доходить до человека --------------------------------

    @Test
    fun `both chats surface the voice substitution event`() {
        // Серверный чат: движок и обёртка выбора голоса сообщают в одну точку.
        assertTrue(
            "ServerTtsSpeechEngine обязан сообщать о подмене",
            initSection.contains("onVoiceSubstituted = { substitution -> showVoiceSubstitution(substitution) }"),
        )
        assertTrue(
            "PreferOfflineEngine тоже обязан сообщать о подмене",
            Regex("""onVoiceSubstituted\s*=\s*\{\s*substitution\s*->\s*showVoiceSubstitution\(substitution\)\s*\}""")
                .findAll(initSection).count() >= 2,
        )
        assertTrue(
            "точка показа подмены должна существовать",
            speechSection.contains("fun ChatActivity.showVoiceSubstitution("),
        )
        assertTrue(
            "текст подмены берётся из единой политики",
            speechSection.contains("VoiceSubstitutionNotice.text("),
        )

        // Локальный чат: контроллер -> кнопка -> экран.
        assertTrue(
            "LocalVoiceController обязан пробрасывать колбэк подмены",
            localController.contains("onVoiceSubstituted = { substitution -> post { onVoiceSubstituted(substitution) } }"),
        )
        assertTrue(
            "LocalChatActivity обязан показать подмену человеку",
            localChat.contains("onVoiceSubstituted = { substitution ->"),
        )
        assertTrue(localChat.contains("VoiceSubstitutionNotice.text(substitution)"))
    }

    @Test
    fun `voice channel of the offline reserve is declared honestly`() {
        // Резерв серверного чата — локальная модель, а не платформа. Канал сообщается движку,
        // иначе человеку показывали бы «системный голос» даже когда говорит Piper.
        assertTrue(
            initSection.contains("fallbackChannel = if (piperEngine != null) VoiceChannel.OFFLINE else VoiceChannel.SYSTEM"),
        )
        assertTrue(
            "серверный чат обязан падать в Piper, а не сразу в платформу",
            initSection.contains("fallback = piperEngine ?: speechEngine"),
        )
        // Piper не звучит, пока модель не скачана: последний резерв — платформа, и он
        // объявляется тем же событием подмены (иначе первая фраза на новом устройстве
        // пропадала бы молча).
        assertTrue(
            initSection.contains("lastResort = if (piperEngine != null) speechEngine else null"),
        )
    }

    // --- Единый Piper ------------------------------------------------------------------------

    @Test
    fun `offline voice is a single process wide instance`() {
        assertTrue(
            "локальный чат берёт порт у OfflineVoice, а не собирает свой",
            localChat.contains("OfflineVoice.port(this)"),
        )
        assertTrue(localChat.contains("OfflineVoice.warmup(this)"))
        assertFalse(
            "экран не должен собирать собственный OfflineTtsBackend",
            localChat.contains("OfflineTtsBackend("),
        )
        assertFalse(
            "экран не должен освобождать общий движок в onDestroy",
            localChat.contains("ttsWarmup.release()"),
        )
        assertTrue(
            "серверный чат прогревает тот же общий движок",
            // Приёмник намеренно не фиксируется: важно, что прогрев идёт через общий
            // `OfflineVoice`, а не через собственный экземпляр движка.
            Regex("""OfflineVoice\.prepare\(this@\w+\)""").containsMatchIn(initSection),
        )
        // Движки, построенные поверх общего порта, не освобождают его.
        assertTrue(initSection.contains("releasePort = false"))
        assertTrue(localController.contains("ownsOfflinePort = false"))
    }

    @Test
    fun `system tts is only the last reserve and it is announced`() {
        assertTrue(
            "последний резерв объявляет себя",
            localChat.contains("VoiceSubstitutionNotice.SYSTEM_RESERVE"),
        )
    }
}
