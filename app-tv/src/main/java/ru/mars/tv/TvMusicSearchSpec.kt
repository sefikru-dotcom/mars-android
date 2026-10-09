package ru.mars.tv

/**
 * Pure search rules of the TV music screen (no Android, no I/O — JVM-testable,
 * [TvMusicNowPlayingSpec] parity):
 *
 * - [normalizedQuery]: what the D-pad «Найти» button / IME search action actually sends —
 *   the trimmed field text, or `null` when it is blank. A blank query must never reach
 *   the gateway (core-network `MusicRepository.search` rejects it), and the screen keeps
 *   its current state instead of a pointless refetch.
 * - [emptyMessage]: the zero-results wording. The two empties are deliberately different:
 *   an empty chart («Треков пока нет») is a service condition, an empty search result
 *   quotes the query so the user knows WHAT found nothing and can edit it.
 */
object TvMusicSearchSpec {

    /** Trimmed non-blank query for the gateway; `null` when the raw field text is blank. */
    fun normalizedQuery(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** Zero-results line for the state text: `query == null` — charts, otherwise a search. */
    fun emptyMessage(query: String?): String =
        if (query == null) "Треков пока нет"
        else "По запросу «$query» ничего не нашлось"
}
