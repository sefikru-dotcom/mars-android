package ru.mars.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsMetricsParserTest {

    /**
     * Реальный ответ https://marscore.ru/dashboard/api/metrics (срез 14.10.2026 16:30 МСК;
     * длинные массивы backups/restart_log укорочены, структура и значения сохранены).
     * Ключевое: «prod.host» — прод 45.129.2.36 (3.8 ГБ RAM, диск 29G), «local» — хост
     * Aeza, где живёт сам Марс (15.6 ГБ RAM, диск 50G). Сервисы отдают state/sub/
     * uptime_sec/uptime/restarts/http и НЕ отдают latency.
     */
    private val REAL_DASHBOARD_JSON = """
        {
          "ts": "14.10.2026 в 16:30:28 (МСК)",
          "prod": {
            "services": [
              {"name": "kvartal", "title": "Квартал", "desc": "Браузерная гео-игра на Leaflet/Node.js",
               "url": "https://kvartalpro.ru/", "state": "active", "sub": "running",
               "uptime_sec": 320043, "uptime": "3 д 16 ч 54 мин", "restarts": 0, "http": "200"},
              {"name": "karaoke-battle", "title": "Альпен", "desc": "Караоке-баттл (порт 8090)",
               "url": "https://alpenhall.ru/", "state": "active", "sub": "running",
               "uptime_sec": 103352, "uptime": "1 д 4 ч 42 мин", "restarts": 0, "http": "200"},
              {"name": "quiz-duel", "title": "Квиз-Дуэль", "desc": "Командная викторина с админкой и дуэлями",
               "url": "https://quiz-duel.example.com/", "state": "active", "sub": "running",
               "uptime_sec": 1235790, "uptime": "14 д 7 ч 16 мин", "restarts": 0, "http": "200"}
            ],
            "host": {
              "mem": {"total_gb": 3.8, "used_gb": 0.9, "avail_gb": 2.9, "pct": 22,
                      "text": "0.9 ГБ из 3.8 ГБ (22%)"},
              "disk": {"size": "29G", "used": "6.9G", "avail": "23G", "pct": 24,
                       "text": "Занято 6.9G из 29G (24%), свободно 23G"},
              "load": "1.04 (1 мин), 1.05 (5 мин)"
            },
            "backups": [
              {"time": "2026-10-14 01:30:01", "archive": "kvartal-data-2026-10-14-013001.tar.gz",
               "size": "2.9M", "profiles": "35", "status": "Успешно"}
            ],
            "restart_log": [
              "2026-09-29T07:42:54+03:00 kvartal restart triggered by root pid 234739"
            ],
            "telegram": []
          },
          "vpn": {"xray": "active", "caddy": "active", "hysteria": "active", "sub": "active", "http": "404"},
          "local": {
            "mem": {"total_gb": 15.6, "used_gb": 4.0, "avail_gb": 11.6, "pct": 25,
                    "text": "4.0 ГБ из 15.6 ГБ (25%)"},
            "disk": {"size": "50G", "used": "37G", "avail": "11G", "pct": 78,
                     "text": "Занято 37G из 50G (78%), свободно 11G"},
            "load": "1.27 (1 мин), 0.66 (5 мин)",
            "services": {"webui": "active", "neighbor": "active", "office": "active",
                         "gateway": "active", "caddy": "active", "socks": "active"},
            "radar_products": 287
          },
          "tunnels": [], "alerts": []
        }
    """.trimIndent()

    private fun parseReal() = ProjectsMetricsParser.parse(JSONObject(REAL_DASHBOARD_JSON))

    @Test fun parsesRealDashboardFixture() {
        val result = parseReal()
        assertEquals(listOf("Квартал", "Альпен", "Квиз-Дуэль"), result.services.map { it.title })
        val kvartal = result.services.first()
        assertEquals("Браузерная гео-игра на Leaflet/Node.js", kvartal.description)
        assertEquals("https://kvartalpro.ru/", kvartal.url)
        assertEquals("active", kvartal.state)
        assertTrue(kvartal.active)
        assertEquals("3 д 16 ч 54 мин", kvartal.uptime)
    }

    /** P0-4, дефект 3: «ts» ответа (момент снятия метрик) обязан доходить до модели. */
    @Test fun responseTsReachesParsedMetrics() {
        assertEquals("14.10.2026 в 16:30:28 (МСК)", parseReal().timestamp)
    }

    /**
     * P0-4, дефект 1: прод-метрики больше не подписываются именем Aeza. Каждый хост
     * парсится из своего блока ответа и несёт честное имя.
     */
    @Test fun bothHostsParsedFromOwnBlocksWithoutConfusion() {
        val result = parseReal()
        val aeza = result.aezaHost
        val prod = result.prodHost
        assertNotNull("хост Aeza обязан парситься из блока local", aeza)
        assertNotNull("прод обязан парситься из prod.host", prod)
        assertEquals(ProjectsMetricsParser.AEZA_HOST_TITLE, aeza!!.title)
        assertEquals(ProjectsMetricsParser.PROD_HOST_TITLE, prod!!.title)
        assertTrue("имя Aeza — только у хоста из local", aeza.title.contains("Aeza"))
        assertFalse("прод не имеет права называться Aeza", prod.title.contains("Aeza"))
        assertTrue("прод честно назван своим адресом", prod.title.contains("45.129.2.36"))
        // Aeza (блок local): 15.6 ГБ RAM, диск 50G.
        assertEquals("4.0 ГБ из 15.6 ГБ (25%)", aeza.ram)
        assertEquals("Занято 37G из 50G (78%), свободно 11G", aeza.disk)
        assertEquals("1.27 (1 мин), 0.66 (5 мин)", aeza.cpu)
        // Прод (блок prod.host): 3.8 ГБ RAM, диск 29G.
        assertEquals("0.9 ГБ из 3.8 ГБ (22%)", prod.ram)
        assertEquals("Занято 6.9G из 29G (24%), свободно 23G", prod.disk)
        assertEquals("1.04 (1 мин), 1.05 (5 мин)", prod.cpu)
        // Прямой запрет путаницы: метрики блоков не перепутаны местами.
        assertFalse(aeza.ram.contains("3.8"))
        assertFalse(aeza.disk.contains("29G"))
        assertFalse(prod.ram.contains("15.6"))
        assertFalse(prod.disk.contains("50G"))
    }

    @Test fun hostsExposedInDisplayOrderAezaThenProd() {
        assertEquals(
            listOf(ProjectsMetricsParser.AEZA_HOST_TITLE, ProjectsMetricsParser.PROD_HOST_TITLE),
            parseReal().hosts.map { it.title },
        )
    }

    /**
     * P0-4, дефект 2: сервер не отдаёт «latency» вообще — реальная схема сервиса
     * state/sub/uptime_sec/uptime/restarts/http, и модель следует ей.
     */
    @Test fun serviceFieldsFollowRealServerContractNotPhantomLatency() {
        assertFalse("в живой схеме latency отсутствует", REAL_DASHBOARD_JSON.contains("latency"))
        val kvartal = parseReal().services.first()
        assertEquals("0", kvartal.restarts)
        assertEquals("200", kvartal.http)
    }

    @Test fun serviceWithoutOptionalFieldsParsesToBlanks() {
        val result = ProjectsMetricsParser.parse(
            JSONObject("""{"prod":{"services":[{"title":"X","state":"active"}]}}""")
        )
        val s = result.services.single()
        assertTrue(s.active)
        assertEquals("", s.uptime)
        assertEquals("", s.restarts)
        assertEquals("", s.http)
    }

    @Test fun supportsLegacyFlatHostMetrics() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"host_metrics": {"ram": "4 GB", "cpu": "12%", "disk": "60%", "uptime": "10d"}}"""
        ))
        val legacy = result.legacyHost
        assertNotNull("легаси-схема обязана парситься и не падать", legacy)
        assertEquals(ProjectsMetricsParser.LEGACY_HOST_TITLE, legacy!!.title)
        assertEquals("4 GB", legacy.ram)
        assertEquals("12%", legacy.cpu)
        assertEquals("60%", legacy.disk)
        assertEquals("10d", legacy.uptime)
        assertEquals(listOf(legacy), result.hosts)
        assertNull(result.prodHost)
        assertNull(result.aezaHost)
    }

    @Test fun prodHostTakesPriorityOverLegacyFlatHost() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"prod": {"host": {"load": "0.5"}}, "host": {"cpu": "99%"}}"""
        ))
        assertEquals("0.5", result.prodHost!!.cpu)
        assertNull("легаси-блок игнорируется, пока есть prod", result.legacyHost)
    }

    /** В старых срезах local/vpn/tunnels могли быть пустыми массивами — не объектами. */
    @Test fun legacyEmptyArrayBlocksAreTolerated() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"ts":"x","prod":{"services":[]},"vpn": [], "local": [], "tunnels": [], "alerts": []}"""
        ))
        assertNull(result.aezaHost)
        assertTrue(result.hosts.isEmpty())
        assertEquals("x", result.timestamp)
    }

    @Test fun neverEmitsSerializedJsonAsText() {
        parseReal().hosts.forEach { h ->
            listOf(h.title, h.ram, h.cpu, h.disk, h.uptime).forEach {
                assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("{"))
                assertFalse("поле не должно содержать сериализованный JSON: $it", it.contains("\""))
            }
        }
    }

    @Test fun missingHostFieldsDenotedAsEmDashNotJson() {
        val prod = ProjectsMetricsParser.parse(JSONObject("""{"prod": {"host": {}}}""")).prodHost
        assertNotNull(prod)
        assertEquals("—", prod!!.ram)
        assertEquals("—", prod.cpu)
        assertEquals("—", prod.disk)
        assertEquals("—", prod.uptime)
    }

    @Test fun hostObjectWithoutTextFieldFallsBackToEmDash() {
        val result = ProjectsMetricsParser.parse(JSONObject(
            """{"prod": {"host": {"mem": {"pct": 22}, "disk": {"pct": 23}, "load": null}}}"""
        ))
        val prod = result.prodHost
        assertNotNull(prod)
        assertEquals("—", prod!!.ram)
        assertEquals("—", prod.disk)
        assertEquals("—", prod.cpu)
    }

    @Test fun toleratesMissingSectionsAndUnknownServiceState() {
        val result = ProjectsMetricsParser.parse(JSONObject("""{"prod":{"services":[{"state":"maintenance"}]}}"""))
        assertEquals("Сервис", result.services.single().title)
        assertFalse(result.services.single().active)
        assertTrue(result.hosts.isEmpty())
    }

    @Test fun emptyPayloadProducesNoServicesAndNoHosts() {
        val result = ProjectsMetricsParser.parse(JSONObject("{}"))
        assertTrue(result.services.isEmpty())
        assertNull(result.aezaHost)
        assertNull(result.prodHost)
        assertNull(result.legacyHost)
        assertTrue(result.hosts.isEmpty())
        assertEquals("", result.timestamp)
    }
}
