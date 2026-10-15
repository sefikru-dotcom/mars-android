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

/**
 * Строка состояния плоского объекта-секции («vpn» без вложенного «host»,
 * «local.services»): ключ + сырой текст состояния + признак активности.
 */
data class ParsedStateEntry(
    val key: String,
    val state: String,
    /** Состояние считается активным (active/ok/online) — как в разборе prod-сервисов. */
    val active: Boolean,
)

/** Секция «vpn»: плоский объект состояний компонентов + собственное «host» VPN-узла. */
data class ParsedVpn(
    val states: List<ParsedStateEntry>,
    val host: ParsedHost?,
) {
    /** Нечего показывать: ни состояний, ни метрик узла. */
    val isEmpty: Boolean get() = states.isEmpty() && host == null
}

/** Туннель связи из объекта «tunnels» (ключ объекта — идентификатор туннеля). */
data class ParsedTunnel(
    val key: String,
    val name: String,
    val description: String,
    /** Порт текстом; "" — сервер его не отдал. */
    val port: String,
    /** Честный статус: false — туннель не на связи (эталон /dashboard/: «Ожидание»). */
    val online: Boolean,
)

/** Запись резервной копии из «prod.backups» (секция ВНУТРИ prod, не верхний уровень). */
data class ParsedBackup(
    val time: String,
    val archive: String,
    val size: String,
    val profiles: String,
    val status: String,
)

/** Запись журнала рестартов из «prod.restart_log»: «<ISO-время> <текст>». */
data class ParsedRestart(
    val time: String,
    val text: String,
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
    /** Секция «vpn»; null — секция отсутствует или не объект (усечённый ответ/старый кэш). */
    val vpn: ParsedVpn? = null,
    /** Секция «tunnels» — ОБЪЕКТ с динамическими ключами; пустой список, если её нет. */
    val tunnels: List<ParsedTunnel> = emptyList(),
    /** «local.services» — службы хоста Aeza (плоский объект состояний). */
    val hostServices: List<ParsedStateEntry> = emptyList(),
    /** «local.radar_products» — число товаров прайс-радара; null — поле не отдано. */
    val radarProducts: Int? = null,
    /** «prod.backups» — список резервных копий. */
    val backups: List<ParsedBackup> = emptyList(),
    /** «prod.restart_log» — журнал рестартов прод-сервисов. */
    val restartLog: List<ParsedRestart> = emptyList(),
    /** «alerts» — тексты алертов; пусто — алертов нет (секция не выводится). */
    val alerts: List<String> = emptyList(),
) {
    /** Все доступные хосты в порядке показа: Aeza, прод, VPN-узел, затем легаси. */
    val hosts: List<ParsedHost> get() = listOfNotNull(aezaHost, prodHost, vpn?.host, legacyHost)
}

/**
 * Чистое преобразование dashboard JSON (https://marscore.ru/dashboard/api/metrics)
 * в модели для экрана проектов. Без зависимостей от Android, тестируется на JVM.
 *
 * Реальная схема (сверено с живым ответом 15.10.2026, фикстура dashboard-metrics.json;
 * верхнеуровневых секций ровно 6: ts, prod, vpn, local, tunnels, alerts):
 * - «prod.host» — ПРОД-сервер 45.129.2.36: mem.text, disk.text, load;
 * - «prod.services[]» — state, sub, uptime_sec, uptime, restarts, http. Поле «latency»
 *   сервер НЕ отдаёт никогда — в модели его нет (мёртвая ветка «Пинг: N ms» удалена);
 * - «prod.backups[]» и «prod.restart_log[]» — ВНУТРИ prod, не на верхнем уровне;
 * - «vpn» — ПЛОСКИЙ объект состояний (xray/caddy/hysteria/sub/http) со своим «host»
 *   (у VPN-узла собственные mem/disk/load);
 * - «local» — хост Aeza (VPS, где живёт Марс): mem.text, disk.text, load, плюс
 *   «services» (плоский объект состояний) и «radar_products» (int). В старых срезах
 *   вместо объекта может быть пустой массив — тогда хост просто не показывается;
 * - «tunnels» — ОБЪЕКТ (не список!) с динамическими ключами: каждый
 *   {name, desc, port, online:bool}. Ключи могут появляться/исчезать — итерируем keys();
 * - «alerts» — список (сейчас сервер отдаёт пустой; форма элемента не стабилизирована).
 *
 * Сервер может отдать УСЕЧЁННЫЙ ответ (перезапуск сборщика): любая секция может
 * отсутствовать или быть null/пустым массивом — разбор обязан выжить, поэтому все
 * поля читаются через opt*, отсутствующая секция → пустой список/null.
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

    /** Заголовок хоста VPN-узла из блока «vpn.host» (имя — по эталонной странице /dashboard/). */
    const val VPN_HOST_TITLE = "VPN-узел STV (Нидерланды)"

    /** Состояния, которые считаются активными (как в существующем разборе сервисов). */
    private val ACTIVE_STATES = setOf("active", "ok", "online")

    fun parse(data: JSONObject): ParsedMetrics {
        val prod = data.optJSONObject("prod")
        val local = data.optJSONObject("local")
        val services = parseServices(prod?.optJSONArray("services"))
        val prodHost = parseHost(prod?.optJSONObject("host"), PROD_HOST_TITLE)
        val legacyHost = if (prodHost == null) {
            parseHost(data.optJSONObject("host") ?: data.optJSONObject("host_metrics"), LEGACY_HOST_TITLE)
        } else {
            null
        }
        val aezaHost = parseHost(local, AEZA_HOST_TITLE)
        val vpn = data.optJSONObject("vpn")?.let { vpnJson ->
            ParsedVpn(
                states = parseStates(vpnJson),
                host = parseHost(vpnJson.optJSONObject("host"), VPN_HOST_TITLE),
            )
        }
        return ParsedMetrics(
            timestamp = data.optString("ts", ""),
            services = services,
            aezaHost = aezaHost,
            prodHost = prodHost,
            legacyHost = legacyHost,
            vpn = vpn,
            tunnels = parseTunnels(data.optJSONObject("tunnels")),
            hostServices = parseStates(local?.optJSONObject("services")),
            radarProducts = intOf(local, "radar_products"),
            backups = parseBackups(prod?.optJSONArray("backups")),
            restartLog = parseRestartLog(prod?.optJSONArray("restart_log")),
            alerts = parseAlerts(data.optJSONArray("alerts")),
        )
    }

    private fun parseServices(array: JSONArray?): List<ParsedService> {
        if (array == null) return emptyList()
        val services = mutableListOf<ParsedService>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val state = item.optString("state", "offline")
            services += ParsedService(
                title = item.optString("title", "Сервис"),
                description = item.optString("desc"),
                url = item.optString("url"),
                state = state,
                active = isActiveState(state),
                uptime = item.optString("uptime"),
                restarts = textOf(item, "restarts") ?: "",
                http = textOf(item, "http") ?: "",
            )
        }
        return services
    }

    /**
     * Плоский объект состояний («vpn» без вложенного «host», «local.services»).
     * Ключи могут появляться/исчезать — итерация по keys(), без хардкода имён;
     * JSONObject не гарантирует порядок ключей, поэтому сортировка для стабильного
     * показа. Вложенные объекты/массивы (например «host» внутри «vpn») строками
     * состояния не считаются и пропускаются.
     */
    private fun parseStates(json: JSONObject?): List<ParsedStateEntry> {
        if (json == null) return emptyList()
        return json.keys().asSequence().sorted().mapNotNull { key ->
            val state = when (val value = json.opt(key)) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> null
                is String -> value.ifBlank { null }
                else -> value.toString().ifBlank { null }
            }
            state?.let { ParsedStateEntry(key, it, isActiveState(it)) }
        }.toList()
    }

    /** Секция «tunnels» — объект с динамическими ключами, каждый {name,desc,port,online}. */
    private fun parseTunnels(json: JSONObject?): List<ParsedTunnel> {
        if (json == null) return emptyList()
        return json.keys().asSequence().sorted().mapNotNull { key ->
            val item = json.optJSONObject(key) ?: return@mapNotNull null
            ParsedTunnel(
                key = key,
                name = textOf(item, "name") ?: key,
                description = textOf(item, "desc") ?: "",
                port = textOf(item, "port") ?: "",
                online = item.optBoolean("online", false),
            )
        }.toList()
    }

    /** «prod.backups» — список {time,archive,size,profiles,status}. */
    private fun parseBackups(array: JSONArray?): List<ParsedBackup> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ParsedBackup(
                time = textOf(item, "time") ?: "",
                archive = textOf(item, "archive") ?: "",
                size = textOf(item, "size") ?: "",
                profiles = textOf(item, "profiles") ?: "",
                status = textOf(item, "status") ?: "",
            )
        }
    }

    /** «prod.restart_log» — список строк «<ISO-время> <текст>»; режем по первому пробелу. */
    private fun parseRestartLog(array: JSONArray?): List<ParsedRestart> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val raw = when (val value = array.opt(i)) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> null // сериализованный JSON в UI не выдаём
                is String -> value.ifBlank { null }
                else -> value.toString().ifBlank { null }
            } ?: return@mapNotNull null
            val space = raw.indexOf(' ')
            if (space < 0) ParsedRestart(time = "", text = raw)
            else ParsedRestart(time = raw.substring(0, space), text = raw.substring(space + 1).trim())
        }
    }

    /**
     * «alerts» — список; форма элемента сервером не стабилизирована (сейчас пуст).
     * Строка берётся как есть, объект — по первому непустому из text/message/msg;
     * остальное пропускается (сериализованный JSON в UI не выдаём никогда).
     */
    private fun parseAlerts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            when (val value = array.opt(i)) {
                null, JSONObject.NULL -> null
                is JSONObject, is JSONArray -> null
                is String -> value.ifBlank { null }
                else -> value.toString().ifBlank { null }
            }
        }
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

    private fun isActiveState(state: String): Boolean = state.lowercase() in ACTIVE_STATES

    /** Целое из поля, только если сервер реально отдал число; иначе null (без выдумок). */
    private fun intOf(json: JSONObject?, key: String): Int? = (json?.opt(key) as? Number)?.toInt()

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
