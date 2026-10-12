package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarNavPolicyTest {

    @Test
    fun `exactly seven sections`() {
        assertEquals(7, SidebarNavPolicy.sections.size)
    }

    @Test
    fun `all ids unique`() {
        val ids = SidebarNavPolicy.sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `order is stable and matches WebUI parity`() {
        val expected = listOf("todos", "cron", "projects", "music", "memory", "skills", "settings")
        assertEquals(expected, SidebarNavPolicy.sections.map { it.id })
        // Повторный доступ к тому же списку даёт тот же порядок (стабильность).
        assertEquals(expected, SidebarNavPolicy.sections.map { it.id })
    }

    @Test
    fun `every NavTarget present exactly once`() {
        val targets = SidebarNavPolicy.sections.map { it.target }
        assertEquals(NavTarget.entries.size, targets.size)
        assertEquals(NavTarget.entries.toSet(), targets.toSet())
        assertEquals(targets.size, targets.toSet().size)
    }

    @Test
    fun `labels non-empty plain text without emoji or symbol glyphs`() {
        val forbiddenRanges = listOf(
            0x2600..0x27BF, // Miscellaneous Symbols / Dingbats
            0xFE00..0xFE0F, // variation selectors
            0x1F000..0x1FAFF, // эмодзи-плоскости
        )
        SidebarNavPolicy.sections.forEach { section ->
            val label = section.label
            assertTrue("label пустой у ${section.id}", label.isNotBlank())
            assertEquals("label имеет пробелы по краям у ${section.id}", label.trim(), label)
            val hasForbiddenGlyph = label.codePoints().anyMatch { cp ->
                val type = Character.getType(cp)
                type == Character.OTHER_SYMBOL.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    type == Character.CONTROL.toInt() ||
                    type == Character.UNASSIGNED.toInt() ||
                    forbiddenRanges.any { cp in it }
            }
            assertTrue("label содержит эмодзи/глифы у ${section.id}: $label", !hasForbiddenGlyph)
            // Только буквы кириллицы — без цифр, пунктуации-символики и спецглифов.
            assertTrue(
                "label содержит не-буквы у ${section.id}: $label",
                label.all { it.isLetter() }
            )
        }
    }

    @Test
    fun `each id maps to correct target`() {
        val expected = mapOf(
            "todos" to NavTarget.TODOS,
            "cron" to NavTarget.CRON,
            "projects" to NavTarget.PROJECTS,
            "music" to NavTarget.MUSIC,
            "memory" to NavTarget.MEMORY,
            "skills" to NavTarget.SKILLS,
            "settings" to NavTarget.SETTINGS,
        )
        expected.forEach { (id, target) ->
            val section = SidebarNavPolicy.sections.first { it.id == id }
            assertEquals(target, section.target)
            assertEquals(section, SidebarNavPolicy.sectionById(id))
            assertEquals(section, SidebarNavPolicy.sectionByTarget(target))
        }
    }

    @Test
    fun `labels match WebUI parity`() {
        val expected = listOf(
            "Задачи", "Планировщик", "Проекты", "Музыка", "Память", "Скиллы", "Настройки"
        )
        assertEquals(expected, SidebarNavPolicy.sections.map { it.label })
    }

    @Test
    fun `icon keys are unique and use ic_nav prefix`() {
        val keys = SidebarNavPolicy.sections.map { it.iconKey }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("iconKey без префикса ic_nav_: $key", key.startsWith("ic_nav_"))
        }
    }

    @Test
    fun `sectionById and sectionByTarget return null for unknown input`() {
        assertNull(SidebarNavPolicy.sectionById("nonexistent"))
    }

    @Test
    fun `resolveIconRes passes through lookup and maps zero to null`() {
        val lookup = { name: String -> if (name == "ic_nav_todos") 42 else 0 }
        assertNotNull(SidebarNavPolicy.resolveIconRes("ic_nav_todos", lookup))
        assertEquals(42, SidebarNavPolicy.resolveIconRes("ic_nav_todos", lookup))
        assertNull(SidebarNavPolicy.resolveIconRes("ic_nav_missing", lookup))
    }
}
