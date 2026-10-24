package ru.mars.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Бюджет плотности девяти разделов ТВ-клиента.
 *
 * Причина существования: карточки разделов были свёрстаны padding=24dp при кегле
 * 18sp. На 960dp ширины это 48dp чистой пустоты в каждой карточке, а при шести
 * карточках в ряд — почти треть экрана, занятая ничем. Плюс текст шёл сплошняком
 * крупным кеглем (32/24/18), без иерархии.
 *
 * Тест держит контракт волны tv-density:
 *   а) кегль задаётся только шкалой AppText.*, а не числом в layout;
 *   б) карточки берут внутренний отступ из @dimen/tv_card_pad, а не 24dp;
 *   в) телесные TextView ограничены по строкам (иначе длинное имя ломает сетку);
 *   г) заголовки экранов не схлопываются в колонку (minWidth) и не переносятся.
 *
 * Шкала кеглей как таковая проверяется общим TvTypeScaleGateTest — здесь только
 * бюджет плотности этих восемнадцати файлов.
 */
class TvDensityBudgetTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val layoutDir = File(moduleDir, "src/main/res/layout")

    /** Девять экранов разделов (в шапке у каждого заголовок + статус + кнопки). */
    private val screens = listOf(
        "activity_tv_projects", "activity_tv_todos", "activity_tv_cron", "activity_tv_kanban",
        "activity_tv_insights", "activity_tv_spaces", "activity_tv_profiles",
        "activity_tv_memory", "activity_tv_skills",
    )

    /** Девять карточек/строк этих разделов. */
    private val cards = listOf(
        "item_tv_todo", "item_tv_skill", "item_tv_space", "item_tv_profile",
        "item_tv_kanban_card", "item_tv_insight_row", "item_tv_cron_job",
        "item_tv_host_card", "item_tv_service_card",
    )

    /**
     * Документ-тело «Памяти» прокручивается целиком: ограничение строк обрезало бы
     * содержимое секции. Осознанное исключение, зафиксировано и в самом layout.
     */
    private val unboundedText = setOf("bodyText")

    private fun xml(name: String): String = File(layoutDir, "$name.xml").readText()

    private fun all(): List<String> = screens + cards

    private fun textViewBlocks(source: String): List<String> =
        Regex("""<TextView\b.*?/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).map { it.value }.toList()

    private fun idOf(block: String): String? =
        Regex("""android:id="@\+id/(\w+)"""").find(block)?.groupValues?.get(1)

    private fun has(block: String, attr: String): Boolean =
        Regex("""android:$attr="[^"]+"""").containsMatchIn(block)

    /** Корневой тег карточки: только у него отступ наружу — зазор сетки. */
    private fun rootTag(source: String): String {
        val start = source.indexOf("<LinearLayout")
        val end = source.indexOf('>', start)
        return if (start >= 0 && end > start) source.substring(start, end) else ""
    }

    @Test
    fun `all nine sections and nine cards are present`() {
        val missing = all().filter { !File(layoutDir, "$it.xml").isFile }
        assertTrue("нет layout-файлов разделов: $missing", missing.isEmpty())
    }

    @Test
    fun `sections and cards set text size only through the scale`() {
        val offenders = all().flatMap { name ->
            Regex("""android:textSize="[^"]*"""").findAll(xml(name))
                .map { "$name.xml: ${it.value}" }.toList()
        }
        assertTrue(
            "кегль числом в layout раздела (используй @style/AppText.*): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `cards take inner padding from the density token`() {
        val offenders = mutableListOf<String>()
        cards.forEach { name ->
            val source = xml(name)
            Regex("""android:padding="(?:20|24)dp"""").findAll(source).forEach {
                offenders += "$name.xml: ${it.value}"
            }
            if (!source.contains("""android:padding="@dimen/tv_card_pad"""")) {
                offenders += "$name.xml: корень не на @dimen/tv_card_pad"
            }
        }
        assertTrue("карточка не уплотнена до tv_card_pad: $offenders", offenders.isEmpty())
    }

    @Test
    fun `card gaps come from the gap token`() {
        val offenders = cards.filter { name ->
            val root = rootTag(xml(name))
            Regex("""android:layout_marginTop="(?:12|14)dp"""").containsMatchIn(root)
        }
        assertTrue("зазор между карточками не из tv_card_gap: $offenders", offenders.isEmpty())
    }

    @Test
    fun `body text is clamped to a line budget`() {
        val offenders = mutableListOf<String>()
        all().forEach { name ->
            textViewBlocks(xml(name)).forEach tv@{ block ->
                val id = idOf(block) ?: return@tv
                if (id in unboundedText) return@tv
                if (!has(block, "maxLines")) offenders += "$name.xml#$id: нет maxLines"
                if (!has(block, "ellipsize")) offenders += "$name.xml#$id: нет ellipsize"
            }
        }
        assertTrue("TextView без ограничения строк: $offenders", offenders.isEmpty())
    }

    @Test
    fun `host and service cards clamp long names`() {
        val expected = mapOf(
            "item_tv_host_card" to listOf("hostTitle" to "2", "hostStatusLabel" to "1", "hostMetrics" to "1"),
            "item_tv_service_card" to listOf("serviceTitle" to "2", "serviceStatusLabel" to "1",
                                             "serviceDesc" to "2", "serviceMeta" to "1"),
        )
        val offenders = mutableListOf<String>()
        expected.forEach { (file, rows) ->
            val blocks = textViewBlocks(xml(file)).associateBy { idOf(it) }
            rows.forEach row@{ (id, lines) ->
                val block = blocks[id] ?: run { offenders += "$file#$id: нет TextView"; return@row }
                val actual = Regex("""android:maxLines="(\d+)"""")
                    .find(block)?.groupValues?.get(1)
                if (actual != lines) offenders += "$file#$id: maxLines=$actual, ожидалось $lines"
                if (!has(block, "ellipsize")) offenders += "$file#$id: нет ellipsize"
            }
        }
        assertTrue("карточки хоста/сервиса ломают сетку длинным именем: $offenders", offenders.isEmpty())
    }

    @Test
    fun `screen titles keep a minimum width and stay on one line`() {
        val offenders = mutableListOf<String>()
        screens.forEach { name ->
            val block = textViewBlocks(xml(name))
                .firstOrNull { it.contains("""@dimen/tv_title_min_w""") }
                ?: run { offenders += "$name.xml: заголовок без minWidth"; return@forEach }
            if (!has(block, "minWidth")) offenders += "$name.xml: у заголовка нет minWidth"
            if (!Regex("""android:maxLines="1"""").containsMatchIn(block)) {
                offenders += "$name.xml: заголовок не в одну строку"
            }
            if (!has(block, "ellipsize")) offenders += "$name.xml: у заголовка нет ellipsize"
        }
        assertTrue("заголовок экрана схлопывается в колонку: $offenders", offenders.isEmpty())
    }
}
