package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.InsightsChunkError
import ru.mars.network.model.InsightsErrorGroup
import ru.mars.network.model.InsightsKnowledge
import ru.mars.network.model.InsightsKnowledgeRecent
import ru.mars.network.model.InsightsSession
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.InsightsTimelinePoint
import java.io.IOException

/** Outcome of `GET /api/insights`. */
sealed interface InsightsResult {
    /** 2xx with a parseable snapshot. */
    data class Snapshot(val snapshot: InsightsSnapshot) : InsightsResult

    /** 400 — invalid `days`/`limit`/`status` filter (server rejects, nothing fetched). */
    data class InvalidRequest(val message: String) : InsightsResult

    /** 503 — insights pipeline unavailable on the server (ImportError). */
    data class Unavailable(val message: String) : InsightsResult

    /** Other HTTP errors (mapped `error` message when present) or transport failure. */
    data class Failed(val message: String) : InsightsResult
}

/**
 * `GET /api/insights` — session/error/knowledge digest of the WebUI backend
 * (contract verified read-only against `/opt/hermes-webui/api/route_insights.py`;
 * error bodies are `{"error": "<msg>"}`).
 *
 * Filters (all optional, validated server-side):
 * - [days]   — 1..365, server default 7;
 * - [limit]  — 1..1000, server default 50 (the server CLAMPS over-limit requests and
 *   echoes `requested_limit`/`effective_limit`/`max_limit` in the snapshot);
 * - [status] — `active` | `stuck` | `error` | `all` (default `all`);
 * - [scope]  — reserved by the server, echoed back verbatim.
 *
 * Client-side values are passed through as-is: the server's 400 answer is surfaced
 * as [InsightsResult.InvalidRequest] instead of being silently pre-clamped, so a UI
 * bug stays visible rather than fetching a window the caller did not ask for.
 */
class InsightsRepository(private val client: OkHttpClient = HttpClients.rest) {

    suspend fun getInsights(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        days: Int? = null,
        limit: Int? = null,
        status: String? = null,
        scope: String? = null,
    ): InsightsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/insights")
            .intParam("days", days)
            .intParam("limit", limit)
            .stringParam("status", status)
            .stringParam("scope", scope)
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
                        InsightsResult.Failed("HTTP $code: unparseable body")
                    code == 400 -> InsightsResult.InvalidRequest(errorOf(root, code))
                    code == 503 -> InsightsResult.Unavailable(errorOf(root, code))
                    code in 200..299 -> InsightsResult.Snapshot(parseSnapshot(root!!))
                    else -> InsightsResult.Failed(errorOf(root, code))
                }
            }
        } catch (error: IOException) {
            // IOException messages carry host/path at worst — never request headers, no token leak.
            InsightsResult.Failed(error.message ?: "Network error")
        }
    }

    private fun errorOf(root: Map<String, Any?>?, code: Int): String =
        TolerantJson.string(root, "error") ?: "HTTP $code"

    // ── parsing (tolerant: every counter/section degrades to zero/empty when absent) ──

    internal fun parseSnapshot(root: Map<String, Any?>): InsightsSnapshot = InsightsSnapshot(
        ok = TolerantJson.bool(root, "ok"),
        generatedAt = TolerantJson.string(root, "generated_at"),
        days = TolerantJson.int(root, "days"),
        scope = TolerantJson.string(root, "scope").orEmpty(),
        limit = TolerantJson.int(root, "limit"),
        requestedLimit = TolerantJson.intOrNull(root, "requested_limit"),
        effectiveLimit = TolerantJson.intOrNull(root, "effective_limit"),
        maxLimit = TolerantJson.intOrNull(root, "max_limit"),
        totalSessions = TolerantJson.int(root, "total_sessions"),
        filteredSessions = TolerantJson.int(root, "filtered_sessions"),
        returnedSessions = TolerantJson.int(root, "returned_sessions"),
        sessionsScanned = TolerantJson.int(root, "sessions_scanned"),
        sessionsImported = TolerantJson.int(root, "sessions_imported"),
        sessionsImportFailed = TolerantJson.int(root, "sessions_import_failed"),
        active = TolerantJson.int(root, "active"),
        stuck = TolerantJson.int(root, "stuck"),
        longRunning = TolerantJson.int(root, "long_running"),
        errorStates = TolerantJson.int(root, "error_states"),
        errors = TolerantJson.listField(root, "errors").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsErrorGroup(
                sample = TolerantJson.string(map, "sample").orEmpty(),
                kind = TolerantJson.string(map, "kind").orEmpty(),
                count = TolerantJson.int(map, "count"),
                firstSeen = TolerantJson.string(map, "first_seen"),
                lastSeen = TolerantJson.string(map, "last_seen"),
            )
        },
        kinds = TolerantJson.intCounts(root, "kinds"),
        timeline = TolerantJson.listField(root, "timeline").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsTimelinePoint(
                day = TolerantJson.string(map, "day").orEmpty(),
                count = TolerantJson.int(map, "count"),
            )
        },
        sessions = TolerantJson.listField(root, "sessions").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsSession(
                sessionId = TolerantJson.string(map, "session_id").orEmpty(),
                source = TolerantJson.string(map, "source").orEmpty(),
                whenEpoch = TolerantJson.longOrNull(map, "when"),
                isoTime = TolerantJson.string(map, "iso_time"),
                title = TolerantJson.string(map, "title"),
                model = TolerantJson.string(map, "model"),
                messageCount = TolerantJson.int(map, "message_count"),
                hasErrors = TolerantJson.bool(map, "has_errors"),
                errorCount = TolerantJson.int(map, "error_count"),
                firstError = TolerantJson.string(map, "first_error"),
                errorSample = TolerantJson.string(map, "error_sample"),
                errorKinds = TolerantJson.stringListOrEmpty(map, "error_kinds"),
            )
        },
        knowledge = TolerantJson.mapField(root, "knowledge")?.let { parseKnowledge(it) },
    )

    private fun parseKnowledge(map: Map<String, Any?>): InsightsKnowledge = InsightsKnowledge(
        indexed = TolerantJson.int(map, "indexed"),
        queued = TolerantJson.int(map, "queued"),
        failed = TolerantJson.int(map, "failed"),
        queueSize = TolerantJson.int(map, "queue_size"),
        pendingChunks = TolerantJson.int(map, "pending_chunks"),
        chunkErrorCount = TolerantJson.int(map, "chunk_error_count"),
        totalChunks = TolerantJson.int(map, "total_chunks"),
        totalDocuments = TolerantJson.int(map, "total_documents"),
        totalProjects = TolerantJson.int(map, "total_projects"),
        sessionCount = TolerantJson.int(map, "session_count"),
        status = TolerantJson.string(map, "status"),
        statusMessage = TolerantJson.string(map, "status_message"),
        lastIndexedAt = TolerantJson.string(map, "last_indexed_at"),
        lastIndexedRelative = TolerantJson.string(map, "last_indexed_relative"),
        lastIndexError = TolerantJson.string(map, "last_index_error"),
        chunkErrors = TolerantJson.listField(map, "chunk_errors").mapNotNull { entry ->
            val row = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsChunkError(
                sessionId = TolerantJson.string(row, "session_id").orEmpty(),
                errorCount = TolerantJson.int(row, "error_count"),
                firstError = TolerantJson.string(row, "first_error"),
                lastError = TolerantJson.string(row, "last_error"),
            )
        },
        recent = TolerantJson.listField(map, "recent").mapNotNull { entry ->
            val row = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsKnowledgeRecent(
                sessionId = TolerantJson.string(row, "session_id").orEmpty(),
                chunks = TolerantJson.int(row, "chunks"),
                documents = TolerantJson.int(row, "documents"),
                project = TolerantJson.string(row, "project"),
                status = TolerantJson.string(row, "status"),
                error = TolerantJson.string(row, "error"),
                indexedAt = TolerantJson.string(row, "indexed_at"),
                indexedAtRelative = TolerantJson.string(row, "indexed_at_relative"),
            )
        },
    )

    private fun HttpUrl.Builder.stringParam(name: String, value: String?): HttpUrl.Builder =
        apply { value?.takeIf(String::isNotBlank)?.let { addQueryParameter(name, it) } }

    private fun HttpUrl.Builder.intParam(name: String, value: Int?): HttpUrl.Builder =
        apply { value?.let { addQueryParameter(name, it.toString()) } }
}
