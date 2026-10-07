package ru.mars.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsMetricsParserTest {

    /** Реальный ответ https://marscore.ru/dashboard/api/metrics (срез 07.10.2026). */
    private val REAL_DASHBOARD_JSON = """
        {
          "ts": "07.10.2026 в 22:54:48 (МСК)",
          "prod": {
            "services": [
              {"name": "kvartal", "title": "Квартал", "desc": "Браузерная гео-игра на Leaflet/Node.js",
               "url": "https://kvartalpro.ru/", "state": "active", "sub": "running",
               "uptime_sec": 141211, "uptime": "1 д 15 ч 13 мин", "restarts": 0, "http": "200"},
              {"name": "karaoke-battle", "title": "Альпен", "desc": "Караоке-баттл (порт 8090)",
               "url": "https://alpenhall.ru/", "state": "active", "sub": "running",
               "uptime_sec": 654049, "uptime": "7 д 13 ч 40 мин", "restarts": 0, "http": "200"},
              {"name": "quiz-duel", "title": "Квиз-Дуэль", "desc": "Командная викторина с админкой и дуэлями",
               "url": "https://quiz-duel.45.129.2.36.nip.io/", "state": "active", "sub": "running",
               "uptime_sec": 654049, "uptime": "7 д 13 ч 40 мин", "restarts": 0, "http": "200"}
            ],
            "host": {
              "mem": {"total_gb": 3.8, "used_gb": 0.9, "avail_gb": 3.0, "pct": 22,
                      "text": "0.9 ГБ из 3.8 ГБ (22%)"},
              "disk": {"size": "29G", "used": "6.7G", "avail": "23G", "pct": 23,
                       "text": "Занято 6.7G из 29G (23%), свободно 23G"},
              "load": "1.00 (1 мин), 1.00 (5 мин)"
            }
          },
          "vpn": [], "local": [], "tunnels": [], "alerts": []
        }
    """.trimIndent()

    @Test fun parsesRealDashboardFixture() {
        val result = ProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON))
        assertEquals("07.10.2026 в 22:54:48 (МСК)", result.timestamp)
        assertEquals(3, result.services.size)
        val kvartal = result.services.first()
        assertEquals("Квартал", kvartal.title)
        assertEquals("Браузерная гео-игра на Leaflet/Node.js", kvartal.description)
        assertEquals("https://kvartalpro.ru/", kvartal.url)
        assertTrue(kvartal.active)
        assertEquals("1 д 15 ч 13 мин", kvartal.uptime)
        assertEquals(listOf("Квартал", "Альпен", "Квиз-Дуэль"), result.services.map { it.title })
    }

    @Test fun readsNestedHostTextFieldsFromRealDashboard() {
        val host = ProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON)).host
        assertNotNull(host)
        assertEquals("0.9 ГБ из 3.8 ГБ (22%)", host!!.ram)
        assertEquals("Занято 6.7G из 29G (23%), свободно 23G", host.disk)
        assertEquals("1.00 (1 мин), 1.00 (5 мин)", host.cpu)
        assertEquals("—", host.uptime)
    }

    @Test fun neverEmitsSerializedJsonAsText() {
        val host = ProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON)).host
        assertNotNull(host)
        listOf(host!!.ram, host.cpu, host.disk, host.uptime).forEach {
            assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("{"))
            assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("\""))
        }
    }

    @Test fun supportsLegacyFlatHostMetrics() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"host_metrics": {"ram": "4 GB", "cpu": "12%", "disk": "60%", "uptime": "10d"}}"""
        ))
        val host = result.host
        assertNotNull(host)
        assertEquals("4 GB", host!!.ram)
        assertEquals("12%", host.cpu)
        assertEquals("60%", host.disk)
        assertEquals("10d", host.uptime)
    }

    @Test fun missingHostDenotedAsEmDashNotJson() {
        val result = ProjectsMetricsParser.parse(JSONObject("""{"prod": {"host": {}}}"""))
        val host = result.host
        assertNotNull(host)
        assertEquals("—", host!!.ram)
        assertEquals("—", host.cpu)
        assertEquals("—", host.disk)
        assertEquals("—", host.uptime)
    }

    @Test fun hostObjectWithoutTextFieldFallsBackToEmDash() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"prod": {"host": {"mem": {"pct": 22}, "disk": {"pct": 23}, "load": null}}}"""
        ))
        val host = result.host
        assertNotNull(host)
        assertEquals("—", host!!.ram)
        assertEquals("—", host.disk)
        assertEquals("—", host.cpu)
    }

    @Test fun prodHostTakesPriorityOverLegacyFlatHost() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"prod": {"host": {"load": "0.5"}}, "host": {"cpu": "99%"}}"""
        ))
        assertEquals("0.5", result.host!!.cpu)
    }

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
        assertNull(result.host)
    }

    @Test fun emptyPayloadProducesNoServicesAndNoHost() {
        val result = ProjectsMetricsParser.parse(JSONObject("{}"))
        assertTrue(result.services.isEmpty())
        assertNull(result.host)
        assertEquals("", result.timestamp)
    }
}
