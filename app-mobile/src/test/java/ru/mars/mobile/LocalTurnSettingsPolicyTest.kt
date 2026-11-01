package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalTurnSettings
import ru.mars.network.local.SamplerParam
import ru.mars.network.local.SettingsSource

/**
 * Тесты чистой логики шторки «Параметры хода».
 *
 * Проверяем ровно те места, где ошибка не видна глазом на телефоне: подпись состояния,
 * перевод «пустое поле → провайдер сам», границы и то, что правка одного параметра не
 * перетаскивает соседние. Формально это регрессия на два реальных сценария: «человек
 * стёр поле — ушло ли поле из запроса» и «человек поправил температуру — не сбросились
 * ли стоп-последовательности и уровень размышлений».
 */
class LocalTurnSettingsPolicyTest {

    /** Источник, отличный от MODEL, — чтобы сброс было видно. Если он один, тест слабее. */
    private val otherSource: SettingsSource =
        SettingsSource.entries.firstOrNull { it != SettingsSource.MODEL } ?: SettingsSource.MODEL

    private fun row(
        param: SamplerParam = SamplerParam.TEMPERATURE,
        value: Double? = null,
        providerDefault: String? = null,
        min: Double = 0.0,
        max: Double = 2.0,
        step: Double = 0.1,
    ) = LocalTurnSettingsPolicy.ParamRow(
        param = param,
        label = "температура",
        hint = "насколько свободно модель выбирает слова",
        min = min,
        max = max,
        step = step,
        value = value,
        providerDefault = providerDefault,
    )

    /** Настройки, в которых заполнено ВСЁ: так видно любую лишнюю правку. */
    private fun full(): LocalTurnSettings = LocalTurnSettings(
        temperature = 1.3,
        topP = 0.9,
        topK = 40,
        minP = 0.05,
        presencePenalty = 0.1,
        frequencyPenalty = 0.2,
        repetitionPenalty = 1.1,
        seed = 42,
        maxTokens = 512,
        stop = listOf("</s>"),
        reasoningEffort = "low",
        source = otherSource,
    )

    /** Имена полей, которые различаются: «меняет ровно одно поле» проверяется списком. */
    private fun differingFields(before: LocalTurnSettings, after: LocalTurnSettings): List<String> =
        buildList {
            if (before.temperature != after.temperature) add("temperature")
            if (before.topP != after.topP) add("topP")
            if (before.topK != after.topK) add("topK")
            if (before.minP != after.minP) add("minP")
            if (before.presencePenalty != after.presencePenalty) add("presencePenalty")
            if (before.frequencyPenalty != after.frequencyPenalty) add("frequencyPenalty")
            if (before.repetitionPenalty != after.repetitionPenalty) add("repetitionPenalty")
            if (before.seed != after.seed) add("seed")
            if (before.maxTokens != after.maxTokens) add("maxTokens")
            if (before.stop != after.stop) add("stop")
            if (before.reasoningEffort != after.reasoningEffort) add("reasoningEffort")
            if (before.source != after.source) add("source")
        }

    // --- Подпись состояния --------------------------------------------------------------------

    @Test fun badgeNullMeansProviderDecides() {
        assertEquals(
            LocalTurnSettingsPolicy.PROVIDER_SELF,
            LocalTurnSettingsPolicy.badge(row(value = null)),
        )
    }

    @Test fun badgeShowsFormattedValue() {
        assertEquals("1,3", LocalTurnSettingsPolicy.badge(row(value = 1.3)))
    }

    @Test fun badgeShowsAppDefaultWhenValueEqualsDefault() {
        // Дефолт приходит уже отформатированным той же функцией, что и значение.
        assertEquals(
            LocalTurnSettingsPolicy.APP_DEFAULT,
            LocalTurnSettingsPolicy.badge(row(value = 0.7, providerDefault = "0,7")),
        )
    }

    @Test fun badgeKeepsWholeNumberWithoutFraction() {
        assertEquals(
            "40",
            LocalTurnSettingsPolicy.badge(
                row(param = SamplerParam.TOP_K, value = 40.0, max = 100.0, step = 1.0),
            ),
        )
    }

    // --- Валидация ---------------------------------------------------------------------------

    @Test fun validateEmptyStringIsProviderSelf() {
        val result = LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        // Пробелы — то же пустое поле: человек стёр число, а не ввёл « ».
        assertNull(LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "   ").getOrNull())
    }

    @Test fun validateAcceptsCommaAndDotAsDecimalSeparator() {
        assertEquals(
            1.3,
            LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "1,3").getOrNull()!!,
            1e-9,
        )
        assertEquals(
            1.3,
            LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "1.3").getOrNull()!!,
            1e-9,
        )
    }

    @Test fun validateRejectsGarbageWithRussianMessage() {
        val result = LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "abc")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("не число", ignoreCase = true))
    }

    @Test fun validateRejectsValueOutsideRange() {
        val result = LocalTurnSettingsPolicy.validate(SamplerParam.TEMPERATURE, "9999")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("Допустимо"))
    }

    @Test fun validateRejectsFractionForIntegerParam() {
        val result = LocalTurnSettingsPolicy.validate(
            row(param = SamplerParam.TOP_K, max = 100.0, step = 1.0),
            "2,5",
        )
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("целое", ignoreCase = true),
        )
    }

    @Test fun validateRowRespectsModelTokenCeiling() {
        // Потолок из каталога модели (4096) обязан отсечь 8192 до запроса к провайдеру.
        val capped = row(param = SamplerParam.MAX_TOKENS, min = 1.0, max = 4096.0, step = 1.0)
        assertTrue(LocalTurnSettingsPolicy.validate(capped, "8192").isFailure)
        assertEquals(
            4096.0,
            LocalTurnSettingsPolicy.validate(capped, "4096").getOrNull()!!,
            1e-9,
        )
    }

    // --- Правка одного параметра --------------------------------------------------------------

    @Test fun applyChangesExactlyOneField() {
        val before = full()
        val after = LocalTurnSettingsPolicy.apply(row(value = 1.3), 0.2, before)
        assertEquals(listOf("temperature"), differingFields(before, after))
        assertEquals(0.2, after.temperature!!, 1e-9)
    }

    @Test fun applyNullClearsOnlyThatFieldAndKeepsRest() {
        val before = full()
        val after = LocalTurnSettingsPolicy.apply(
            row(param = SamplerParam.TOP_P, value = 0.9, max = 1.0),
            null,
            before,
        )
        assertEquals(listOf("topP"), differingFields(before, after))
        assertNull(after.topP)
        assertEquals(before.stop, after.stop)
        assertEquals(before.reasoningEffort, after.reasoningEffort)
    }

    @Test fun applyRoundsFractionIntoIntegerField() {
        val after = LocalTurnSettingsPolicy.apply(
            row(param = SamplerParam.TOP_K, max = 100.0, step = 1.0),
            41.9,
            full(),
        )
        assertEquals(42, after.topK)
    }

    // --- Сброс к модели -----------------------------------------------------------------------

    @Test fun resetToModelDefaultsClearsEverything() {
        val reset = LocalTurnSettingsPolicy.resetToModelDefaults(full())
        assertNull(reset.temperature)
        assertNull(reset.topP)
        assertNull(reset.topK)
        assertNull(reset.minP)
        assertNull(reset.presencePenalty)
        assertNull(reset.frequencyPenalty)
        assertNull(reset.repetitionPenalty)
        assertNull(reset.seed)
        assertNull(reset.maxTokens)
        assertNull(reset.reasoningEffort)
        assertTrue(reset.stop.isEmpty())
        assertEquals(SettingsSource.MODEL, reset.source)
    }

    // --- Строки и стоп-лист -------------------------------------------------------------------

    @Test fun rowsCapMaxTokensByModelWindow() {
        val capped = LocalTurnSettingsPolicy.rows(full(), modelMaxTokens = 4096)
            .first { it.param == SamplerParam.MAX_TOKENS }
        assertEquals(4096.0, capped.max, 1e-9)
        // Без каталога потолок шире: выдумывать окно модели нельзя.
        val open = LocalTurnSettingsPolicy.rows(full(), modelMaxTokens = null)
            .first { it.param == SamplerParam.MAX_TOKENS }
        assertTrue(open.max >= capped.max)
    }

    @Test fun rowsHaveNoStopRowBecauseStopsLiveInTheirOwnBlock() {
        assertTrue(
            LocalTurnSettingsPolicy.rows(full(), modelMaxTokens = null)
                .none { it.param == SamplerParam.STOP },
        )
    }

    @Test fun addStopTrimsSkipsDuplicatesAndCapsList() {
        val one = LocalTurnSettingsPolicy.addStop(emptyList(), "  </s>  ")
        assertEquals(listOf("</s>"), one)
        assertEquals(one, LocalTurnSettingsPolicy.addStop(one, "</s>"))
        assertEquals(one, LocalTurnSettingsPolicy.addStop(one, "   "))
        var stops: List<String> = emptyList()
        repeat(LocalTurnSettingsPolicy.MAX_STOPS + 3) { index ->
            stops = LocalTurnSettingsPolicy.addStop(stops, "stop-$index")
        }
        assertEquals(LocalTurnSettingsPolicy.MAX_STOPS, stops.size)
    }
}
