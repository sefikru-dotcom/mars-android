package ru.mars.tv

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.core.content.ContextCompat

/**
 * Секция «Оболочка экрана» ТВ-экрана «Музыка».
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3).
 *
 * Единственная точка рендера ([renderCurrent]) и общая часть каждого кадра: ветку выбирает
 * АКТИВНАЯ вкладка контроллера, а не тот наблюдатель, который сработал, поэтому смена
 * вкладки, лента, чарты и библиотека рисуются одним путём. Здесь же оформление вкладок,
 * пересборка D-pad-цепочек после рендера, вход на библиотечную вкладку, «Повторить»
 * активной ветки, отправка поиска и экранная клавиатура.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, контроллеры,
 * видимость IME) остаётся в хосте, тела перенесены дословно. Члены хоста, которые читают
 * секции, подняты `private` -> `internal`.
 */

/**
 * Вход на библиотечную вкладку: переключение ветки + ПЕРВАЯ загрузка. Повторный вход на
 * уже загруженную вкладку ничего не перезапрашивает — D-pad не должен дёргать сеть на
 * каждом нажатии; «Повторить» на сбое повторяет именно эту ветку ([retryActiveBranch]).
 */
internal fun TvMusicActivity.openLibraryTab(tab: TvMusicTab) {
    if (controller.tab != tab) controller.selectTab(tab)
    when (tab) {
        TvMusicTab.PLAYLISTS ->
            if (libraryController.playlistsState is TvPlaylistsState.Idle) {
                libraryController.loadPlaylists()
            }

        TvMusicTab.HISTORY ->
            if (libraryController.historyState is TvHistoryState.Idle) {
                libraryController.loadHistory()
            }

        else -> Unit
    }
}

/**
 * «Повторить» повторяет операцию АКТИВНОЙ ветки: чарты/поиск и лента — контроллер
 * экрана, плейлисты и история — контроллер библиотеки. Иначе кнопка под «Плейлистами»
 * молча перезагружала бы чарты, а вкладка оставалась бы пустой.
 */
internal fun TvMusicActivity.retryActiveBranch() {
    when (controller.tab) {
        TvMusicTab.PLAYLISTS -> libraryController.retryPlaylists()
        TvMusicTab.HISTORY -> libraryController.retryHistory()
        else -> controller.retry()
    }
}

/** Submits the field text as a search and dismisses the IME so results stay visible. */
internal fun TvMusicActivity.submitSearch() {
    controller.search(searchInput.text.toString())
    hideIme()
}

/** Явный показ экранной клавиатуры (DPAD_CENTER на searchInput). */
internal fun TvMusicActivity.showIme() {
    searchInput.requestFocus()
    val ime = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    ime.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
}

/** Скрыть экранную клавиатуру; видимость пересчитает [globalLayoutListener]. */
internal fun TvMusicActivity.hideIme() {
    val ime = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    ime.hideSoftInputFromWindow(searchInput.windowToken, 0)
}

/**
 * Единственная точка рендера: ветку выбирает АКТИВНАЯ вкладка контроллера
 * ([TvMusicTab.FOR_YOU] — лента, [TvMusicTab.CHARTS] — чарты/поиск), а не то, какой из
 * наблюдателей сработал, поэтому смена вкладки, состояние ленты и чартовый список
 * рисуются одним и тем же путём. Общая часть (статусная строка, транспорт, цепочки
 * фокуса) — после ветки.
 */
internal fun TvMusicActivity.renderCurrent() {
    trackList.removeAllViews()
    trackRows.clear()
    retryButton.visibility = View.GONE
    when (controller.tab) {
        TvMusicTab.FOR_YOU -> renderHome(controller.homeState)
        TvMusicTab.CHARTS -> renderCharts(controller.state)
        TvMusicTab.PLAYLISTS -> renderPlaylists()
        TvMusicTab.HISTORY -> renderHistory(libraryController.historyState)
    }
    renderTabStyles()
    renderTransportState()
    // DOWN из строки поиска ведёт к списку треков: список динамический, поэтому
    // цель переставляется кодом после каждого рендера (XML nextFocusDown — лишь
    // стартовый fallback на @+id/trackList).
    rewireSearchDownFocus()
}

/** Вкладки: активная — акцентная рамка и яркий текст, остальные — карточка и обычный. */
internal fun TvMusicActivity.renderTabStyles() {
    val tab = controller.tab
    styleTab(forYouButton, R.string.tv_music_tab_for_you, active = tab == TvMusicTab.FOR_YOU)
    styleTab(chartsButton, R.string.tv_music_tab_charts, active = tab == TvMusicTab.CHARTS)
    styleTab(playlistsButton, R.string.tv_music_tab_playlists, active = tab == TvMusicTab.PLAYLISTS)
    styleTab(historyButton, R.string.tv_music_tab_history, active = tab == TvMusicTab.HISTORY)
}

/**
 * Оформление одной вкладки: цвета только из темы, а TalkBack читает, какая вкладка
 * открыта (текст плюс подпись активной — паритет с мобильным клиентом).
 */
internal fun TvMusicActivity.styleTab(button: Button, labelRes: Int, active: Boolean) {
    val label = getString(labelRes)
    button.setTextColor(
        ContextCompat.getColor(this, if (active) R.color.mars_text_strong else R.color.mars_text),
    )
    button.setBackgroundResource(if (active) R.drawable.bg_focus_accent else R.drawable.bg_focus_card)
    button.contentDescription =
        if (active) getString(R.string.tv_music_tab_active, label) else label
}

/**
 * Взаимные nextFocus* всех кнопок верхней панели + строки поиска — только между
 * ВИДИМЫМИ элементами; края самопетлят (фокус остаётся в панели). Вызывается
 * после каждой смены видимости транспорта ([renderTransportState]).
 */
internal fun TvMusicActivity.rewireHeaderFocusChain() {
    val visible = listOf(backButton, repeatButton, lyricsButton, playPauseButton, stopButton)
        .filter { it.visibility == View.VISIBLE }
    TvMusicFocusPolicy.horizontalChain(visible.map { it.id }).forEach { (id, sides) ->
        findViewById<View>(id).apply {
            nextFocusLeftId = sides.first
            nextFocusRightId = sides.second
        }
    }
    // Вниз из любой кнопки шапки — в поле поиска; вверх — оставаться в шапке.
    visible.forEach {
        it.nextFocusDownId = R.id.searchInput
        it.nextFocusUpId = it.id
    }
    // Вверх из строки поиска и вкладок — только в видимые кнопки шапки.
    searchInput.nextFocusUpId = backButton.id
    searchButton.nextFocusUpId = repeatButton.id
    forYouButton.nextFocusUpId = (visible.lastOrNull() ?: backButton).id
    chartsButton.nextFocusUpId = (visible.lastOrNull() ?: backButton).id
    playlistsButton.nextFocusUpId = (visible.lastOrNull() ?: backButton).id
    historyButton.nextFocusUpId = (visible.lastOrNull() ?: backButton).id
}

/**
 * Код-механизм «поиск -> динамический список треков»: после рендера наводим
 * nextFocusDown строки поиска (и соседей) на ПЕРВУЮ строку [trackList]; если списка
 * нет — на видимую «Повторить», иначе цели нет и фокус честно остаётся на месте.
 * Первый трек получает nextFocusUp обратно в поле поиска.
 */
internal fun TvMusicActivity.rewireSearchDownFocus() {
    // Первая ФОКУСИРУЕМАЯ строка: в ленте первым ребёнком полки идёт заголовок,
    // который не фокусируется, поэтому просто getChildAt(0) навёл бы DOWN в никуда.
    val firstRow = firstTrackRow()
    val target = TvMusicFocusPolicy.downTargetFromSearchRow(
        firstTrackRowId = firstRow?.id?.takeIf { it != View.NO_ID },
        retryButtonId = R.id.retryButton,
        retryVisible = retryButton.visibility == View.VISIBLE,
    )
    searchInput.nextFocusDownId = target
    searchButton.nextFocusDownId = target
    forYouButton.nextFocusDownId = target
    chartsButton.nextFocusDownId = target
    playlistsButton.nextFocusDownId = target
    historyButton.nextFocusDownId = target
    // «Повторить» вниз — в первый трек, а без треков цели нет (фокус остаётся на месте;
    // самопетлю GONE-кнопке не ставим, чтобы чужой focusSearch не зациклился о невидимое).
    retryButton.nextFocusDownId = firstRow?.id ?: TvMusicFocusPolicy.NO_FOCUS
    retryButton.nextFocusUpId = R.id.searchInput
    firstRow?.nextFocusUpId = R.id.searchInput
}
