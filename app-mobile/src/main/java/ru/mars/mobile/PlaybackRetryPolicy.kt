package ru.mars.mobile

import java.io.IOException

/** Classification of a MediaPlayer failure, derived from its raw `what`/`extra` error codes. */
enum class PlaybackErrorKind {
    /** Network/transport hiccup (IO error, timeout, server died) — the stream may work again. */
    TRANSIENT_NETWORK,

    /** The stream itself is broken (malformed, unsupported, not progressive) — retrying is pointless. */
    PERMANENT,

    /** Unrecognized code combination — retry once, then treat like a skip. */
    UNKNOWN,
}

/** Verdict of [PlaybackRetryPolicy.decide] for one playback failure. */
sealed interface RetryDecision {
    /**
     * Re-prepare the SAME track after [delayMs] of backoff. [attempt] is the 1-based retry
     * number for this track; [resumeFromMs] is the playback position to resume from when the
     * failure happened mid-track (0 = start over, e.g. the stream never reached prepared).
     */
    data class RetryTrack(val delayMs: Long, val attempt: Int, val resumeFromMs: Int) : RetryDecision

    /** The track exhausted its retry budget (or is permanently broken): advance the queue. */
    data object SkipTrack : RetryDecision

    /** Circuit breaker: a full pass over the queue failed — stop playback instead of looping. */
    data object Stop : RetryDecision
}

/**
 * Pure decision core of playback failure recovery (network resilience) — Android-free and
 * unit-testable on the JVM. [MarsMusicPlayer] owns one instance, feeds it engine error
 * codes (Media3 `PlaybackException.errorCode` through [media3CodeFor], or the legacy
 * MediaPlayer code space) and applies the verdicts; all counting, backoff and
 * classification live here.
 *
 * Failure model per track:
 * 1. [classify] maps the raw `what`/`extra` codes onto a [PlaybackErrorKind].
 * 2. TRANSIENT_NETWORK failures retry the same track up to [maxRetriesPerTrack] times with
 *    capped exponential backoff ([baseDelayMs] doubling per attempt, never above [maxDelayMs]);
 *    a mid-track failure resumes from the last known position instead of restarting the song.
 *    UNKNOWN failures get a single retry; PERMANENT failures never retry the same stream.
 * 3. When the budget is spent the track is SKIPPED ([RetryDecision.SkipTrack]).
 * 4. Skipping [queueSize] tracks in a row (a full failed pass over the queue) trips the
 *    circuit breaker ([RetryDecision.Stop]) so a dead network or a broken queue cannot make
 *    the player churn forever. The stop is self-clearing: a later decision starts a fresh
 *    streak, matching the queue-resume path ([QueueResumePolicy]).
 *
 * State resets: [onPlaybackReady] on any successful prepare or explicit user selection
 * (forget everything); [onTrackSelected] when navigation moves to a different track (fresh
 * per-track budget, but the skip streak survives — it is the circuit-breaker memory).
 */
class PlaybackRetryPolicy(
    maxRetriesPerTrack: Int = DEFAULT_MAX_RETRIES_PER_TRACK,
    baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    private val maxRetriesPerTrack = maxRetriesPerTrack.coerceAtLeast(0)
    private val baseDelayMs = baseDelayMs.coerceAtLeast(0L)
    private val maxDelayMs = maxDelayMs.coerceAtLeast(this.baseDelayMs)

    /** Same-track retries already consumed for the current track. */
    var trackRetries: Int = 0
        private set

    /** Tracks skipped in a row without any successful playback (circuit-breaker counter). */
    var consecutiveSkips: Int = 0
        private set

    /** Successful prepare or explicit user selection: forget the whole failure history. */
    fun onPlaybackReady() {
        trackRetries = 0
        consecutiveSkips = 0
    }

    /** Navigation moved to a different track: fresh retry budget, skip streak survives. */
    fun onTrackSelected() {
        trackRetries = 0
    }

    /**
     * Decides the recovery for one failure. [what]/[extra] are the raw MediaPlayer error
     * codes (or [codeFor] of a thrown exception in `what` with `extra = 0`); [queueSize]
     * bounds the circuit breaker; [positionMs]/[durationMs] are the last known playback
     * position and prepared duration, used to resume a mid-track failure.
     */
    fun decide(
        what: Int,
        extra: Int,
        queueSize: Int,
        positionMs: Int = 0,
        durationMs: Int = 0,
    ): RetryDecision {
        val budget = when (classify(what, extra)) {
            PlaybackErrorKind.TRANSIENT_NETWORK -> maxRetriesPerTrack
            PlaybackErrorKind.UNKNOWN -> maxRetriesPerTrack.coerceAtMost(1)
            PlaybackErrorKind.PERMANENT -> 0
        }
        if (trackRetries < budget) {
            trackRetries += 1
            return RetryDecision.RetryTrack(
                delayMs = delayFor(trackRetries),
                attempt = trackRetries,
                resumeFromMs = resumeFrom(positionMs, durationMs),
            )
        }
        consecutiveSkips += 1
        return if (consecutiveSkips >= queueSize.coerceAtLeast(1)) {
            // Full pass over the queue failed; self-clear so a resumed session starts fresh.
            onPlaybackReady()
            RetryDecision.Stop
        } else {
            RetryDecision.SkipTrack
        }
    }

    /** Permanent markers win: a malformed stream stays broken even beside an IO-flavored code. */
    fun classify(what: Int, extra: Int): PlaybackErrorKind = when {
        what in PERMANENT_CODES || extra in PERMANENT_CODES -> PlaybackErrorKind.PERMANENT
        what in TRANSIENT_CODES || extra in TRANSIENT_CODES -> PlaybackErrorKind.TRANSIENT_NETWORK
        else -> PlaybackErrorKind.UNKNOWN
    }

    /** Capped exponential backoff: base, 2×base, 4×base, … never above [maxDelayMs]. */
    private fun delayFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        return (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
    }

    /** Resume only when the position is meaningfully inside a known duration. */
    private fun resumeFrom(positionMs: Int, durationMs: Int): Int =
        if (durationMs > 0 && positionMs in 1 until durationMs) positionMs else 0

    companion object {
        const val DEFAULT_MAX_RETRIES_PER_TRACK = 2
        const val DEFAULT_BASE_DELAY_MS = 1_000L
        const val DEFAULT_MAX_DELAY_MS = 8_000L

        /** Shift guard for [delayFor]: keeps `baseDelayMs shl exponent` away from overflow. */
        private const val MAX_BACKOFF_SHIFT = 16

        // android.media.MediaPlayer error codes, mirrored so this policy stays Android-free.
        const val ERROR_UNKNOWN = 1
        const val ERROR_SERVER_DIED = 100
        const val ERROR_NOT_VALID_FOR_PROGRESSIVE = 200
        const val ERROR_TIMED_OUT = -110
        const val ERROR_IO = -1004
        const val ERROR_MALFORMED = -1007
        const val ERROR_UNSUPPORTED = -1010

        private val TRANSIENT_CODES = setOf(ERROR_IO, ERROR_TIMED_OUT, ERROR_SERVER_DIED)
        private val PERMANENT_CODES =
            setOf(ERROR_MALFORMED, ERROR_UNSUPPORTED, ERROR_NOT_VALID_FOR_PROGRESSIVE)

        /**
         * Media3/ExoPlayer `androidx.media3.common.PlaybackException` error codes, mirrored
         * as plain ints so this policy stays Android-free (JVM-testable). Parity with the
         * library constants is pinned by PlaybackRetryMedia3CodeTest — if Media3 ever
         * renumbers them, the test fails before production does.
         */
        const val MEDIA3_ERROR_UNSPECIFIED = 1000
        const val MEDIA3_ERROR_REMOTE = 1001
        const val MEDIA3_ERROR_BEHIND_LIVE_WINDOW = 1002
        const val MEDIA3_ERROR_TIMEOUT = 1003
        const val MEDIA3_ERROR_FAILED_RUNTIME_CHECK = 1004
        const val MEDIA3_IO_UNSPECIFIED = 2000
        const val MEDIA3_IO_NETWORK_CONNECTION_FAILED = 2001
        const val MEDIA3_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val MEDIA3_IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val MEDIA3_IO_BAD_HTTP_STATUS = 2004
        const val MEDIA3_IO_FILE_NOT_FOUND = 2005
        const val MEDIA3_IO_NO_PERMISSION = 2006
        const val MEDIA3_IO_CLEARTEXT_NOT_PERMITTED = 2007
        const val MEDIA3_IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val MEDIA3_PARSING_CONTAINER_MALFORMED = 3001
        const val MEDIA3_PARSING_MANIFEST_MALFORMED = 3002
        const val MEDIA3_PARSING_CONTAINER_UNSUPPORTED = 3003
        const val MEDIA3_PARSING_MANIFEST_UNSUPPORTED = 3004
        const val MEDIA3_DECODER_INIT_FAILED = 4001
        const val MEDIA3_DECODER_QUERY_FAILED = 4002
        const val MEDIA3_DECODING_FAILED = 4003
        const val MEDIA3_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
        const val MEDIA3_DECODING_FORMAT_UNSUPPORTED = 4005
        const val MEDIA3_AUDIO_TRACK_INIT_FAILED = 5001

        /**
         * Maps a Media3 `PlaybackException.errorCode` into the policy's MediaPlayer-shaped
         * code space, so [classify]/[decide] stay engine-agnostic:
         * - I/O and network failures (2xxx) → [ERROR_IO]: transient, retry with backoff;
         * - connection timeouts → [ERROR_TIMED_OUT]: transient, same lane;
         * - malformed containers/manifests → [ERROR_MALFORMED]: permanent, skip;
         * - unsupported containers/manifests and undecodable formats → [ERROR_UNSUPPORTED]:
         *   permanent, skip;
         * - decoder/audio-track failures and everything else → [ERROR_UNKNOWN]: one retry,
         *   then skip.
         */
        fun media3CodeFor(media3ErrorCode: Int): Int = when (media3ErrorCode) {
            MEDIA3_ERROR_TIMEOUT, MEDIA3_IO_NETWORK_CONNECTION_TIMEOUT -> ERROR_TIMED_OUT
            in MEDIA3_IO_UNSPECIFIED..MEDIA3_IO_READ_POSITION_OUT_OF_RANGE -> ERROR_IO
            MEDIA3_PARSING_CONTAINER_MALFORMED, MEDIA3_PARSING_MANIFEST_MALFORMED -> ERROR_MALFORMED
            MEDIA3_PARSING_CONTAINER_UNSUPPORTED,
            MEDIA3_PARSING_MANIFEST_UNSUPPORTED,
            MEDIA3_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            MEDIA3_DECODING_FORMAT_UNSUPPORTED -> ERROR_UNSUPPORTED
            else -> ERROR_UNKNOWN
        }

        /**
         * Maps an exception thrown by `setDataSource`/`prepareAsync` (or an ExoPlayer
         * `PlaybackException` cause) onto the same code space as the error callback,
         * so both failure sources share one decision path.
         */
        fun codeFor(cause: Throwable?): Int = when (cause) {
            is IOException -> ERROR_IO
            is IllegalArgumentException, is SecurityException -> ERROR_UNSUPPORTED
            else -> ERROR_UNKNOWN
        }
    }
}
