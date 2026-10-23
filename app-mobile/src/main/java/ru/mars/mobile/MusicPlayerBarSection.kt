package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import ru.mars.mobile.service.MarsMusicService
import ru.mars.network.model.MusicTrack

/**
 * Нижняя панель плеера: сборка вида, отрисовка состояния и прогресса.
 *
 * Вынесено из [MusicActivity] (W1, шаг 15). `onStateChanged`/`onProgress` — методы
 * интерфейса [MarsMusicPlayer.MarsMusicListener], их нельзя сделать extension-функциями,
 * поэтому в хосте остались двухстрочные делегаты на [renderPlayerState] и
 * [renderProgress]; вся отрисовка и обращения к плееру — здесь.
 */

internal fun MusicActivity.buildPlayerBar(): View {
    val playerPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = MarsTheme.card(this@buildPlayerBar, MarsTheme.SURFACE_CARD, radiusDp = 18, strokeColor = MarsTheme.BORDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    val trackRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    artwork = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@buildPlayerBar, MarsTheme.SURFACE_SUBTLE, radiusDp = 8)
        clipToOutline = true
    }
    trackRow.addView(artwork, LinearLayout.LayoutParams(dp(52), dp(52)))

    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
    }
    title = TextView(this).apply {
        text = "Ничего не играет"
        textSize = 15f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    info.addView(title)

    artistView = TextView(this).apply {
        text = "Mars Audio"
        textSize = 13f
        setTextColor(MarsTheme.ACCENT_TEXT)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, 0)
        // Переход к исполнителю текущего трека: цвет акцента — единственная
        // подсказка кликабельности, поэтому он же и в палитре.
        isClickable = true
        isFocusable = true
        setOnClickListener { onArtistClicked(MarsMusicPlayer.currentTrack?.artist) }
    }
    info.addView(artistView)

    trackRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

    // Per-track actions: «Радио» (append similar tracks) and «Текст песни» (lyrics dialog).
    // Disabled until something is loaded in the player (see updatePlayerActionStates).
    radioBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_radio,
        "Радио по треку",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onRadioClicked() }
    }
    trackRow.addView(radioBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })

    lyricsBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_lyrics,
        "Текст песни",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onLyricsClicked() }
    }
    trackRow.addView(lyricsBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })

    playerPanel.addView(trackRow)

    // Progress Seek Bar
    seek = SeekBar(this).apply {
        max = 1
        progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) {
                if (!u) return
                // During the drag only the time label previews the position; the player
                // is seeked once on release — no MediaPlayer.seekTo spam mid-gesture.
                pendingSeekMs = p
                time.text = "${fmt(p)} / ${fmt(MarsMusicPlayer.duration)}"
            }
            override fun onStartTrackingTouch(b: SeekBar?) { isDraggingSeek = true }
            override fun onStopTrackingTouch(b: SeekBar?) {
                isDraggingSeek = false
                SeekDragPolicy.pendingSeekTarget(pendingSeekMs, MarsMusicPlayer.duration)
                    ?.let { MarsMusicPlayer.seekTo(it) }
                pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
            }
        })
    }
    playerPanel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val timeRow = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
    time = TextView(this).apply {
        text = "00:00 / 00:00"
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
    }
    timeRow.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
    playerPanel.addView(timeRow)

    // Controls (Shuffle, Previous, Play/Pause, Next, Repeat)
    val controls = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, 0)
    }

    shuffleBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_shuffle,
        MusicA11yPolicy.shuffleAction(shuffleOn = false),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 38
    ).apply {
        setOnClickListener { MarsMusicPlayer.toggleShuffle(); updateQueueIcons() }
    }
    controls.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })

    val prevBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_skip_prev,
        "Предыдущий трек",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT,
        sizeDp = 42
    ).apply {
        setOnClickListener { MarsMusicPlayer.previous() }
    }
    controls.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

    playButton = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_play,
        MusicA11yPolicy.playPauseAction(isPlaying = false),
        bg = MarsTheme.ACCENT,
        iconTint = MarsTheme.TEXT_STRONG,
        sizeDp = 50,
        strokeColor = 0
    ).apply {
        setOnClickListener { MarsMusicPlayer.togglePlay() }
    }
    val playParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
        marginStart = dp(10)
        marginEnd = dp(10)
    }
    controls.addView(playButton, playParams)

    val nextBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_skip_next,
        "Следующий трек",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT,
        sizeDp = 42
    ).apply {
        setOnClickListener { MarsMusicPlayer.next() }
    }
    controls.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

    repeatBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_repeat,
        MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 38
    ).apply {
        setOnClickListener { MarsMusicPlayer.cycleRepeatMode(); updateQueueIcons() }
    }
    controls.addView(repeatBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })

    playerPanel.addView(controls)

    return playerPanel
}

/**
 * Один кадр состояния плеера: подписи, глиф, иконки очереди и перекраска списка.
 * Вызывается из [MusicActivity.onStateChanged] — сам override остался в хосте.
 */
internal fun MusicActivity.renderPlayerState(track: MusicTrack?, isPlaying: Boolean) {
    runOnUiThread {
        syncMusicService(track, isPlaying)
        title.text = track?.title ?: "Ничего не играет"
        // Мини-плеер: альбом дописывается в ТУ ЖЕ строку, что и исполнитель —
        // второй строки у подписи нет (на 411dp после обложки и двух кнопок
        // остаётся ~229dp, третья строка выломала бы однострочную вёрстку блока).
        // Исполнитель стоит первым, поэтому ellipsize срезает хвост альбома, а не
        // имя; навигация к альбому остаётся в строке трека — тап по мини-плееру
        // по-прежнему ведёт к исполнителю (см. artistView.setOnClickListener).
        artistView.text = track
            ?.let { TrackSubtitlePolicy.subtitle(it.artist, it.albumTitle, null) }
            ?: "Mars Audio"
        setPlayPauseGlyph(playButton, isPlaying)
        // MUS-M1: the main button's description is the ACTION the tap performs —
        // «Пауза» while playing, «Воспроизвести» while paused — refreshed on every
        // transport state change from the shared pure policy.
        playButton.contentDescription = MusicA11yPolicy.playPauseAction(isPlaying)
        seek.max = MarsMusicPlayer.duration.coerceAtLeast(1)
        updateQueueIcons()
        updatePlayerActionStates()
        refreshTrackRows()
        // Keep the queue tab in sync with player state (active track, play/pause,
        // auto-advance): re-render it whenever the queue view is on screen.
        if (currentTab == MusicTab.QUEUE && isResultsReady()) renderQueue()
        recordPlayedTrack(track)
        track?.thumbnail?.let {
            artwork.visibility = View.VISIBLE
            ImageLoader.load(it, artwork)
        } ?: run {
            artwork.visibility = View.GONE
        }
    }
}

/**
 * Один кадр прогресса: максимум и позиция seek-бара плюс подпись времени.
 * Пока палец владеет тумбом ([isDraggingSeek]), позиция не трогается.
 * Вызывается из [MusicActivity.onProgress] — сам override остался в хосте.
 */
internal fun MusicActivity.renderProgress(currentMs: Int, totalMs: Int) {
    runOnUiThread {
        seek.max = totalMs.coerceAtLeast(1)
        if (isDraggingSeek) return@runOnUiThread // thumb belongs to the finger, not the player
        seek.progress = currentMs
        time.text = "${fmt(currentMs)} / ${fmt(totalMs)}"
    }
}

/**
 * Keeps the shade notification in step with playback: a track starting raises the
 * foreground service (the activity is visible here, so the Android 12+ background-FGS
 * ban does not apply); the service tears itself down when playback dies, so we only
 * reset the flag and let it own its lifecycle.
 */
internal fun MusicActivity.syncMusicService(track: MusicTrack?, isPlaying: Boolean) {
    if (track != null && !musicServiceStarted) {
        MarsMusicService.start(this)
        musicServiceStarted = true
    } else if (track == null && !isPlaying) {
        musicServiceStarted = false
    }
}

/** Radio/lyrics act on the current track only: dim and disable both without one. */
internal fun MusicActivity.updatePlayerActionStates() {
    if (!isPlayerActionBtnsReady()) return
    val hasTrack = MarsMusicPlayer.currentTrack != null
    val busy = radioJob?.isActive == true
    radioBtn.isEnabled = hasTrack && !busy
    radioBtn.alpha = if (hasTrack && !busy) 1f else 0.4f
    lyricsBtn.isEnabled = hasTrack
    lyricsBtn.alpha = if (hasTrack) 1f else 0.4f
}

internal fun MusicActivity.updateQueueIcons() {
    if (!isQueueIconBtnsReady()) return
    shuffleBtn.imageTintList = ColorStateList.valueOf(
        if (MarsMusicPlayer.isShuffled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
    )
    // MUS-M3: the toggles announce the action the tap performs next
    shuffleBtn.contentDescription = MusicA11yPolicy.shuffleAction(MarsMusicPlayer.isShuffled)
    val repeatOne = MarsMusicPlayer.repeatMode == MusicQueueManager.RepeatMode.ONE
    repeatBtn.setImageResource(if (repeatOne) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
    repeatBtn.imageTintList = ColorStateList.valueOf(
        if (MarsMusicPlayer.repeatMode == MusicQueueManager.RepeatMode.OFF) MarsTheme.TEXT_MUTED else MarsTheme.ACCENT
    )
    repeatBtn.contentDescription = MusicA11yPolicy.repeatAction(MarsMusicPlayer.repeatMode)
}
