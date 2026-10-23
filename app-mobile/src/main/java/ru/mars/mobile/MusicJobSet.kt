package ru.mars.mobile

import kotlinx.coroutines.Job

/**
 * Набор фоновых запросов экрана «Музыка» и их разовая отмена.
 *
 * Единственное место, где перечислены ВСЕ job-ы, которые обязаны умереть вместе с
 * экраном. Раньше `onDestroy` отменял только чартовый `job`, а `libraryJob`, `radioJob`,
 * `lyricsJob`, `artistJob`, `albumsJob`, `albumJob` и `suggestionsJob` переживали выход:
 * их колбэки приходили в уничтоженную Activity, а сеть/диск продолжали работать без
 * экрана. Список держится отдельным объектом, чтобы регресс ловился JVM-тестом
 * (`MusicJobSetTest`), а не глазами в ревью.
 */
object MusicJobSet {
    /**
     * Отменяет весь набор. `null` — запрос не запускался, отменять нечего.
     * Порядок не важен: job-ы независимы друг от друга.
     */
    fun cancelAll(
        charts: Job?,
        library: Job?,
        radio: Job?,
        lyrics: Job?,
        artist: Job?,
        albums: Job?,
        album: Job?,
        suggestions: Job?,
    ) {
        listOf(charts, library, radio, lyrics, artist, albums, album, suggestions)
            .forEach { it?.cancel() }
    }
}
