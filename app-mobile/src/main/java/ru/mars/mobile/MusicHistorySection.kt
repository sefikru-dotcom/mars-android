package ru.mars.mobile

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.HistoryEntry
import ru.mars.network.model.MusicTrack

/**
 * «История»: серверная история прослушивания и её запись.
 *
 * Вынесено из [MusicActivity] (шаг 8 раскладки) как internal-расширения:
 * каркас загрузки/ошибки по-прежнему рисует `renderLibrary`, а запись события
 * гейтит чистая [HistoryRecordPolicy].
 */

/** «История»: server-side listening history, newest first, each row playable. */
internal fun MusicActivity.loadHistory() {
    libraryJob?.cancel()
    openPlaylist = null
    renderLibrary(LibraryUiState.Loading, { loadHistory() }) {}
    libraryJob = lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getHistory() } }
        fetched.onSuccess { entries ->
            historyEntries = entries
            renderLibrary(MusicLibraryPolicy.state(false, null, entries.size), { loadHistory() }) {
                if (entries.isEmpty()) {
                    results.addView(libraryPlaceholder("Вы ещё ничего не слушали"))
                    return@renderLibrary
                }
                headerCard(title = "Прослушано · ${MusicLibraryPolicy.trackCountLabel(entries.size)}")
                    ?.let { results.addView(it) }
                val now = System.currentTimeMillis()
                entries.forEach { entry -> results.addView(historyCard(entry, now)) }
            }
        }.onFailure {
            renderLibrary(LibraryUiState.Error("История недоступна — проверьте сеть"), { loadHistory() }) {}
        }
    }
}

/**
 * One history row: cover, title, «исполнитель · длительность», the human played-at
 * label and the same transport/favorite/queue actions as any track row. Tapping it
 * plays that very track (the payload's `id` is the video id).
 */
internal fun MusicActivity.historyCard(entry: HistoryEntry, nowMillis: Long): View {
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        background = MarsTheme.card(this@historyCard, MarsTheme.SURFACE, radiusDp = 12)
        isClickable = true
        isFocusable = true
        setOnClickListener { playFromHistory(entry) }
    }
    val image = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@historyCard, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
        clipToOutline = true
    }
    entry.thumbnail?.takeIf { it.isNotBlank() }?.let { ImageLoader.load(it, image) }
    row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

    val info = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
    }
    info.addView(TextView(this).apply {
        text = entry.title
        textSize = 15f
        setTextColor(MarsTheme.TEXT_STRONG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    info.addView(TextView(this).apply {
        text = "${entry.artist} · ${entry.duration}"
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    info.addView(TextView(this).apply {
        text = MusicLibraryPolicy.playedAtLabel(entry.playedAt, nowMillis)
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

    val playBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_play,
        MusicA11yPolicy.ACTION_PLAY,
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.ACCENT,
        sizeDp = 34
    ).apply {
        setOnClickListener { playFromHistory(entry) }
    }
    row.addView(playBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

    val enqueueBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_playlist_add,
        "В очередь",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener {
            MarsMusicPlayer.enqueue(entry.toTrack())
            showStatus("Добавлено в очередь: ${entry.title}")
        }
    }
    row.addView(enqueueBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

    return row.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
}

/**
 * Plays a history entry: the player needs a queue, so the whole history becomes the
 * queue and playback starts at the tapped row (same contract as a chart row).
 */
internal fun MusicActivity.playFromHistory(entry: HistoryEntry) {
    val queue = historyEntries.map { it.toTrack() }.ifEmpty { listOf(entry.toTrack()) }
    val index = queue.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } ?: 0
    MarsMusicPlayer.playTrack(index, queue)
}

/**
 * Records the track now playing on the server (POST /api/history/record), so the
 * «История» tab and every other Mars client see the same list. Only a genuinely new
 * track is recorded — a pause/resume or a re-broadcast of the same id must not spam
 * the history ([HistoryRecordPolicy] decides on the pure id comparison).
 */
internal fun MusicActivity.recordPlayedTrack(track: MusicTrack?) {
    if (track == null) return
    if (!HistoryRecordPolicy.shouldRecord(track.id, lastRecordedTrackId)) return
    lastRecordedTrackId = track.id
    lifecycleScope.launch {
        withContext(Dispatchers.IO) { runCatching { repository.recordHistory(track) } }
        // A failed write stays silent: playback is what matters, the tab re-fetches.
    }
}
