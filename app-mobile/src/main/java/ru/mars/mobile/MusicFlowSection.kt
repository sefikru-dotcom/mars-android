package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.mobile.MusicActivity.MusicTab
import ru.mars.network.model.MusicTrack

/**
 * Вкладка «Волна»: персональный поток GET /api/flow, его заголовок-карточка и
 * «радио по треку» — добор похожих треков в очередь.
 *
 * Что именно добавить в очередь, решает чистая [RadioQueuePolicy]; здесь — загрузка,
 * рендер шапки и обновление состояния кнопки радио.
 */

/**
 * Loads the personal stream («Волна»): the server-picked Mars Flow track list with a
 * «Включить поток» primary action that starts playback of the whole stream.
 */
internal fun MusicActivity.loadFlow() {
    job?.cancel()
    showStatus(null)
    resetList()
    results.addView(TextView(this).apply {
        text = "Настраиваем волну…"
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(32), 0, dp(32))
    })
    job = lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { repository.getFlow() } }
        result.onSuccess { flow ->
            if (flow.isEmpty()) {
                tracks = emptyList()
                resetList()
                results.addView(TextView(this@loadFlow).apply {
                    text = "Волна пока пуста — сервер не подобрал треки"
                    setTextColor(MarsTheme.TEXT_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(32), 0, dp(32))
                })
            } else {
                showTracks(flow, header = flowHeader(flow))
            }
        }.onFailure {
            showListError(
                message = "Волна недоступна — проверьте сеть",
                retryDescription = "Повторить загрузку волны",
                onRetry = { loadFlow() },
            )
        }
    }
}

/** «Волна» section card: title, stream size and the accent «Включить поток» button. */
private fun MusicActivity.flowHeader(items: List<MusicTrack>): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = MarsTheme.card(this@flowHeader, MarsTheme.SURFACE, radiusDp = 14)
        setPadding(dp(14), dp(12), dp(14), dp(14))
    }
    card.addView(TextView(this).apply {
        text = "Волна"
        textSize = 17f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
    })
    card.addView(TextView(this).apply {
        text = "Персональный поток Марса · ${items.size} треков"
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(0, dp(2), 0, dp(12))
    })
    val playFlowBtn = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = MarsTheme.button(this@flowHeader, MarsTheme.ACCENT, 12)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = "Включить поток"
        setOnClickListener {
            if (tracks.isEmpty()) return@setOnClickListener
            MarsMusicPlayer.playTrack(0, tracks)
            showStatus("Волна включена")
        }
    }
    playFlowBtn.addView(ImageView(this).apply {
        setImageDrawable(ContextCompat.getDrawable(this@flowHeader, R.drawable.ic_play))
        imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
    playFlowBtn.addView(TextView(this).apply {
        text = "Включить поток"
        textSize = 14f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
    })
    card.addView(playFlowBtn, LinearLayout.LayoutParams(-1, -2))
    return card
}

/**
 * «Радио по треку»: fetches similar tracks for the current track and appends the
 * deduplicated additions (pure [RadioQueuePolicy]) to the queue — playback continues
 * seamlessly because enqueuing never restarts a track that is already playing.
 */
internal fun MusicActivity.onRadioClicked() {
    val track = MarsMusicPlayer.currentTrack ?: run { showStatus("Сначала включите трек"); return }
    if (radioJob?.isActive == true) return
    radioBtn.isEnabled = false
    radioBtn.alpha = 0.4f
    showStatus("Собираем радио по треку «${track.title}»…")
    radioJob = lifecycleScope.launch {
        val fetched = withContext(Dispatchers.IO) { runCatching { repository.getRadio(track.id) } }
        fetched.onSuccess { candidates ->
            val additions = RadioQueuePolicy.tracksToAdd(
                fetched = candidates,
                queuedIds = MarsMusicPlayer.tracks.map { it.id },
                currentTrackId = MarsMusicPlayer.currentTrack?.id,
            )
            additions.forEach { MarsMusicPlayer.enqueue(it) }
            showStatus(
                if (additions.isEmpty()) "Радио: новых похожих треков нет"
                else "Радио: +${additions.size} треков в очередь"
            )
            if (currentTab == MusicTab.QUEUE) renderQueue()
        }.onFailure {
            showStatus("Радио недоступно — проверьте сеть")
        }
        updatePlayerActionStates()
    }
}
