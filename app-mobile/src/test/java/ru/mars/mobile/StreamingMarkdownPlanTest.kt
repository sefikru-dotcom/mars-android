package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * План инкрементальной отрисовки markdown-секции: на кадре стрима пересобирается только
 * хвост, префикс уже нарисованных единиц переиспользуется. Плюс гарантия, что
 * переиспользованный префикс состоит ИСКЛЮЧИТЕЛЬНО из равных единиц (иначе на экране
 * остался бы устаревший view).
 */
class StreamingMarkdownPlanTest {

    private fun text(vararg lines: String): ContentBlock =
        ContentBlock.Text(lines.map { RichLine.Normal(listOf(InlineSpan.Normal(it))) })

    @Test
    fun `prose block разворачивается по строке на единицу`() {
        val units = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три")))

        assertEquals(3, units.size)
        assertEquals(MarkdownUnit.Line(0, RichLine.Normal(listOf(InlineSpan.Normal("раз")))), units[0])
        assertEquals(MarkdownUnit.Line(0, RichLine.Normal(listOf(InlineSpan.Normal("три")))), units[2])
    }

    @Test
    fun `code и table — по одной единице на блок`() {
        val blocks = listOf(
            text("до"),
            ContentBlock.Code("kotlin", "val a = 1"),
            ContentBlock.Table(headers = listOf(listOf(InlineSpan.Normal("h"))), rows = emptyList()),
            text("после"),
        )

        val units = StreamingMarkdownPlan.units(blocks)

        assertEquals(4, units.size)
        assertTrue(units[0] is MarkdownUnit.Line)
        assertEquals(MarkdownUnit.Code(1, ContentBlock.Code("kotlin", "val a = 1")), units[1])
        assertTrue(units[2] is MarkdownUnit.Table)
        assertEquals(3, units[3].blockIndex)
    }

    @Test
    fun `пустой текст не даёт единиц`() {
        assertTrue(StreamingMarkdownPlan.units(emptyList()).isEmpty())
    }

    @Test
    fun `дописывание хвоста переиспользует все строки кроме последней`() {
        val previous = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три")))
        val current = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три", "четыре")))

        // Последняя нарисованная строка («три») может дорасти следующим чанком — её
        // пересобираем всегда (TAIL_GUARD), остальные две переиспользуются.
        assertEquals(2, StreamingMarkdownPlan.reusablePrefix(previous, current))
    }

    @Test
    fun `изменение середины обрезает префикс по первой различающейся строке`() {
        val previous = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три", "четыре")))
        val current = StreamingMarkdownPlan.units(listOf(text("раз", "два!", "три", "четыре")))

        // Общая часть — две единицы («раз», «два»), но «два» была последней нарисованной
        // строкой, а её TAIL_GUARD пересобирает всегда: переиспользуется только «раз».
        assertEquals(0, StreamingMarkdownPlan.reusablePrefix(previous, current))
    }

    @Test
    fun `строка ставшая заголовком пересобирается, а не переиспользуется`() {
        val previous = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три")))
        val current = StreamingMarkdownPlan.units(
            listOf(
                text("раз"),
                ContentBlock.Text(listOf(RichLine.Header(2, listOf(InlineSpan.Normal("два"))), RichLine.Normal(listOf(InlineSpan.Normal("три"))))),
            )
        )

        // «два» из Normal стала Header — единица не равна, дальше переиспользовать нельзя.
        assertEquals(0, StreamingMarkdownPlan.reusablePrefix(previous, current))
    }

    @Test
    fun `закрытие code-фенса превращает хвост в отдельный блок`() {
        val previous = StreamingMarkdownPlan.units(
            listOf(text("итог:"), ContentBlock.Code("kt", "val a = 1"))
        )
        val current = StreamingMarkdownPlan.units(
            listOf(text("итог:"), ContentBlock.Code("kt", "val a = 1"), text("готово"))
        )

        // Code-блок тот же, но в предыдущем кадре он был последней единицей — его
        // пересобираем (TAIL_GUARD); прозаическая строка до него переиспользуется.
        assertEquals(1, StreamingMarkdownPlan.reusablePrefix(previous, current))
    }

    @Test
    fun `сокращение списка не переиспользует ничего лишнего`() {
        val previous = StreamingMarkdownPlan.units(listOf(text("раз", "два", "три")))
        val current = StreamingMarkdownPlan.units(listOf(text("раз")))

        assertEquals(0, StreamingMarkdownPlan.reusablePrefix(previous, current))
    }

    @Test
    fun `переиспользованный префикс всегда состоит из равных единиц`() {
        val previous = StreamingMarkdownPlan.units(listOf(text("а", "б", "в", "г")))
        val current = StreamingMarkdownPlan.units(listOf(text("а", "б", "в2", "г")))

        val prefix = StreamingMarkdownPlan.reusablePrefix(previous, current)

        for (index in 0 until prefix) {
            assertEquals("единица $index должна быть равна", previous[index], current[index])
        }
        assertTrue(prefix < current.size)
    }
}
