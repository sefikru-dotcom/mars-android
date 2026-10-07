package ru.mars.mobile

import org.json.JSONObject

data class ParsedService(
    val title: String,
    val description: String,
    val url: String,
    val state: String,
    val active: Boolean,
    val uptime: String,
    val latency: String,
)

data class ParsedHost(
    val ram: String,
    val cpu: String,
    val disk: String,
    val uptime: String,
)

data class ParsedMetrics(val timestamp: String, val services: List<ParsedService>, val host: ParsedHost?)

object ProjectsMetricsParser {
    fun parse(data: JSONObject): ParsedMetrics {
        val services = mutableListOf<ParsedService>()
        val array = data.optJSONObject("prod")?.optJSONArray("services")
        if (array != null) for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val state = item.optString("state", "offline")
            val normalized = state.lowercase()
            services += ParsedService(
                item.optString("title", "Сервис"), item.optString("desc"), item.optString("url"), state,
                normalized == "active" || normalized == "ok" || normalized == "online",
                item.optString("uptime"), item.optString("latency")
            )
        }
        val hostJson = data.optJSONObject("host") ?: data.optJSONObject("host_metrics")
        val host = hostJson?.let { ParsedHost(it.optString("ram", "—"), it.optString("cpu", "—"), it.optString("disk", "—"), it.optString("uptime", "—")) }
        return ParsedMetrics(data.optString("ts", ""), services, host)
    }
}
