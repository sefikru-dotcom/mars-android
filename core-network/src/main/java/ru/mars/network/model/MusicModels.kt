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

/**
 * Lyrics for a track from GET /api/lyrics/{videoId}; any field may be absent.
 *
 * [translated] is NOT part of that payload — it is filled in by a later
 * `translateLyrics` call (POST /api/lyrics/translate) and stays null until the user
 * asks for the Russian translation.
 */
data class LyricsResult(
    val lyrics: String?,
    val synced: String?,
    val source: String?,
    val translated: String? = null,
)

/**
 * Server-side playlist from GET /api/playlists.
 *
 * [id] is an 8-hex-character server key (e.g. `2e0d0d47`) used verbatim in every
 * `/api/playlist/{id}...` path; [createdAt] and [trackCount] are shown as-is
 * (`2026-09-30 14:25:34`, 1).
 */
data class Playlist(
    val id: String,
    val title: String,
    val createdAt: String,
    val trackCount: Int,
)

/**
 * Одна полка персональной ленты из GET /api/home → shelf.sections[].
 *
 * [id] — машинный ключ полки (`continue`, `new`, `for_you`, `similar`);
 * [title] — человекочитаемый заголовок («Продолжить слушать», «Новинки», …);
 * [items] — треки внутри полки (пустой список допустим и означает «нет данных»,
 * а не ошибку — UI такую полку пропускает).
 */
data class MusicShelf(
    val id: String,
    val title: String,
    val items: List<MusicTrack>,
)

/**
 * One row of the server listening history from GET /api/history.
 *
 * [id] is the VIDEO id (the payload key is `id`, not `video_id`), so an entry can be
 * played straight away; [thumbnail] is a site-relative path (`/music/api/thumb?v=...`)
 * resolved by ThumbnailUrlResolver; [playedAt] is a server-local timestamp string
 * (`2026-10-12 09:37:59`) that MusicLibraryPolicy turns into a human label.
 */
data class HistoryEntry(
    val id: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnail: String? = null,
    val playedAt: String,
) {
    /** The same track, ready for playback/enqueue (history rows are playable). */
    fun toTrack(): MusicTrack = MusicTrack(id, title, artist, duration, thumbnail)
}
