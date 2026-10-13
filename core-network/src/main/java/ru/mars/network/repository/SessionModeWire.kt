package ru.mars.network.repository

import ru.mars.network.model.ConsumedDeviceCredential

/**
 * ISOLATED WIRE ADAPTER for the session-autonomy contract.
 *
 * The server (hermes-webui `api/routes.py` / `static/messages.js`) exposes this feature under a
 * legacy internal name: endpoint `/api/session/yolo`, request flag `"yolo"`, response field
 * `"yolo_enabled"`. Those literals are part of the server contract and must match byte-for-byte;
 * they live ONLY in this file. Everything else (core-network repositories, app-mobile, app-tv)
 * speaks the neutral domain language ("autonomy") and maps through this adapter.
 *
 * Consumers:
 * - [SessionAutonomyRepository] — toggle path + settled-state response field;
 * - [InteractiveRequestsRepository] — grant flag in approval responds + settled-state field in
 *   both success and error payloads.
 */
object SessionModeWire {

    /** Path segments of the session-autonomy lookup/toggle endpoint (`GET`/`POST`). */
    const val SESSION_MODE_PATH = "api/session/yolo"

    /** Response field carrying the settled autonomy state (present on success AND error bodies). */
    const val ENABLED_FIELD = "yolo_enabled"

    /** Request flag of an approval respond that grants session-wide autonomy. */
    const val GRANT_FIELD = "yolo"

    /** Reads the settled autonomy flag from a parsed response body (null when absent/non-bool). */
    fun readEnabledFlag(obj: Map<String, Any?>?): Boolean? = obj?.get(ENABLED_FIELD) as? Boolean

    /** Approval-respond body suffix granting session-wide autonomy. */
    fun grantSuffix(): String = ",\"$GRANT_FIELD\":true"
}

// ------------------------------------------------------------------------------------------------
// Frozen-caller compatibility (core-network ONLY): ChatActivity (app-mobile) is frozen by a
// parallel workstream and still constructs the legacy repository and matches on the legacy
// result types. Kotlin cannot resolve nested classifiers through typealiases, so these are real
// thin mirrors delegating to [SessionAutonomyRepository] — no wire literals, no behaviour of
// their own. Remove them together with the ChatActivity migration (app-mobile carries the
// matching member shim in ChatActivityCompat.kt).
// ------------------------------------------------------------------------------------------------

/** Legacy result mirror of [AutonomyLookup] for the frozen caller. */
@Deprecated("Legacy wire-era name; use AutonomyLookup", ReplaceWith("AutonomyLookup"))
sealed interface YoloLookup {
    data class State(val enabled: Boolean) : YoloLookup
    data class Failed(val message: String) : YoloLookup
}

/** Legacy result mirror of [AutonomyToggleResult] for the frozen caller. */
@Deprecated("Legacy wire-era name; use AutonomyToggleResult", ReplaceWith("AutonomyToggleResult"))
sealed interface YoloToggleResult {
    data class Applied(val enabled: Boolean) : YoloToggleResult
    data class Failed(val message: String, val knownState: Boolean? = null) : YoloToggleResult
}

/** Legacy facade over [SessionAutonomyRepository] for the frozen caller; delegates every call. */
@Deprecated("Legacy wire-era name; use SessionAutonomyRepository", ReplaceWith("SessionAutonomyRepository"))
class SessionYoloRepository {
    private val delegate = SessionAutonomyRepository()

    fun fetchState(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): YoloLookup = when (val result = delegate.fetchState(baseUrl, credential, sessionId)) {
        is AutonomyLookup.State -> YoloLookup.State(result.enabled)
        is AutonomyLookup.Failed -> YoloLookup.Failed(result.message)
    }

    fun setEnabled(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        enabled: Boolean,
    ): YoloToggleResult = when (val result = delegate.setEnabled(baseUrl, credential, sessionId, enabled)) {
        is AutonomyToggleResult.Applied -> YoloToggleResult.Applied(result.enabled)
        is AutonomyToggleResult.Failed -> YoloToggleResult.Failed(result.message, result.knownState)
    }
}
