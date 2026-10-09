package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/**
 * Чистая логика постановки радио-треков в очередь (без Android).
 *
 * Радио по треку досылает похожие треки и продолжает текущее воспроизведение бесшовно:
 * кандидаты добавляются в хвост очереди, поэтому политика обязана
 * - исключить дубликаты (трек уже в очереди или это сам текущий трек),
 * - сохранить порядок выдачи сервера,
 * - ограничить рост очереди, чтобы одно нажатие не могло залить её тысячей треков.
 *
 * Решения тестируются на JVM; [MusicActivity] только применяет результат к плееру.
 */
object RadioQueuePolicy {

    /** Максимум треков, добавляемых в очередь за одно нажатие «Радио». */
    const val MAX_ADDITIONS = 30

    /**
     * Треки из [fetched], которые реально нужно добавить в очередь.
     *
     * @param fetched ответ `/api/radio/{videoId}` (порядок сервера сохраняется);
     * @param queuedIds id треков, уже стоящих в очереди;
     * @param currentTrackId id текущего трека — отсеивается, даже если его нет в очереди;
     * @param limit максимальное число добавлений.
     */
    fun tracksToAdd(
        fetched: List<MusicTrack>,
        queuedIds: Collection<String>,
        currentTrackId: String?,
        limit: Int = MAX_ADDITIONS,
    ): List<MusicTrack> {
        if (limit <= 0) return emptyList()
        val blocked = queuedIds.toMutableSet()
        if (!currentTrackId.isNullOrBlank()) blocked += currentTrackId
        val additions = ArrayList<MusicTrack>(minOf(fetched.size, limit))
        for (track in fetched) {
            if (additions.size >= limit) break
            if (track.id.isBlank() || !blocked.add(track.id)) continue
            additions += track
        }
        return additions
    }
}
