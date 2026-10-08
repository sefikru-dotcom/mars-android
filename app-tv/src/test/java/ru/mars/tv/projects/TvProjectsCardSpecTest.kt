package ru.mars.tv.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Текстовые состояния экрана проектов: все лейблы выводятся ИЗ ДАННЫХ,
 * заглушка «Онлайн» запрещена (acceptance TV P1).
 */
class TvProjectsCardSpecTest {

    @Test
    fun serviceStateLabelIsDerivedFromData() {
        assertEquals("Активен", TvProjectsCardSpec.serviceStateLabel(active = true, state = "active"))
        assertEquals("Активен", TvProjectsCardSpec.serviceStateLabel(active = true, state = "ok"))
        assertEquals("Не активен", TvProjectsCardSpec.serviceStateLabel(active = false, state = "stopped"))
        assertEquals("Не активен", TvProjectsCardSpec.serviceStateLabel(active = false, state = "error"))
        assertEquals("Не активен", TvProjectsCardSpec.serviceStateLabel(active = false, state = ""))
    }

    @Test
    fun hostStatusIsDerivedFromPresenceOfData() {
        assertEquals("Данные получены", TvProjectsCardSpec.hostStatus(available = true))
        assertEquals("Данные недоступны", TvProjectsCardSpec.hostStatus(available = false))
    }

    @Test
    fun noOnlineStubInAnySpecLabel() {
        val labels = listOf(
            TvProjectsCardSpec.serviceStateLabel(true, "active"),
            TvProjectsCardSpec.serviceStateLabel(false, "stopped"),
            TvProjectsCardSpec.hostStatus(true),
            TvProjectsCardSpec.hostStatus(false),
            TvProjectsCardSpec.SERVICES_MISSING,
        )
        labels.forEach {
            assertFalse("заглушка «Онлайн» запрещена: $it", it.contains("онлайн", ignoreCase = true))
        }
    }

    @Test
    fun missingSectionsHaveExplicitUnavailableText() {
        assertEquals("Данные недоступны", TvProjectsCardSpec.SERVICES_MISSING)
    }
}
