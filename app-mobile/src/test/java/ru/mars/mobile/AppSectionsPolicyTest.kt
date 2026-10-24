package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт левого бара: только приложенческие разделы, «Чат» первым,
 * «Настройки» последними, дубли входов сокращены, logout — один хост.
 * «Пространства» — полноценный раздел: экран вернулся в mobile, вход у него
 * единственный (строка бара), поэтому раздел стоит перед «Настройками».
 */
class AppSectionsPolicyTest {

    @Test
    fun `bar sections are all destinations in contract order`() {
        assertEquals(AppDestination.entries, AppSectionsPolicy.sections)
        assertEquals(AppDestination.CHAT, AppSectionsPolicy.sections.first())
        assertEquals(AppDestination.SETTINGS, AppSectionsPolicy.sections.last())
        assertEquals(11, AppSectionsPolicy.sections.size)
    }

    @Test
    fun `session list is not a bar section`() {
        // Диалоги — уровень внутри CHAT, а не раздел бара.
        assertTrue(AppSectionsPolicy.sections.none { it.title.contains("Диалог", ignoreCase = true) })
        assertEquals(ChatLevel.SessionList, AppNavState(AppDestination.CHAT).chatLevel)
    }

    @Test
    fun `duplicate entries are reduced - logs are not a section`() {
        // Логи живут внутри «Аналитики», отдельного входа в баре нет. Пространства,
        // наоборот, раздел: экран самостоятельный, дубля внутри других экранов нет.
        val keys = AppSectionsPolicy.sections.map { it.key }
        assertTrue("logs" !in keys)
        assertTrue("spaces" in keys)
        assertEquals(1, keys.count { it == "spaces" })
    }

    @Test
    fun `spaces sits right before settings`() {
        // «Настройки» всегда последние; «Пространства» — предпоследний раздел.
        assertEquals(
            listOf(AppDestination.SPACES, AppDestination.SETTINGS),
            AppSectionsPolicy.sections.takeLast(2),
        )
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
