package ru.mars.network.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица сэмпсинга: границы, правило «дефолт не отправляем» и исключение для [SamplerParams.ALWAYS_SENT].
 *
 * Тесты держат два решения, которые легко сломать правкой «на глаз»:
 *  - приведение к границам и шагу идёт ДО сравнения с дефолтом — иначе значение с
 *    плавающей пылью (`0.9999999999`) считалось бы заданным и уезжало на провод;
 *  - `null` и «равно дефолту приложения» одинаково означают «решает провайдер», но
 *    `temperature`/`top_p` из этого правила исключены: наш дефолт 1.0 — наше решение, и
 *    промолчать о нём значит получить у посредника не тот режим, что на ползунке.
 */
class SamplerParamsTest {

    @Test
    fun clampStopsAtBoundaries() {
        assertEquals("ниже нуля температуры не бывает", 0.0, SamplerParams.clamp(SamplerParam.TEMPERATURE, -0.4), 0.0)
        assertEquals("выше двух — тоже", 2.0, SamplerParams.clamp(SamplerParam.TEMPERATURE, 3.5), 0.0)
        assertEquals(-2.0, SamplerParams.clamp(SamplerParam.PRESENCE_PENALTY, -9.0), 0.0)
        assertEquals(2.0, SamplerParams.clamp(SamplerParam.FREQUENCY_PENALTY, 9.0), 0.0)
        assertEquals("top_k начинается с единицы: ноль вариантов — недопустимый запрос", 1.0, SamplerParams.clamp(SamplerParam.TOP_K, 0.0), 0.0)
        assertEquals(128.0, SamplerParams.clamp(SamplerParam.TOP_K, 500.0), 0.0)
        assertEquals("max_tokens меньше одного токена не бывает", 1.0, SamplerParams.clamp(SamplerParam.MAX_TOKENS, 0.0), 0.0)
        assertEquals(200_000.0, SamplerParams.clamp(SamplerParam.MAX_TOKENS, 1_000_000.0), 0.0)
    }

    @Test
    fun clampRoundsToStep() {
        assertEquals("шаг 0.01 округляет до двух знаков", 0.78, SamplerParams.clamp(SamplerParam.TEMPERATURE, 0.777), 0.0)
        assertEquals("пыль после умножения не должна уезжать в тело", 0.7, SamplerParams.clamp(SamplerParam.TEMPERATURE, 0.7), 0.0)
        assertEquals("целые параметры не дробятся", 41.0, SamplerParams.clamp(SamplerParam.TOP_K, 40.6), 0.0)
        assertEquals(2000.0, SamplerParams.clamp(SamplerParam.SEED, 1999.6), 0.0)
    }

    @Test
    fun clampReplacesUnsendableNumbers() {
        // NaN/бесконечность в JSON невыразимы: тело с ними провайдер отвергнет целиком, а не
        // «поправит» одно поле. Поэтому у параметра с дефолтом берётся дефолт, у остальных —
        // нижняя граница, и запрос остаётся отправляемым.
        assertEquals(1.0, SamplerParams.clamp(SamplerParam.TEMPERATURE, Double.NaN), 0.0)
        assertEquals(0.0, SamplerParams.clamp(SamplerParam.PRESENCE_PENALTY, Double.NEGATIVE_INFINITY), 0.0)
        assertEquals("у top_k дефолта нет — нижняя граница", 1.0, SamplerParams.clamp(SamplerParam.TOP_K, Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun nullIsAlwaysLeftToProvider() {
        for (param in SamplerParam.entries) {
            assertTrue(
                "не заданное человеком поле не должно уходить на провод: $param",
                SamplerParams.isLeftToProvider(param, null),
            )
        }
    }

    @Test
    fun appDefaultIsLeftToProvider() {
        assertEquals("дефолт presence_penalty — 0.0", 0.0, SamplerParam.PRESENCE_PENALTY.defaultValue!!, 0.0)
        assertTrue(
            "значение, равное дефолту приложения, ничего не сообщает о намерении человека",
            SamplerParams.isLeftToProvider(SamplerParam.PRESENCE_PENALTY, 0.0),
        )
        assertTrue(SamplerParams.isLeftToProvider(SamplerParam.FREQUENCY_PENALTY, 0.0))
        assertFalse("у top_k дефолта нет — любое значение задано человеком", SamplerParams.isLeftToProvider(SamplerParam.TOP_K, 40.0))
    }

    @Test
    fun nonDefaultValueIsSent() {
        assertFalse(SamplerParams.isLeftToProvider(SamplerParam.PRESENCE_PENALTY, 0.5))
        assertFalse(SamplerParams.isLeftToProvider(SamplerParam.TEMPERATURE, 0.2))
        assertFalse(SamplerParams.isLeftToProvider(SamplerParam.MAX_TOKENS, 4096.0))
        // Значение с плавающей пылью приводится к шагу и только потом сравнивается:
        // иначе «почти дефолт» уезжал бы на провод как осознанная настройка, хотя человек
        // ползунок не двигал.
        assertTrue(
            "0.0000000001 после приведения к шагу 0.01 — это ровно дефолт",
            SamplerParams.isLeftToProvider(SamplerParam.PRESENCE_PENALTY, 0.0000000001),
        )
        assertFalse(
            "0.9999999999 приводится к 1.0, а это уже заданное значение",
            SamplerParams.isLeftToProvider(SamplerParam.PRESENCE_PENALTY, 0.9999999999),
        )
    }

    @Test
    fun alwaysSentIgnoresDefault() {
        assertEquals(
            "набор обязательных полей должен совпадать с пометками таблицы",
            setOf(SamplerParam.TEMPERATURE, SamplerParam.TOP_P, SamplerParam.MAX_TOKENS),
            SamplerParams.ALWAYS_SENT,
        )
        for (param in SamplerParams.ALWAYS_SENT) {
            assertTrue("$param обязан помечаться в таблице", param.alwaysSent)
            if (param.defaultValue != null) {
                assertFalse(
                    "$param в дефолте всё равно уходит: наш дефолт может отличаться от дефолта посредника",
                    SamplerParams.isLeftToProvider(param, param.defaultValue),
                )
            }
        }
        // max_tokens — особый случай: своего дефолта нет, «всегда» значит «как только задан».
        assertTrue(SamplerParams.isLeftToProvider(SamplerParam.MAX_TOKENS, null))
        assertFalse(SamplerParams.isLeftToProvider(SamplerParam.MAX_TOKENS, 512.0))
    }

    @Test
    fun formatValueSpeaksToHuman() {
        assertEquals("провайдер сам", SamplerParams.formatValue(SamplerParam.TEMPERATURE, null))
        assertEquals("случайный", SamplerParams.formatValue(SamplerParam.SEED, null))
        assertEquals("1.0", SamplerParams.formatValue(SamplerParam.TEMPERATURE, 1.0))
        assertEquals("0.7", SamplerParams.formatValue(SamplerParam.TEMPERATURE, 0.7))
        assertEquals("0.0", SamplerParams.formatValue(SamplerParam.PRESENCE_PENALTY, 0.0))
        assertEquals("40", SamplerParams.formatValue(SamplerParam.TOP_K, 40.0))
        // Формат тоже проходит через приведение: показать «9» у температуры, которую
        // провайдер получит как «2», значит соврать человеку о том, что уйдёт.
        assertEquals("2.0", SamplerParams.formatValue(SamplerParam.TEMPERATURE, 9.0))
    }

    @Test
    fun tableCoversEveryOpenAiCompatibleField() {
        assertEquals(
            "выпавший параметр молча перестаёт настраиваться — это должен ловить тест",
            setOf(
                "temperature", "top_p", "top_k", "min_p", "presence_penalty",
                "frequency_penalty", "repetition_penalty", "seed", "max_tokens", "stop",
            ),
            SamplerParam.entries.map { it.wireName }.toSet(),
        )
        assertEquals("фиктивное поле не должно появиться в таблице", 10, SamplerParam.entries.size)
        assertNull("у stop нет числового дефолта", SamplerParam.STOP.defaultValue)
        assertEquals(SamplerKind.STRING_LIST, SamplerParam.STOP.kind)
    }
}
