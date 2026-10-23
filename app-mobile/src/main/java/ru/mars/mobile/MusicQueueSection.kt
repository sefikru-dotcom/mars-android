package ru.mars.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * «Очередь»: живой список очереди воспроизведения.
 *
 * Вынесено из [MusicActivity] (шаг 7 раскладки) как internal-расширения:
 * состояние остаётся в полях экрана, здесь только рендер. Строки перерисовываются
 * из `onStateChanged`, поэтому активная позиция читается напрямую у [MarsMusicPlayer].
 */

/**
 * Renders the live playback queue from [MarsMusicPlayer]: header with a clear action,
 * then one row per queued track. The active track gets an accent border and accent
 * title; tapping a row jumps to it, the trash button removes it from the queue.
 */
internal fun MusicActivity.renderQueue() {
    tracks = emptyList() // stale chart cards must not stay clickable under the queue
    resetList()

    val queue = MarsMusicPlayer.tracks
    if (queue.isEmpty()) {
        results.addView(TextView(this).apply {
            text = "Очередь воспроизведения пуста"
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        })
        return
    }

    // Header: queue size + clear action
    val headerRow = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), dp(10))
    }
    headerRow.addView(TextView(this).apply {
        text = "В очереди: ${queue.size}"
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val clearQueueBtn = TextView(this).apply {
        text = "Очистить очередь"
        textSize = 13f
        setTextColor(MarsTheme.ACCENT_TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = MarsTheme.button(this@renderQueue, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            MarsMusicPlayer.clearQueue()
            renderQueue()
        }
    }
    headerRow.addView(clearQueueBtn, LinearLayout.LayoutParams(-2, -2))
    results.addView(headerRow, LinearLayout.LayoutParams(-1, -2))

    val activeIndex = MarsMusicPlayer.index
    queue.forEachIndexed { i, t ->
        val active = i == activeIndex
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = MarsTheme.card(
                this@renderQueue,
                if (active) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
                radiusDp = 12,
                strokeColor = if (active) MarsTheme.ACCENT else MarsTheme.BORDER
            )
            setOnClickListener { MarsMusicPlayer.playQueueAt(i) }
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@renderQueue, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
            clipToOutline = true
        }
        val thumb = t.thumbnail
        if (!thumb.isNullOrBlank()) ImageLoader.load(thumb, image)
        row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

        val trackInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        trackInfo.addView(TextView(this).apply {
            text = t.title
            textSize = 15f
            setTextColor(if (active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        trackInfo.addView(TextView(this).apply {
            text = if (active && MarsMusicPlayer.isPlaying) "Сейчас играет · ${t.artist}" else "${t.artist} · ${t.duration}"
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))

        // Quick jump to this queue position
        val jumpBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            "Перейти к треку",
            bg = if (active) MarsTheme.ACCENT else MarsTheme.SURFACE_SUBTLE,
            iconTint = if (active) MarsTheme.TEXT_STRONG else MarsTheme.ACCENT,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.playQueueAt(i) }
        }
        row.addView(jumpBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        // Remove this track from the queue
        val removeBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_trash,
            "Убрать из очереди",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener {
                val removed = MarsMusicPlayer.removeFromQueue(i)
                if (removed != null) showStatus("Удалено из очереди: ${removed.title}")
                renderQueue()
            }
        }
        row.addView(removeBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
}
