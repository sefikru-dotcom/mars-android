package ru.mars.tv

import ru.mars.network.model.HistoryEntry
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Чистая политика библиотеки TV-музыки — «Плейлисты» и «История» (паритет с мобильными
 * вкладками `MusicActivity` + `MusicLibraryPolicy`, но без Android: JVM-тестируемая).
 *
 * Что здесь решается и почему именно здесь:
 *
 * - [trackCountLabel]/[playlistMeta] — сервер отдаёт `track_count` числом и `created_at`
 *   строкой `2026-09-30 14:25:34`; «1 трек / 3 трека / 11 треков» — правило языка, а не
 *   вёрстки, поэтому плюрализация живёт в политике и покрыта тестами. Дата обрезается до
 *   `30.09.2026`: время создания плейлиста пользователю не нужно, а нераспознанная строка
 *   не печатается вовсе (никаких «создан » с висящим хвостом).
 * - [historyQueue] — строки истории играбельны (у записи есть video id), и вся история
 *   становится очередью воспроизведения: выбранная пультом строка стартует с себя и
 *   дальше идёт по списку ([TvPlaylistPolicy]) — ровно как лента «Для тебя».
 * - [historyMeta] — подпись строки истории: исполнитель, длительность и человеческое
 *   время («сегодня, 04:31» / «вчера, 09:37» / «12.10.2026, 09:37»). Сервер печатает
 *   `played_at` в своей зоне (`Europe/Moscow`), поэтому «сегодня» считается в ней же, а
 *   не в зоне приставки; нераспознанное время просто не попадает в подпись.
 * - [playlistsState]/[playlistTracksState]/[historyState] — ответ шва превращается в
 *   состояние экрана: пустой ответ — это «пусто», а не ошибка, а текст сбоя никогда не
 *   пустой (иначе строка статуса выглядела бы как зависшая загрузка).
 */
object TvMusicLibraryPolicy {

    /** Заголовок плейлиста, если сервер прислал пустой (пользователь его всё равно видит). */
    const val UNTITLED_PLAYLIST: String = "Без названия"

    /** Зона серверных timestamp'ов (`played_at`, `created_at`) — как в ответе mars-music. */
    const val SERVER_TIME_ZONE: String = "Europe/Moscow"

    /** Разделитель фрагментов подписи (та же грамматика, что у альбома и чартов). */
    const val DETAIL_SEPARATOR: String = " · "

    /** Текст сбоя плейлистов, когда сервер не дал причины. */
    const val PLAYLISTS_FAILURE: String = "Плейлисты недоступны"

    /** Текст сбоя треков открытого плейлиста, когда сервер не дал причины. */
    const val PLAYLIST_TRACKS_FAILURE: String = "Треки плейлиста недоступны"

    /** Текст сбоя истории, когда сервер не дал причины. */
    const val HISTORY_FAILURE: String = "История недоступна"

    private const val SERVER_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val SERVER_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T].*)?$""")

    /** «1 трек», «3 трека», «11 треков» — правило русского счёта, а не строк в вёрстке. */
    fun trackCountLabel(count: Int): String = "${absolute(count)} ${trackWord(count)}"

    /** Заголовок строки плейлиста: серверный, а при пустом — понятная заглушка. */
    fun playlistTitle(playlist: Playlist): String =
        playlist.title.trim().ifEmpty { UNTITLED_PLAYLIST }

    /**
     * Подпись строки плейлиста: «3 трека · создан 30.09.2026». Нулевой счёт печатается
     * («0 треков» — это честное состояние нового плейлиста), а нераспознанная дата просто
     * выпадает из подписи.
     */
    fun playlistMeta(playlist: Playlist): String {
        val created = createdDate(playlist.createdAt)
        return listOf(trackCountLabel(playlist.trackCount), created)
            .filter { it.isNotEmpty() }
            .joinToString(DETAIL_SEPARATOR)
    }

    /** Строки истории как очередь воспроизведения (порядок сервера — от новых к старым). */
    fun historyQueue(entries: List<HistoryEntry>): List<MusicTrack> = entries.map { it.toTrack() }

    /**
     * Подпись строки истории: «ELMAN · 2:45 · вчера, 04:30». Пустые фрагменты не печатаются,
     * поэтому запись без длительности или с нераспознанным временем не даёт висящих
     * разделителей.
     */
    fun historyMeta(entry: HistoryEntry, nowMillis: Long): String =
        listOf(entry.artist.trim(), entry.duration.trim(), playedAtLabel(entry.playedAt, nowMillis))
            .filter { it.isNotEmpty() }
            .joinToString(DETAIL_SEPARATOR)

    /**
     * Человеческое время прослушивания в зоне сервера: «сегодня, 04:31», «вчера, 09:37»,
     * «12.10.2026, 09:37». Нераспознанная строка -> пусто (подпись остаётся без времени).
     */
    fun playedAtLabel(playedAt: String, nowMillis: Long): String {
        val moment = parseServerTime(playedAt) ?: return ""
        val zone = TimeZone.getTimeZone(SERVER_TIME_ZONE)
        val today = dayStamp(nowMillis, zone) ?: return ""
        val yesterday = dayStamp(nowMillis - DAY_MILLIS, zone) ?: return ""
        val day = dayStamp(moment.timeInMillis, zone) ?: return ""
        val clock = String.format(
            Locale.US,
            "%02d:%02d",
            moment.get(Calendar.HOUR_OF_DAY),
            moment.get(Calendar.MINUTE),
        )
        return when (day) {
            today -> "сегодня, $clock"
            yesterday -> "вчера, $clock"
            else -> "${dayStampText(day)}, $clock"
        }
    }

    /** Ответ шва по списку плейлистов -> состояние вкладки. */
    fun playlistsState(result: TvPlaylistsResult): TvPlaylistsState = when (result) {
        is TvPlaylistsResult.Loaded ->
            if (result.playlists.isEmpty()) TvPlaylistsState.Empty
            else TvPlaylistsState.Content(result.playlists)

        is TvPlaylistsResult.Failed ->
            TvPlaylistsState.Failed(result.message.ifBlank { PLAYLISTS_FAILURE })
    }

    /** Ответ шва по трекам открытого плейлиста -> состояние drill-down. */
    fun playlistTracksState(
        playlist: Playlist,
        result: TvPlaylistTracksResult,
    ): TvPlaylistTracksState = when (result) {
        is TvPlaylistTracksResult.Loaded ->
            if (result.tracks.isEmpty()) TvPlaylistTracksState.Empty(playlist)
            else TvPlaylistTracksState.Content(playlist, result.tracks)

        is TvPlaylistTracksResult.Failed ->
            TvPlaylistTracksState.Failed(playlist, result.message.ifBlank { PLAYLIST_TRACKS_FAILURE })
    }

    /** Ответ шва по истории -> состояние вкладки. */
    fun historyState(result: TvHistoryResult): TvHistoryState = when (result) {
        is TvHistoryResult.Loaded ->
            if (result.entries.isEmpty()) TvHistoryState.Empty
            else TvHistoryState.Content(result.entries)

        is TvHistoryResult.Failed ->
            TvHistoryState.Failed(result.message.ifBlank { HISTORY_FAILURE })
    }

    private fun absolute(value: Int): Int = if (value < 0) -value else value

    private fun trackWord(count: Int): String {
        val value = absolute(count)
        val tail = value % 100
        if (tail in 11..14) return "треков"
        return when (value % 10) {
            1 -> "трек"
            2, 3, 4 -> "трека"
            else -> "треков"
        }
    }

    /** `2026-09-30 14:25:34` -> `30.09.2026`; пустая/битая строка -> пусто. */
    private fun createdDate(raw: String): String {
        val match = SERVER_DATE.find(raw.trim()) ?: return ""
        val (year, month, day) = match.destructured
        return "$day.$month.$year"
    }

    /** Разбор серверного времени в зоне сервера (без Android-зависимостей). */
    private fun parseServerTime(raw: String): Calendar? {
        val value = raw.trim().replace('T', ' ')
        if (!value.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?"""))) return null
        return try {
            val format = java.text.SimpleDateFormat(SERVER_PATTERN, Locale.US)
            format.timeZone = TimeZone.getTimeZone(SERVER_TIME_ZONE)
            val normalized = if (value.length == 16) "$value:00" else value
            val parsed = format.parse(normalized) ?: return null
            Calendar.getInstance(TimeZone.getTimeZone(SERVER_TIME_ZONE)).apply {
                timeInMillis = parsed.time
            }
        } catch (error: RuntimeException) {
            null
        }
    }

    /** `2026-10-20` — ключ дня в зоне сервера. */
    private fun dayStamp(millis: Long, zone: TimeZone): String? {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** `2026-10-20` -> `20.10.2026` для подписи. */
    private fun dayStampText(stamp: String): String {
        val parts = stamp.split("-")
        return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else stamp
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}
