package ru.mars.network.repository

/**
 * Forward-progress comparison for server run-journal SSE event ids.
 *
 * On the wire the ids are OPAQUE ([StreamResumeTracker] never parses them). This helper
 * is the ONE place that uses the documented server shape `<run_id>:<seq>` (verified
 * read-only in `/opt/hermes-webui/api`: `run_journal.py` stores
 * `"event_id": f"{run_id}:{seq}"` with a monotonically growing per-run `seq`;
 * `routes.py` re-emits it as the SSE `id:`) to answer a single
 * question for [MidTurnRecoveryCoordinator]: is [candidate] STRICTLY NEWER than the
 * high-water cursor [highWater]?
 *
 * Rules:
 * - `candidate == null` → false (nothing to compare);
 * - `highWater == null` → true (first id of the turn is progress by definition);
 * - same run prefix and both seqs parse → numeric comparison (a replayed journal frame
 *   has `seq <= highWater` and is NOT progress — that is what stops a replay-only
 *   reconnect from resetting the retry budget forever);
 * - different run prefixes, or anything unparseable → ids are not comparable: treat a
 *   DIFFERENT id as progress (fail-open: never block recovery on an unknown id shape),
 *   an EQUAL id as a duplicate.
 *
 * Pure JVM, no state, no Android.
 */
internal object RunJournalCursor {

    /** Whether [candidate] is strictly newer than [highWater]; see the object KDoc. */
    fun isAfter(candidate: String?, highWater: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        if (highWater.isNullOrBlank()) return true
        if (candidate == highWater) return false
        val candidateRun = candidate.runPrefix()
        val candidateSeq = candidate.seqSuffix()
        val highWaterRun = highWater.runPrefix()
        val highWaterSeq = highWater.seqSuffix()
        if (
            candidateRun.isNotEmpty() && candidateRun == highWaterRun &&
            candidateSeq != null && highWaterSeq != null
        ) {
            return candidateSeq > highWaterSeq
        }
        return true // different run or opaque/unparseable shape: not a replay duplicate
    }

    private fun String.runPrefix(): String =
        if (':' in this) substringBeforeLast(':') else ""

    private fun String.seqSuffix(): Long? =
        if (':' in this) substringAfterLast(':').toLongOrNull() else null
}
