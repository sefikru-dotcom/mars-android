package ru.mars.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsMetricsParserTest {
    @Test fun parsesServicesAndHostMetrics() {
        val result = ProjectsMetricsParser.parse(JSONObject("""
            {"ts":"2026-10-07T11:32:05Z","prod":{"services":[{"title":"API","desc":"Core","url":"https://marscore.ru","state":"active","uptime":"2d","latency":42}]},"host":{"ram":"4 GB","cpu":"12%","disk":"60%","uptime":"10d"}}
        """))
        assertEquals("API", result.services.single().title)
        assertTrue(result.services.single().active)
        assertEquals("42", result.services.single().latency)
        assertEquals("10d", result.host?.uptime)
    }

    @Test fun toleratesMissingSectionsAndUnknownServiceState() {
        val result = ProjectsMetricsParser.parse(JSONObject("""{"prod":{"services":[{"state":"maintenance"}]}}"""))
        assertEquals("Сервис", result.services.single().title)
        assertEquals(false, result.services.single().active)
        assertEquals(null, result.host)
    }
}
