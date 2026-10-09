package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SkillItem

class SkillsFilterPolicyTest {

    private val skills = listOf(
        SkillItem("mars-toolkit", "RAG-память, карта кода и lint", "devops"),
        SkillItem("kvartal-release", "Релиз Квартала: коммит, бамп, деплой", "devops"),
        SkillItem("api-design", "Проектирование HTTP API", "ecc"),
        SkillItem("ascii-art", "ASCII art and diagrams", "creative"),
        SkillItem("no-category", "Скилл без категории", ""),
    )

    @Test fun blankQueryKeepsEverything() {
        assertEquals(skills, SkillsFilterPolicy.filter(skills, "   ", SkillsFilterPolicy.ALL))
    }

    @Test fun queryMatchesNameCaseInsensitively() {
        val result = SkillsFilterPolicy.filter(skills, "MARS-Tool", SkillsFilterPolicy.ALL)
        assertEquals(listOf("mars-toolkit"), result.map { it.name })
    }

    @Test fun queryMatchesDescription() {
        val result = SkillsFilterPolicy.filter(skills, "диаграмма", SkillsFilterPolicy.ALL).map { it.name } +
            SkillsFilterPolicy.filter(skills, "diagrams", SkillsFilterPolicy.ALL).map { it.name }
        assertTrue(result.contains("ascii-art"))
        assertTrue(SkillsFilterPolicy.filter(skills, "карта кода", SkillsFilterPolicy.ALL).map { it.name }.contains("mars-toolkit"))
    }

    @Test fun queryTrimsSurroundingWhitespace() {
        val result = SkillsFilterPolicy.filter(skills, "  api  ", SkillsFilterPolicy.ALL)
        assertEquals(listOf("api-design"), result.map { it.name })
    }

    @Test fun allCategoryKeepsEverySkillIncludingBlankCategory() {
        assertEquals(5, SkillsFilterPolicy.filter(skills, "", SkillsFilterPolicy.ALL).size)
        assertEquals(5, SkillsFilterPolicy.filter(skills, "", "").size)
    }

    @Test fun categoryFilterIsCaseInsensitive() {
        val result = SkillsFilterPolicy.filter(skills, "", "DevOps")
        assertEquals(listOf("mars-toolkit", "kvartal-release"), result.map { it.name })
    }

    @Test fun categoryAndQueryCombine() {
        val result = SkillsFilterPolicy.filter(skills, "релиз", "devops")
        assertEquals(listOf("kvartal-release"), result.map { it.name })
    }

    @Test fun categoriesStartWithAllDedupedAndAlphabeticalSkippingBlank() {
        assertEquals(
            listOf(SkillsFilterPolicy.ALL, "creative", "devops", "ecc"),
            SkillsFilterPolicy.categories(skills),
        )
        assertEquals(listOf(SkillsFilterPolicy.ALL), SkillsFilterPolicy.categories(emptyList()))
    }

    @Test fun categoriesDeduplicateCaseVariants() {
        val mixed = listOf(
            SkillItem("a", "", "devops"),
            SkillItem("b", "", "DevOps"),
            SkillItem("c", "", "ecc"),
        )
        assertEquals(listOf(SkillsFilterPolicy.ALL, "devops", "ecc"), SkillsFilterPolicy.categories(mixed))
    }

    @Test fun categoryLabelRendersAllAndCapitalizes() {
        assertEquals("Все", SkillsFilterPolicy.categoryLabel(SkillsFilterPolicy.ALL))
        assertEquals("Devops", SkillsFilterPolicy.categoryLabel("devops"))
        assertEquals("Ecc", SkillsFilterPolicy.categoryLabel("ecc"))
    }

    @Test fun sortedIsStableByCategoryThenName() {
        val sorted = SkillsFilterPolicy.sorted(skills)
        // Пустая категория сортируется первой, внутри категории — по имени.
        assertEquals(
            listOf("no-category", "ascii-art", "kvartal-release", "mars-toolkit", "api-design"),
            sorted.map { it.name },
        )
    }

    @Test fun matchesIsPurePredicate() {
        assertTrue(SkillsFilterPolicy.matches(SkillItem("mars-osint", "OSINT разведка", "devops"), "osint"))
        assertFalse(SkillsFilterPolicy.matches(SkillItem("mars-osint", "OSINT разведка", "devops"), "weather"))
    }
}
