package ru.mars.network.repository

/**
 * Memory/UI guard for a streaming transcript. Pure and stateless — the caller owns the
 * buffer text and passes it in, which keeps the guard trivially testable and free of
 * synchronization concerns.
 *
 * Why it exists: an agent turn can emit a megabyte-scale frame (a `cat` of a binary, an
 * infinite `yes`, a runaway log), and thousands of small chunks can accumulate into an
 * unbounded transcript. Rendering or even holding that on a phone means ANR/OOM. Two
 * independent limits:
 * - [maxFrameChars] clamps ONE incoming chunk; an oversized frame is cut and flagged with
 *   [TRUNCATION_MARKER] so the user sees the output was clipped, not silently lost.
 * - [maxAccumulatedChars] is a sliding window over the whole transcript: when exceeded,
 *   the OLDEST text is dropped (the latest output is what the user is watching) and the
 *   buffer is prefixed with [TRUNCATION_MARKER] exactly once.
 *
 * Hard invariant: `append(...).text.length <= maxAccumulatedChars`, for any inputs.
 */
class StreamBufferGuard(
    maxFrameChars: Int = DEFAULT_MAX_FRAME_CHARS,
    maxAccumulatedChars: Int = DEFAULT_MAX_ACCUMULATED_CHARS,
) {
    /** Per-frame clamp. Coerced to >= 1 so a frame is never erased entirely. */
    val maxFrameChars: Int = maxFrameChars.coerceAtLeast(1)

    /** Sliding-window cap for the whole transcript, marker included. */
    val maxAccumulatedChars: Int =
        maxAccumulatedChars.coerceAtLeast(TRUNCATION_MARKER.length + 1)

    /**
     * Outcome of one [append]: the new buffer text plus what had to be cut.
     * [droppedChars] counts characters lost to BOTH limits (frame clamp + window slide),
     * useful for telemetry ("stream produced N more chars than the UI can hold").
     */
    data class BufferGuardResult(
        val text: String,
        val frameTruncated: Boolean,
        val accumulatedTruncated: Boolean,
        val droppedChars: Int,
    ) {
        val truncated: Boolean get() = frameTruncated || accumulatedTruncated
    }

    /**
     * Appends [incomingChunk] to [current], enforcing both limits. Never throws, never
     * returns text longer than [maxAccumulatedChars]. The marker is de-duplicated: the
     * sliding window never produces a `MARKER + MARKER` head even when the dropped tail
     * itself ended in a frame-truncation marker.
     */
    fun append(current: String, incomingChunk: String): BufferGuardResult {
        val frameTruncated = incomingChunk.length > maxFrameChars
        val frame = if (frameTruncated) incomingChunk.take(maxFrameChars) else incomingChunk
        val frameDropped = incomingChunk.length - frame.length

        var combined = current + frame
        if (frameTruncated) combined += TRUNCATION_MARKER

        if (combined.length <= maxAccumulatedChars) {
            return BufferGuardResult(combined, frameTruncated, false, frameDropped)
        }

        // Sliding window: keep the NEWEST chars; the marker at the head tells the user
        // the beginning of the transcript was evicted.
        val keep = maxAccumulatedChars - TRUNCATION_MARKER.length
        var tail = combined.takeLast(keep)
        val windowDropped = combined.length - tail.length
        while (tail.startsWith(TRUNCATION_MARKER)) {
            tail = tail.substring(TRUNCATION_MARKER.length)
        }
        val text = TRUNCATION_MARKER + tail
        return BufferGuardResult(text, frameTruncated, true, frameDropped + windowDropped)
    }

    companion object {
        /** User-visible clip notice (Russian UI, same language as `describeNetworkError`). */
        const val TRUNCATION_MARKER = "[...вывод обрезан во избежание OOM...]"

        /**
         * A single SSE frame is normally a few hundred chars; 32K chars (~64 KB UTF-16)
         * already means something abnormal (base64 blob, log flood).
         */
        const val DEFAULT_MAX_FRAME_CHARS: Int = 32 * 1024

        /** 256K chars (~512 KB UTF-16) of transcript is far beyond what a phone UI renders. */
        const val DEFAULT_MAX_ACCUMULATED_CHARS: Int = 256 * 1024
    }
}
