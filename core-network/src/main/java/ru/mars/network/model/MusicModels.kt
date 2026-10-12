package ru.mars.network.model

/** Track shape shared by Mars Music HTTP and Mars Connect WebSocket APIs. */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: String = "--:--",
    val thumbnail: String? = null,
)

enum class PlaybackStatus { PLAYING, PAUSED, STOPPED }

/**
 * Snapshot of the Mars Connect global music session.
 *
 * [timestampMs] is the server's session timestamp (`session.timestamp`, epoch millis) when
 * the frame carried one; consumers use it to drop out-of-order/stale broadcasts. It is the
 * last field with a default so every existing positional/named construction stays valid.
 */
data class MusicPlayerState(
    val track: MusicTrack? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val playback: PlaybackStatus = PlaybackStatus.STOPPED,
    val volume: Double = 0.8,
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = 0,
    val activeDeviceId: String? = null,
    val timestampMs: Long? = null,
)

data class MusicSearchResult(
    val tracks: List<MusicTrack>,
)

/** Lyrics for a track from GET /api/lyrics/{videoId}; any field may be absent. */
data class LyricsResult(
    val lyrics: String?,
    val synced: String?,
    val source: String?,
)
