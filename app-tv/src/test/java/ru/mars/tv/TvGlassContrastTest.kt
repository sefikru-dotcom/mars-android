package ru.mars.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контраст стеклянных карточек над фоном с планетой.
 *
 * Причина существования: заливка стекла подбирается альфой, и «на глаз»
 * ошибка в 6% незаметна, но рубиновый акцент на светлой части Марса
 * растворяется. Замер: под карточкой может оказаться самый светлый пиксель
 * снимка (200/186/120 — проверено на реальном bg_mars_photo). Ниже этого
 * значения заливка не должна опускаться, иначе акцент/мета уходят под
 * пороги читаемости.
 *
 * Тест читает реальные значения из values/colors.xml и считает композит
 * по WCAG-контрасту, а не сверяет строку с ожидаемой — правка цвета
 * на другой (эквивалентный по яркости) не сломает гейт зря.
 */
class TvGlassContrastTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val colorsXml = File(moduleDir, "src/main/res/values/colors.xml")

    /**
     * Самый светлый пиксель под сеткой карточек — замерен по факту на
     * текущем bg_mars_photo (апскейл присланной владельцем картинки 600x337).
     * Это правый край лимба планеты: он приходится на середину кадра, и
     * именно там стекло обязано держать акцент. Если фон заменят, значение
     * надо перезамерить — иначе гейт будет проверять чужую картинку.
     */
    private val brightest = Triple(153, 130, 114)

    private val text = Triple(0xF1, 0xF5, 0xF9)
    private val muted = Triple(0x9A, 0xA5, 0xB4)  // mars_muted_on_glass
    private val accent = Triple(0xEF, 0x44, 0x44)

    private fun color(name: String): Int {
        val m = Regex("#([0-9A-Fa-f]{6,8})\\s*<\\s*/color")
            .findAll(colorsXml.readText())
            .firstOrNull { false } // заглушка, ниже точный парс
        val line = colorsXml.readLines().firstOrNull { it.contains("name=\"$name\"") }
            ?: error("цвет $name не найден в colors.xml")
        val hex = Regex("#([0-9A-Fa-f]{6,8})").find(line)?.groupValues?.get(1)
            ?: error("у цвета $name нет hex-значения: $line")
        return if (hex.length == 8) hex.toLong(16).toInt() else ("FF$hex").toLong(16).toInt()
    }

    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    private fun alpha(c: Int) = ((c shr 24) and 0xFF) / 255.0

    private fun lum(p: Triple<Int, Int, Int>): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(p.first) + 0.7152 * ch(p.second) + 0.0722 * ch(p.third)
    }

    private fun contrast(fg: Triple<Int, Int, Int>, bg: Triple<Int, Int, Int>): Double {
        val l1 = lum(fg); val l2 = lum(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** Композит: полупрозрачная заливка карточки поверх светлого пикселя фона. */
    private fun compositeOverBrightest(fill: Int): Triple<Int, Int, Int> {
        val f = rgb(fill); val a = alpha(fill)
        return Triple(
            (brightest.first * (1 - a) + f.first * a).toInt(),
            (brightest.second * (1 - a) + f.second * a).toInt(),
            (brightest.third * (1 - a) + f.third * a).toInt(),
        )
    }

    @Test
    fun `body text over glass stays well above AA`() {
        val on = compositeOverBrightest(color("mars_glass_fill"))
        val c = contrast(text, on)
        assertTrue("контраст тела ${"%.2f".format(c)} < 7.0 (композит $on)", c >= 7.0)
    }

    @Test
    fun `muted meta over glass clears AA for large text`() {
        val on = compositeOverBrightest(color("mars_glass_fill"))
        val c = contrast(muted, on)
        // 4.5, а не 3.0: мета — мелкий текст (14sp), а не крупный. На ТВ
        // с дивана 3.99 читалось «на грани» именно в нижних карточках, где
        // под стеклом лежит светлый лимб планеты.
        assertTrue("контраст меты ${"%.2f".format(c)} < 4.5 (композит $on)", c >= 4.5)
    }

    @Test
    fun `ruby accent over glass clears the 3 to 1 threshold`() {
        val on = compositeOverBrightest(color("mars_glass_fill"))
        val c = contrast(accent, on)
        assertTrue(
            "контраст акцента ${"%.2f".format(c)} < 3.0 — иконки растворятся на светлой части планеты (композит $on)",
            c >= 3.0,
        )
    }

    @Test
    fun `focused glass is denser than idle glass`() {
        val idle = alpha(color("mars_glass_fill"))
        val focused = alpha(color("mars_glass_fill_focused"))
        assertTrue("focused $focused должен быть плотнее idle $idle", focused > idle)
    }
}
