package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {

    @Test
    fun `exactly ten app-level sections`() {
        assertEquals(10, AppDestination.entries.size)
    }

    @Test
    fun `order is stable and matches product contract`() {
        val expected = listOf(
            "chat", "tasks", "kanban", "scheduler", "projects",
            "music", "memory", "skills", "analytics", "settings"
        )
        assertEquals(expected, AppDestination.entries.map { it.key })
    }

    @Test
    fun `chat is first and settings is last`() {
        assertEquals(AppDestination.CHAT, AppDestination.entries.first())
        assertEquals(AppDestination.SETTINGS, AppDestination.entries.last())
    }

    @Test
    fun `session list is not an app-level section`() {
        // Диалоги — уровень внутри CHAT, а не раздел бара: ни один ключ не
        // описывает список сессий.
        assertTrue(AppDestination.entries.none { it.key.contains("session") })
        assertTrue(AppDestination.entries.none { it.key.contains("dialog") })
    }

    @Test
    fun `keys are unique lowercase ascii slugs`() {
        val keys = AppDestination.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("key не ascii-slug: $key", key.isNotEmpty() && key.all { it in 'a'..'z' })
        }
    }

    @Test
    fun `titles are non-blank plain cyrillic letters`() {
        AppDestination.entries.forEach { destination ->
            val title = destination.title
            assertTrue("title пустой у ${destination.key}", title.isNotBlank())
            assertEquals("title имеет пробелы по краям у ${destination.key}", title.trim(), title)
            assertTrue(
                "title содержит не-буквы у ${destination.key}: $title",
                title.all { it.isLetter() }
            )
        }
    }

    @Test
    fun `fromKey round-trips every destination and rejects unknown`() {
        AppDestination.entries.forEach { destination ->
            assertEquals(destination, AppDestination.fromKey(destination.key))
        }
        assertNull(AppDestination.fromKey("unknown"))
        assertNull(AppDestination.fromKey(""))
        assertNull(AppDestination.fromKey("CHAT"))
    }

    @Test
    fun `legacy NavTarget mapping covers all sections except chat`() {
        AppDestination.entries.forEach { destination ->
            val legacy = destination.toLegacyNavTarget()
            if (destination == AppDestination.CHAT) {
                assertNull("у CHAT не должно быть легаси-цели", legacy)
            } else {
                assertTrue("нет легаси-цели у ${destination.key}", legacy != null)
            }
        }
    }

    @Test
    fun `legacy mapping is a bijection onto NavTarget without duplication`() {
        val expected = mapOf(
            AppDestination.TASKS to NavTarget.TODOS,
            AppDestination.SCHEDULER to NavTarget.CRON,
            AppDestination.PROJECTS to NavTarget.PROJECTS,
            AppDestination.MUSIC to NavTarget.MUSIC,
            AppDestination.MEMORY to NavTarget.MEMORY,
            AppDestination.SKILLS to NavTarget.SKILLS,
            AppDestination.KANBAN to NavTarget.KANBAN,
            AppDestination.ANALYTICS to NavTarget.INSIGHTS,
            AppDestination.SETTINGS to NavTarget.SETTINGS,
        )
        expected.forEach { (destination, target) ->
            assertEquals(target, destination.toLegacyNavTarget())
        }
        // Каждая легаси-цель покрыта ровно одним разделом (без дублей и сирот).
        val mapped = AppDestination.entries.mapNotNull { it.toLegacyNavTarget() }
        assertEquals(mapped.size, mapped.toSet().size)
        assertEquals(NavTarget.entries.toSet(), mapped.toSet())
    }
}
