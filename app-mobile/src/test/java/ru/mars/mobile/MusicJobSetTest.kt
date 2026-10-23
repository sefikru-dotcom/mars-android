package ru.mars.mobile

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Отмена фоновых запросов экрана «Музыка» при выходе.
 *
 * Регресс, который ловит тест: `onDestroy` отменял только чартовый `job`, а
 * `libraryJob`/`radioJob`/`lyricsJob`/`artistJob`/`albumsJob`/`albumJob`/`suggestionsJob`
 * переживали экран. Их колбэки приходили в уничтоженную Activity, а запросы продолжали
 * занимать сеть и диск. Здесь проверяются обе половины контракта: сам набор реально
 * отменяется (поведение [MusicJobSet]) и `onDestroy` передаёт в него ВСЕ поля-джобы
 * (проводка в production-исходнике — instrumented-слоя в проекте нет).
 */
class MusicJobSetTest {

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

    /** Тело `onDestroy`: от объявления до вызова super. */
    private val onDestroy by lazy {
        activity.substringAfter("override fun onDestroy() {")
            .substringBefore("super.onDestroy()")
    }

    // --- поведение набора ---

    @Test
    fun cancelAllCancelsEveryJobInTheSet() {
        val charts = Job()
        val library = Job()
        val radio = Job()
        val lyrics = Job()
        val artist = Job()
        val albums = Job()
        val album = Job()
        val suggestions = Job()

        MusicJobSet.cancelAll(charts, library, radio, lyrics, artist, albums, album, suggestions)

        listOf(
            "charts" to charts,
            "library" to library,
            "radio" to radio,
            "lyrics" to lyrics,
            "artist" to artist,
            "albums" to albums,
            "album" to album,
            "suggestions" to suggestions,
        ).forEach { (name, job) ->
            assertFalse("$name обязан быть отменён вместе с экраном", job.isActive)
        }
    }

    @Test
    fun cancelAllToleratesJobsThatWereNeverStarted() {
        val running = Job()
        MusicJobSet.cancelAll(null, null, running, null, null, null, null, null)
        assertFalse("единственный запущенный запрос отменяется, остальные null не мешают", running.isActive)
    }

    // --- проводка в onDestroy ---

    @Test
    fun onDestroyCancelsTheWholeJobSet() {
        assertTrue(
            "onDestroy обязан отменять набор через MusicJobSet.cancelAll",
            onDestroy.contains("MusicJobSet.cancelAll("),
        )
        listOf("charts = job", "library = libraryJob", "radio = radioJob", "lyrics = lyricsJob",
            "artist = artistJob", "albums = albumsJob", "album = albumJob",
            "suggestions = suggestionsJob").forEach { field ->
            assertTrue(
                "onDestroy обязан передавать в набор $field — иначе этот запрос переживёт экран",
                onDestroy.contains(field),
            )
        }
        assertFalse(
            "единственная отмена job?.cancel() в onDestroy оставляла 7 запросов живыми",
            onDestroy.contains("job?.cancel()"),
        )
    }
}
