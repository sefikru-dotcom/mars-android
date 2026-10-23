package ru.mars.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ru.mars.network.model.MusicTrack

/**
 * Список треков: реестр строк, карточка трека и живая перекраска под состояние плеера.
 *
 * Вынесено из [MusicActivity] (W1, шаг 13) как extension-функции на Activity: проводка
 * (кнопки строки, тап/долгий тап) не менялась, решения по-прежнему принимают чистые
 * политики [TrackRowUiState], [TrackSubtitlePolicy] и [MusicA11yPolicy].
 * [RowAction] и [TrackRowHolder] переехали сюда же — они нужны только списку.
 */

/** Rendered chart/search row views per track id, so playback state can re-style them live. */
internal class TrackRowHolder(
    val trackId: String,
    val row: View,
    val titleView: TextView,
    /**
     * Подпись строки целиком («исполнитель · альбом · 3:24»): её раскладку и текст
     * держит [TrackSubtitleBinder] — экран не склеивает сегменты сам, поэтому
     * перекраска «Сейчас играет» не может разъехаться с разделителями.
     */
    val subtitle: TrackSubtitleBinder,
    val playButton: ImageButton,
)

/**
 * Optional extra per-row control (icon, TalkBack description, tap handler). Used by the
 * playlist drill-down for «убрать из плейлиста», which has no place on other lists.
 */
internal data class RowAction(
    val iconRes: Int,
    val description: String,
    val onTap: (MusicTrack) -> Unit,
)

/**
 * Renders a track list (chart/search/favorites/flow rows). [header] is an optional
 * section card placed above the rows — used by «Волна» for its stream action.
 * [action] adds one extra button to every row; a long press anywhere offers
 * «Добавить в плейлист» and opens [promptAddToPlaylist] for that track.
 */
internal fun MusicActivity.showTracks(items: List<MusicTrack>, header: View? = null, action: RowAction? = null) {
    tracks = items
    resetList()
    header?.let { results.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    if (items.isEmpty()) {
        results.addView(TextView(this).apply {
            text = "Треки не найдены"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
        return
    }

    items.forEachIndexed { i, t ->
        results.addView(trackRow(t, i, action), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
}

/**
 * Одна строка трека (карточка списка: обложка, play, избранное, очередь).
 * [index] — позиция трека в списке [tracks]: тап по строке запускает
 * воспроизведение очереди именно с неё. [action] добавляет одну дополнительную
 * кнопку (drill-down плейлиста). Долгий тап предлагает «Добавить в плейлист».
 * Строка регистрируется в [trackRows], чтобы состояние плеера перекрашивало её живьём.
 */
internal fun MusicActivity.trackRow(t: MusicTrack, index: Int, action: RowAction? = null): View {
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        background = MarsTheme.card(this@trackRow, MarsTheme.SURFACE, radiusDp = 12)
        setOnClickListener { onTrackRowClicked(index) }
        setOnLongClickListener {
            if (action == null) promptAddToPlaylist(t)
            true
        }
    }

    val image = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@trackRow, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
        clipToOutline = true
    }
    val thumb = t.thumbnail
    if (!thumb.isNullOrBlank()) {
        ImageLoader.load(thumb, image)
    }
    row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

    val trackInfo = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
    }
    val titleView = TextView(this).apply {
        text = t.title
        textSize = 15f
        setTextColor(MarsTheme.TEXT_STRONG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    trackInfo.addView(titleView)
    // Подпись строки разделена: имя исполнителя — отдельная кликабельная вьюха
    // (переход к исполнителю), длительность/статус — некликабельный хвост.
    // Иначе тап по нижней половине строки перестал бы включать трек.
    val subRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val artistLabel = TextView(this).apply {
        text = t.artist
        textSize = 12f
        setTextColor(MarsTheme.ACCENT_TEXT)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        setOnClickListener { onArtistClicked(t.artist) }
    }
    subRow.addView(artistLabel, LinearLayout.LayoutParams(0, -2, 1f))
    // Альбом — отдельный кликабельный сегмент подписи. Строка трека знает свой
    // альбом (см. MusicTrack.albumBrowseId): тап открывает тот же альбомный диалог,
    // что и карточка альбома на вкладке «Обзор». Если у трека нет browseId, имя
    // остаётся просто подписью без перехода.
    //
    // Две вьюхи на один альбом — не дублирование, а два места одной политики:
    // в узкой строке (POCO F5 даёт под подпись ~141dp) имя альбома не влезает
    // вместе с длительностью, и [TrackSubtitlePolicy] уводит его на отдельную
    // строку вместо того, чтобы выдавить длительность за край. Видимой остаётся
    // ровно одна из них.
    val albumName = TrackSubtitlePolicy.albumLine(t.albumTitle)
    val albumBrowseId = t.albumBrowseId
    val albumInline = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    val albumSecondLine = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(1), 0, 0)
        visibility = View.GONE
    }
    if (albumName != null) {
        // Цвет акцента и кликабельность — только когда альбом реально открывается:
        // подсказка «сюда можно нажать» не должна врать.
        val openable = !albumBrowseId.isNullOrBlank()
        listOf(albumInline, albumSecondLine).forEach { label ->
            if (openable) {
                label.setTextColor(MarsTheme.ACCENT_TEXT)
                label.isClickable = true
                label.isFocusable = true
                label.setOnClickListener { onAlbumClicked(albumBrowseId) }
            }
        }
        subRow.addView(albumInline, LinearLayout.LayoutParams(-2, -2))
    }
    val metaView = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
    }
    subRow.addView(metaView, LinearLayout.LayoutParams(-2, -2))
    trackInfo.addView(subRow)
    if (albumName != null) {
        trackInfo.addView(albumSecondLine, LinearLayout.LayoutParams(-1, -2))
    }
    // Раскладка подписи зависит от фактической ширины блока текста, а она известна
    // только после первой меры: LinearLayout отдаёт weight-вьюхе остаток уже после
    // того, как отнял обложку и кнопки. Слушатель срабатывает один раз на ширину.
    val subtitle = TrackSubtitleBinder(
        artist = t.artist,
        album = albumName,
        duration = t.duration,
        density = resources.displayMetrics.density,
        artistLabel = artistLabel,
        albumInline = albumInline,
        albumSecondLine = albumSecondLine,
        metaView = metaView,
    )
    trackInfo.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        subtitle.onAvailableWidth(view.width - view.paddingLeft - view.paddingRight)
    }
    row.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))

    val playTrackBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_play,
        MusicA11yPolicy.ACTION_PLAY,
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.ACCENT,
        sizeDp = 34
    ).apply {
        setOnClickListener { onTrackRowClicked(index) }
    }
    row.addView(playTrackBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

    // Favorite heart: filled+accent when the track is already in favorites
    val isFav = favoriteIds.contains(t.id)
    val heartBtn = MarsTheme.circleIconButton(
        this,
        if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        MusicA11yPolicy.heartAction(isFav),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = if (isFav) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onHeartClicked(t, this) }
    }
    heartButtons.getOrPut(t.id) { mutableListOf() }.add(heartBtn)
    row.addView(heartBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

    // Quick add to the playback queue
    val enqueueBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_playlist_add,
        "В очередь",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onQueueAddClicked(t) }
    }
    row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

    // Playlist drill-down only: «убрать из плейлиста» next to the usual actions.
    action?.let { extra ->
        val extraBtn = MarsTheme.circleIconButton(
            this,
            extra.iconRes,
            extra.description,
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { extra.onTap(t) }
        }
        row.addView(extraBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
    }

    // Register the row so player state changes can highlight the current track live.
    val holder = TrackRowHolder(t.id, row, titleView, subtitle, playTrackBtn)
    trackRows.getOrPut(t.id) { mutableListOf() }.add(holder)
    applyTrackRowState(holder)
    return row
}

/** Applies the pure [TrackRowUiState] to a rendered row: accent card, title, play/pause icon. */
internal fun MusicActivity.applyTrackRowState(holder: TrackRowHolder) {
    val state = TrackRowUiState.resolve(holder.trackId, MarsMusicPlayer.currentTrack?.id, MarsMusicPlayer.isPlaying)
    holder.row.background = MarsTheme.card(
        this,
        if (state.active) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
        radiusDp = 12,
        strokeColor = if (state.active) MarsTheme.ACCENT else MarsTheme.BORDER,
    )
    holder.titleView.setTextColor(if (state.active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
    holder.titleView.typeface = if (state.active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    holder.subtitle.render(active = state.active, playing = MarsMusicPlayer.isPlaying)
    setPlayPauseGlyph(holder.playButton, state.showPauseIcon)
    holder.playButton.contentDescription = MusicA11yPolicy.playPauseAction(state.showPauseIcon)
}

/** Re-styles every rendered chart/search row from the current player state (UI thread). */
internal fun MusicActivity.refreshTrackRows() {
    trackRows.values.forEach { holders -> holders.forEach { applyTrackRowState(it) } }
}
