package ru.mars.tv

import kotlinx.coroutines.runBlocking
import ru.mars.network.repository.MusicRepository

/**
 * Production [TvMusicGateway]: binds the REAL core-network [MusicRepository]
 * (`GET {baseUrl}/api/charts`, baseUrl = [MUSIC_BASE_URL], mobile MusicActivity parity)
 * into the TV controller's blocking seam. All HTTP, envelope normalization, id cleanup
 * and error mapping stay in core-network — the TV layer only bridges suspend to blocking
 * (the controller runs this on its background executor) and guarantees that no exception
 * ever escapes: every outcome lands as an explicit [TvMusicChartsResult].
 */
class RepositoryTvMusicGateway(
    private val repository: MusicRepository,
) : TvMusicGateway {

    override fun fetchCharts(): TvMusicChartsResult = try {
        TvMusicChartsResult.Loaded(runBlocking { repository.getCharts() })
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
