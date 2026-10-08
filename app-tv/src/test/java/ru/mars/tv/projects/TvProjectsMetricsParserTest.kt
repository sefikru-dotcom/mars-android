package ru.mars.tv.projects

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт GET https://marscore.ru/dashboard/api/metrics проверен живым запросом
 * (срез 08.10.2026) и кодом мобильного ProjectsMetricsParser: prod.host.mem.text,
 * prod.host.disk.text, prod.host.load, prod.services[] {title, desc, url, state,
 * uptime, latency?}, корневой ts. Легаси: плоский host / host_metrics.
 */
class TvProjectsMetricsParserTest {

    /** Реальный ответ dashboard API (срез 08.10.2026, backups/restart_log опущены). */
    private val REAL_DASHBOARD_JSON = """
        {
          "ts": "08.10.2026 в 22:19:34 (МСК)",
          "prod": {
            "services": [
              {"name": "kvartal", "title": "Квартал", "desc": "Браузерная гео-игра на Leaflet/Node.js",
               "url": "https://kvartalpro.ru/", "state": "active", "sub": "running",
               "uptime_sec": 225498, "uptime": "2 д 14 ч 38 мин", "restarts": 0, "http": "200"},
              {"name": "karaoke-battle", "title": "Альпен", "desc": "Караоке-баттл (порт 8090)",
               "url": "https://alpenhall.ru/", "state": "active", "sub": "running",
               "uptime_sec": 738336, "uptime": "8 д 13 ч 5 мин", "restarts": 0, "http": "200"},
              {"name": "quiz-duel", "title": "Квиз-Дуэль", "desc": "Командная викторина с админкой и дуэлями",
               "url": "https://quiz-duel.example.com/", "state": "active", "sub": "running",
               "uptime_sec": 738336, "uptime": "8 д 13 ч 5 мин", "restarts": 0, "http": "200"}
            ],
            "host": {
              "mem": {"total_gb": 3.8, "used_gb": 0.8, "avail_gb": 3.0, "pct": 22,
                      "text": "0.8 ГБ из 3.8 ГБ (22%)"},
              "disk": {"size": "29G", "used": "6.7G", "avail": "23G", "pct": 24,
                       "text": "Занято 6.7G из 29G (24%), свободно 23G"},
              "load": "1.22 (1 мин), 1.10 (5 мин)"
            }
          },
          "vpn": [], "local": [], "tunnels": [], "alerts": []
        }
    """.trimIndent()

    @Test
    fun parsesRealDashboardFixture() {
        val result = TvProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON))
        assertEquals("08.10.2026 в 22:19:34 (МСК)", result.timestamp)
        assertEquals(3, result.services.size)
        assertEquals(listOf("Квартал", "Альпен", "Квиз-Дуэль"), result.services.map { it.title })
        val kvartal = result.services.first()
        assertEquals("Браузерная гео-игра на Leaflet/Node.js", kvartal.description)
        assertEquals("https://kvartalpro.ru/", kvartal.url)
        assertEquals("active", kvartal.state)
        assertTrue(kvartal.active)
        assertEquals("2 д 14 ч 38 мин", kvartal.uptime)
    }

    @Test
    fun readsNestedHostTextFieldsFromRealDashboard() {
        val host = TvProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON)).host
        assertNotNull(host)
        assertEquals("0.8 ГБ из 3.8 ГБ (22%)", host!!.ram)
        assertEquals("Занято 6.7G из 29G (24%), свободно 23G", host.disk)
        assertEquals("1.22 (1 мин), 1.10 (5 мин)", host.cpu)
        assertEquals("—", host.uptime)
    }

    @Test
    fun neverEmitsSerializedJsonAsText() {
        val parsed = TvProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON))
        val host = parsed.host
        assertNotNull(host)
        val fields = listOf(host!!.ram, host.cpu, host.disk, host.uptime) +
            parsed.services.flatMap { listOf(it.title, it.description, it.uptime, it.latency) }
        fields.forEach {
            assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("{"))
            assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("\""))
        }
    }

    @Test
    fun supportsLegacyFlatHostAndHostMetrics() {
        val viaHostMetrics = TvProjectsMetricsParser.parse(
            JSONObject("""{"host_metrics": {"ram": "4 GB", "cpu": "12%", "disk": "60%", "uptime": "10d"}}""")
        ).host
        assertNotNull(viaHostMetrics)
        assertEquals("4 GB", viaHostMetrics!!.ram)
        assertEquals("12%", viaHostMetrics.cpu)
        assertEquals("60%", viaHostMetrics.disk)
        assertEquals("10d", viaHostMetrics.uptime)

        val viaHost = TvProjectsMetricsParser.parse(
            JSONObject("""{"host": {"ram": "2 GB", "cpu": "5%", "disk": "40%", "uptime": "3d"}}""")
        ).host
        assertNotNull(viaHost)
        assertEquals("2 GB", viaHost!!.ram)
    }

    @Test
    fun prodHostWinsOverLegacyFlatHost() {
        val host = TvProjectsMetricsParser.parse(
            JSONObject(
                """{"prod": {"host": {"mem": {"text": "prod mem"}}}, "host": {"ram": "legacy"}}"""
            )
        ).host
        assertNotNull(host)
        assertEquals("prod mem", host!!.ram)
    }

    @Test
    fun missingHostValuesBecomeEmDashNotRawJson() {
        val host = TvProjectsMetricsParser.parse(
            JSONObject("""{"prod": {"host": {"mem": {"pct": 22}, "disk": null}}}""")
        ).host
        assertNotNull(host)
        assertEquals("—", host!!.ram)
        assertEquals("—", host.cpu)
        assertEquals("—", host.disk)
        assertEquals("—", host.uptime)
    }

    @Test
    fun normalizesServiceStates() {
        val json = JSONObject(
            """{"prod": {"services": [
                 {"title": "A", "state": "active"},
                 {"title": "B", "state": "OK"},
                 {"title": "C", "state": "online"},
                 {"title": "D", "state": "stopped"},
                 {"title": "E", "state": "error"},
                 {"title": "F"}
               ]}}"""
        )
        val services = TvProjectsMetricsParser.parse(json).services
        assertEquals(listOf(true, true, true, false, false, false), services.map { it.active })
        // Сырое состояние сохраняется для явной индикации в UI.
        assertEquals(listOf("active", "OK", "online", "stopped", "error", ""), services.map { it.state })
    }

    @Test
    fun malformedServicesAreSkippedAndTitleFallsBack() {
        val parsed = TvProjectsMetricsParser.parse(
            JSONObject("""{"prod": {"services": [null, 42, {"desc": "без title"}]}}""")
        )
        assertEquals(1, parsed.services.size)
        assertEquals("Сервис", parsed.services.first().title)
        assertFalse(parsed.services.first().active)
    }

    @Test
    fun emptyPayloadParsesToSafeEmptyResult() {
        val parsed = TvProjectsMetricsParser.parse(JSONObject("{}"))
        assertEquals("", parsed.timestamp)
        assertTrue(parsed.services.isEmpty())
        assertNull(parsed.host)
    }

    @Test
    fun primitiveHostFieldsAreStringified() {
        val host = TvProjectsMetricsParser.parse(
            JSONObject("""{"host": {"ram": "4 GB", "cpu": 12, "disk": 60.5}}""")
        ).host
        assertNotNull(host)
        assertEquals("4 GB", host!!.ram)
        assertEquals("12", host.cpu)
        assertEquals("60.5", host.disk)
    }
}
