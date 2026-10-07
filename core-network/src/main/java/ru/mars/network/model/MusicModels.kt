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

data class MusicPlayerState(
    val track: MusicTrack? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val playback: PlaybackStatus = PlaybackStatus.STOPPED,
    val volume: Double = 0.8,
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = 0,
    val activeDeviceId: String? = null,
)

data class MusicSearchResult(
    val tracks: List<MusicTrack>,
)
