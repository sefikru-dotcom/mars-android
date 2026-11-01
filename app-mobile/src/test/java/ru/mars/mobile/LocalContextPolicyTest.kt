package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalUsage

/**
 * Пилюля заполнения контекста для локального чата.
 *
 * Главное, что здесь закрепляется, — **честность индикатора**. Локальный провайдер
 * сообщает `usage` не всегда (модель может не поддерживать `stream_options`, ответ может
 * прийти из кэша), а размер окна известен только после успешного `GET /models`. В обоих
 * случаях правильное поведение — скрыть пилюлю, а не нарисовать «0%»: ноль читается как
 * «контекст пуст», хотя на деле он просто не измерен, и человек принимает решение
 * (уходить в новый чат или нет) по ложному сигналу.
 */
class LocalContextPolicyTest {

    @Test
    fun unknownWindowHidesPill() {
        val view = LocalContextPolicy.view(promptTokens = 12_000, windowTokens = null)
        assertFalse("окно неизвестно — процент выдумывать нельзя", view.visible)
        assertNull(view.percent)
        assertNull(view.text)
    }

    @Test
    fun zeroUsageHidesPill() {
        val view = LocalContextPolicy.view(promptTokens = 0, windowTokens = 200_000)
        assertFalse("замера не было — показывать нечего", view.visible)
        assertNull(view.percent)
    }

    @Test
    fun knownWindowAndUsageShowPercent() {
        val view = LocalContextPolicy.view(promptTokens = 50_000, windowTokens = 200_000)
        assertTrue(view.visible)
        assertEquals(25, view.percent)
        assertEquals(LocalContextLevel.NORMAL, view.level)
        assertNotNull(view.text)
        assertTrue("текст должен содержать процент", view.text!!.contains("25"))
    }

    @Test
    fun levelsFollowServerThresholds() {
        // Пороги те же, что в серверной пилюле (ContextBudgetPolicy): 50 и 75.
        assertEquals(LocalContextLevel.NORMAL, LocalContextPolicy.level(0))
        assertEquals(LocalContextLevel.NORMAL, LocalContextPolicy.level(50))
        assertEquals(LocalContextLevel.WARNING, LocalContextPolicy.level(51))
        assertEquals(LocalContextLevel.WARNING, LocalContextPolicy.level(75))
        assertEquals(LocalContextLevel.CRITICAL, LocalContextPolicy.level(76))
        assertEquals(LocalContextLevel.CRITICAL, LocalContextPolicy.level(100))
    }

    @Test
    fun percentIsCappedAtHundred() {
        // Провайдер может сообщить promptTokens больше сохранённого окна (модель сменили
        // на меньшую). Процент обязан остаться 100, а не уехать в 250 — иначе кольцо
        // ломается, а число выглядит как ошибка.
        val view = LocalContextPolicy.view(promptTokens = 500_000, windowTokens = 200_000)
        assertEquals(100, view.percent)
        assertEquals(LocalContextLevel.CRITICAL, view.level)
    }

    @Test
    fun percentIsNullForNonPositiveWindow() {
        assertNull(LocalContextPolicy.percent(usedTokens = 100, windowTokens = 0))
        assertNull(LocalContextPolicy.percent(usedTokens = 100, windowTokens = -1))
    }

    @Test
    fun thresholdTokensMatchesPercentOfWindow() {
        // Порог 75% от 200k = 150k — то число, которое показывается в деталях.
        assertEquals(150_000, LocalContextPolicy.thresholdTokens(200_000, 75))
        assertEquals(100_000, LocalContextPolicy.thresholdTokens(200_000, 50))
    }

    @Test
    fun detailsTextNamesUnmeasuredState() {
        // Пустой расход: текст обязан сказать, что замера нет, а не печатать нули.
        val text = LocalContextPolicy.detailsText(LocalUsage(), windowTokens = null)
        assertTrue("детали без замера должны объяснять причину", text.isNotBlank())
        assertFalse("нули как факт — ложь", text.contains("0%"))
    }

    @Test
    fun detailsTextShowsUsageWhenMeasured() {
        val usage = LocalUsage(promptTokens = 30_000, completionTokens = 1_200, totalTokens = 31_200)
        val text = LocalContextPolicy.detailsText(usage, windowTokens = 200_000)
        assertTrue("процент заполнения в деталях", text.contains("15"))
        assertTrue("расход должен быть назван", text.isNotBlank())
    }
}
