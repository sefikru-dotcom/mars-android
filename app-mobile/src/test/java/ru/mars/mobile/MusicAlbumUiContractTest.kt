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
 *
 * После раскладки экрана на `Music*Section.kt` разметка диалогов живёт НЕ в
 * `MusicActivity.kt`, поэтому исходник экрана здесь — хост ПЛЮС все секции
 * (glob `Music*Section.kt`, как `chatSections` в [ChatClickPathContractTest]).
 * Новый секционный файл подхватывается сам, ослаблять ожидания не нужно.
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

    /** Каталог production-исходников экрана — от него берётся список секций. */
    private val sourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val flat = File(dir, "src/main/java/ru/mars/mobile/MusicActivity.kt")
            if (flat.isFile) return@lazy flat.absoluteFile.parentFile!!
            val nested = File(dir, "app-mobile/src/main/java/ru/mars/mobile/MusicActivity.kt")
            if (nested.isFile) return@lazy nested.absoluteFile.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников MusicActivity.kt")
    }

    /**
     * Все секции экрана: `MusicQueueSection.kt`, `MusicHistorySection.kt`, … Список
     * читается с диска, а не переписывается руками — иначе каждый следующий шаг
     * раскладки снова ронял бы контракт.
     */
    private val musicSections: List<String> by lazy {
        sourceDir.listFiles { f -> f.isFile && f.name.startsWith("Music") && f.name.endsWith("Section.kt") }
            ?.sortedBy { it.name }
            ?.map { it.readText() }
            .orEmpty()
    }

    /** Исходник экрана целиком: хост + секции (в каком файле лежит строка — не важно). */
    private val activity by lazy {
        (listOf(source("src/main/java/ru/mars/mobile/MusicActivity.kt")) + musicSections).joinToString("\n")
    }

    /**
     * Тело функции по имени: объявление может быть и методом хоста (`private fun x(`),
     * и расширением секции (`internal fun MusicActivity.x(`). Режется по парной скобке,
     * поэтому в тело не попадает соседняя функция.
     */
    private fun body(src: String, name: String): String {
        val match = Regex("""fun\s+(?:[\w.]+\.)?${Regex.escape(name)}\s*\(""").find(src)
        assertTrue("функция $name не найдена в исходниках экрана", match != null)
        val start = match!!.range.first
        val open = src.indexOf('{', start)
        assertTrue("у $name нет тела", open > start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не закрыто тело $name")
    }

    /** Тело диалога исполнителя (объявление ищется в хосте или в MusicArtistSection.kt). */
    private val artistDialog by lazy { body(activity, "openArtistDialog") }

    /** Тело диалога альбома (хост или MusicAlbumSection.kt). */
    private val albumDialog by lazy { body(activity, "openAlbumDialog") }

    /** Тело карточки альбома (хост или MusicAlbumSection.kt). */
    private val albumCard by lazy { body(activity, "albumCard") }

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
        assertTrue(
            "Чужие альбомы из выдачи поиска не показываются первыми: порядок доводит rankForArtist",
            artistDialog.contains("MusicAlbumPolicy.rankForArtist("),
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
        assertTrue(
            "Карточка альбома озвучивает действие тапа (в стиле строк треков)",
            albumCard.contains("contentDescription = MusicAlbumPolicy.cardDescription(album)"),
        )
        assertTrue(
            "Карточка кликабельна и фокусируема — иначе TalkBack её не выберет",
            albumCard.contains("isClickable = true") && albumCard.contains("isFocusable = true"),
        )
    }
}
