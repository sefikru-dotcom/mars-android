package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MemoryContent

class MemoryTabPolicyTest {

    private val content = MemoryContent(
        memory = "  # MEMORY.md\nФакты  ",
        user = "Профиль Стефа",
        soul = "",
        projectContext = "   ",
    )

    @Test fun eachTabMapsToItsOwnSection() {
        assertEquals(content.memory, MemoryTabPolicy.textFor(MemoryTab.MEMORY, content))
        assertEquals("Профиль Стефа", MemoryTabPolicy.textFor(MemoryTab.USER, content))
        assertEquals("", MemoryTabPolicy.textFor(MemoryTab.SOUL, content))
        assertEquals("   ", MemoryTabPolicy.textFor(MemoryTab.PROJECT, content))
    }

    @Test fun nullContentGivesEmptyText() {
        MemoryTab.entries.forEach { tab ->
            assertEquals("", MemoryTabPolicy.textFor(tab, null))
        }
    }

    @Test fun loadingWinsOverEverything() {
        val state = MemoryTabPolicy.resolve(MemoryTab.MEMORY, content, loading = true, failure = "старая ошибка")
        assertTrue(state is MemorySectionState.Loading)
    }

    @Test fun missingContentIsFailureWithCustomMessage() {
        val state = MemoryTabPolicy.resolve(MemoryTab.USER, null, loading = false, failure = "Сервер молчит")
        assertTrue(state is MemorySectionState.Failure)
        assertEquals("Сервер молчит", (state as MemorySectionState.Failure).message)
    }

    @Test fun missingContentWithoutMessageUsesDefaultFailure() {
        val state = MemoryTabPolicy.resolve(MemoryTab.USER, null, loading = false, failure = "   ")
        assertEquals(MemoryTabPolicy.DEFAULT_FAILURE, (state as MemorySectionState.Failure).message)
    }

    @Test fun blankSectionIsEmptyWithFileName() {
        val soul = MemoryTabPolicy.resolve(MemoryTab.SOUL, content, loading = false)
        assertTrue(soul is MemorySectionState.Empty)
        assertEquals("Секция SOUL.md пуста", (soul as MemorySectionState.Empty).message)

        val project = MemoryTabPolicy.resolve(MemoryTab.PROJECT, content, loading = false)
        assertEquals("Секция AGENTS.md пуста", (project as MemorySectionState.Empty).message)
    }

    @Test fun nonBlankSectionIsTrimmedBody() {
        val state = MemoryTabPolicy.resolve(MemoryTab.MEMORY, content, loading = false)
        assertTrue(state is MemorySectionState.Body)
        assertEquals("# MEMORY.md\nФакты", (state as MemorySectionState.Body).text)
    }

    @Test fun canCopyOnlyForNonBlankSections() {
        assertTrue(MemoryTabPolicy.canCopy(MemoryTab.MEMORY, content))
        assertFalse(MemoryTabPolicy.canCopy(MemoryTab.SOUL, content))
        assertFalse(MemoryTabPolicy.canCopy(MemoryTab.PROJECT, content))
        assertFalse(MemoryTabPolicy.canCopy(MemoryTab.MEMORY, null))
    }

    @Test fun tabLabelsAndFileNamesMatchScreenContract() {
        assertEquals(listOf("Память", "Профиль", "SOUL", "Контекст"), MemoryTab.entries.map { it.label })
        assertEquals(
            listOf("MEMORY.md", "USER.md", "SOUL.md", "AGENTS.md"),
            MemoryTab.entries.map { it.fileName },
        )
    }
}
