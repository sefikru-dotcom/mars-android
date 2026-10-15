package ru.mars.mobile

import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack

/**
 * Чистая политика персональной ленты «Для тебя» (без Android-импортов, JVM-тестируемая).
 *
 * Сервер отдаёт полки GET /api/home → shelf.sections; пустая полка — нормальное
 * состояние («нет данных», server.py build_personal_shelf), но показывать её нельзя:
 * заголовок без треков выглядит как баг. Порядок полок и треков внутри них задаёт
 * сервер — клиент его не переставляет.
 */
object MusicHomePolicy {

    /** Полки для рендера: пустые пропускаются, остальные идут в серверном порядке. */
    fun visibleShelves(shelves: List<MusicShelf>): List<MusicShelf> =
        shelves.filter { it.items.isNotEmpty() }

    /**
     * Все треки видимых полок одной плоской очередью в порядке рендера — ровно тот
     * список, который кладётся в [MusicQueueManager] при тапе на строку ленты, и
     * ровно та нумерация, по которой рендерятся строки (индекс строки = индекс здесь).
     */
    fun playbackQueue(shelves: List<MusicShelf>): List<MusicTrack> =
        visibleShelves(shelves).flatMap { it.items }

    /** Лента пуста, когда ни одна полка не несёт треков (полный ответ пуст или все полки пусты). */
    fun isFeedEmpty(shelves: List<MusicShelf>): Boolean = visibleShelves(shelves).isEmpty()
}
