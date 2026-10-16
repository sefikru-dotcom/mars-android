package ru.mars.tv

import kotlinx.coroutines.runBlocking
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

/**
 * Production [TvMusicGateway] + [TvLyricsGateway]: binds the REAL core-network [MusicRepository]
 * (`GET {baseUrl}/api/charts`, `GET {baseUrl}/api/search?q=…` and the personal feed
 * `GET {baseUrl}/api/home`, baseUrl = [MUSIC_BASE_URL], mobile MusicActivity parity) into
 * the TV controller's blocking seam.
 * All HTTP, envelope normalization, id cleanup and error mapping stay in core-network —
 * the TV layer only bridges suspend to blocking (the controller runs this on its
 * background executor) and guarantees that no exception ever escapes: every outcome lands
 * as an explicit [TvMusicChartsResult] / [TvMusicHomeResult] / [TvLyricsResult].
 */
class RepositoryTvMusicGateway(
    private val repository: MusicRepository,
) : TvMusicGateway, TvLyricsGateway {

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

    /** Suspend-to-blocking bridge; contains every failure as [TvMusicChartsResult.Failed]. */
    private fun fetch(block: suspend () -> List<MusicTrack>): TvMusicChartsResult = try {
        TvMusicChartsResult.Loaded(runBlocking { block() })
    } catch (error: Throwable) {
        TvMusicChartsResult.Failed(reasonOf(error))
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
