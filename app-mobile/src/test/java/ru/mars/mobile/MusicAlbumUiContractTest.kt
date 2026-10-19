package ru.mars.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки навигации по альбомам в [MusicActivity]: где берётся browseId,
 * чем грузятся альбомы исполнителя, что играет тап по треку и откуда берутся
 * TalkBack-подписи.
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена по
 * РЕАЛЬНОМУ production-исходнику (тот же приём, что в [MusicA11yContractTest]):
 * сами строки и правила проверяются на чистой политике в [MusicAlbumPolicyTest],
 * а здесь фиксируется, что экран их действительно использует. Подмена запроса на
 * `/api/album` «поиском треков» или удаление подписи карточки ломает тест.
 */
class MusicAlbumUiContractTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val activity by lazy { source("src/main/java/ru/mars/mobile/MusicActivity.kt") }

    /** Тело диалога исполнителя: от объявления до строки трека. */
    private val artistDialog by lazy {
        activity.substringAfter("private fun openArtistDialog(target: String) {")
            .substringBefore("private fun artistSongRow(")
    }

    /** Тело диалога альбома. */
    private val albumDialog by lazy {
        activity.substringAfter("private fun openAlbumDialog(rawBrowseId: String) {")
            .substringBefore("/** Radio/lyrics act on the current track only")
    }

    @Test
    fun artistDialogLoadsAlbumsThroughAlbumSearch() {
        assertTrue(
            "Альбомы исполнителя берутся поиском с type=albums — страница исполнителя их не отдаёт",
            artistDialog.contains("repository.searchAlbums(target)"),
        )
        assertTrue(
            "Повтор загрузки альбомов гейтится тем же canStartRequest, что и страница исполнителя",
            artistDialog.contains("MusicArtistPolicy.canStartRequest(albumsJob?.isActive == true)"),
        )
        assertTrue(
            "Карточки альбомов строит albumCard, тап открывает диалог альбома",
            artistDialog.contains("albumCard(album) { openAlbumDialog(album.browseId) }"),
        )
    }

    @Test
    fun albumDialogReadsTheAlbumEndpointAndPlaysItsTracks() {
        assertTrue(
            "Диалог альбома грузит GET /api/album через репозиторий",
            albumDialog.contains("repository.getAlbum(browseId)"),
        )
        assertTrue(
            "browseId проверяется политикой до запроса (MPRE)",
            albumDialog.contains("MusicAlbumPolicy.requestTarget(rawBrowseId)"),
        )
        assertTrue(
            "Тап по треку играет ВЕСЬ альбом с этой песни, а не одиночный трек",
            albumDialog.contains("artistSongRow(track, index, album.tracks, ::feedback)"),
        )
        assertTrue(
            "Состояния диалога берутся из политики, а не пишутся в слое вью",
            albumDialog.contains("MusicAlbumPolicy.failureText(notFound = notFound)") &&
                albumDialog.contains("MusicAlbumPolicy.retryAvailable(notFound = notFound)"),
        )
    }

    @Test
    fun albumCardHasTalkBackDescription() {
        val card = activity.substringAfter("private fun albumCard(")
            .substringBefore("private fun openAlbumDialog(")
        assertTrue(
            "Карточка альбома озвучивает действие тапа (в стиле строк треков)",
            card.contains("contentDescription = MusicAlbumPolicy.cardDescription(album)"),
        )
        assertTrue(
            "Карточка кликабельна и фокусируема — иначе TalkBack её не выберет",
            card.contains("isClickable = true") && card.contains("isFocusable = true"),
        )
    }
}
