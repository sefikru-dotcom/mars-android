package ru.mars.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Гейт типографической шкалы app-tv.
 *
 * Причина существования: до волны tv-scale в модуле было 16 кеглей
 * (14,15,16,17,18,19,20,21,22,24,26,28,32,34,44,48) с шагом 1sp —
 * это не система, а подбор на глаз. Иерархия 32/21sp давала слабый
 * контраст, и экран читался как однородная каша крупного текста.
 *
 * Тест держит контракт: кегль задаётся ТОЛЬКО через шкалу
 * ([SCALE]) или токен из dimens.xml. Любой новый «подобранный» кегль
 * ломает сборку — так шкала не расползётся обратно.
 */
class TvTypeScaleGateTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val layoutDir = File(moduleDir, "src/main/res/layout")
    private val javaDir = File(moduleDir, "src/main/java")

    /** Разрешённые кегли: ровно шкала styles.xml + micro/display. */
    private val scale = setOf(11, 12, 14, 16, 20, 28, 40)

    private fun layoutFiles(): List<File> =
        layoutDir.listFiles { f -> f.isFile && f.extension == "xml" }?.toList() ?: emptyList()

    private fun kotlinFiles(): List<File> =
        javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `layouts use only scale sizes when a raw textSize is present`() {
        val offenders = mutableListOf<String>()
        val re = Regex("""android:textSize="(\d+)sp"""")
        layoutFiles().forEach { f ->
            re.findAll(f.readText()).forEach { m ->
                val sp = m.groupValues[1].toInt()
                if (sp !in scale) offenders += "${f.name}: ${sp}sp"
            }
        }
        assertTrue("Кегли вне шкалы $scale (используй AppText.*): $offenders", offenders.isEmpty())
    }

    @Test
    fun `kotlin renderers use only scale sizes`() {
        val offenders = mutableListOf<String>()
        val patterns = listOf(
            Regex("""textSize\s*=\s*(\d+(?:\.\d+)?)f"""),
            Regex("""setTextSize\(\s*TypedValue\.COMPLEX_UNIT_SP\s*,\s*(\d+(?:\.\d+)?)f"""),
        )
        kotlinFiles().forEach { f ->
            val text = f.readText()
            patterns.forEach { re ->
                re.findAll(text).forEach { m ->
                    val sp = m.groupValues[1].toFloat()
                    if (sp != sp.toInt().toFloat() || sp.toInt() !in scale) {
                        offenders += "${f.name}: ${sp}f"
                    }
                }
            }
        }
        assertTrue("Кегли вне шкалы $scale в Kotlin: $offenders", offenders.isEmpty())
    }

    @Test
    fun `scale styles exist for every level`() {
        val styles = File(moduleDir, "src/main/res/values/styles.xml").readText()
        listOf("AppText.Display", "AppText.Headline", "AppText.Title",
               "AppText.Body", "AppText.Label", "AppText.Micro", "AppText.Button")
            .forEach { assertTrue("нет стиля $it", styles.contains("name=\"$it\"")) }
    }

    @Test
    fun `dimens define the scale tokens`() {
        val dimens = File(moduleDir, "src/main/res/values/dimens.xml").readText()
        listOf("tv_text_display", "tv_text_headline", "tv_text_title",
               "tv_text_body", "tv_text_label", "tv_text_micro")
            .forEach { assertTrue("нет токена $it", dimens.contains("name=\"$it\"")) }
        listOf("tv_gap_xs", "tv_gap_s", "tv_gap_m", "tv_gap_l", "tv_gap_xl")
            .forEach { assertTrue("нет токена отступов $it", dimens.contains("name=\"$it\"")) }
    }
}
