package ru.mars.mobile

import org.json.JSONArray
import org.json.JSONObject

data class ParsedService(
    val title: String,
    val description: String,
    val url: String,
    val state: String,
    val active: Boolean,
    val uptime: String,
    /** Число рестартов сервиса — реальное поле ответа «restarts»; "" — сервер его не отдал. */
    val restarts: String,
    /** HTTP-код проверки доступности — реальное поле ответа «http»; "" — отсутствует. */
    val http: String,
)

data class ParsedHost(
    /** Честное имя хоста — из какого блока ответа сняты метрики (константы парсера). */
    val title: String,
    val ram: String,
    val cpu: String,
    val disk: String,
    val uptime: String,
)

data class ParsedMetrics(
    /** «ts» ответа — момент снятия метрик, напр. «14.10.2026 в 16:30:28 (МСК)». */
    val timestamp: String,
    val services: List<ParsedService>,
    /** Хост Aeza — блок «local» ответа (машина, где живёт сам Марс). */
    val aezaHost: ParsedHost?,
    /** Прод-сервер 45.129.2.36 — блок «prod.host» ответа. */
    val prodHost: ParsedHost?,
    /** Хост легаси-плоской схемы (корневой «host»/«host_metrics»); только если нет prod-блока. */
    val legacyHost: ParsedHost?,
) {
    /** Все доступные хосты в порядке показа: Aeza, затем прод, затем легаси. */
    val hosts: List<ParsedHost> get() = listOfNotNull(aezaHost, prodHost, legacyHost)
}

/**
 * Чистое преобразование dashboard JSON (https://marscore.ru/dashboard/api/metrics)
 * в модели для экрана проектов. Без зависимостей от Android, тестируется на JVM.
 *
 * Реальная схема (сверено с живым ответом 14.10.2026):
 * - «prod.host» — ПРОД-сервер 45.129.2.36: mem.text, disk.text, load;
 * - «local» — хост Aeza (VPS, где живёт Марс): mem.text, disk.text, load, services,
 *   radar_products. В старых срезах вместо объекта может быть пустой массив — тогда
 *   хост просто не показывается;
 * - «prod.services[]» — state, sub, uptime_sec, uptime, restarts, http. Поле «latency»
 *   сервер НЕ отдаёт никогда — в модели его нет (мёртвая ветка «Пинг: N ms» удалена).
 *
 * Легаси-схема: плоский host/host_metrics в корне со строковыми ram/cpu/disk/uptime —
 * поддерживается как [ParsedMetrics.legacyHost] и только когда «prod»-блока нет
 * (прод-блок всегда в приоритете).
 * Отсутствующее значение обозначается [MISSING]; сериализованный JSON вместо
 * текста не выдаётся никогда.
 */
object ProjectsMetricsParser {

    const val MISSING = "—"

    /** Заголовок карточки хоста из блока «local». */
    const val AEZA_HOST_TITLE = "Хост Aeza (mars)"

    /** Заголовок карточки хоста из блока «prod.host». */
    const val PROD_HOST_TITLE = "Прод-сервер 45.129.2.36"

    /** Легаси-плоская схема: достоверно назвать хост нельзя — нейтральный заголовок. */
    const val LEGACY_HOST_TITLE = "Хост"

    fun parse(data: JSONObject): ParsedMetrics {
        val prod = data.optJSONObject("prod")
        val services = parseServices(prod?.optJSONArray("services"))
        val prodHost = parseHost(prod?.optJSONObject("host"), PROD_HOST_TITLE)
        val legacyHost = if (prodHost == null) {
            parseHost(data.optJSONObject("host") ?: data.optJSONObject("host_metrics"), LEGACY_HOST_TITLE)
        } else {
            null
        }
        val aezaHost = parseHost(data.optJSONObject("local"), AEZA_HOST_TITLE)
        return ParsedMetrics(data.optString("ts", ""), services, aezaHost, prodHost, legacyHost)
    }

    private fun parseServices(array: JSONArray?): List<ParsedService> {
        if (array == null) return emptyList()
        val services = mutableListOf<ParsedService>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val state = item.optString("state", "offline")
            val normalized = state.lowercase()
            services += ParsedService(
                title = item.optString("title", "Сервис"),
                description = item.optString("desc"),
                url = item.optString("url"),
                state = state,
                active = normalized == "active" || normalized == "ok" || normalized == "online",
                uptime = item.optString("uptime"),
                restarts = textOf(item, "restarts") ?: "",
                http = textOf(item, "http") ?: "",
            )
        }
        return services
    }

    private fun parseHost(json: JSONObject?, title: String): ParsedHost? {
        if (json == null) return null
        return ParsedHost(
            title = title,
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
