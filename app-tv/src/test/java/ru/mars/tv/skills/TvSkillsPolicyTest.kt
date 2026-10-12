package ru.mars.tv.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SkillItem

/**
 * Unit-тесты [TvSkillsPolicy] на чистой JVM (без Android, без сети): парсинг
 * каталога `GET /api/skills` и статистики `GET /api/skills/usage`, пустые поля,
 * категории, обе сортировки, бейдж «использован N раз» со склонением,
 * разделение «пусто» и «фильтр ничего не дал».
 */
class TvSkillsPolicyTest {

    /** Форма реального ответа hermes-webui api/route_skills.py. */
    private val catalogPayload = """
        {
          "success": true,
          "skills": [
            {"name": "mars-toolkit", "description": "RAG-память и карта кода", "category": "devops", "disabled": false},
            {"name": "weather-stavropol", "description": "Погода Ставрополя", "category": "productivity"},
            {"name": "pdf", "description": "", "category": ""},
            {"name": "", "description": "безымянный скилл отбрасывается", "category": "devops"}
          ],
          "categories": ["devops", "productivity"],
          "count": 4
        }
    """.trimIndent()

    /** Форма реального ответа hermes-webui api/routes.py /api/skills/usage. */
    private val usagePayload = """
        {
          "usage": {
            "mars-toolkit": {"use_count": 7, "view_count": 2, "patch_count": 1},
            "pdf": {"use_count": 1, "view_count": 0, "patch_count": 0},
            "broken-entry": "не объект — пропускается"
          },
          "skill_names": ["mars-toolkit", "pdf", "weather-stavropol"],
          "total_invocations": 11,
          "unique_skills_used": 2
        }
    """.trimIndent()

    private val catalog = TvSkillsPolicy.parseCatalog(catalogPayload)!!
    private val usage = TvSkillsPolicy.parseUsage(usagePayload)!!

    @Test
    fun `catalog parses name, description and category - nameless entries are dropped`() {
        assertEquals(3, catalog.size)
        assertEquals(
            SkillItem(name = "mars-toolkit", description = "RAG-память и карта кода", category = "devops"),
            catalog[0],
        )
        assertEquals("пустое описание остаётся пустой строкой", "", catalog[2].description)
        assertEquals("пустая категория остаётся пустой строкой", "", catalog[2].category)
    }

    @Test
    fun `broken catalog payloads never parse into a fake empty list`() {
        assertNull(TvSkillsPolicy.parseCatalog("не json"))
        assertNull(TvSkillsPolicy.parseCatalog(""))
        assertNull("без ключа skills ответ неполный", TvSkillsPolicy.parseCatalog("""{"count":0}"""))
        assertEquals("валидный пустой каталог — это данные", emptyList<SkillItem>(), TvSkillsPolicy.parseCatalog("""{"skills":[]}"""))
    }

    @Test
    fun `usage parses per-skill counters and totals, skipping non-object entries`() {
        assertEquals(TvSkillUsage(useCount = 7, viewCount = 2, patchCount = 1), usage.bySkill["mars-toolkit"])
        assertEquals(10, usage.bySkill.getValue("mars-toolkit").invocations)
        assertEquals(1, usage.bySkill.getValue("pdf").invocations)
        assertEquals(2, usage.bySkill.size)
        assertEquals(11, usage.totalInvocations)
        assertEquals(2, usage.uniqueSkillsUsed)
    }

    @Test
    fun `usage payload without a usage object or broken json is null`() {
        assertNull(TvSkillsPolicy.parseUsage("не json"))
        assertNull(TvSkillsPolicy.parseUsage("""{"total_invocations":0}"""))
    }

    @Test
    fun `negative counters are coerced to zero`() {
        val coerced = TvSkillsPolicy.parseUsage(
            """{"usage":{"x":{"use_count":-3,"view_count":"много","patch_count":null}},"total_invocations":-9,"unique_skills_used":1}"""
        )!!
        assertEquals(TvSkillUsage(0, 0, 0), coerced.bySkill.getValue("x"))
        assertEquals(0, coerced.totalInvocations)
    }

    @Test
    fun `category chips put All first then unique categories alphabetically`() {
        assertEquals(listOf("all", "devops", "productivity"), TvSkillsPolicy.categories(catalog))
        assertEquals("Все", TvSkillsPolicy.categoryLabel(TvSkillsPolicy.ALL))
        assertEquals("Devops", TvSkillsPolicy.categoryLabel("devops"))
    }

    @Test
    fun `category filter is case-insensitive and All passes everything`() {
        val devops = catalog.filter { TvSkillsPolicy.inCategory(it, "DevOps") }
        assertEquals(listOf("mars-toolkit"), devops.map { it.name })
        assertEquals(3, catalog.count { TvSkillsPolicy.inCategory(it, TvSkillsPolicy.ALL) })
    }

    @Test
    fun `name sort orders by category then name, case-insensitively`() {
        val rows = TvSkillsPolicy.rows(catalog, null, TvSkillsPolicy.ALL, TvSkillsSort.NAME)
        // "" < "devops" < "productivity"; pdf без категории идёт первым.
        assertEquals(listOf("pdf", "mars-toolkit", "weather-stavropol"), rows.map { it.name })
        assertNull("без статистики бейджей нет", rows[0].usageBadge)
        assertEquals(0, rows[0].invocations)
    }

    @Test
    fun `usage sort orders by total invocations descending then by name`() {
        val rows = TvSkillsPolicy.rows(catalog, usage, TvSkillsPolicy.ALL, TvSkillsSort.USAGE)
        assertEquals(listOf("mars-toolkit", "pdf", "weather-stavropol"), rows.map { it.name })
        assertEquals(10, rows[0].invocations)
        assertEquals("использован 10 раз", rows[0].usageBadge)
        assertEquals("использован 1 раз", rows[1].usageBadge)
        assertNull("нулевая статистика бейджа не даёт", rows[2].usageBadge)
    }

    @Test
    fun `usage sort without statistics degenerates into the name order`() {
        val rows = TvSkillsPolicy.rows(catalog, null, TvSkillsPolicy.ALL, TvSkillsSort.USAGE)
        assertEquals(listOf("mars-toolkit", "pdf", "weather-stavropol"), rows.map { it.name })
        assertTrue("без статистики все счётчики нулевые", rows.all { it.invocations == 0 })
    }

    @Test
    fun `rows combine category filter and sort`() {
        val rows = TvSkillsPolicy.rows(catalog, usage, "devops", TvSkillsSort.USAGE)
        assertEquals(listOf("mars-toolkit"), rows.map { it.name })
        assertEquals("devops", rows.single().category)
        assertEquals("RAG-память и карта кода", rows.single().description)
    }

    @Test
    fun `timesWord covers russian pluralization`() {
        assertEquals("1 раз", TvSkillsPolicy.timesWord(1))
        assertEquals("2 раза", TvSkillsPolicy.timesWord(2))
        assertEquals("4 раза", TvSkillsPolicy.timesWord(4))
        assertEquals("5 раз", TvSkillsPolicy.timesWord(5))
        assertEquals("11 раз", TvSkillsPolicy.timesWord(11))
        assertEquals("14 раз", TvSkillsPolicy.timesWord(14))
        assertEquals("21 раз", TvSkillsPolicy.timesWord(21))
        assertEquals("22 раза", TvSkillsPolicy.timesWord(22))
        assertEquals("100 раз", TvSkillsPolicy.timesWord(100))
    }

    @Test
    fun `summary lines count rows and report usage totals`() {
        val rows = TvSkillsPolicy.rows(catalog, usage, TvSkillsPolicy.ALL, TvSkillsSort.NAME)
        assertEquals("Скиллов: 3", TvSkillsPolicy.summaryLine(rows))
        assertEquals(
            "Вызовов: 11 · задействовано скиллов: 2",
            TvSkillsPolicy.usageSummaryLine(usage),
        )
        assertNull(TvSkillsPolicy.usageSummaryLine(null))
    }

    @Test
    fun `empty catalog and empty filter results are different honest states`() {
        val noRows = TvSkillsPolicy.rows(catalog, usage, "несуществующая", TvSkillsSort.NAME)
        assertEquals(TvSkillsPolicy.FILTER_EMPTY, TvSkillsPolicy.emptyLine(catalog, noRows))
        assertEquals(TvSkillsPolicy.EMPTY_LIST, TvSkillsPolicy.emptyLine(emptyList(), emptyList()))
        val rows = TvSkillsPolicy.rows(catalog, usage, TvSkillsPolicy.ALL, TvSkillsSort.NAME)
        assertNull(TvSkillsPolicy.emptyLine(catalog, rows))
    }

    @Test
    fun `restoreSelection clamps into the new list without wrap`() {
        assertEquals(4, TvSkillsPolicy.restoreSelection(9, 5))
        assertEquals(0, TvSkillsPolicy.restoreSelection(-3, 5))
        assertEquals(2, TvSkillsPolicy.restoreSelection(2, 5))
        assertEquals(TvSkillsPolicy.NO_SELECTION, TvSkillsPolicy.restoreSelection(0, 0))
    }

    @Test
    fun `sort labels and cache window match the screen contract`() {
        assertEquals("По имени", TvSkillsPolicy.sortLabel(TvSkillsSort.NAME))
        assertEquals("По использованиям", TvSkillsPolicy.sortLabel(TvSkillsSort.USAGE))
        assertEquals(24L * 60 * 60 * 1000, TvSkillsPolicy.CACHE_MAX_AGE_MS)
        assertTrue(TvSkillsPolicy.rows(catalog, usage, TvSkillsPolicy.ALL, TvSkillsSort.NAME).all { it.key == it.name })
    }
}
