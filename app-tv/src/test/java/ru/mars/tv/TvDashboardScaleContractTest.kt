package ru.mars.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт плотного дашборда (activity_dashboard.xml).
 *
 * Причина существования: шапка жила «гигантоманией» — часы 48sp были крупнее
 * заголовка страницы (28sp), кегли писались числами мимо шкалы, лента плеера
 * занимала половину ширины, а карточки были плотной заливкой без стекла.
 * Тест держит инварианты, чтобы правка не откатилась:
 *
 *  1) кегль в layout не пишется числом — только AppText.*;
 *  2) часы — ступень Display (40sp), то есть не крупнее иерархии экрана;
 *  3) кнопки-карточки используют стеклянный селектор bg_card_glass (фокус D-pad внутри);
 *  4) сетка и отступы — токены tv_dash_* / tv_gap_*, хардкод dp не остаётся;
 *  5) у заголовка и статуса шапки есть maxLines + ellipsize (без переполнения),
 *     а весовые элементы имеют minWidth=0.
 *
 * JVM-тест: читает исходники модуля с диска (user.dir = каталог app-tv), как
 * TvDesignSystemTest и TvTypeScaleGateTest.
 */
class TvDashboardScaleContractTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val layoutFile = File(moduleDir, "src/main/res/layout/activity_dashboard.xml")
    private val layout: String = layoutFile.readText()

    /**
     * Открывающие теги разметки вместе с атрибутами (закрывающий тег отсекается:
     * после «<» обязан идти буквенный символ, а не «/»). Годится и для
     * самозакрывающихся элементов, и для контейнеров с детьми.
     */
    private val elementTag = Regex("<[A-Za-z]+\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)

    private fun blocks(): List<String> = elementTag.findAll(layout).map { it.value }.toList()

    private fun buttons(): List<String> = blocks().filter { it.startsWith("<Button") }

    /** Блок элемента по id (id может стоять в любом месте списка атрибутов). */
    private fun blockById(id: String): String {
        val hit = blocks().filter { it.contains("@+id/$id\"") }
        assertTrue("в activity_dashboard.xml нет элемента @+id/$id", hit.isNotEmpty())
        assertTrue("id @+id/$id встречается несколько раз", hit.size == 1)
        return hit.single()
    }

    private fun blockByText(text: String): String {
        val hit = blocks().filter { it.contains("android:text=\"$text\"") }
        assertTrue("в activity_dashboard.xml нет элемента с текстом «$text»", hit.size == 1)
        return hit.single()
    }

    @Test
    fun `layout has no raw sp font sizes`() {
        val offenders = Regex("android:textSize=\"[^\"]*\"").findAll(layout).map { it.value }.toList()
        assertTrue("кегль в layout пишется числом, нужен AppText.*: $offenders", offenders.isEmpty())
    }

    @Test
    fun `clock is the Display step and not larger than the page title`() {
        val clock = blockById("time")
        assertTrue(
            "часы (@id/time) должны использовать @style/AppText.Display",
            clock.contains("android:textAppearance=\"@style/AppText.Display\""),
        )
        val title = blockByText("Марс")
        assertTrue(
            "заголовок страницы должен использовать @style/AppText.Headline",
            title.contains("android:textAppearance=\"@style/AppText.Headline\""),
        )
        // Иерархия ступеней: Display(40) > Headline(28) — часы не «гигантомания».
        val dimens = File(moduleDir, "src/main/res/values/dimens.xml").readText()
        val display = sp(dimens, "tv_text_display")
        val headline = sp(dimens, "tv_text_headline")
        assertTrue("Display ($display) должен быть >= Headline ($headline)", display >= headline)
        assertTrue("часы не должны быть крупнее 40sp, было $display", display <= 40)
    }

    @Test
    fun `cards and buttons use the glass selector with a D-pad focus state`() {
        val all = buttons()
        assertTrue("в дашборде должны быть кнопки-карточки, найдено ${all.size}", all.size >= 12)
        val offenders = all.filterNot { it.contains("android:background=\"@drawable/bg_card_glass\"") }
        assertTrue("кнопок без стеклянной карточки: ${offenders.size}", offenders.isEmpty())
        assertFalse(
            "старая плотная заливка bg_focus_card осталась в дашборде",
            layout.contains("@drawable/bg_focus_card"),
        )
        // Сам селектор стекла обязан держать фокус D-pad, иначе карточка не подсвечивается.
        val glass = File(moduleDir, "src/main/res/drawable/bg_card_glass.xml").readText()
        assertTrue("bg_card_glass должен быть селектором", glass.contains("<selector"))
        assertTrue("bg_card_glass без state_focused", glass.contains("state_focused"))
        assertTrue("bg_card_glass без рубинового акцента", glass.contains("mars_accent"))
    }

    @Test
    fun `grid sizes come from dimens and no dp hardcode remains`() {
        // Только кнопки сетки (весовые). Кнопка транспорта в ленте плеера живёт по своим
        // токенам tv_dash_btn_pad_h / tv_gap_* и высоту берёт по контенту.
        val gridButtons = buttons().filter { it.contains("android:layout_weight=\"1\"") }
        assertTrue("кнопок сетки найдено ${gridButtons.size}, ожидалось 12", gridButtons.size == 12)
        gridButtons.forEach { block ->
            val id = Regex("@\\+id/(\\w+)").find(block)?.groupValues?.get(1) ?: "?"
            assertTrue(
                "$id: горизонтальный паддинг не из токена tv_dash_btn_pad_h",
                block.contains("android:paddingStart=\"@dimen/tv_dash_btn_pad_h\"") &&
                    block.contains("android:paddingEnd=\"@dimen/tv_dash_btn_pad_h\""),
            )
            assertTrue(
                "$id: minHeight не из токена tv_dash_btn_min_h",
                block.contains("android:minHeight=\"@dimen/tv_dash_btn_min_h\""),
            )
        }
        // Хардкод dp в отступах/размерах: разрешён только 0dp (веса), плюс match_parent.
        val hardcoded = Regex(
            "android:(layout_margin\\w*|padding\\w*|layout_width|layout_height|minHeight|minWidth)" +
                "=\"(?!0dp)(\\d+)dp\"",
        ).findAll(layout).map { it.value }.toList()
        assertTrue("хардкод dp вместо токенов: $hardcoded", hardcoded.isEmpty())
        assertTrue(
            "сетка должна брать вертикальный ритм из tv_dash_*",
            layout.contains("android:layout_marginTop=\"@dimen/tv_dash_row_gap\"") &&
                layout.contains("android:layout_marginTop=\"@dimen/tv_dash_grid_gap_top\""),
        )
    }

    @Test
    fun `title and header status cannot overflow`() {
        val title = blockByText("Марс")
        assertTrue("заголовок без maxLines", title.contains("android:maxLines=\"1\""))
        assertTrue("заголовок без ellipsize", title.contains("android:ellipsize=\"end\""))

        val status = blockById("headerStatus")
        assertTrue("headerStatus без maxLines", status.contains("android:maxLines=\"1\""))
        assertTrue("headerStatus без ellipsize", status.contains("android:ellipsize=\"end\""))
        assertTrue(
            "headerStatus должен быть ступенью AppText.Label",
            status.contains("android:textAppearance=\"@style/AppText.Label\""),
        )
        // Статус живёт в весовом слоте: без minWidth=0 он разъедет шапку.
        assertTrue("headerStatus без minWidth=0", status.contains("android:minWidth=\"0dp\""))
        val slot = blockById("headerStatusSlot")
        assertTrue("слот статуса должен тянуться по weight", slot.contains("android:layout_weight=\"1\""))
        assertTrue("слот статуса без minWidth=0", slot.contains("android:minWidth=\"0dp\""))
    }

    @Test
    fun `now playing strip spans the full width`() {
        val row = blockById("nowPlayingRow")
        assertTrue(
            "лента плеера должна занимать всю ширину",
            row.contains("android:layout_width=\"match_parent\""),
        )
        val text = blockById("nowPlayingText")
        assertTrue("nowPlayingText должен тянуться по weight", text.contains("android:layout_weight=\"1\""))
        assertTrue("nowPlayingText без minWidth=0", text.contains("android:minWidth=\"0dp\""))
        assertTrue("nowPlayingText без ellipsize", text.contains("android:ellipsize=\"end\""))
        val toggle = blockById("nowPlayingToggle")
        assertTrue(
            "кнопка транспорта должна стоять в конце строки",
            toggle.contains("android:layout_marginStart=\"@dimen/tv_gap_l\""),
        )
    }

    private fun sp(dimens: String, token: String): Int {
        val m = Regex("<dimen name=\"$token\">(\\d+)sp</dimen>").find(dimens)
        assertTrue("нет токена $token в dimens.xml", m != null)
        return m!!.groupValues[1].toInt()
    }
}
