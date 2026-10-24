package ru.mars.tv

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.Playlist

/**
 * Секция «Ветки и списки» ТВ-экрана «Музыка»: «Чарты»/поиск, лента «Для тебя» с полками,
 * плейлисты с drill-down в треки и история прослушивания.
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3) как extension-функции на Activity.
 * Все ветки рисуют в ОДИН [TvMusicActivity.trackList] хоста, поэтому D-pad-цепочка строк
 * собирается одинаково, а фокус после рендера отдаёт [focusFirstRow]: у терминальных
 * пустых состояний своего «Повторить» нет, и окно без фокуса залипло бы насовсем.
 */

/** Ветка «Чарты»/поиск: те же четыре состояния, что и раньше — loading/error/empty/content. */
internal fun TvMusicActivity.renderCharts(state: TvMusicUiState) {
    when (state) {
        is TvMusicUiState.Loading -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_charts_loading)
            // Список стёрт этим же кадром: если фокус был в строке трека, окно
            // осталось бы без фокуса — отдаём его устойчивой шапке.
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvMusicUiState.Error -> {
            stateText.visibility = View.VISIBLE
            stateText.text = state.message
            retryButton.visibility = View.VISIBLE
            if (currentFocus == null) retryButton.requestFocus()
        }
        is TvMusicUiState.Empty -> {
            stateText.visibility = View.VISIBLE
            // Charts-empty and search-empty read differently (TvMusicSearchSpec).
            stateText.text = TvMusicSearchSpec.emptyMessage(state.query)
            // Empty is terminal (no retry affordance of its own): a re-render hid
            // «Повторить» and could have taken D-pad focus with it — the stable
            // header must hold focus or navigation stalls with no way to recover.
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvMusicUiState.Content -> {
            stateText.visibility = View.GONE
            renderTracks(state.tracks)
        }
    }
}

/**
 * Ветка «Для тебя»: полки персональной ленты. Loading/Failed — статусная строка (у
 * сбоя свой «Повторить», который повторяет ИМЕННО ленту, а не чарты), Shelves — полки
 * заголовками и строками, Empty — кадр до отката на чарты, который уже сделал
 * контроллер ([TvMusicHomePolicy.tabAfterHomeResult]); фокус здесь всегда уходит в
 * устойчивую шапку, потому что терминального «Повторить» у пустой ленты нет.
 */
internal fun TvMusicActivity.renderHome(state: TvMusicHomeState) {
    when (state) {
        is TvMusicHomeState.Idle, is TvMusicHomeState.Loading -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_home_loading)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvMusicHomeState.Failed -> {
            stateText.visibility = View.VISIBLE
            stateText.text = state.message
            retryButton.visibility = View.VISIBLE
            if (currentFocus == null) retryButton.requestFocus()
        }
        is TvMusicHomeState.Empty -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_home_empty)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvMusicHomeState.Shelves -> {
            stateText.visibility = View.GONE
            renderShelves(state.shelves)
        }
    }
}

/**
 * Полки ленты: заголовок (серверный [MusicShelf.title] — это данные, а не хардкод) и
 * строки треков под ним. Индекс строки совпадает с позицией в плоской очереди
 * ([TvMusicHomePolicy.playbackQueue]), поэтому пульт запускает ленту ровно с
 * выбранного трека. Заголовок не фокусируется — D-pad его перешагивает.
 */
internal fun TvMusicActivity.renderShelves(shelves: List<MusicShelf>) {
    var index = 0
    shelves.forEach { shelf ->
        trackList.addView(
            shelfHeading(shelf.title),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            },
        )
        shelf.items.forEach { track ->
            addTrackRow(track, index) { controller.onHomeTrackActivated(it) }
            index++
        }
    }
    // Заголовки не фокусируются, поэтому строка ищется среди фокусируемых детей
    // ([firstTrackRow]) — иначе после смены вкладки окно осталось бы без фокуса.
    if (currentFocus == null || currentFocus === retryButton) {
        firstTrackRow()?.requestFocus()
    }
}

/** Заголовок полки: тема-цвета, жирный, НЕ фокусируемый ([MusicShelf.title] как есть). */
internal fun TvMusicActivity.shelfHeading(title: String): TextView = TextView(this).apply {
    text = title
    tvTextSize(R.dimen.tv_text_headline)
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(ContextCompat.getColor(context, R.color.mars_text_strong))
    isFocusable = false
}

/**
 * Ветка «Плейлисты» (mobile parity): список серверных плейлистов, а после выбора строки —
 * drill-down в треки открытого плейлиста с фокусируемой кнопкой возврата. Обе части
 * живут в одном [trackList], поэтому D-pad-цепочка строк собирается одинаково.
 */
internal fun TvMusicActivity.renderPlaylists() {
    val open = libraryController.openPlaylist
    if (open != null) {
        renderPlaylistTracks(open, libraryController.playlistTracksState)
        return
    }
    when (val state = libraryController.playlistsState) {
        is TvPlaylistsState.Idle, is TvPlaylistsState.Loading -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_playlists_loading)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvPlaylistsState.Failed -> {
            stateText.visibility = View.VISIBLE
            stateText.text = state.message
            retryButton.visibility = View.VISIBLE
            if (currentFocus == null) retryButton.requestFocus()
        }
        is TvPlaylistsState.Empty -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_playlists_empty)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvPlaylistsState.Content -> {
            stateText.visibility = View.GONE
            state.playlists.forEach { addPlaylistRow(it) }
            focusFirstRow()
        }
    }
}

/**
 * Drill-down открытого плейлиста: «Все плейлисты» сверху (единственный выход назад,
 * кроме BACK), затем строки треков. Очередь воспроизведения — весь плейлист
 * ([TvMusicLibraryController.onPlaylistTrackActivated]), индекс строки совпадает с
 * позицией в нём.
 */
internal fun TvMusicActivity.renderPlaylistTracks(playlist: Playlist, state: TvPlaylistTracksState) {
    trackList.addView(
        playlistBackChip(playlist),
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(48),
        ).apply { bottomMargin = dp(14) },
    )
    when (state) {
        is TvPlaylistTracksState.Loading -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_playlist_tracks_loading)
            focusFirstRow()
        }
        is TvPlaylistTracksState.Failed -> {
            stateText.visibility = View.VISIBLE
            stateText.text = state.message
            retryButton.visibility = View.VISIBLE
            focusFirstRow()
        }
        is TvPlaylistTracksState.Empty -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_playlist_tracks_empty)
            focusFirstRow()
        }
        is TvPlaylistTracksState.Content -> {
            stateText.visibility = View.GONE
            state.tracks.forEachIndexed { index, track ->
                addTrackRow(track, index) { libraryController.onPlaylistTrackActivated(it) }
            }
            focusFirstRow()
        }
        TvPlaylistTracksState.Idle -> Unit
    }
}

/** Кнопка «Все плейлисты»: возврат из drill-down без повторного запроса списка. */
internal fun TvMusicActivity.playlistBackChip(playlist: Playlist): TextView = TextView(this).apply {
    text = getString(R.string.tv_music_playlist_back)
    tvTextSize(R.dimen.tv_text_body)
    gravity = Gravity.CENTER
    setPadding(dp(20), 0, dp(20), 0)
    setTextColor(ContextCompat.getColor(context, R.color.mars_text))
    setBackgroundResource(R.drawable.bg_focus_card)
    isFocusable = true
    isClickable = true
    id = View.generateViewId()
    // TalkBack: и из какого плейлиста возвращаемся, и что кнопка делает.
    contentDescription = getString(R.string.tv_music_playlist_open, TvMusicLibraryPolicy.playlistTitle(playlist))
    setOnClickListener { libraryController.closePlaylist() }
    TvFocusLift.attach(this, reducedMotion)
}

/**
 * Строка плейлиста: заголовок и подпись «N треков · создан DD.MM.YYYY»
 * ([TvMusicLibraryPolicy.playlistMeta] — правило языка, не вёрстки). Обложки у плейлиста
 * сервер не отдаёт, поэтому строка текстовая; клик открывает drill-down.
 */
internal fun TvMusicActivity.addPlaylistRow(playlist: Playlist) {
    val title = TextView(this).apply {
        text = TvMusicLibraryPolicy.playlistTitle(playlist)
        tvTextSize(R.dimen.tv_text_title)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(ContextCompat.getColor(context, R.color.mars_text_strong))
    }
    val meta = TextView(this).apply {
        text = TvMusicLibraryPolicy.playlistMeta(playlist)
        tvTextSize(R.dimen.tv_text_body)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        setOnClickListener { libraryController.openPlaylist(playlist) }
    }
    row.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    row.addView(meta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    TvFocusLift.attach(row, reducedMotion)
    trackList.addView(
        row,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
            bottomMargin = dp(12)
        },
    )
}

/**
 * Ветка «История» (mobile parity): записи прослушивания сверху вниз от новых к старым.
 * Строка играбельна ([HistoryEntry.toTrack]) и стартует очередь из ВСЕЙ истории;
 * подпись — [TvMusicLibraryPolicy.historyMeta] («ELMAN · 2:45 · вчера, 04:30»).
 */
internal fun TvMusicActivity.renderHistory(state: TvHistoryState) {
    when (state) {
        is TvHistoryState.Idle, is TvHistoryState.Loading -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_history_loading)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvHistoryState.Failed -> {
            stateText.visibility = View.VISIBLE
            stateText.text = state.message
            retryButton.visibility = View.VISIBLE
            if (currentFocus == null) retryButton.requestFocus()
        }
        is TvHistoryState.Empty -> {
            stateText.visibility = View.VISIBLE
            stateText.text = getString(R.string.tv_music_history_empty)
            if (currentFocus == null) backButton.requestFocus()
        }
        is TvHistoryState.Content -> {
            stateText.visibility = View.GONE
            val now = System.currentTimeMillis()
            state.entries.forEachIndexed { index, entry ->
                val track = entry.toTrack()
                val meta = TvMusicLibraryPolicy.historyMeta(entry, now)
                addTrackRow(
                    track,
                    index,
                    label = listOf(track.title, meta).filter { it.isNotBlank() }
                        .joinToString(TvMusicLibraryPolicy.DETAIL_SEPARATOR),
                ) { libraryController.onHistoryTrackActivated(it) }
            }
            focusFirstRow()
        }
    }
}

/**
 * Фокус после рендера библиотечной ветки: первой фокусируемой строке (drill-down отдаёт
 * его кнопке «Все плейлисты»). Если строк нет — «Повторить» или устойчивая шапка, чтобы
 * окно никогда не осталось без фокуса.
 */
internal fun TvMusicActivity.focusFirstRow() {
    val first = firstTrackRow()
    if (first != null) {
        if (currentFocus == null || currentFocus === retryButton) first.requestFocus()
        return
    }
    if (currentFocus == null) {
        if (retryButton.visibility == View.VISIBLE) retryButton.requestFocus() else backButton.requestFocus()
    }
}
