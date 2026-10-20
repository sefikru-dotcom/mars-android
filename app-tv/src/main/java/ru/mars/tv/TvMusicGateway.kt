package ru.mars.tv

import kotlinx.coroutines.runBlocking
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.AlbumResult
import ru.mars.network.repository.MusicRepository

/**
 * Production [TvMusicGateway] + [TvLyricsGateway] + [TvAlbumGateway] + [TvMusicLibraryGateway]:
 * binds the REAL core-network [MusicRepository]
 * (`GET {baseUrl}/api/charts`, `GET {baseUrl}/api/search?q=…`, the personal feed
 * `GET {baseUrl}/api/home`, the library `GET {baseUrl}/api/playlists`,
 * `GET {baseUrl}/api/playlist/{id}`, `GET {baseUrl}/api/history` and
 * `POST {baseUrl}/api/history/record`, baseUrl = [MUSIC_BASE_URL], mobile MusicActivity parity)
 * into the TV controller's blocking seam.
 * All HTTP, envelope normalization, id cleanup and error mapping stay in core-network —
 * the TV layer only bridges suspend to blocking (the controllers run this on their
 * background executor) and guarantees that no exception ever escapes: every outcome lands
 * as an explicit [TvMusicChartsResult] / [TvMusicHomeResult] / [TvLyricsResult] /
 * [TvAlbumResult] / [TvPlaylistsResult] / [TvPlaylistTracksResult] / [TvHistoryResult].
 */
class RepositoryTvMusicGateway(
    private val repository: MusicRepository,
) : TvMusicGateway, TvLyricsGateway, TvLyricsTranslationGateway, TvAlbumGateway, TvMusicLibraryGateway {

    override fun fetchCharts(): TvMusicChartsResult = fetch { repository.getCharts() }

    override fun search(query: String): TvMusicChartsResult = fetch { repository.search(query) }

    /**
     * `GET {baseUrl}/api/home` -> `List<MusicShelf>` — персональные полки «Продолжить слушать»,
     * «Новинки», «Для тебя», «Похожие на …». Серверные пустые полки НЕ ошибка: они доезжают как
     * есть, а решение «что рендерим» принимает [TvMusicHomePolicy]. Ошибкой считается
     * только сбой сети/разбора/`success:false` ([MusicRepository.getHome]).
     */
    override fun fetchHome(): TvMusicHomeResult = try {
        TvMusicHomeResult.Loaded(runBlocking { repository.getHome() })
    } catch (error: Throwable) {
        TvMusicHomeResult.Failed(reasonOf(error))
    }

    /**
     * `GET {baseUrl}/api/lyrics/{videoId}?title=…&artist=…`: title/artist текущего трека
     * передаются ОБЯЗАТЕЛЬНО — живой сервер без них часто отвечает заглушкой
     * «Текст не найден.» вместо настоящего текста.
     */
    override fun fetchLyrics(track: MusicTrack): TvLyricsResult = try {
        TvLyricsResult.Loaded(
            runBlocking { repository.getLyrics(track.id, title = track.title, artist = track.artist) },
        )
    } catch (error: Throwable) {
        TvLyricsResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
    }

    /**
     * `POST {baseUrl}/api/lyrics/translate` с телом `{"text": …}`: ответ
     * `{"success": bool, "translated": "…"}` (при пустом text — `{"translated": ""}`).
     * Пустоту ответа трактует не шов, а [TvLyricsTranslationFlow] (policy-ветка EMPTY_ANSWER).
     */
    override fun translate(text: String): TvLyricsTranslationResult = try {
        TvLyricsTranslationResult.Translated(runBlocking { repository.translateLyrics(text) })
    } catch (error: Throwable) {
        TvLyricsTranslationResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
    }

    /**
     * `GET {baseUrl}/api/album/{browseId}` -> [TvAlbumResult]. Используется тот же
     * `MusicRepository.getAlbum`, что и мобильным диалогом: сервер принимает только
     * `MPRE…`, отказ по префиксу приходит вариантом [AlbumResult.InvalidBrowseId]
     * (в TV-шов — [TvAlbumResult.NotFound]), транспорт — [TvAlbumResult.Failed].
     */
    override fun fetchAlbum(browseId: String): TvAlbumResult = try {
        when (val result = runBlocking { repository.getAlbum(browseId) }) {
            is AlbumResult.Loaded -> TvAlbumResult.Loaded(result.album)
            AlbumResult.InvalidBrowseId -> TvAlbumResult.NotFound
            is AlbumResult.Failed -> TvAlbumResult.Failed(result.message)
        }
    } catch (error: Throwable) {
        TvAlbumResult.Failed(reasonOf(error))
    }

    /** Suspend-to-blocking bridge; contains every failure as [TvMusicChartsResult.Failed]. */
    private fun fetch(block: suspend () -> List<MusicTrack>): TvMusicChartsResult = try {
        TvMusicChartsResult.Loaded(runBlocking { block() })
    } catch (error: Throwable) {
        TvMusicChartsResult.Failed(reasonOf(error))
    }

    // ------------------------------------------------------------------ library

    /**
     * `GET {baseUrl}/api/playlists` -> [TvPlaylistsResult]. Пустой список — валидный ответ
     * («плейлистов ещё нет»), поэтому он доезжает как `Loaded(emptyList())`, а решение
     * «пусто или контент» принимает [TvMusicLibraryPolicy].
     */
    override fun fetchPlaylists(): TvPlaylistsResult = try {
        TvPlaylistsResult.Loaded(runBlocking { repository.getPlaylists() })
    } catch (error: Throwable) {
        TvPlaylistsResult.Failed(reasonOf(error))
    }

    /**
     * `GET {baseUrl}/api/playlist/{id}` -> [TvPlaylistTracksResult]. Тот же конверт, что у
     * чартов; пустой id отвергается core-network (`require`) и приезжает как `Failed`, а не
     * как исключение в контроллер.
     */
    override fun fetchPlaylistTracks(playlistId: String): TvPlaylistTracksResult = try {
        TvPlaylistTracksResult.Loaded(runBlocking { repository.getPlaylistTracks(playlistId) })
    } catch (error: Throwable) {
        TvPlaylistTracksResult.Failed(reasonOf(error))
    }

    /**
     * `GET {baseUrl}/api/history` -> [TvHistoryResult]. Записи играбельны (`id` — video id),
     * а `thumbnail` внутри них относительный: резолвится [TvThumbnailUrlResolver] от origin
     * сайта, а не от `/music` ([TvMusicLibraryPolicy] строит по ним очередь и подписи).
     */
    override fun fetchHistory(): TvHistoryResult = try {
        TvHistoryResult.Loaded(runBlocking { repository.getHistory() })
    } catch (error: Throwable) {
        TvHistoryResult.Failed(reasonOf(error))
    }

    /**
     * `POST {baseUrl}/api/history/record`: `false` вместо исключения — запись истории
     * вспомогательная, из-за неё воспроизведение на приставке ломаться не должно.
     */
    override fun recordHistory(track: MusicTrack): Boolean = try {
        runBlocking { repository.recordHistory(track) }
        true
    } catch (error: Throwable) {
        false
    }

    /** Причина сбоя для показа пользователю: сообщение сервера, иначе имя класса ошибки. */
    private fun reasonOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    companion object {
        /** Mars Music HTTP base path (mobile MusicActivity parity: BASE_URL + /music). */
        const val MUSIC_BASE_URL = "https://marscore.ru/music"

        /** Convenience factory for the TV music screen: real repository over the production base. */
        fun create(): RepositoryTvMusicGateway = RepositoryTvMusicGateway(MusicRepository(MUSIC_BASE_URL))
    }
}
