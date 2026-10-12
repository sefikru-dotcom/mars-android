package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import java.io.IOException

/** Outcome of `GET /api/logs`. */
sealed interface ServerLogsResult {
    /**
     * 2xx with a parseable window. A not-yet-existing log file is ALSO a Window
     * (empty `lines` + non-empty `hint`) — the server answers 200 for it.
     */
    data class Window(val window: ServerLogWindow) : ServerLogsResult

    /** 400 — the server rejected the file key (client enums make this unreachable
     * unless the server whitelist changed; surfaced honestly, not swallowed). */
    data class InvalidRequest(val message: String) : ServerLogsResult

    /** Other HTTP errors (mapped `error` message when present) or transport failure. */
    data class Failed(val message: String) : ServerLogsResult
}

/**
 * `GET /api/logs?file=&tail=` — bounded tail of a whitelisted Hermes log file of the
 * ACTIVE profile (contract verified read-only against `/opt/hermes-webui/api/route_logs.py`).
 *
 * Both query params are whitelisted server-side: `file ∈ {agent, errors, gateway}`
 * (anything else → 400 `Unknown log file`) and `tail ∈ {100, 200, 500, 1000}`
 * (anything else silently resets to 200). The [ServerLogFile]/[ServerLogTail] enums
 * mirror both whitelists, so a request built through this repository can never be
 * malformed; the server caps the read window at 4 MiB and flags [ServerLogWindow.truncated].
 */
class LogsRepository(private val client: OkHttpClient = HttpClients.rest) {

    suspend fun getLogs(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        file: ServerLogFile = ServerLogFile.AGENT,
        tail: ServerLogTail = ServerLogTail.TAIL_200,
    ): ServerLogsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/logs")
            .addQueryParameter("file", file.wireKey)
            .addQueryParameter("tail", tail.lines.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = TolerantJson.parseMap(body)
                val code = response.code
                when {
                    root == null && code in 200..299 ->
                        ServerLogsResult.Failed("HTTP $code: unparseable body")
                    code == 400 -> ServerLogsResult.InvalidRequest(errorOf(root, code))
                    code in 200..299 -> ServerLogsResult.Window(parseWindow(root!!))
                    else -> ServerLogsResult.Failed(errorOf(root, code))
                }
            }
        } catch (error: IOException) {
            // IOException messages carry host/path at worst — never request headers, no token leak.
            ServerLogsResult.Failed(error.message ?: "Network error")
        }
    }

    private fun errorOf(root: Map<String, Any?>?, code: Int): String =
        TolerantJson.string(root, "error") ?: "HTTP $code"

    /** Tolerant parse: `mtime` is null for a missing file, `hint` may be absent (older builds). */
    internal fun parseWindow(root: Map<String, Any?>): ServerLogWindow = ServerLogWindow(
        file = TolerantJson.string(root, "file").orEmpty(),
        tail = TolerantJson.int(root, "tail"),
        lines = TolerantJson.listField(root, "lines").mapNotNull { value ->
            when (value) {
                null -> null // a null "line" is junk — drop it
                is String -> value // blank log lines ARE legitimate content — keep them verbatim
                else -> TolerantJson.renderString(value) ?: value.toString()
            }
        },
        truncated = TolerantJson.bool(root, "truncated"),
        totalBytes = TolerantJson.longOrNull(root, "total_bytes") ?: 0L,
        mtime = TolerantJson.doubleOrNull(root, "mtime"),
        hint = TolerantJson.string(root, "hint").orEmpty(),
    )
}
