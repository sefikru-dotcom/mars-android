package ru.mars.tv.projects

import org.json.JSONArray
import org.json.JSONObject

data class TvServiceMetrics(
    val title: String,
    val description: String,
    val url: String,
    /** Сырое состояние из API (active/stopped/...); пустая строка, если поле отсутствует. */
    val state: String,
    val active: Boolean,
    val uptime: String,
    val latency: String,
)

data class TvHostMetrics(
    val ram: String,
    val cpu: String,
    val disk: String,
    val uptime: String,
)

data class TvProjectsMetrics(
    val timestamp: String,
    val services: List<TvServiceMetrics>,
    val host: TvHostMetrics?,
)

/**
 * Чистое преобразование dashboard JSON (GET https://marscore.ru/dashboard/api/metrics)
 * в модели TV-экрана проектов. Без зависимостей от Android, тестируется на JVM.
 *
 * Контракт сверен с живым API и мобильным ProjectsMetricsParser (app-mobile):
 * актуальная схема — prod.host.mem.text / prod.host.disk.text / prod.host.load и
 * prod.services[] {title, desc, url, state, uptime, latency?}; легаси — плоский
 * host / host_metrics со строковыми ram/cpu/disk/uptime.
 *
 * Гарантии: отсутствующее значение обозначается [MISSING] (прочерк), сериализованный
 * JSON вместо текста не выдаётся никогда, состояние сервиса нормализуется в
 * [TvServiceMetrics.active], а сырое состояние сохраняется для явной индикации.
 */
object TvProjectsMetricsParser {

    const val MISSING = "—"

    private val ACTIVE_STATES = setOf("active", "ok", "online")

    fun parse(data: JSONObject): TvProjectsMetrics {
        val prod = data.optJSONObject("prod")
        val services = parseServices(prod?.optJSONArray("services"))
        val host = parseHost(prod?.optJSONObject("host"))
            ?: parseHost(data.optJSONObject("host") ?: data.optJSONObject("host_metrics"))
        return TvProjectsMetrics(data.optString("ts", ""), services, host)
    }

    private fun parseServices(array: JSONArray?): List<TvServiceMetrics> {
        if (array == null) return emptyList()
        val services = mutableListOf<TvServiceMetrics>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val state = item.optString("state", "")
            services += TvServiceMetrics(
                title = item.optString("title", "Сервис"),
                description = item.optString("desc"),
                url = item.optString("url"),
                state = state,
                active = state.lowercase() in ACTIVE_STATES,
                uptime = item.optString("uptime"),
                latency = item.optString("latency"),
            )
        }
        return services
    }

    private fun parseHost(json: JSONObject?): TvHostMetrics? {
        if (json == null) return null
        return TvHostMetrics(
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
