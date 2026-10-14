package ru.mars.mobile

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract pin between the pure [PlaybackRetryPolicy] and the Media3 engine that now feeds
 * it: [PlaybackRetryPolicy.Companion.media3CodeFor] translates `PlaybackException.errorCode`
 * into the policy's MediaPlayer-shaped code space, and the mirrored MEDIA3_* constants must
 * stay numerically identical to the library's. PlaybackException is a plain Java class, so
 * this runs as a pure JVM test — if Media3 ever renumbers the codes (or the mapping drifts),
 * this fails at build time instead of misclassifying failures in production.
 */
class PlaybackRetryMedia3CodeTest {

    // --- constant parity with androidx.media3.common.PlaybackException --------------

    @Test
    fun `mirrored media3 constants match the library values`() {
        assertEquals(PlaybackException.ERROR_CODE_UNSPECIFIED, PlaybackRetryPolicy.MEDIA3_ERROR_UNSPECIFIED)
        assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, PlaybackRetryPolicy.MEDIA3_ERROR_REMOTE)
        assertEquals(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, PlaybackRetryPolicy.MEDIA3_ERROR_BEHIND_LIVE_WINDOW)
        assertEquals(PlaybackException.ERROR_CODE_TIMEOUT, PlaybackRetryPolicy.MEDIA3_ERROR_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, PlaybackRetryPolicy.MEDIA3_ERROR_FAILED_RUNTIME_CHECK)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackRetryPolicy.MEDIA3_IO_UNSPECIFIED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackRetryPolicy.MEDIA3_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackRetryPolicy.MEDIA3_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, PlaybackRetryPolicy.MEDIA3_IO_INVALID_HTTP_CONTENT_TYPE)
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackRetryPolicy.MEDIA3_IO_BAD_HTTP_STATUS)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackRetryPolicy.MEDIA3_IO_FILE_NOT_FOUND)
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, PlaybackRetryPolicy.MEDIA3_IO_NO_PERMISSION)
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, PlaybackRetryPolicy.MEDIA3_IO_READ_POSITION_OUT_OF_RANGE)
        assertEquals(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, PlaybackRetryPolicy.MEDIA3_IO_CLEARTEXT_NOT_PERMITTED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackRetryPolicy.MEDIA3_PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackRetryPolicy.MEDIA3_PARSING_MANIFEST_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackRetryPolicy.MEDIA3_PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackRetryPolicy.MEDIA3_PARSING_MANIFEST_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackRetryPolicy.MEDIA3_DECODER_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackRetryPolicy.MEDIA3_DECODER_QUERY_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackRetryPolicy.MEDIA3_DECODING_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackRetryPolicy.MEDIA3_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackRetryPolicy.MEDIA3_DECODING_FORMAT_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackRetryPolicy.MEDIA3_AUDIO_TRACK_INIT_FAILED)
    }

    // --- mapping onto the policy code space -------------------------------------------

    @Test
    fun `network and io failures map to the transient IO lane`() {
        for (code in listOf(
            PlaybackRetryPolicy.MEDIA3_IO_UNSPECIFIED,
            PlaybackRetryPolicy.MEDIA3_IO_NETWORK_CONNECTION_FAILED,
            PlaybackRetryPolicy.MEDIA3_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackRetryPolicy.MEDIA3_IO_BAD_HTTP_STATUS,
            PlaybackRetryPolicy.MEDIA3_IO_FILE_NOT_FOUND,
            PlaybackRetryPolicy.MEDIA3_IO_NO_PERMISSION,
            PlaybackRetryPolicy.MEDIA3_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackRetryPolicy.MEDIA3_IO_CLEARTEXT_NOT_PERMITTED,
        )) {
            assertEquals(
                "media3 code $code must land in ERROR_IO",
                PlaybackRetryPolicy.ERROR_IO,
                PlaybackRetryPolicy.media3CodeFor(code)
            )
        }
    }

    @Test
    fun `timeouts map to the timed-out lane`() {
        assertEquals(
            PlaybackRetryPolicy.ERROR_TIMED_OUT,
            PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_ERROR_TIMEOUT)
        )
        assertEquals(
            PlaybackRetryPolicy.ERROR_TIMED_OUT,
            PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_IO_NETWORK_CONNECTION_TIMEOUT)
        )
    }

    @Test
    fun `malformed parsing maps to the malformed lane`() {
        assertEquals(
            PlaybackRetryPolicy.ERROR_MALFORMED,
            PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_PARSING_CONTAINER_MALFORMED)
        )
        assertEquals(
            PlaybackRetryPolicy.ERROR_MALFORMED,
            PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_PARSING_MANIFEST_MALFORMED)
        )
    }

    @Test
    fun `unsupported containers and undecodable formats map to the unsupported lane`() {
        for (code in listOf(
            PlaybackRetryPolicy.MEDIA3_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackRetryPolicy.MEDIA3_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackRetryPolicy.MEDIA3_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackRetryPolicy.MEDIA3_DECODING_FORMAT_UNSUPPORTED,
        )) {
            assertEquals(
                PlaybackRetryPolicy.ERROR_UNSUPPORTED,
                PlaybackRetryPolicy.media3CodeFor(code)
            )
        }
    }

    @Test
    fun `decoder audio-track and unknown failures map to ERROR_UNKNOWN`() {
        for (code in listOf(
            PlaybackRetryPolicy.MEDIA3_ERROR_UNSPECIFIED,
            PlaybackRetryPolicy.MEDIA3_ERROR_REMOTE,
            PlaybackRetryPolicy.MEDIA3_ERROR_BEHIND_LIVE_WINDOW,
            PlaybackRetryPolicy.MEDIA3_ERROR_FAILED_RUNTIME_CHECK,
            PlaybackRetryPolicy.MEDIA3_DECODER_INIT_FAILED,
            PlaybackRetryPolicy.MEDIA3_DECODER_QUERY_FAILED,
            PlaybackRetryPolicy.MEDIA3_DECODING_FAILED,
            PlaybackRetryPolicy.MEDIA3_AUDIO_TRACK_INIT_FAILED,
            -12345, // a code from a future Media3 release
        )) {
            assertEquals(
                "media3 code $code must land in ERROR_UNKNOWN",
                PlaybackRetryPolicy.ERROR_UNKNOWN,
                PlaybackRetryPolicy.media3CodeFor(code)
            )
        }
    }

    // --- decisions through the mapped codes (engine swap must not change behavior) ----

    @Test
    fun `a mapped io failure retries the same track from the failure position`() {
        val policy = PlaybackRetryPolicy()
        val what = PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_IO_BAD_HTTP_STATUS)
        val decision = policy.decide(what, 0, queueSize = 3, positionMs = 95_000, durationMs = 210_000)
        assertTrue("expected RetryTrack, got $decision", decision is RetryDecision.RetryTrack)
        decision as RetryDecision.RetryTrack
        assertEquals(1, decision.attempt)
        assertEquals(95_000, decision.resumeFromMs)
        assertEquals(PlaybackRetryPolicy.DEFAULT_BASE_DELAY_MS, decision.delayMs)
    }

    @Test
    fun `a mapped malformed failure skips the track instead of retrying`() {
        val policy = PlaybackRetryPolicy()
        val what = PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_PARSING_CONTAINER_MALFORMED)
        assertEquals(
            RetryDecision.SkipTrack,
            policy.decide(what, 0, queueSize = 3, positionMs = 10_000, durationMs = 210_000)
        )
    }

    @Test
    fun `a mapped decoder failure retries once then skips`() {
        val policy = PlaybackRetryPolicy()
        val what = PlaybackRetryPolicy.media3CodeFor(PlaybackRetryPolicy.MEDIA3_DECODING_FAILED)
        val first = policy.decide(what, 0, queueSize = 3, positionMs = 0, durationMs = 210_000)
        assertTrue("expected RetryTrack, got $first", first is RetryDecision.RetryTrack)
        // Second failure of the same track: budget spent (UNKNOWN retries once) → skip.
        val second = policy.decide(what, 0, queueSize = 3, positionMs = 0, durationMs = 210_000)
        assertEquals(RetryDecision.SkipTrack, second)
    }
}
