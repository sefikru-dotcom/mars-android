package ru.mars.network.model

/**
 * Wire models for `GET /api/insights` (contract verified read-only against
 * `/opt/hermes-webui/api/route_insights.py`).
 *
 * Query filters (all validated server-side, 400 `{"error": …}` on bad input):
 * - `days`  — int 1..365, default 7;
 * - `limit` — int 1..1000, default 50;
 * - `status`— `active` | `stuck` | `error` | `all` (default `all`);
 * - `scope` — reserved, echoed back.
 *
 * Failure modes: 400 invalid filter, 503 `insights unavailable` (ImportError),
 * 500 internal error — all as `{"error": "<msg>"}`.
 */

/** One `errors[]` bucket: a normalised error sample with its occurrence window. */
data class InsightsErrorGroup(
    val sample: String = "",
    val kind: String = "",
    val count: Int = 0,
    val firstSeen: String? = null,
    val lastSeen: String? = null,
)

/** One `timeline[]` point: sessions per day inside the requested window. */
data class InsightsTimelinePoint(
    val day: String = "",
    val count: Int = 0,
)

/** One `sessions[]` row. Every field is optional on the wire (partial imports). */
data class InsightsSession(
    val sessionId: String = "",
    val source: String = "",
    val whenEpoch: Long? = null,
    val isoTime: String? = null,
    val title: String? = null,
    val model: String? = null,
    val messageCount: Int = 0,
    val hasErrors: Boolean = false,
    val errorCount: Int = 0,
    val firstError: String? = null,
    val errorSample: String? = null,
    val errorKinds: List<String> = emptyList(),
)

/** One `knowledge.chunk_errors[]` row. */
data class InsightsChunkError(
    val sessionId: String = "",
    val errorCount: Int = 0,
    val firstError: String? = null,
    val lastError: String? = null,
)

/** One `knowledge.recent[]` row. */
data class InsightsKnowledgeRecent(
    val sessionId: String = "",
    val chunks: Int = 0,
    val documents: Int = 0,
    val project: String? = null,
    val status: String? = null,
    val error: String? = null,
    val indexedAt: String? = null,
    val indexedAtRelative: String? = null,
)

/** `knowledge` block — RAG index status; entirely optional (older servers omit it). */
data class InsightsKnowledge(
    val indexed: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    val queueSize: Int = 0,
    val pendingChunks: Int = 0,
    val chunkErrorCount: Int = 0,
    val totalChunks: Int = 0,
    val totalDocuments: Int = 0,
    val totalProjects: Int = 0,
    val sessionCount: Int = 0,
    val status: String? = null,
    val statusMessage: String? = null,
    val lastIndexedAt: String? = null,
    val lastIndexedRelative: String? = null,
    val lastIndexError: String? = null,
    val chunkErrors: List<InsightsChunkError> = emptyList(),
    val recent: List<InsightsKnowledgeRecent> = emptyList(),
)

/** Full `GET /api/insights` payload with all counters echoed by the server. */
data class InsightsSnapshot(
    val ok: Boolean = false,
    val generatedAt: String? = null,
    val days: Int = 0,
    val scope: String = "",
    val limit: Int = 0,
    val requestedLimit: Int? = null,
    val effectiveLimit: Int? = null,
    val maxLimit: Int? = null,
    val totalSessions: Int = 0,
    val filteredSessions: Int = 0,
    val returnedSessions: Int = 0,
    val sessionsScanned: Int = 0,
    val sessionsImported: Int = 0,
    val sessionsImportFailed: Int = 0,
    val active: Int = 0,
    val stuck: Int = 0,
    val longRunning: Int = 0,
    val errorStates: Int = 0,
    val errors: List<InsightsErrorGroup> = emptyList(),
    val kinds: Map<String, Int> = emptyMap(),
    val timeline: List<InsightsTimelinePoint> = emptyList(),
    val sessions: List<InsightsSession> = emptyList(),
    val knowledge: InsightsKnowledge? = null,
)
