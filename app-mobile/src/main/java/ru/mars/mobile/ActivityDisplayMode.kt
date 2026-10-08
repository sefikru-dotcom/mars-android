package ru.mars.mobile

import android.content.SharedPreferences

/**
 * Streaming activity display modes — parity with the WebUI header switch
 * (`stream_full` / `stream_window` / `stream_quiet` from `api/settings`).
 *
 * - [STREAM_FULL]: every thought and tool call stays expanded live.
 * - [STREAM_WINDOW] (default): only the currently running tool is expanded;
 *   finished blocks collapse into the compact one-line «Готово» plate.
 * - [STREAM_QUIET]: thoughts and tools stay collapsed; the status line is brief.
 *
 * Kept platform independent (pure enum + storage port) so the mode logic is
 * covered by JVM unit tests; only the SharedPreferences adapter is Android code.
 */
enum class ActivityDisplayMode(val wire: String, val label: String, val description: String) {
    STREAM_FULL("stream_full", "Всё", "Мысли и инструменты развёрнуты полностью"),
    STREAM_WINDOW("stream_window", "Окно", "Текущий блок развёрнут, прошлые сворачиваются в «Готово»"),
    STREAM_QUIET("stream_quiet", "Тишина", "Мысли и инструменты свёрнуты, статус краткий");

    /** Next mode in the fixed cycle FULL -> WINDOW -> QUIET -> FULL. */
    fun next(): ActivityDisplayMode = when (this) {
        STREAM_FULL -> STREAM_WINDOW
        STREAM_WINDOW -> STREAM_QUIET
        STREAM_QUIET -> STREAM_FULL
    }

    companion object {
        /** WebUI default mode. */
        val DEFAULT: ActivityDisplayMode = STREAM_WINDOW

        /** Parses a wire value from settings/SharedPreferences; unknown or blank falls back to [DEFAULT]. */
        fun fromWire(value: String?): ActivityDisplayMode =
            values().firstOrNull { it.wire == value?.trim() } ?: DEFAULT
    }
}

/** Persistence port for [ActivityDisplayMode]; the app implements it with SharedPreferences. */
interface ActivityModeStorage {
    fun load(): ActivityDisplayMode
    fun save(mode: ActivityDisplayMode)
}

/** SharedPreferences-backed [ActivityModeStorage] keyed by the stable wire value. */
class PrefsActivityModeStorage(private val prefs: SharedPreferences) : ActivityModeStorage {
    override fun load(): ActivityDisplayMode = ActivityDisplayMode.fromWire(prefs.getString(KEY_MODE, null))

    override fun save(mode: ActivityDisplayMode) {
        prefs.edit().putString(KEY_MODE, mode.wire).apply()
    }

    private companion object {
        const val KEY_MODE = "activity_display_mode"
    }
}

/**
 * Domain holder for the effective mode (UI -> state -> persistence), mirroring
 * [ModelSelection]: restores the persisted pick, persists user changes and
 * exposes the header-chip label. Knows nothing about Android views.
 */
class ActivityModeSelection(private val storage: ActivityModeStorage) {

    /** Effective mode: persisted pick or [ActivityDisplayMode.DEFAULT]. */
    var current: ActivityDisplayMode = storage.load()
        private set

    /** Persists a user pick and returns it. */
    fun select(mode: ActivityDisplayMode): ActivityDisplayMode {
        current = mode
        storage.save(mode)
        return mode
    }

    /** Cycles to the next mode, persists it and returns the new mode. */
    fun cycle(): ActivityDisplayMode = select(current.next())

    /** Header-chip label for the current mode («Окно», «Всё», «Тишина»). */
    fun buttonLabel(): String = current.label
}
