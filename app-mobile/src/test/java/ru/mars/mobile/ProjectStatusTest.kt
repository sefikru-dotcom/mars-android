package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStatusTest {
    @Test fun includesRequiredProjects() {
        assertEquals(listOf("Квартал", "Альпен", "Квиз-Дуэль", "Хост Aeza"), MarsProjects.all.map { it.name })
    }
    @Test fun webProjectsExposeLinksAndHealthyStatuses() {
        assertTrue(MarsProjects.all.take(2).all { it.url.startsWith("https://") })
        assertTrue(MarsProjects.all.all { it.status == "Активен" || it.status == "OK" })
    }
}
