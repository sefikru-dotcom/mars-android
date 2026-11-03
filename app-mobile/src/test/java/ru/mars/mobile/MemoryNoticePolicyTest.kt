package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Индикатор «Запомнил: …»: что показать и когда убрать.
 *
 * Проверка §согласована с фактическим ответом инструмента (§ — не символ, а акцент):
 * успех приходит строкой «Запомнил: <факт>», любой отказ — другой строкой. Подсказка
 * «запомнил» на неудаче хуже её отсутствия: человек решил бы, что факт сохранён, и не
 * повторил бы просьбу.
 */
class MemoryNoticePolicyTest {

    @Test
    fun `успешная запись даёт подсказку`() {
        val notice = MemoryNoticePolicy.fromToolResult("Запомнил: Владельца зовут Игорь", 1000L)
        assertEquals("Владельца зовут Игорь", notice?.text)
        assertEquals(1000L, notice?.at)
        assertTrue(notice!!.isVisible)
    }

    @Test
    fun `края срезаются`() {
        val notice = MemoryNoticePolicy.fromToolResult("Запомнил: Владельца зовут Игорь\n", 1000L)
        assertEquals("Владельца зовут Игорь", notice?.text)
    }

    @Test
    fun `все виды отказа не дают подсказки`() {
        // Четыре причины отказа MemoryStore плюс отказы валидации инструмента.
        assertNull(
            MemoryNoticePolicy.fromToolResult(
                "В память не записано: пустая запись: нечего сохранять", 1000L,
            ),
        )
        assertNull(
            MemoryNoticePolicy.fromToolResult(
                "В память не записано: не удалось записать память на диск", 1000L,
            ),
        )
        assertNull(
            MemoryNoticePolicy.fromToolResult(
                "Не указан обязательный аргумент text — нечего запоминать.", 1000L,
            ),
        )
        assertNull(
            MemoryNoticePolicy.fromToolResult(
                "Факт слишком длинный: 2100 символов при пределе 2000.", 1000L,
            ),
        )
    }

    @Test
    fun `пустые строки и пустой факт не дают подсказки`() {
        assertNull(MemoryNoticePolicy.fromToolResult("", 1000L))
        assertNull(MemoryNoticePolicy.fromToolResult("   \n\t ", 1000L))
        assertNull(MemoryNoticePolicy.fromToolResult("Запомнил: ", 1000L))
        assertNull(MemoryNoticePolicy.fromToolResult("Запомнил: \n  ", 1000L))
    }

    @Test
    fun `префикс обязан стоять в начале строки`() {
        // Ответ модели может цитировать инструмент — цитата не должна выглядеть
        // как состоявшаяся запись.
        assertNull(MemoryNoticePolicy.fromToolResult("запомнил: Владельца зовут Игорь", 1000L))
        assertNull(MemoryNoticePolicy.fromToolResult("Запомнил:Владельца зовут Игорь", 1000L))
        assertNull(MemoryNoticePolicy.fromToolResult("Модель сказала: Запомнил: что-то", 1000L))
    }

    @Test
    fun `показ добавляет префикс и не обрезает короткое`() {
        assertEquals(
            "Запомнил: Владельца зовут Игорь",
            MemoryNoticePolicy.render(MemoryNotice("Владельца зовут Игорь", 0L)),
        )
    }

    @Test
    fun `длинный факт обрезается с многоточием`() {
        val rendered = MemoryNoticePolicy.render(MemoryNotice("а".repeat(200), 0L))
        assertTrue(rendered.endsWith("…"))
        assertEquals(MemoryNoticePolicy.MAX_CHARS + 1, rendered.length)
        assertEquals("Запомнил: " + "а".repeat(110), rendered.dropLast(1))
    }

    @Test
    fun `граница обрезки ровно по лимиту`() {
        val exact = MemoryNoticePolicy.render(MemoryNotice("б".repeat(110), 0L))
        assertEquals(MemoryNoticePolicy.MAX_CHARS, exact.length)
        assertFalse(exact.endsWith("…"))

        val over = MemoryNoticePolicy.render(MemoryNotice("б".repeat(111), 0L))
        assertTrue(over.endsWith("…"))
    }

    @Test
    fun `пустой факт не рисуется`() {
        assertEquals("", MemoryNoticePolicy.render(MemoryNotice("   ", 0L)))
    }

    @Test
    fun `истекает ровно на границе`() {
        val notice = MemoryNotice("факт", 1000L)
        assertFalse(MemoryNoticePolicy.isExpired(notice, 61_000L - 1))
        assertTrue(MemoryNoticePolicy.isExpired(notice, 61_000L))
        assertTrue(MemoryNoticePolicy.isExpired(notice, 61_001L))
        // Часы назад (сменили часовой пояс) — не повод гасить только что показанное.
        assertFalse(MemoryNoticePolicy.isExpired(notice, 0L))
    }

    @Test
    fun `подпись счётчика склоняется`() {
        assertEquals("", MemoryNoticePolicy.countHint(-1))
        assertEquals("всего 0 фактов", MemoryNoticePolicy.countHint(0))
        assertEquals("всего 1 факт", MemoryNoticePolicy.countHint(1))
        assertEquals("всего 2 факта", MemoryNoticePolicy.countHint(2))
        assertEquals("всего 4 факта", MemoryNoticePolicy.countHint(4))
        assertEquals("всего 5 фактов", MemoryNoticePolicy.countHint(5))
        assertEquals("всего 11 фактов", MemoryNoticePolicy.countHint(11))
        assertEquals("всего 21 факт", MemoryNoticePolicy.countHint(21))
        assertEquals("всего 22 факта", MemoryNoticePolicy.countHint(22))
        assertEquals("всего 111 фактов", MemoryNoticePolicy.countHint(111))
    }
}
