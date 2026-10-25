package ru.mars.tv

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.model.MusicTrack

/**
 * Секция «Строки треков и транспорт» ТВ-экрана «Музыка».
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3) как extension-функции на Activity.
 * Одна строка трека ([addTrackRow]) — общая для чартов, полок ленты, плейлистов и истории:
 * обложка [TvImageLoader], подпись с альбомом, кнопка «Альбом» и маркер текущего трека.
 * Шапка транспорта ([renderTransportState]) рисует пару «Пауза/Играть» + «Остановить»,
 * строку «Сейчас играет» и подсветку текущей строки; фокус с исчезающих кнопок снимается
 * ДО их скрытия ([TvMusicFocusPolicy.handoffBeforeHide]), иначе пульт замолкает.
 */

/** Ветка «Чарты»/поиск в Content: строки треков и передача фокуса списку. */
internal fun TvMusicActivity.renderTracks(tracks: List<MusicTrack>) {
    tracks.forEachIndexed { index, track ->
        addTrackRow(track, index) { controller.onTrackActivated(it) }
    }
    // Hand D-pad focus to the list when nothing meaningful is focused (retry may be GONE now).
    if (currentFocus == null || currentFocus === retryButton) {
        firstTrackRow()?.requestFocus()
    }
}

/**
 * Первая ФОКУСИРУЕМАЯ строка списка: в ветке чартов это trackList.getChildAt(0), а в
 * ленте первым ребёнком полки идёт заголовок (не фокусируется) — после быстрого пути
 * ищем первого фокусируемого ребёнка, иначе DOWN из поиска вёл бы в невидимую цель.
 */
internal fun TvMusicActivity.firstTrackRow(): View? {
    val first = trackList.getChildAt(0)
    if (first != null && first.isFocusable) return first
    for (index in 0 until trackList.childCount) {
        val child = trackList.getChildAt(index)
        if (child.isFocusable) return child
    }
    return null
}

/**
 * Одна строка трека — общая для чартов и полок ленты: обложка, подпись, маркер
 * текущего трека и [onActivate] с индексом строки (в ленте — позиция в плоской
 * очереди полок, [TvMusicHomePolicy.playbackQueue]).
 */
internal fun TvMusicActivity.addTrackRow(
    track: MusicTrack,
    index: Int,
    label: String = trackLabel(track),
    onActivate: (Int) -> Unit,
) {
    // Обложка трека: серверный thumbnail грузит TvImageLoader; при пустом/нерезолвящемся
    // URL или ошибке загрузки вьюха остаётся GONE (строка читается как раньше).
    val cover = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        contentDescription = null
    }
    val labelView = TextView(this).apply {
        text = label
        tvTextSize(R.dimen.tv_text_title)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
    }
    // Маркер текущего трека (вместо compound-drawable у Button-строки старой версии).
    val marker = ImageView(this).apply {
        setImageResource(R.drawable.ic_music)
        imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.mars_accent))
        visibility = View.GONE
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), 0, dp(24), 0)
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = true
        isClickable = true
        // Динамические строки получают собственный id, чтобы код мог навести
        // nextFocusDown поля поиска на ПЕРВЫЙ трек: rewireSearchDownFocus().
        id = View.generateViewId()
        setOnClickListener { onActivate(index) }
    }
    row.addView(
        cover,
        LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(14)
        },
    )
    row.addView(
        labelView,
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        },
    )
    // Кнопка «Альбом» — только у трека, который реально откроется ([TvAlbumPolicy.openable]):
    // у строки с пустым/битым browseId кнопки нет вовсе, иначе пульт упирался бы в
    // фокусируемую, но мёртвую цель. Фокус справа от строки: строка играет трек,
    // кнопка — открывает альбом (mobile-parity: там тап по подписи альбома).
    val albumChip = TextView(this).apply {
        text = getString(R.string.tv_album_action)
        tvTextSize(R.dimen.tv_text_body)
        isSingleLine = true
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        visibility = if (TvAlbumPolicy.openable(track)) View.VISIBLE else View.GONE
        contentDescription = getString(R.string.tv_album_action_for, track.albumTitle.orEmpty())
        setOnClickListener { openAlbumDialog(track.albumBrowseId) }
    }
    row.addView(
        albumChip,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(12)
        },
    )
    row.addView(
        marker,
        LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(12)
        },
    )
    TvFocusLift.attach(row, reducedMotion)
    TvFocusLift.attach(albumChip, reducedMotion)
    val trackRow = TvMusicActivity.TrackRow(track.id, labelView, marker)
    applyRowState(trackRow)
    trackRows += trackRow
    trackList.addView(
        row,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
            bottomMargin = dp(12)
        },
    )
    TvImageLoader.load(track.thumbnail, cover)
}

/** Transport snapshot to the UI: transport pair, header line, row highlight. */
internal fun TvMusicActivity.renderTransportState() {
    val controls = TvMusicNowPlayingSpec.transportControls(
        track = TvMusicPlayer.currentTrack,
        isPlaying = TvMusicPlayer.isPlaying,
    )
    // The transport pair disappears the moment the transport goes idle — in production
    // this fires synchronously from the D-pad press on the button itself (stop()/pause
    // notify observers), so hiding it under focus would leave the window unfocused and
    // stall D-pad navigation. Hand focus to the stable header BEFORE hiding
    // ([TvMusicFocusPolicy]).
    if (
        TvMusicFocusPolicy.handoffBeforeHide(
            viewVisible = stopButton.visibility == View.VISIBLE,
            viewHasFocus = stopButton.hasFocus(),
            staysVisible = controls.visible,
        ) ||
        TvMusicFocusPolicy.handoffBeforeHide(
            viewVisible = playPauseButton.visibility == View.VISIBLE,
            viewHasFocus = playPauseButton.hasFocus(),
            staysVisible = controls.visible,
        ) ||
        TvMusicFocusPolicy.handoffBeforeHide(
            viewVisible = lyricsButton.visibility == View.VISIBLE,
            viewHasFocus = lyricsButton.hasFocus(),
            staysVisible = controls.visible,
        )
    ) {
        backButton.requestFocus()
    }
    val pairVisibility = if (controls.visible) View.VISIBLE else View.GONE
    stopButton.visibility = pairVisibility
    playPauseButton.visibility = pairVisibility
    // «Текст песни» живёт и умирает вместе с транспортной парой: нет привязанного
    // трека — нет и текста, который можно показать.
    lyricsButton.visibility = pairVisibility
    playPauseButton.text = controls.playPauseLabel
    playPauseButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (controls.showPauseIcon) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0,
    )
    playPauseButton.compoundDrawableTintList =
        ContextCompat.getColorStateList(this, R.color.icon_focus_tint)
    renderRepeatMode()
    renderNowPlaying()
    trackRows.forEach { applyRowState(it) }
    // Видимость транспортных кнопок изменилась — пересобираем LEFT/RIGHT цепочку
    // шапки только из видимых кнопок, чтобы фокус никогда не терялся и не «нырял»
    // в GONE-вьюху (чистая [TvMusicFocusPolicy.horizontalChain]).
    rewireHeaderFocusChain()
}

/** Repeat-toggle label mirrors the transport preference (pure: [TvMusicNowPlayingSpec]). */
internal fun TvMusicActivity.renderRepeatMode() {
    repeatButton.text = TvMusicNowPlayingSpec.repeatButtonLabel(TvMusicPlayer.repeatMode)
}

internal fun TvMusicActivity.renderNowPlaying() {
    val track = TvMusicPlayer.currentTrack
    val line = TvMusicNowPlayingSpec.nowPlayingLine(
        track = track,
        isPlaying = TvMusicPlayer.isPlaying,
        error = TvMusicPlayer.lastError,
    )
    nowText.text = line
    nowText.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
    // Обложка текущего трека в шапке: без трека — прячем и чистим bitmap;
    // относительный thumbnail резолвится от origin сайта (TvThumbnailUrlResolver),
    // нерезолвящийся URL загрузчик отрабатывает как GONE.
    if (track == null) {
        nowArt.tag = null
        nowArt.setImageDrawable(null)
        nowArt.visibility = View.GONE
    } else {
        TvImageLoader.load(track.thumbnail, nowArt)
    }
}

/** Highlights the transport's current row (accent text + icon), rest without a marker. */
internal fun TvMusicActivity.applyRowState(row: TvMusicActivity.TrackRow) {
    if (TvMusicNowPlayingSpec.isCurrent(row.trackId, TvMusicPlayer.currentTrack?.id)) {
        row.label.setTextColor(ContextCompat.getColor(this, R.color.mars_accent_text))
        row.marker.visibility = View.VISIBLE
    } else {
        row.label.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
        row.marker.visibility = View.GONE
    }
}

/**
 * Подпись строки трека. Собирает [TvAlbumPolicy.trackLabel]: альбом — часть подписи,
 * и правило «пустые сегменты не печатаются» живёт в одном месте с тестами, а не в
 * вёрстке (иначе трек без альбома получал бы висящий « · »).
 */
internal fun TvMusicActivity.trackLabel(track: MusicTrack): String = TvAlbumPolicy.trackLabel(track)
