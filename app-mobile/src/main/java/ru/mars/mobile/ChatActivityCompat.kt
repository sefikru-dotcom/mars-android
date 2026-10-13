package ru.mars.mobile

/**
 * ISOLATED COMPAT SHIM for a frozen caller.
 *
 * [ChatActivity] is owned by a parallel workstream and must not be edited in this branch; it
 * still calls the pre-rename member names. The domain/UI everywhere else speaks the neutral
 * "autonomy" language ([ChatUiState.autonomyEnabled], [InteractiveRequestController.onAutonomyChanged],
 * [MarsInteractiveView.autonomyEnabledProvider]); the legacy internal term survives in app-mobile
 * only here and inside the frozen ChatActivity itself. The wire contract is mapped exclusively
 * by the core-network adapter (`ru.mars.network.repository.SessionModeWire`).
 *
 * Remove this file together with the ChatActivity migration.
 */

/** Legacy read access to [ChatUiState.autonomyEnabled]. */
@Deprecated("Legacy name; use autonomyEnabled", ReplaceWith("autonomyEnabled"))
val ChatUiState.yoloEnabled: Boolean
    get() = autonomyEnabled

/** Legacy write access to [ChatUiState.setAutonomyEnabled]. */
@Deprecated("Legacy name; use setAutonomyEnabled", ReplaceWith("setAutonomyEnabled(value)"))
fun ChatUiState.setYoloEnabled(value: Boolean): Unit = setAutonomyEnabled(value)

/** Legacy access to [MarsInteractiveView.autonomyEnabledProvider]. */
@Deprecated("Legacy name; use autonomyEnabledProvider", ReplaceWith("autonomyEnabledProvider"))
var MarsInteractiveView.yoloEnabledProvider: () -> Boolean
    get() = autonomyEnabledProvider
    set(value) {
        autonomyEnabledProvider = value
    }

/** Legacy access to [InteractiveRequestController.onAutonomyChanged]. */
@Deprecated("Legacy name; use onAutonomyChanged", ReplaceWith("onAutonomyChanged"))
var InteractiveRequestController.onYoloChanged: ((Boolean) -> Unit)?
    get() = onAutonomyChanged
    set(value) {
        onAutonomyChanged = value
    }
