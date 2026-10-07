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

/**
 * Чистое преобразование dashboard JSON (https://marscore.ru/dashboard/api/metrics)
 * в модели для экрана проектов. Без зависимостей от Android, тестируется на JVM.
 *
 * Реальная схема: prod.host.mem.text, prod.host.disk.text, prod.host.load.
 * Легаси-схема: плоский host/host_metrics со строковыми ram/cpu/disk/uptime.
 * Отсутствующее значение обозначается [MISSING]; сериализованный JSON вместо
 * текста не выдаётся никогда.
 */
object ProjectsMetricsParser {

    const val MISSING = "—"

    fun parse(data: JSONObject): ParsedMetrics {
        val prod = data.optJSONObject("prod")
        val services = parseServices(prod?.optJSONArray("services"))
        val host = parseHost(prod?.optJSONObject("host"))
            ?: parseHost(data.optJSONObject("host") ?: data.optJSONObject("host_metrics"))
        return ParsedMetrics(data.optString("ts", ""), services, host)
    }

    private fun parseServices(array: org.json.JSONArray?): List<ParsedService> {
        if (array == null) return emptyList()
        val services = mutableListOf<ParsedService>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val state = item.optString("state", "offline")
            val normalized = state.lowercase()
            services += ParsedService(
                item.optString("title", "Сервис"), item.optString("desc"), item.optString("url"), state,
                normalized == "active" || normalized == "ok" || normalized == "online",
                item.optString("uptime"), item.optString("latency")
            )
        }
        return services
    }

    private fun parseHost(json: JSONObject?): ParsedHost? {
        if (json == null) return null
        return ParsedHost(
            ram = textOf(json, "mem") ?: textOf(json, "ram") ?: MISSING,
            cpu = textOf(json, "load") ?: textOf(json, "cpu") ?: MISSING,
            disk = textOf(json, "disk") ?: MISSING,
            uptime = textOf(json, "uptime") ?: MISSING,
        )
    }

    /**
     * Человекочитаемый текст из поля: объект -> его "text", строка -> как есть,
     * примитив -> toString, отсутствующее/пустое/NULL -> null.
     * Гарантирует, что JSON-объект не попадёт в UI строкой.
     */
    private fun textOf(json: JSONObject, key: String): String? {
        return when (val value = json.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.optString("text", "").ifBlank { null }
            is String -> value.ifBlank { null }
            else -> value.toString()
        }
    }
}
