package ru.mars.mobile

import ru.mars.network.model.SessionItem

/**
 * Pure policy for drawer session rows: which rows offer management actions
 * (rename/delete).
 *
 * The server-side `read_only` flag on [SessionItem] is the single authority —
 * delegated subagent children arrive with `read_only = true` and must not offer
 * rename/delete (the server rejects those mutations anyway). Provenance alone
 * (`sourceTag`/`rawSource`/`sessionSource`) never disables actions: a row
 * tagged `subagent` but not marked read-only by the server stays fully
 * manageable. ChatActivity only wires the outcome in `renderSessionList`.
 */
object SessionRowActions {
    /** True when the row may show rename/delete actions. */
    fun canManage(session: SessionItem): Boolean = !session.readOnly
}
