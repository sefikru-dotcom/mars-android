package ru.mars.tv

import kotlinx.coroutines.runBlocking
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository

/**
 * Production [TvMusicGateway]: binds the REAL core-network [MusicRepository]
 * (`GET {baseUrl}/api/charts` and `GET {baseUrl}/api/search?q=…`, baseUrl =
 * [MUSIC_BASE_URL], mobile MusicActivity parity) into the TV controller's blocking seam.
 * All HTTP, envelope normalization, id cleanup and error mapping stay in core-network —
 * the TV layer only bridges suspend to blocking (the controller runs this on its
 * background executor) and guarantees that no exception ever escapes: every outcome lands
 * as an explicit [TvMusicChartsResult].
 */
class RepositoryTvMusicGateway(
    private val repository: MusicRepository,
) : TvMusicGateway {

    override fun fetchCharts(): TvMusicChartsResult = fetch { repository.getCharts() }

    override fun search(query: String): TvMusicChartsResult = fetch { repository.search(query) }

    /** Suspend-to-blocking bridge; contains every failure as [TvMusicChartsResult.Failed]. */
    private fun fetch(block: suspend () -> List<MusicTrack>): TvMusicChartsResult = try {
        TvMusicChartsResult.Loaded(runBlocking { block() })
    } catch (error: Throwable) {
        TvMusicChartsResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
    }

    companion object {
        /** Mars Music HTTP base path (mobile MusicActivity parity: BASE_URL + /music). */
        const val MUSIC_BASE_URL = "https://marscore.ru/music"

        /** Convenience factory for the TV music screen: real repository over the production base. */
        fun create(): RepositoryTvMusicGateway = RepositoryTvMusicGateway(MusicRepository(MUSIC_BASE_URL))
    }
}
