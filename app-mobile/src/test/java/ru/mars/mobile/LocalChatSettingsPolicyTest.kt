package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.ServerTtsVoices

/**
 * Состав окна «Контекст и параметры» локального чата.
 *
 * Проверяется именно то, что нельзя увидеть на телефоне без приёмки: что строки не
 * дублируют друг друга, что подписи не обещают того, чего нет, и что данные голоса/темпа
 * не переименованы вместе с подписями. Отдельно закреплено исчезновение устаревшего
 * утверждения «Piper офлайн / Голос Ирины готов»: пока голосов шесть и основной —
 * облачная Алиса, эта подпись врала о том, что человек услышит.
 */
class LocalChatSettingsPolicyTest {

    @Test
    fun `полочки идут в порядке вопросов`() {
        assertEquals(
            listOf("ОКНО КОНТЕКСТА И РАСХОД", "МОДЕЛЬ, МЫШЛЕНИЕ И ГЕНЕРАЦИЯ", "ГОЛОС, ПАМЯТЬ И ФОНОВЫЙ СЛУХ"),
            LocalChatSettingsPolicy.Section.entries.map { it.title },
        )
    }

    @Test
    fun `строка модели показывает выбранную модель или честное «не выбрана»`() {
        val rows = LocalChatSettingsPolicy.modelRows(
            model = "claude-haiku-5.5",
            supportsReasoning = true,
            reasoningLabel = "Средний",
            turnSettingsCustom = false,
        )
        assertEquals("claude-haiku-5.5", rows.first { it.id == LocalChatSettingsPolicy.RowId.MODEL }.value)

        val empty = LocalChatSettingsPolicy.modelRows(
            model = "  ",
            supportsReasoning = false,
            reasoningLabel = null,
            turnSettingsCustom = false,
        )
        // Пустая строка вместо имени модели читалась бы как «сломано», а не «не выбрано».
        assertEquals(
            LocalChatSettingsPolicy.MODEL_UNSET,
            empty.first { it.id == LocalChatSettingsPolicy.RowId.MODEL }.value,
        )
    }

    @Test
    fun `модель без reasoning даёт строку-факт, а не мёртвый тап`() {
        val unsupported = LocalChatSettingsPolicy.modelRows(
            model = "gpt-4o-mini",
            supportsReasoning = false,
            reasoningLabel = "Средний",
            turnSettingsCustom = false,
        ).first { it.id == LocalChatSettingsPolicy.RowId.REASONING }

        assertFalse(unsupported.enabled)
        assertEquals(LocalChatSettingsPolicy.REASONING_UNSUPPORTED, unsupported.value)

        val supported = LocalChatSettingsPolicy.modelRows(
            model = "claude-haiku-5.5",
            supportsReasoning = true,
            reasoningLabel = "Средний",
            turnSettingsCustom = false,
        ).first { it.id == LocalChatSettingsPolicy.RowId.REASONING }
        assertTrue(supported.enabled)
        assertEquals("Средний", supported.value)
    }

    @Test
    fun `звёздочка параметров появляется только после ручной правки`() {
        val clean = LocalChatSettingsPolicy.modelRows("m", false, null, turnSettingsCustom = false)
            .first { it.id == LocalChatSettingsPolicy.RowId.SAMPLING }
        val custom = LocalChatSettingsPolicy.modelRows("m", false, null, turnSettingsCustom = true)
            .first { it.id == LocalChatSettingsPolicy.RowId.SAMPLING }

        assertEquals(LocalChatSettingsPolicy.SAMPLING_DEFAULT, clean.value)
        assertEquals(LocalChatSettingsPolicy.SAMPLING_CUSTOM, custom.value)
        // Метка та же, что на чипе «Параметры *»: две разные метки одного состояния
        // заставили бы человека искать разницу, которой нет.
        assertTrue(custom.value.endsWith("*"))
    }

    @Test
    fun `строка голоса называет выбранный голос и темп`() {
        // Голос не выбран — звучит основной голос приложения, и подпись обязана называть
        // именно его, а не первый попавшийся из списка.
        assertEquals(
            "Алиса (PlusVibe) · обычный темп",
            LocalChatSettingsPolicy.prosodyValue(VoiceProsody()),
        )
        assertEquals(
            "Ирина (офлайн) · 1.15×",
            LocalChatSettingsPolicy.prosodyValue(
                VoiceProsody(speed = 1.15f, voiceId = ServerTtsVoices.LOCAL_IRINA.id),
            ),
        )
    }

    @Test
    fun `устаревшего «Piper офлайн» и обещания голоса Ирины больше нет`() {
        val prosody = VoiceProsody(speed = 1.15f, voiceId = ServerTtsVoices.PLUSVIBE_ALICE.id)
        val row = LocalChatSettingsPolicy.voiceRows(prosody, offlineVoiceReady = true, wakeArmed = false)
            .first { it.id == LocalChatSettingsPolicy.RowId.PROSODY }
        val text = listOfNotNull(row.title, row.value, row.hint).joinToString(" ")

        assertFalse("движок в заголовке строки — устаревшая формулировка", text.contains("Piper офлайн"))
        assertFalse("частота дискретизации человеку ничего не решает", text.contains("22 кГц"))
        assertFalse("«Ирины готов» читалось как «звучит Ирина»", text.contains("Голос Ирины"))
        assertFalse("заглушка про резерв врала о текущем голосе", text.contains("системный резерв"))
        // А выбранный голос и темп видны — строка не потеряла смысл вместе с устаревшей подписью.
        assertTrue(text.contains("Алиса (PlusVibe)"))
        assertTrue(text.contains("1.15×"))
    }

    @Test
    fun `офлайн-голос описан как резерв, а не как текущий голос`() {
        val ready = LocalChatSettingsPolicy.offlineVoiceHint(ready = true)
        val absent = LocalChatSettingsPolicy.offlineVoiceHint(ready = false)

        assertEquals(LocalChatSettingsPolicy.OFFLINE_READY, ready)
        assertTrue("отсутствие резерва обязано объяснять, что звучит вместо него", absent.contains("облако"))
        assertFalse("отсутствие модели не должно выглядеть как ошибка настройки", absent.contains("ошибка"))

        // Подсказка живёт отдельно от значения: «готов» рядом с выбранной Алисой не
        // должно читаться как «звучит офлайн-голос».
        val row = LocalChatSettingsPolicy.voiceRows(
            VoiceProsody(voiceId = ServerTtsVoices.PLUSVIBE_ALICE.id),
            offlineVoiceReady = true,
            wakeArmed = false,
        ).first { it.id == LocalChatSettingsPolicy.RowId.PROSODY }
        assertFalse(row.value.contains("Офлайн"))
    }

    @Test
    fun `слух с погашенным экраном не называется «Фоновый слух»`() {
        // В настройках приложения «Фоновый слух» — флаг wake_word для детектора в чате.
        // Одинаковая подпись у двух разных механизмов и была дублирующей настройкой.
        val on = LocalChatSettingsPolicy.voiceRows(VoiceProsody(), false, wakeArmed = true)
            .first { it.id == LocalChatSettingsPolicy.RowId.WAKE_SERVICE }
        val off = LocalChatSettingsPolicy.voiceRows(VoiceProsody(), false, wakeArmed = false)
            .first { it.id == LocalChatSettingsPolicy.RowId.WAKE_SERVICE }

        assertEquals(LocalChatSettingsPolicy.WAKE_TITLE, on.title)
        assertFalse(on.title.contains("Фоновый слух"))
        assertEquals(LocalChatSettingsPolicy.WAKE_ON, on.value)
        assertEquals(LocalChatSettingsPolicy.WAKE_OFF, off.value)
        assertEquals(LocalChatSettingsPolicy.WAKE_HINT, on.hint)
    }

    @Test
    fun `ровно одна строка на каждый вопрос`() {
        val rows = LocalChatSettingsPolicy.allRows(
            model = "claude-haiku-5.5",
            supportsReasoning = true,
            reasoningLabel = "Средний",
            turnSettingsCustom = true,
            prosody = VoiceProsody(),
            offlineVoiceReady = false,
            wakeArmed = true,
        )
        assertEquals(
            listOf(
                LocalChatSettingsPolicy.RowId.MODEL,
                LocalChatSettingsPolicy.RowId.REASONING,
                LocalChatSettingsPolicy.RowId.SAMPLING,
                LocalChatSettingsPolicy.RowId.VOICE_MODE,
                LocalChatSettingsPolicy.RowId.PROSODY,
                LocalChatSettingsPolicy.RowId.WAKE_SERVICE,
                LocalChatSettingsPolicy.RowId.MEMORY,
            ),
            rows.map { it.id },
        )
        // Ни одного повтора id: дубль строки — это и есть «дублирующая настройка».
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        // И ни одного повтора названия: две строки с одинаковым заголовком человек
        // воспринимает как одну настройку, показанную дважды.
        assertEquals(rows.size, rows.map { it.title }.distinct().size)
    }

    @Test
    fun `данные голоса и темпа не переименованы вместе с подписями`() {
        // Пересборка подписей не имеет права тронуть хранилище: иначе у человека после
        // обновления «сбросился» бы выбранный голос и темп.
        assertEquals("mars-voice-prosody", VoiceProsodyPrefs.PREFS_NAME)
        assertEquals("speed", VoiceProsodyPrefs.KEY_SPEED)
        assertEquals("pitch", VoiceProsodyPrefs.KEY_PITCH)
        assertEquals("voice_id", VoiceProsodyPrefs.KEY_VOICE)

        // Значение проходит через политику без изменений: окно только показывает.
        val saved = VoiceProsody(speed = 1.3f, pitch = 1.1f, voiceId = ServerTtsVoices.NATURAL_DMITRY.id)
        val row = LocalChatSettingsPolicy.voiceRows(saved, offlineVoiceReady = false, wakeArmed = false)
            .first { it.id == LocalChatSettingsPolicy.RowId.PROSODY }
        assertEquals("Дмитрий · 1.3×", row.value)
        assertEquals(saved, VoiceProsody(speed = 1.3f, pitch = 1.1f, voiceId = ServerTtsVoices.NATURAL_DMITRY.id))
    }

    @Test
    fun `строка памяти — вход, а не настройка со значением-переключателем`() {
        val memory = LocalChatSettingsPolicy.memoryRow()
        assertEquals(LocalChatSettingsPolicy.RowId.MEMORY, memory.id)
        assertEquals(LocalChatSettingsPolicy.MEMORY_TITLE, memory.title)
        assertTrue("вход в память обязан быть кликабельным", memory.enabled)
        assertFalse("память не измеряется вкл/выкл", memory.value == "Включено" || memory.value == "Выключено")
    }
}
