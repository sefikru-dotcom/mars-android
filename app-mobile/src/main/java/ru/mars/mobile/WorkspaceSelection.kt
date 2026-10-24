package ru.mars.mobile

import android.content.SharedPreferences
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacePrefix
import ru.mars.network.model.WorkspacesResponse
import java.util.Locale

/**
 * Compact token count for the context pill (WebUI `_fmtTokens` parity,
 * static/ui.js): `0` for negative/zero, `12.3k` from 1000, `1.5M` from 1e6.
 * Pure + internal so JVM unit tests can pin the format.
 */
internal fun formatTokenCount(n: Int): String = when {
    n <= 0 -> "0"
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/**
 * Имя общего префса выбора рабочего пространства. Значение обязано совпадать с
 * тем, что использует композер чата (`ChatInitSection`): экран «Пространства» и
 * чип чата читают ОДНО хранилище, иначе выбор на одном экране не доходил бы до
 * исходящих сообщений.
 */
const val WORKSPACE_CHOICE_PREFS = "mars-workspace-choice"

/**
 * Persistence port for the chosen workspace; the app implements it with
 * SharedPreferences (same pattern as [ModelChoiceStorage]).
 */
interface WorkspaceChoiceStorage {
    fun load(): WorkspaceItem?
    fun save(item: WorkspaceItem)
    fun clear()
}

/** SharedPreferences-backed [WorkspaceChoiceStorage]. */
class PrefsWorkspaceChoiceStorage(private val prefs: SharedPreferences) : WorkspaceChoiceStorage {
    override fun load(): WorkspaceItem? {
        val path = prefs.getString(KEY_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: path
        return WorkspaceItem(name = name, path = path, isDefault = prefs.getBoolean(KEY_IS_DEFAULT, false))
    }

    override fun save(item: WorkspaceItem) {
        prefs.edit()
            .putString(KEY_PATH, item.path)
            .putString(KEY_NAME, item.name)
            .putBoolean(KEY_IS_DEFAULT, item.isDefault)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_PATH).remove(KEY_NAME).remove(KEY_IS_DEFAULT).apply()
    }

    private companion object {
        const val KEY_PATH = "workspace_path"
        const val KEY_NAME = "workspace_name"
        const val KEY_IS_DEFAULT = "workspace_is_default"
    }
}

/**
 * Domain state for the workspace chip (UI -> state/domain -> network): the
 * Activity fetches the catalog via [ru.mars.network.repository.WorkspaceRepository]
 * and hands the parsed [WorkspacesResponse] here; this class resolves, labels
 * and persists the choice and knows nothing about Android views.
 *
 * Parity with the WebUI composer: with no explicit user pick the server's
 * `last` workspace is adopted (the WebUI chip shows it too); a user pick in
 * the dialog wins over it. Outgoing messages carry the `[Workspace::v1: <path>]`
 * sentinel only when a concrete workspace is resolved — otherwise the server
 * routes with its own session default instead of a client-side guess.
 */
class WorkspaceSelection(private val storage: WorkspaceChoiceStorage) {

    /** Effective choice for the next message: persisted user pick, else the server default. */
    var current: WorkspaceItem? = storage.load()
        private set

    private var lastCatalog: WorkspacesResponse? = null

    /** Rows for the picker dialog, in server order. */
    fun pickerEntries(response: WorkspacesResponse): List<WorkspaceItem> {
        lastCatalog = response
        return response.workspaces
    }

    /**
     * Persists an explicit user pick; null (the "Авто" row) clears the pick and
     * falls back to the server default from the last catalog.
     */
    fun select(item: WorkspaceItem?): WorkspaceItem? {
        if (item == null || item.path.isBlank()) {
            current = lastCatalog?.let { serverDefault(it) }
            current?.let(storage::save) ?: storage.clear()
            return current
        }
        current = item
        storage.save(item)
        return item
    }

    /**
     * Resolves the effective choice after a catalog load: a persisted pick wins
     * (re-based onto the catalog row so the label/flag stay fresh); otherwise
     * the server's `last` path is adopted without persisting — it may change
     * server-side at any time.
     */
    fun resolveEffective(response: WorkspacesResponse): WorkspaceItem? {
        lastCatalog = response
        val picked = current
        if (picked != null) {
            val fresh = response.workspaces.firstOrNull { it.path == picked.path }
            current = fresh ?: picked
            return current
        }
        current = serverDefault(response)
        return current
    }

    /**
     * Outgoing wire text for the composer: the raw message prefixed with the
     * `[Workspace::v1: <path>]` sentinel when a concrete workspace is resolved.
     * The transcript keeps displaying the raw text (WebUI parity).
     */
    fun outgoingMessage(text: String): String = WorkspacePrefix.apply(text, current)

    /** Chip label: workspace name (or path tail) until a choice exists, then "Авто". */
    fun buttonLabel(): String? = current?.let { it.name.ifBlank { it.path } }

    /**
     * Re-reads the persisted pick from [storage]: the choice is shared via
     * the `mars-workspace-choice` prefs, so a selection made on another
     * screen must be adopted when the host Activity resumes. With no
     * persisted pick falls back to the server default of the last catalog
     * (same semantics as [resolveEffective], without a network reload).
     */
    fun reloadFromStorage(): WorkspaceItem? {
        current = storage.load() ?: lastCatalog?.let { serverDefault(it) }
        return current
    }

    private fun serverDefault(response: WorkspacesResponse): WorkspaceItem? =
        response.workspaces.firstOrNull { it.path == response.last && it.path.isNotBlank() }
            ?: response.workspaces.firstOrNull { it.isDefault }
}
