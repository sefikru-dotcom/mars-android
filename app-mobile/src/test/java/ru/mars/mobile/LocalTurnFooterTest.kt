package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalRole

/**
 * Правила футера хода: «ноль не показывается», «нет данных — нет футера».
 *
 * Проверяется чистая [LocalTurnFooter], а не вьюха: `TextView` без эмулятора не собрать,
 * а вся логика (что рисовать и рисовать ли вовсе) живёт ровно в этой функции.
 */
class LocalTurnFooterTest {

    @Test
    fun `no usage and no interruption means no footer at all`() {
        // Провайдер не прислал `usage`: пустая строка под ответом читалась бы как
        // «здесь что-то не загрузилось», поэтому футера нет вовсе.
        assertNull(LocalTurnFooter.line())
        assertNull(LocalTurnFooter.line(totalTokens = 0, promptTokens = 0, completionTokens = 0))
    }

    @Test
    fun `zero stays zero and is never rendered`() {
        // Окно известно, а расход — нет: «0/200k» врало бы про пустой запрос.
        assertNull(
            LocalTurnFooter.line(
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = 0,
                windowTokens = 200_000,
            )
        )
    }

    @Test
    fun `total tokens render as a compact number`() {
        assertEquals("13.2k ток", LocalTurnFooter.line(totalTokens = 13_200))
        assertEquals("500 ток", LocalTurnFooter.line(totalTokens = 500))
    }

    @Test
    fun `prompt over window appears only when the window is known`() {
        assertEquals(
            "13.2k ток · 12.4k/200k",
            LocalTurnFooter.line(
                promptTokens = 12_400,
                completionTokens = 800,
                totalTokens = 13_200,
                windowTokens = 200_000,
            ),
        )
        // Окно неизвестно — одно число без знаменателя читалось бы как «сколько осталось».
        assertEquals(
            "13.2k ток",
            LocalTurnFooter.line(
                promptTokens = 12_400,
                completionTokens = 800,
                totalTokens = 13_200,
                windowTokens = null,
            ),
        )
        // Нулевое окно — тот же случай «неизвестно».
        assertEquals(
            "13.2k ток",
            LocalTurnFooter.line(totalTokens = 13_200, promptTokens = 12_400, windowTokens = 0),
        )
    }

    @Test
    fun `completion alone is still a fact`() {
        // Провайдер без `total` в потоке: сумма шагов неизвестна, но ответ измерен.
        assertEquals("800 ток", LocalTurnFooter.line(completionTokens = 800))
    }

    @Test
    fun `interrupted answer is marked even without usage`() {
        assertEquals("оборван", LocalTurnFooter.line(interrupted = true))
        assertEquals(
            "13.2k ток · оборван",
            LocalTurnFooter.line(totalTokens = 13_200, interrupted = true),
        )
    }

    @Test
    fun `interrupted marker goes last`() {
        val line = LocalTurnFooter.line(
            promptTokens = 12_400,
            totalTokens = 13_200,
            windowTokens = 200_000,
            interrupted = true,
        )
        assertEquals("13.2k ток · 12.4k/200k · оборван", line)
        assertTrue(line!!.endsWith(LocalTurnFooter.INTERRUPTED))
    }

    @Test
    fun `item overload reads the same fields`() {
        val item = LocalChatItem(
            id = "a-1",
            role = LocalRole.ASSISTANT,
            text = "ответ",
            promptTokens = 12_400,
            completionTokens = 800,
            totalTokens = 13_200,
            interrupted = true,
        )
        assertEquals(
            "13.2k ток · 12.4k/200k · оборван",
            LocalTurnFooter.line(item, windowTokens = 200_000),
        )
        // Восстановленный из истории ответ метрик не несёт — футера нет.
        assertNull(LocalTurnFooter.line(LocalChatItem(id = "a-2", role = LocalRole.ASSISTANT)))
    }

    @Test
    fun `ttft is shown honestly and only when measured`() {
        // Не измеряли — строки нет: ноль означает «не знаем», а не «ноль миллисекунд».
        assertEquals(null, LocalTurnFooter.line(ttftMs = 0))

        assertEquals("первый символ 640мс", LocalTurnFooter.line(ttftMs = 640))
        // Больше секунды — в секундах с одним знаком: «1.8с» читается быстрее «1800мс».
        assertEquals("первый символ 1.8с", LocalTurnFooter.line(ttftMs = 1800))
        // ttft идёт отдельной частью вместе с расходом, а не вместо него.
        assertEquals(
            "1.2k ток · первый символ 900мс",
            LocalTurnFooter.line(totalTokens = 1200, ttftMs = 900),
        )
    }
}
