package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт левого бара: только приложенческие разделы, «Чат» первым,
 * «Настройки» последними, дубли входов сокращены, logout — один хост.
 */
class AppSectionsPolicyTest {

    @Test
    fun `bar sections are all destinations in contract order`() {
        assertEquals(AppDestination.entries, AppSectionsPolicy.sections)
        assertEquals(AppDestination.CHAT, AppSectionsPolicy.sections.first())
        assertEquals(AppDestination.SETTINGS, AppSectionsPolicy.sections.last())
        assertEquals(10, AppSectionsPolicy.sections.size)
    }

    @Test
    fun `session list is not a bar section`() {
        // Диалоги — уровень внутри CHAT, а не раздел бара.
        assertTrue(AppSectionsPolicy.sections.none { it.title.contains("Диалог", ignoreCase = true) })
        assertEquals(ChatLevel.SessionList, AppNavState(AppDestination.CHAT).chatLevel)
    }

    @Test
    fun `duplicate entries are reduced - spaces and logs are not sections`() {
        val keys = AppSectionsPolicy.sections.map { it.key }
        assertTrue("spaces" !in keys)
        assertTrue("logs" !in keys)
    }

    @Test
    fun `every section has a unique non-blank icon key`() {
        val keys = AppSectionsPolicy.sections.map { AppSectionsPolicy.iconKeyFor(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals("ic_nav_chat", AppSectionsPolicy.iconKeyFor(AppDestination.CHAT))
    }

    @Test
    fun `logout is hosted by settings and only settings`() {
        assertEquals(AppDestination.SETTINGS, AppSectionsPolicy.logoutSection)
        assertTrue(AppNavPolicy.isLogoutAvailable(AppSectionsPolicy.logoutSection))
        assertEquals(1, AppSectionsPolicy.sections.count { AppNavPolicy.isLogoutAvailable(it) })
    }
}
