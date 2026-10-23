package ru.mars.network.model

/**
 * Wire models for the WebUI kanban bridge (contract verified read-only against
 * `/opt/hermes-webui/api/kanban_bridge.py` + `hermes_cli/kanban_db.py`):
 *
 * - `GET /api/kanban/board?board=&tenant=&assignee=&include_archived=&only_mine=&since=`
 *   → `{changed, latest_event_id, read_only, columns[], tenants[], assignees[], filters}`;
 *   with `since >= latest_event_id` the server answers the SHORT envelope
 *   `{changed:false, latest_event_id, read_only}` (no columns) — [KanbanBoard.changed].
 * - `GET /api/kanban/tasks/{id}?board=` → `{task, comments[], events[], links, runs[], read_only}`.
 * - `GET /api/kanban/stats?board=` → `{by_status, by_assignee, oldest_ready_age_seconds?, now?}`.
 * - `GET /api/kanban/config?board=` → `{columns[], assignees[], default_tenant,
 *   lane_by_profile, include_archived_by_default, render_markdown, read_only}` — [KanbanConfig].
 * - `POST /api/kanban/dispatch?board=&dry_run=&max=` → `DispatchResult` asdict.
 *
 * Every field is parsed tolerantly: the task row is a dataclass `asdict` whose optional
 * columns may be absent (older schema) or null, and empty-string-is-null columns
 * (`model_override`, `goal_max_turns`, `block_kind`, …) may arrive as numbers OR strings.
 */

/** One kanban card. Field names mirror the snake_case wire contract. */
data class KanbanTask(
    val id: String,
    val title: String,
    val body: String? = null,
    val status: String = "",
    val assignee: String? = null,
    val priority: Int = 0,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val workspaceKind: String? = null,
    val workspacePath: String? = null,
    val claimLock: String? = null,
    val claimExpires: Long? = null,
    val tenant: String? = null,
    val branchName: String? = null,
    val projectId: String? = null,
    val result: String? = null,
    val consecutiveFailures: Int = 0,
    val workerPid: Long? = null,
    val lastFailureError: String? = null,
    val maxRuntimeSeconds: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val currentRunId: Long? = null,
    val workflowTemplateId: String? = null,
    val currentStepKey: String? = null,
    /** `null` = defaults only; `[]` = explicitly none (wire: `skills`). */
    val skills: List<String>? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val reasoningEffort: String? = null,
    val maxRetries: Int? = null,
    val goalMode: Boolean = false,
    val goalMaxTurns: Long? = null,
    val sessionId: String? = null,
    val blockKind: String? = null,
    val blockRecurrences: Int = 0,
    /** Computed by the bridge (`age_seconds`); null when the server could not compute it. */
    val ageSeconds: Long? = null,
    /** Board-payload annotation; absent (0) in the task-detail payload. */
    val commentCount: Int = 0,
    /** Board-payload annotation `link_counts.parents`. */
    val parentCount: Int = 0,
    /** Board-payload annotation `link_counts.children`. */
    val childCount: Int = 0,
)

/** One display column of the board payload (`{name, statuses?, tasks[]}`). */
data class KanbanColumn(
    val name: String,
    val statuses: List<String> = emptyList(),
    val tasks: List<KanbanTask> = emptyList(),
)

/** Echoed request filters of the board payload (`filters` object). */
data class KanbanBoardFilters(
    val tenant: String? = null,
    val assignee: String? = null,
    val includeArchived: Boolean = false,
    val onlyMine: Boolean = false,
    val profile: String? = null,
)

/**
 * `GET /api/kanban/board` payload. [changed]` == false` is the server's "nothing new
 * since your `since` cursor" answer: [columns] stays empty and only [latestEventId]
 * is meaningful — it must NOT be rendered as an empty board.
 */
data class KanbanBoard(
    val changed: Boolean = true,
    val latestEventId: Long = 0L,
    val readOnly: Boolean = false,
    val columns: List<KanbanColumn> = emptyList(),
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val filters: KanbanBoardFilters? = null,
)

/** `task_comments` row (`{id, task_id, author, body, created_at}`). */
data class KanbanComment(
    val id: Long = 0L,
    val taskId: String = "",
    val author: String = "",
    val body: String = "",
    val createdAt: Long? = null,
)

/** `task_events` row; `payload` is the parsed JSON object (may be null). */
data class KanbanTaskEvent(
    val id: Long = 0L,
    val taskId: String = "",
    val runId: Long? = null,
    val kind: String = "",
    val payload: Map<String, Any?>? = null,
    val createdAt: Long? = null,
)

/** `task_runs` row — one attempt at a task, opened on claim, closed on outcome. */
data class KanbanRun(
    val id: Long = 0L,
    val taskId: String = "",
    val profile: String? = null,
    val stepKey: String? = null,
    val status: String = "",
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val summary: String? = null,
    val error: String? = null,
)

/** Dependency id lists of a task (`links: {parents[], children[]}`). */
data class KanbanTaskLinks(
    val parents: List<String> = emptyList(),
    val children: List<String> = emptyList(),
)

/** `GET /api/kanban/tasks/{id}` payload. */
data class KanbanTaskDetail(
    val task: KanbanTask,
    val comments: List<KanbanComment> = emptyList(),
    val events: List<KanbanTaskEvent> = emptyList(),
    val links: KanbanTaskLinks = KanbanTaskLinks(),
    val runs: List<KanbanRun> = emptyList(),
    val readOnly: Boolean = false,
)

/**
 * Per-assignee stats. The wire shape of `by_assignee` differs between server builds:
 * `kanban_db.board_stats` sends `{assignee: {status: n}}`, the bridge's SQL fallback
 * sends flat `{assignee: n}`. Parsing normalises BOTH: [byStatus] is empty for the
 * flat shape and [total] is the sum (or the flat value).
 */
data class KanbanAssigneeStats(
    val total: Int = 0,
    val byStatus: Map<String, Int> = emptyMap(),
)

/** `GET /api/kanban/stats` payload (`board_stats`). */
data class KanbanStats(
    val byStatus: Map<String, Int> = emptyMap(),
    val byAssignee: Map<String, KanbanAssigneeStats> = emptyMap(),
    /** Age of the oldest `ready` task, seconds; null when no ready tasks exist. */
    val oldestReadyAgeSeconds: Long? = null,
    /** Server clock, epoch seconds (absent in the SQL-fallback shape). */
    val now: Long? = null,
)

/**
 * `GET /api/kanban/config` payload — display configuration of the board, read by the
 * bridge from `dashboard.kanban` in hermes_cli.config.
 *
 * [columns] is the ORDERED list of display-lane names (the server's `DISPLAY_COLUMNS`,
 * e.g. new/waiting/running/fault/done) — plain strings, NOT the object columns of the
 * board envelope. WebUI parity (`panels.js`): the list is the canonical lane order and
 * the skeleton of a board that returned no columns at all.
 *
 * Defaults mirror the SERVER's (`lane_by_profile`/`render_markdown` true,
 * `include_archived_by_default` false — `_config_payload` in `kanban_bridge.py`), so a
 * truncated payload degrades to unconfigured-bridge behaviour instead of inventing one.
 */
data class KanbanConfig(
    val columns: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val defaultTenant: String = "",
    val laneByProfile: Boolean = true,
    /** Server-side default of the board's `include_archived` filter (WebUI checks the box). */
    val includeArchivedByDefault: Boolean = false,
    val renderMarkdown: Boolean = true,
    val readOnly: Boolean = false,
)

/**
 * One row of `GET /api/kanban/boards` — a board the operator can switch to.
 *
 * [counts] is the per-status tally (`{done: 2}`) and [total] its sum; both may be
 * absent in older bridges, in which case they degrade to an empty map / zero and the
 * UI must not draw a "0 задач" badge out of a missing field — see [hasCounts].
 * [isCurrent] marks the board the server treats as default ([KanbanBoards.current]).
 */
data class BoardSummary(
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val icon: String = "",
    val color: String = "",
    val archived: Boolean = false,
    val isCurrent: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
) {
    /** True when the server actually reported a tally (absent counts are not "0"). */
    val hasCounts: Boolean get() = counts.isNotEmpty() || total > 0
}

/** `GET /api/kanban/boards` payload. */
data class KanbanBoards(
    val boards: List<BoardSummary> = emptyList(),
    /** Slug of the active board, or null when the server did not name one. */
    val current: String? = null,
    val readOnly: Boolean = false,
)

/**
 * One row of `GET /api/kanban/assignees` — an assignee known to the bridge.
 *
 * [onDisk] mirrors the bridge's `on_disk` flag: a name with no workspace/profile
 * directory yet. It is reported as a hint, NOT as a filter — the server still
 * accepts the assignment.
 */
data class AssigneeStat(
    val name: String = "",
    val onDisk: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
) {
    val total: Int get() = counts.values.sum()
}

/** `GET /api/kanban/assignees` payload. */
data class KanbanAssignees(
    val assignees: List<AssigneeStat> = emptyList(),
)

/**
 * One `spawned` triple of a dispatch pass: `(task_id, assignee, workspace_path)`.
 */
data class KanbanDispatchSpawn(
    val taskId: String = "",
    val assignee: String = "",
    val workspacePath: String = "",
)

/**
 * `POST /api/kanban/dispatch` payload — `DispatchResult` (kanban_db_dispatch.py) as
 * serialised by `dataclasses.asdict`. Only the operator-relevant buckets are modelled;
 * unknown fields are ignored, missing fields fall back to zero/empty.
 */
data class KanbanDispatchOutcomePayload(
    val spawned: List<KanbanDispatchSpawn> = emptyList(),
    val reclaimed: Int = 0,
    val promoted: Int = 0,
    val reconciledOrphans: List<String> = emptyList(),
    val skippedUnassigned: List<String> = emptyList(),
    val autoAssignedDefault: List<String> = emptyList(),
    val crashed: List<String> = emptyList(),
    val autoBlocked: List<String> = emptyList(),
    val timedOut: List<String> = emptyList(),
    val stale: List<String> = emptyList(),
    val rateLimited: List<String> = emptyList(),
    /** Another dispatcher holds the board's single-writer tick lock; nothing was done. */
    val skippedLocked: Boolean = false,
    val memoryPressure: String? = null,
)

/**
 * One row of `POST /api/kanban/tasks/bulk` → `results[]`: per-task verdict of a
 * batched mutation. The bridge reports per-id failures instead of failing the
 * whole request (`{"id": "...", "ok": false, "error": "not found"}`), so a batch
 * where one card was deleted server-side still applies to the rest.
 */
data class KanbanBulkEntry(
    val id: String = "",
    val ok: Boolean = false,
    val error: String? = null,
)
