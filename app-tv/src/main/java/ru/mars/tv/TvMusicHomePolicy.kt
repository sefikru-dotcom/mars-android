package ru.mars.tv

import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack

/**
 * Чистая политика персональной ленты «Для тебя» на TV-экране музыки (без Android-типов и
 * без пользовательских строк — JVM-тестируемая, паритет с мобильной `MusicHomePolicy`):
 *
 * - [visibleShelves] — что вообще можно рендерить. Сервер отдаёт полки в
 *   `GET /api/home -> shelf.sections`, и пустая полка — нормальное состояние («нет
 *   данных»), а не ошибка; заголовок без треков выглядит как баг, поэтому такая полка
 *   пропускается. Порядок полок и треков задаёт сервер — клиент его не переставляет.
 * - [playbackQueue] — плоская очередь всех видимых полок в порядке рендера: индекс
 *   строки на экране совпадает с позицией в очереди, поэтому выбранная пультом строка
 *   запускает ленту с себя и дальше играет по списку ([TvPlaylistPolicy]).
 * - [isFeedEmpty] — лента пуста только когда НИ ОДНА полка не несёт треков.
 * - [state] — ответ шва [TvMusicHomeResult] превращается в состояние экрана
 *   [TvMusicHomeState]: Loading и генерацию запроса держит контроллер, а здесь решается
 *   «полки или пусто» и подставляется дефолтный текст сбоя вместо пустой строки.
 * - [tabAfterHomeResult] — пустая лента откатывает экран на чарты (там контент есть
 *   всегда), а сбой оставляет пользователя на ленте с честной ошибкой и повтором.
 * - [retryRepeatsFeed] — «Повторить» повторяет операцию АКТИВНОЙ вкладки, поэтому на
 *   ленте оно обязано перезапрашивать ленту, а не молча уводить на чарты.
 */
object TvMusicHomePolicy {

    /** Стартовая вкладка экрана: персональная лента первой, чарты — запасная ветка. */
    val initialTab: TvMusicTab = TvMusicTab.FOR_YOU

    /** Вкладка каталога: в ней живут и чарты, и результаты поиска ([TvMusicScreenController.search]). */
    val chartsTab: TvMusicTab = TvMusicTab.CHARTS

    /** Полки для рендера: пустые пропускаются, остальные идут в серверном порядке. */
    fun visibleShelves(shelves: List<MusicShelf>): List<MusicShelf> =
        shelves.filter { it.items.isNotEmpty() }

    /** Все треки видимых полок одной плоской очередью в порядке рендера строк. */
    fun playbackQueue(shelves: List<MusicShelf>): List<MusicTrack> =
        visibleShelves(shelves).flatMap { it.items }

    /** Лента пуста, когда ни одна полка не несёт треков (пустой ответ или все полки пусты). */
    fun isFeedEmpty(shelves: List<MusicShelf>): Boolean = visibleShelves(shelves).isEmpty()

    /**
     * Ответ шва по ленте как состояние экрана. В [TvMusicHomeState.Shelves] попадают
     * только видимые полки — рендер и очередь воспроизведения читают один и тот же список.
     */
    fun state(result: TvMusicHomeResult): TvMusicHomeState = when (result) {
        is TvMusicHomeResult.Loaded ->
            if (isFeedEmpty(result.shelves)) TvMusicHomeState.Empty
            else TvMusicHomeState.Shelves(visibleShelves(result.shelves))

        is TvMusicHomeResult.Failed ->
            TvMusicHomeState.Failed(result.message.ifBlank { TvMusicScreenController.LOAD_FAILURE })
    }

    /** Вкладка после ответа по ленте: пустая лента откатывает на чарты, остальное — лента. */
    fun tabAfterHomeResult(result: TvMusicHomeResult): TvMusicTab =
        if (result is TvMusicHomeResult.Loaded && isFeedEmpty(result.shelves)) TvMusicTab.CHARTS
        else TvMusicTab.FOR_YOU

    /** «Повторить» на вкладке ленты повторяет ленту (на вкладке чартов — чарты или поиск). */
    fun retryRepeatsFeed(tab: TvMusicTab): Boolean = tab == TvMusicTab.FOR_YOU
}
