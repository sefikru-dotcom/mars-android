package ru.mars.network.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Настройки хода: слои (профиль → каталог модели → сессия) и хранение в мете сессии.
 *
 * Тесты держат три обещания, на которых стоит вся конструкция:
 *  - дефолты модели не выдумываются: молчание каталога — это `null` («лимит провайдера»),
 *    а не догадка по семейству модели;
 *  - merge переопределяет ТОЛЬКО заданные поля, иначе смена модели внутри сессии
 *    приносила бы с собой чужие значения — ту самую беду, из-за которой настройки
 *    переехали из профиля;
 *  - испорченная мета сессии даёт `null`, а не исключение и не пустой объект: пустой
 *    объект стёр бы настройки при следующем сохранении.
 */
class LocalTurnSettingsTest {

    private val profile = LocalProviderProfile(
        id = "p1",
        title = "PlusVibe",
        baseUrl = "https://example.test/v1",
        model = "claude-haiku-5.5",
    )

    // --- defaultsFor ---------------------------------------------------------

    @Test
    fun defaultsDoNotInventMaxTokensWhenCatalogIsSilent() {
        val model = LocalModelInfo(id = "claude-haiku-5.5", maxOutputTokens = null)

        val settings = LocalTurnSettings.defaultsFor(model, profile)

        assertNull("каталог молчит — потолок оставляем провайдеру", settings.maxTokens)
        assertEquals(SettingsSource.DEFAULT, settings.source)
        assertNull("top_p выдумывать нельзя: это положение ползунка, а не режим модели", settings.topP)
        assertNull(settings.topK)
        assertNull(settings.seed)
        assertEquals("стоп-последовательности по умолчанию пусты", emptyList<String>(), settings.stop)
    }

    @Test
    fun defaultsTreatZeroMaxOutputAsNotReported() {
        // Ноль — не «ответить нельзя», а «поле не сообщено»: отправлять max_tokens = 0
        // значит получить отказ на весь запрос.
        val model = LocalModelInfo(id = "m", maxOutputTokens = 0)

        val settings = LocalTurnSettings.defaultsFor(model, profile)

        assertNull(settings.maxTokens)
        assertEquals(SettingsSource.DEFAULT, settings.source)
    }

    @Test
    fun catalogMaxOutputWinsOverProfile() {
        val model = LocalModelInfo(id = "m", maxOutputTokens = 128_000)
        val tuned = profile.copy(temperature = 0.3, maxTokens = 4_096)

        val settings = LocalTurnSettings.defaultsFor(model, tuned)

        assertEquals(128_000, settings.maxTokens)
        assertEquals("температура профиля — единственный источник, пока человек не задал свою", 0.3, settings.temperature!!, 0.0)
        assertEquals(SettingsSource.MODEL, settings.source)
    }

    @Test
    fun profileMaxTokensSurvivesWhenModelIsUnknown() {
        val tuned = profile.copy(maxTokens = 4_096)

        val settings = LocalTurnSettings.defaultsFor(null, tuned)

        assertEquals("старый профиль продолжает работать, пока нет настроек хода", 4_096, settings.maxTokens)
        assertEquals(SettingsSource.DEFAULT, settings.source)
    }

    // --- merge ---------------------------------------------------------------

    @Test
    fun mergeOverridesOnlyFieldsThatAreSet() {
        val base = LocalTurnSettings(
            temperature = 0.3,
            topP = 0.9,
            maxTokens = 4_096,
            stop = listOf("\n\n"),
            source = SettingsSource.MODEL,
        )
        val session = LocalTurnSettings(temperature = 1.2)

        val merged = base.merge(session)

        assertEquals(1.2, merged.temperature!!, 0.0)
        assertEquals("не заданное сессией поле остаётся дефолтом модели", 0.9, merged.topP!!, 0.0)
        assertEquals(4_096, merged.maxTokens)
        assertEquals("пустой stop сессии не стирает настройку модели", listOf("\n\n"), merged.stop)
        assertEquals(SettingsSource.SESSION, merged.source)
    }

    @Test
    fun mergeWithNullChangesNothing() {
        val base = LocalTurnSettings(temperature = 0.3, source = SettingsSource.MODEL)

        assertEquals(base, base.merge(null))
    }

    @Test
    fun mergeTakesSessionStopWhenGiven() {
        val base = LocalTurnSettings(stop = listOf("\n\n"))
        val session = LocalTurnSettings(stop = listOf("###", "</answer>"))

        assertEquals(listOf("###", "</answer>"), base.merge(session).stop)
    }

    // --- мета сессии ---------------------------------------------------------

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val settings = LocalTurnSettings(
            temperature = 0.7,
            topP = 0.95,
            topK = 40,
            minP = 0.05,
            presencePenalty = 0.1,
            frequencyPenalty = -0.2,
            repetitionPenalty = 1.05,
            seed = 42,
            maxTokens = 2_048,
            // Экранирование обязано пережить круг: стоп-последовательность со кавычкой и
            // переводом строки — обычное дело (пустая строка как разделитель абзацев).
            stop = listOf("\n\n", "«стоп»", "quote\"inside"),
            reasoningEffort = "low",
            source = SettingsSource.SESSION,
        )

        val restored = LocalTurnSettings.fromJson(settings.toJson())

        assertNotNull("свой же JSON обязан читаться", restored)
        assertEquals(settings, restored)
    }

    @Test
    fun jsonRoundTripKeepsDefaultsAndSource() {
        // Пустые настройки — тоже данные: без поля source разбор не отличил бы их от мусора
        // в мете, и объект сериализовался бы в `{}`.
        val settings = LocalTurnSettings()

        val restored = LocalTurnSettings.fromJson(settings.toJson())

        assertEquals(settings, restored)
    }

    @Test
    fun brokenJsonGivesNullNotException() {
        val broken = listOf(
            null,
            "",
            "   ",
            "{",
            "{\"temperature\":",
            "не json",
            "[]",
            "null",
            // Объект без единого знакомого поля — не «настройки по умолчанию», а чужие
            // данные в той же мете: вернуть по ним пустые настройки значит соврать.
            "{}",
            "{\"foo\":1}",
        )
        for (raw in broken) {
            assertNull("битый ввод не должен давать настройки: «$raw»", LocalTurnSettings.fromJson(raw))
        }
    }

    @Test
    fun unknownSourceFallsBackToDefault() {
        val restored = LocalTurnSettings.fromJson("{\"temperature\":0.5,\"source\":\"SOMETHING_NEW\"}")

        assertNotNull(restored)
        assertEquals(0.5, restored!!.temperature!!, 0.0)
        assertEquals(
            "новое значение source из будущей версии не должно ронять чтение",
            SettingsSource.DEFAULT,
            restored.source,
        )
    }
}
