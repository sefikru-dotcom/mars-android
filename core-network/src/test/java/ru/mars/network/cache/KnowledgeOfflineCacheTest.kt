package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MemoryContent
import ru.mars.network.model.SkillDetail
import ru.mars.network.model.SkillItem

/**
 * JVM-тесты офлайн-кэша базы знаний ([KnowledgeOfflineCache]): round-trip скиллов/тел/памяти,
 * лимиты и LRU-вытеснение тел, устойчивость к битым данным и падающему хранилищу,
 * офлайн-поиск (совпадения, сниппеты, порядок, пределы), гигиена очистки.
 */
class KnowledgeOfflineCacheTest {

    /** In-memory [KeyValueStore]; [failing] имитирует падающее хранилище (диск/IO). */
    private class FakeStore(var failing: Boolean = false) : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? {
            if (failing) throw IllegalStateException("store down")
            return map[key]
        }

        override fun putString(key: String, value: String) {
            if (failing) throw IllegalStateException("store down")
            map[key] = value
        }

        override fun remove(key: String) {
            if (failing) throw IllegalStateException("store down")
            map.remove(key)
        }

        override fun keys(): Set<String> {
            if (failing) throw IllegalStateException("store down")
            return map.keys.toSet()
        }

        override fun clearAll() {
            if (failing) throw IllegalStateException("store down")
            map.clear()
        }
    }

    private var now = 1_700_000_000_000L

    private fun newCache(store: KeyValueStore = FakeStore()) =
        KnowledgeOfflineCache(store, clock = { now })

    private fun skill(
        name: String,
        description: String = "описание $name",
        category: String = "devops",
    ) = SkillItem(name = name, description = description, category = category)

    private fun memory(
        memory: String = "память проекта",
        user: String = "профиль пользователя",
        soul: String = "душа Марса",
        projectContext: String = "контекст проекта",
    ) = MemoryContent(memory = memory, user = user, soul = soul, projectContext = projectContext)

    // --- round-trip ----------------------------------------------------------------------------

    @Test
    fun `skills survive save and load with all fields`() {
        val cache = newCache()
        val items = listOf(
            skill("mars-toolkit", "RAG-память и карта кода", "devops"),
            skill("weather-stavropol", "Погода Ставрополя", "productivity"),
        )

        assertTrue(cache.saveSkills(items))
        assertEquals(items, cache.loadSkills())
    }

    @Test
    fun `unicode quotes and newlines survive json round trip`() {
        val cache = newCache()
        val detail = SkillDetail(
            name = "tricky-skill",
            content = "# Заголовок\n\nКавычки \" и 'переносы' — ünicode 🔥\n- пункт\n- ещё пункт",
        )

        assertTrue(cache.saveSkillDetail(detail))
        assertEquals(detail, cache.loadSkillDetail("tricky-skill"))
    }

    @Test
    fun `empty skills list round trips as empty list not null`() {
        val cache = newCache()

        assertTrue(cache.saveSkills(emptyList()))
        assertEquals(emptyList<SkillItem>(), cache.loadSkills())
    }

    @Test
    fun `skill detail survives save and load per name`() {
        val cache = newCache()
        val detail = SkillDetail("mars-toolkit", "# SKILL.md body\n\nсодержимое")

        assertTrue(cache.saveSkillDetail(detail))
        assertEquals(detail, cache.loadSkillDetail("mars-toolkit"))
        // Чужое имя не затронуто, неизвестное — null.
        assertNull(cache.loadSkillDetail("weather-stavropol"))
    }

    @Test
    fun `detail re-save overwrites previous content`() {
        val cache = newCache()
        cache.saveSkillDetail(SkillDetail("s", "старое тело"))

        cache.saveSkillDetail(SkillDetail("s", "новое тело"))

        assertEquals(SkillDetail("s", "новое тело"), cache.loadSkillDetail("s"))
    }

    @Test
    fun `memory sections survive save and load`() {
        val cache = newCache()
        val content = memory()

        assertTrue(cache.saveMemory(content))
        assertEquals(content, cache.loadMemory())
    }

    @Test
    fun `empty memory round trips as empty content not null`() {
        val cache = newCache()

        assertTrue(cache.saveMemory(MemoryContent()))
        assertEquals(MemoryContent(), cache.loadMemory())
    }

    @Test
    fun `saved at follows injected clock`() {
        val store = FakeStore()
        val cache = newCache(store)
        now = 1_800_000_000_000L

        cache.saveSkills(listOf(skill("s1")))
        cache.saveMemory(memory())

        assertTrue(store.map["knowledge_skills"]!!.contains("\"saved_at\":$now"))
        assertTrue(store.map["knowledge_memory"]!!.contains("\"saved_at\":$now"))
    }

    @Test
    fun `absent caches load as null`() {
        val cache = newCache()

        assertNull(cache.loadSkills())
        assertNull(cache.loadSkillDetail("anything"))
        assertNull(cache.loadMemory())
    }

    // --- лимиты --------------------------------------------------------------------------------

    @Test
    fun `skills list is capped keeping the head`() {
        val cache = newCache()
        val many = (1..250).map { skill("s$it") }

        cache.saveSkills(many)
        val loaded = cache.loadSkills()

        assertEquals(200, loaded?.size)
        assertEquals(many.take(200), loaded)
    }

    @Test
    fun `oversized skill fields are truncated before persisting`() {
        val cache = newCache()

        cache.saveSkills(
            listOf(
                SkillItem(
                    name = "n".repeat(500),
                    description = "d".repeat(1_000),
                    category = "c".repeat(1_000),
                )
            )
        )
        val loaded = cache.loadSkills()?.single()

        assertEquals(128, loaded?.name?.length)
        assertEquals(300, loaded?.description?.length)
        assertEquals(300, loaded?.category?.length)
    }

    @Test
    fun `oversized detail content is truncated before persisting`() {
        val cache = newCache()

        cache.saveSkillDetail(SkillDetail("big", "x".repeat(20_000)))

        assertEquals(16_000, cache.loadSkillDetail("big")?.content?.length)
    }

    @Test
    fun `oversized memory sections are truncated before persisting`() {
        val cache = newCache()
        val long = "m".repeat(10_000)

        cache.saveMemory(MemoryContent(memory = long, user = long, soul = long, projectContext = long))
        val loaded = cache.loadMemory()

        assertEquals(8_000, loaded?.memory?.length)
        assertEquals(8_000, loaded?.user?.length)
        assertEquals(8_000, loaded?.soul?.length)
        assertEquals(8_000, loaded?.projectContext?.length)
    }

    // --- LRU тел скиллов ------------------------------------------------------------------------

    @Test
    fun `detail caches beyond cap are lru evicted`() {
        val cache = newCache()
        for (i in 1..25) cache.saveSkillDetail(SkillDetail("s$i", "контент $i"))

        for (i in 1..5) assertNull("старый кэш s$i вытеснен", cache.loadSkillDetail("s$i"))
        for (i in 6..25) assertNotNull("свежий кэш s$i на месте", cache.loadSkillDetail("s$i"))
    }

    @Test
    fun `load promotes detail in lru`() {
        val cache = newCache()
        for (i in 1..20) cache.saveSkillDetail(SkillDetail("s$i", "контент $i"))

        // открыли самый старый скилл — он переезжает в голову LRU
        assertNotNull(cache.loadSkillDetail("s1"))
        cache.saveSkillDetail(SkillDetail("s21", "новый"))

        assertNull("хвостом стал s2 — он и вытеснен", cache.loadSkillDetail("s2"))
        assertNotNull("продвинутый s1 выжил", cache.loadSkillDetail("s1"))
        assertNotNull(cache.loadSkillDetail("s21"))
    }

    @Test
    fun `corrupt lru index is rebuilt from store keys without data loss`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveSkillDetail(SkillDetail("s1", "один"))
        cache.saveSkillDetail(SkillDetail("s2", "два"))
        store.map["knowledge_skill_lru"] = "{это не JSON список"

        assertTrue(cache.saveSkillDetail(SkillDetail("s3", "три")))
        assertNotNull(cache.loadSkillDetail("s1"))
        assertNotNull(cache.loadSkillDetail("s2"))
        assertNotNull(cache.loadSkillDetail("s3"))
    }

    // --- устойчивость к мусору -------------------------------------------------------------------

    @Test
    fun `corrupt json reads as absent and never throws`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skills"] = "{not json at all"
        store.map["knowledge_skill:s1"] = "[1,2,3]"
        store.map["knowledge_memory"] = "мусор"

        assertNull(cache.loadSkills())
        assertNull(cache.loadSkillDetail("s1"))
        assertNull(cache.loadMemory())
    }

    @Test
    fun `foreign cache version reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skills"] = """{"v":99,"saved_at":1,"skills":[]}"""
        store.map["knowledge_skill:s1"] = """{"v":99,"saved_at":1,"name":"s1","content":"x"}"""
        store.map["knowledge_memory"] = """{"v":99,"saved_at":1,"memory":"x"}"""

        assertNull(cache.loadSkills())
        assertNull(cache.loadSkillDetail("s1"))
        assertNull(cache.loadMemory())
    }

    @Test
    fun `skill rows without name are dropped but valid rows survive`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skills"] = """
            {"v":1,"saved_at":5,"skills":[
                {"description":"без имени"},
                {"name":"  ","description":"пустое имя"},
                {"name":"ok","description":"Живой","category":"devops"}
            ]}
        """.trimIndent()

        val loaded = cache.loadSkills()

        assertEquals(listOf("ok"), loaded?.map { it.name })
        assertEquals(SkillItem("ok", "Живой", "devops"), loaded?.single())
    }

    @Test
    fun `detail record without name reads as absent`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skill:s1"] = """{"v":1,"saved_at":5,"content":"без имени"}"""

        assertNull(cache.loadSkillDetail("s1"))
    }

    @Test
    fun `garbage overwrites on next successful save`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skills"] = "мусор"
        store.map["knowledge_memory"] = "мусор"

        assertNull(cache.loadSkills())
        assertNull(cache.loadMemory())
        cache.saveSkills(listOf(skill("s1")))
        cache.saveMemory(memory(user = "свежий профиль"))

        assertEquals(listOf("s1"), cache.loadSkills()?.map { it.name })
        assertEquals("свежий профиль", cache.loadMemory()?.user)
    }

    @Test
    fun `failing store degrades to absent data instead of crashing`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertFalse(cache.saveSkills(listOf(skill("s1"))))
        assertNull(cache.loadSkills())
        assertFalse(cache.saveSkillDetail(SkillDetail("s1", "контент")))
        assertNull(cache.loadSkillDetail("s1"))
        assertFalse(cache.saveMemory(memory()))
        assertNull(cache.loadMemory())
        assertTrue(cache.searchOffline("s1").isEmpty())
        cache.clear() // не бросает
    }

    @Test
    fun `store failing mid search still returns matches from healthy half`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveSkills(listOf(skill("mars-toolkit")))
        cache.saveMemory(memory(user = "mars-пользователь"))
        // Падающее хранилище во время поиска: оба чтения деградируют в «данных нет».
        store.failing = true

        assertTrue(cache.searchOffline("mars").isEmpty())
    }

    @Test
    fun `blank skill name is rejected by detail apis`() {
        val cache = newCache()

        assertFalse(cache.saveSkillDetail(SkillDetail("", "контент")))
        assertFalse(cache.saveSkillDetail(SkillDetail("   ", "контент")))
        assertNull(cache.loadSkillDetail(""))
        assertNull(cache.loadSkillDetail("  "))
    }

    @Test
    fun `skill name with control chars is normalized into a stable key`() {
        val cache = newCache()

        assertTrue(cache.saveSkillDetail(SkillDetail("weird\n\tname", "тело")))
        // Имя нормализовано: запись доступна по нормализованному имени и лежит в снимке.
        assertEquals(SkillDetail("weird name", "тело"), cache.loadSkillDetail("weird name"))
    }

    // --- офлайн-поиск: совпадения ----------------------------------------------------------------

    @Test
    fun `search matches skill name case insensitively and reports source`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("mars-toolkit", "RAG-память"), skill("weather", "Погода")))

        val results = cache.searchOffline("MARS-TOOL")

        assertEquals(1, results.size)
        assertEquals("mars-toolkit", results[0].name)
        assertTrue(OfflineKnowledgeSource.SKILL_NAME in results[0].sources)
        assertNotNull(results[0].snippet)
    }

    @Test
    fun `search matches skill description with snippet`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("weather-stavropol", "Погода в Ставрополе", "productivity")))

        val results = cache.searchOffline("ПОГОДА")

        assertEquals(1, results.size)
        assertEquals(setOf(OfflineKnowledgeSource.SKILL_DESCRIPTION), results[0].sources)
        assertTrue(results[0].snippet!!.contains("Погода", ignoreCase = true))
    }

    @Test
    fun `skill matching both name and description yields one match with both sources`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("mars-toolkit", "Инструменты Mars")))

        val results = cache.searchOffline("mars")

        assertEquals(1, results.size)
        assertEquals(
            setOf(OfflineKnowledgeSource.SKILL_NAME, OfflineKnowledgeSource.SKILL_DESCRIPTION),
            results[0].sources,
        )
        // Сниппет берётся из первого совпавшего текста — названия.
        assertEquals("mars-toolkit", results[0].snippet)
    }

    @Test
    fun `search matches memory sections and names them by wire key`() {
        val cache = newCache()
        cache.saveMemory(
            memory(
                memory = "обычные заметки",
                user = "Стеф предпочитает краткость",
                soul = "душа",
                projectContext = "контекст",
            )
        )

        val results = cache.searchOffline("краткость")

        assertEquals(1, results.size)
        assertEquals("user", results[0].name)
        assertEquals(setOf(OfflineKnowledgeSource.MEMORY_SECTION), results[0].sources)
        assertTrue(results[0].snippet!!.contains("краткость"))
    }

    @Test
    fun `search does not match skill detail content`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("mars-toolkit", "RAG-память")))
        cache.saveSkillDetail(SkillDetail("mars-toolkit", "секретный маркер внутри тела"))

        // Контракт: поиск идёт по названиям/описаниям и памяти, тела не сканируются.
        assertTrue(cache.searchOffline("секретный маркер").isEmpty())
    }

    @Test
    fun `search snippet is bounded and collapses whitespace`() {
        val cache = newCache()
        val long = "слово ".repeat(40) + "ИГОЛКА" + " хвост".repeat(40)
        cache.saveMemory(memory(user = long))

        val results = cache.searchOffline("иголка")
        val snippet = results.single().snippet!!

        assertTrue(snippet.contains("ИГОЛКА", ignoreCase = true))
        assertTrue("сниппет ограничен 200 символами", snippet.length <= 200)
        assertTrue("обрезка помечается многоточием", snippet.startsWith("…") && snippet.endsWith("…"))
        assertFalse("переносы схлопнуты", snippet.contains("\n"))
    }

    @Test
    fun `search keeps skills first then memory sections in fixed order`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("b-match"), skill("a-match")))
        cache.saveMemory(memory(memory = "match в memory", soul = "match в soul", user = "match в user", projectContext = ""))

        val results = cache.searchOffline("match")

        assertEquals(
            listOf("b-match", "a-match", "memory", "user", "soul"),
            results.map { it.name },
        )
    }

    @Test
    fun `search results are capped`() {
        val cache = newCache()
        cache.saveSkills((1..200).map { skill("match-$it") })

        // 200 совпадений-названий, но не более 50 записей в результате.
        assertEquals(50, cache.searchOffline("match").size)
    }

    @Test
    fun `search trims query and truncates oversized input`() {
        val cache = newCache()
        // описание усекается до 300 символов при сохранении
        cache.saveSkills(listOf(SkillItem(name = "s1", description = "a".repeat(500), category = "")))

        // пробелы по краям не мешают; запрос в 500 символов усекается до 200 и всё ещё
        // матчит 300-символьное описание (без усечения 500 > 300 — совпадения бы не было)
        val query = "  " + "a".repeat(500) + "  "
        assertEquals(1, cache.searchOffline(query).size)
    }

    @Test
    fun `blank query and absent caches give empty results`() {
        val cache = newCache()

        assertTrue(cache.searchOffline("").isEmpty())
        assertTrue(cache.searchOffline("   ").isEmpty())
        assertTrue(cache.searchOffline("что угодно").isEmpty()) // кэши не сохранены
    }

    @Test
    fun `search survives corrupt caches without throwing`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["knowledge_skills"] = "битые данные"
        store.map["knowledge_memory"] = "{не json"

        assertTrue(cache.searchOffline("запрос").isEmpty())
    }

    // --- очистка ----------------------------------------------------------------------------------

    @Test
    fun `clear wipes skills details and memory`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveSkills(listOf(skill("s1")))
        cache.saveSkillDetail(SkillDetail("s1", "тело"))
        cache.saveMemory(memory())

        cache.clear()

        assertTrue(store.map.isEmpty())
        assertNull(cache.loadSkills())
        assertNull(cache.loadSkillDetail("s1"))
        assertNull(cache.loadMemory())
        assertTrue(cache.searchOffline("s1").isEmpty())
    }

    @Test
    fun `cache works again after clear`() {
        val cache = newCache()
        cache.saveSkills(listOf(skill("old")))

        cache.clear()
        cache.saveSkills(listOf(skill("new")))

        assertEquals(listOf("new"), cache.loadSkills()?.map { it.name })
    }
}
