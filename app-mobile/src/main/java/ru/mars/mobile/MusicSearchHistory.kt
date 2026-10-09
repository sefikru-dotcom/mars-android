package ru.mars.mobile

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException

/**
 * Pure history/state policy for music search (Android-free, for JVM tests):
 * which queries get stored (successful, non-blank only), how the list is
 * ordered (newest first), de-duplicated (case-insensitive) and capped
 * ([MAX_ENTRIES]). The Activity only renders the result.
 */
object MusicSearchHistoryPolicy {
    /** Hard cap on stored queries; the oldest entry falls off the tail. */
    const val MAX_ENTRIES = 8

    /** Storable/searchable form of raw field input: trimmed, null when blank. */
    fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Next list after a *successful* search for [rawQuery]: the query moves to
     * the front, a case-insensitive duplicate is dropped (the newest spelling
     * wins), and the tail beyond [MAX_ENTRIES] falls off. Failed or cancelled
     * searches must never call this — the history records successes only.
     */
    fun onSearchSucceeded(history: List<String>, rawQuery: String?): List<String> {
        val q = normalize(rawQuery) ?: return history
        val rest = history.filterNot { it.equals(q, ignoreCase = true) }
        return (listOf(q) + rest).take(MAX_ENTRIES)
    }

    /** Removes [entry] case-insensitively (same rule as dedupe); order of the rest is kept. */
    fun delete(history: List<String>, entry: String): List<String> {
        val target = normalize(entry) ?: return history
        return history.filterNot { it.equals(target, ignoreCase = true) }
    }

    /**
     * Cleans a restored/edited list: trims entries, drops blanks, de-duplicates
     * case-insensitively (first occurrence wins — the list is newest-first) and
     * caps at [MAX_ENTRIES]. Idempotent, so storage layers can always re-run it.
     */
    fun sanitize(entries: List<String>): List<String> {
        val out = ArrayList<String>(entries.size.coerceAtMost(MAX_ENTRIES))
        for (raw in entries) {
            val q = normalize(raw) ?: continue
            if (out.any { it.equals(q, ignoreCase = true) }) continue
            out.add(q)
            if (out.size == MAX_ENTRIES) break
        }
        return out
    }
}

/**
 * Race guard for search runs: every search increments a monotonic run id and
 * cancels the previous job. A result from a superseded or cancelled run must
 * never reach the UI (no stale-tab overwrite) and never enter the history, and
 * a cancellation must never be dressed up as a search error. Pure ids/flags so
 * JVM tests can pin the guard.
 */
object SearchRunGuard {
    /** True only for the live, non-cancelled run that is still the current one. */
    fun mayApplyResult(runId: Int, currentRunId: Int, runCancelled: Boolean): Boolean =
        !runCancelled && runId == currentRunId

    /** Cancellation (superseding query, tab switch, clear, destroy) is not a failure. */
    fun isCancellation(error: Throwable?): Boolean = error is CancellationException
}

/**
 * When the history overlay is shown: only while the search field is empty and
 * focused, and only when there is something to show. The overlay floats above
 * the results list — it never replaces the tab content or the player.
 */
object SearchHistoryPanelPolicy {
    fun isVisible(fieldText: String?, fieldFocused: Boolean, historyEmpty: Boolean): Boolean =
        fieldFocused && !historyEmpty && fieldText.isNullOrBlank()
}

/** Persistence port for the stored queries; the app implements it with SharedPreferences. */
interface MusicSearchHistoryStorage {
    fun load(): List<String>
    fun save(entries: List<String>)
}

/**
 * SharedPreferences-backed [MusicSearchHistoryStorage] for the private
 * `mars-music-search-history` file: a count plus indexed keys, so queries
 * round-trip verbatim (no separator escaping) and stale tail keys are removed
 * on shrink. Corrupted content degrades through
 * [MusicSearchHistoryPolicy.sanitize] instead of crashing.
 */
class PrefsMusicSearchHistoryStorage(private val prefs: SharedPreferences) : MusicSearchHistoryStorage {
    override fun load(): List<String> {
        val count = prefs.getInt(KEY_COUNT, 0).coerceAtLeast(0)
        val raw = (0 until count).mapNotNull { prefs.getString(entryKey(it), null) }
        return MusicSearchHistoryPolicy.sanitize(raw)
    }

    override fun save(entries: List<String>) {
        val cleaned = MusicSearchHistoryPolicy.sanitize(entries)
        val editor = prefs.edit()
        // Drop tail keys left over from a longer list, then rewrite the rest.
        for (i in cleaned.size until prefs.getInt(KEY_COUNT, 0).coerceAtLeast(0)) {
            editor.remove(entryKey(i))
        }
        cleaned.forEachIndexed { i, entry -> editor.putString(entryKey(i), entry) }
        editor.putInt(KEY_COUNT, cleaned.size).apply()
    }

    private fun entryKey(index: Int) = KEY_ENTRY_PREFIX + index

    private companion object {
        const val KEY_COUNT = "count"
        const val KEY_ENTRY_PREFIX = "entry_"
    }
}

/**
 * Search history backed by [storage]: every mutation goes through
 * [MusicSearchHistoryPolicy] and is persisted immediately, so the UI always
 * renders [entries] newest-first. Knows nothing about Android views.
 */
class MusicSearchHistory(private val storage: MusicSearchHistoryStorage) {
    /** Current queries, newest first; already sanitized on restore. */
    var entries: List<String> = MusicSearchHistoryPolicy.sanitize(storage.load())
        private set

    /** Records a successful search for [rawQuery]; returns the updated list. */
    fun recordSuccess(rawQuery: String?): List<String> =
        update { MusicSearchHistoryPolicy.onSearchSucceeded(it, rawQuery) }

    /** Removes one entry (row trash button); returns the updated list. */
    fun delete(entry: String): List<String> =
        update { MusicSearchHistoryPolicy.delete(it, entry) }

    /** Removes everything («Очистить историю»); returns the updated list. */
    fun clear(): List<String> = update { emptyList() }

    private fun update(transform: (List<String>) -> List<String>): List<String> {
        entries = transform(entries)
        storage.save(entries)
        return entries
    }
}
