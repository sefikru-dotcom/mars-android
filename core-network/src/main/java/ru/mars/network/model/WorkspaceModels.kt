package ru.mars.network.model

/**
 * One entry of `GET /api/workspaces` (`workspaces[]`).
 *
 * Wire contract (hermes-webui api/workspace.py `load_workspaces`): each entry is
 * `{path, name}` where `path` is an absolute server-side directory and `name`
 * the user-facing label (server renames a literal "default" label to "Home").
 * There is no per-entry default flag on the wire: the response-level `last`
 * field carries the active/default workspace path, so [isDefault] is derived
 * client-side as `path == last`.
 */
data class WorkspaceItem(
    val name: String,
    val path: String,
    val isDefault: Boolean = false,
)

/** Parsed `GET /api/workspaces` response: `{workspaces: [...], last: "..."}`. */
data class WorkspacesResponse(
    val workspaces: List<WorkspaceItem> = emptyList(),
    val last: String? = null,
)

/**
 * Builder/parser for the WebUI workspace sentinel (parity with hermes-webui
 * api/streaming.py `_workspace_context_prefix` / `_escape_workspace_prefix_path`,
 * injected since #1918): every user message carries a
 * `[Workspace::v1: <escaped path>]\n` prefix so mid-session workspace switches
 * reach the model without touching the system prompt.
 *
 * Escaping contract: `\` → `\\`, `]` → `\]` — exactly what the server-side
 * regex `^\s*\[Workspace::v1:\s*(?:\\.|[^\]\\])+\]\s*` expects.
 */
object WorkspacePrefix {

    /** Server-parity escaping of a workspace path for the sentinel. */
    fun escape(path: String): String = path.replace("\\", "\\\\").replace("]", "\\]")

    /** The sentinel line (with trailing newline) for [path]. */
    fun build(path: String): String = "[Workspace::v1: ${escape(path)}]\n"

    /**
     * Prepends the sentinel to [message] when a concrete [workspace] is chosen.
     * A null workspace (or blank path) means "server default" and leaves the
     * message untouched — never invent a prefix from a guessed path.
     */
    fun apply(message: String, workspace: WorkspaceItem?): String {
        val path = workspace?.path?.takeIf { it.isNotBlank() } ?: return message
        return build(path) + message
    }

    /**
     * Strips a leading v1 (or legacy `[Workspace: ...]`) sentinel for display,
     * mirroring the WebUI `_strip_workspace_prefix` used when rendering history.
     */
    fun strip(text: String): String {
        val v1 = Regex("""^\s*\[Workspace::v1:\s*(?:\\.|[^\]\\])+\]\s*""")
        val legacy = Regex("""^\s*\[Workspace:[^\]]+\]\s*""")
        return legacy.replaceFirst(v1.replaceFirst(text, ""), "")
    }
}
