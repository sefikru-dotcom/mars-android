package ru.mars.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Каркас экрана «Музыка»: корень с insets, шапка, ряд вкладок, статусная строка и
 * сборка порядка вставки (список под оверлеем истории, плеер снизу).
 *
 * Вынесено из [MusicActivity] (W1, шаг 16) как extension-функции на Activity. Порядок
 * вставки и z-order не менялись: оверлей истории лежит в [FrameLayout] над списком,
 * панель плеера добавляется последней. [showStatus] переехал сюда же (цикл C4 плана):
 * он владеет статусной строкой, поэтому список треков и библиотека зовут его из Shell,
 * а не друг у друга.
 */

internal fun MusicActivity.buildView(): View {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MarsTheme.SCREEN_VEIL)
        setPadding(dp(16), dp(16), dp(16), dp(12))
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
        insets
    }

    // Header
    root.addView(buildHeader())

    // Tabs: Чарт / Волна / Избранное / Очередь / Плейлисты / История (pill toggles).
    // Horizontally scrollable: six weighted pills clip «Избранное» on narrow screens,
    // chips must stay whole.
    val tabRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    chartTabButton = tabButton("Чарт") { selectTab(MusicTab.CHART) }
    flowTabButton = tabButton("Волна") { selectTab(MusicTab.FLOW) }
    forYouTabButton = tabButton(getString(R.string.music_tab_for_you)) { selectTab(MusicTab.FOR_YOU) }
    favoritesTabButton = tabButton("Избранное") { selectTab(MusicTab.FAVORITES) }
    playlistsTabButton = tabButton("Плейлисты") { selectTab(MusicTab.PLAYLISTS) }
    historyTabButton = tabButton("История") { selectTab(MusicTab.HISTORY) }
    queueTabButton = tabButton("Очередь") { selectTab(MusicTab.QUEUE) }
    tabRow.addView(chartTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    tabRow.addView(flowTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    // «Для тебя» — персональная лента /api/home: в общем ряду вкладок, на узком
    // экране ряд листается горизонтально (перенос ломал бы быструю навигацию).
    tabRow.addView(forYouTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    tabRow.addView(favoritesTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    tabRow.addView(playlistsTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    tabRow.addView(historyTabButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    tabRow.addView(queueTabButton, LinearLayout.LayoutParams(-2, -2))
    val tabScroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabRow, ViewGroup.LayoutParams(-2, -2))
    }
    root.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

    // Transient status line (favorites/queue feedback), hidden by default.
    // POLITE live region (MUS-M2): TalkBack announces the feedback on its own,
    // without moving focus to the line — parity with SectionStateViews and the
    // TV section status views.
    statusText = TextView(this).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(dp(4), 0, dp(4), dp(8))
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

    // Search Bar (Floating Capsule)
    root.addView(buildSearchBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    // Track list; the search-history overlay floats above it (FrameLayout),
    // so showing history never displaces the tab content or the player.
    results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val listScroll = ScrollView(this).apply {
        addView(results, ViewGroup.LayoutParams(-1, -2))
        isFillViewport = true
    }
    historyPanel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = MarsTheme.card(this@buildView, MarsTheme.SURFACE_CARD, radiusDp = 14, strokeColor = MarsTheme.BORDER)
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }
    historyOverlay = ScrollView(this).apply {
        addView(historyPanel, ViewGroup.LayoutParams(-1, -2))
        visibility = View.GONE
        // Swallow taps so they don't fall through to the list underneath.
        isClickable = true
        isFocusable = true
    }
    val listArea = FrameLayout(this).apply {
        addView(listScroll, FrameLayout.LayoutParams(-1, -1))
        addView(historyOverlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }
    root.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))

    // Bottom Player Bar
    root.addView(buildPlayerBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    return root
}

/**
 * Шапка экрана: «назад», заголовок, «Звук» и «Качество потока».
 */
internal fun MusicActivity.buildHeader(): View {
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(14))
    }
    val backBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_ui_arrow_back,
        "Назад",
        bg = MarsTheme.SURFACE,
        iconTint = MarsTheme.TEXT
    ).apply {
        setOnClickListener { finish() }
    }
    header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

    val titleHeader = TextView(this).apply {
        text = "Музыка Марса"
        textSize = 22f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.05f
    }
    header.addView(titleHeader, LinearLayout.LayoutParams(0, -2, 1f))

    eqBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_volume,
        "Эквалайзер и звук",
        bg = MarsTheme.SURFACE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onEqClicked() }
    }
    header.addView(eqBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
    qualityBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_settings,
        "Качество потока",
        bg = MarsTheme.SURFACE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34
    ).apply {
        setOnClickListener { onQualityClicked() }
    }
    header.addView(qualityBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
        marginStart = dp(8)
    })

    return header
}

internal fun MusicActivity.tabButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 13f
    setTextColor(MarsTheme.TEXT_MUTED)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    isSingleLine = true
    setPadding(dp(14), dp(9), dp(14), dp(9))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

internal fun MusicActivity.updateTabStyles() {
    fun style(button: TextView, active: Boolean) {
        button.setTextColor(if (active) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
        button.background = if (active) {
            MarsTheme.pill(this, MarsTheme.ACCENT_BG, MarsTheme.ACCENT)
        } else {
            MarsTheme.pill(this, MarsTheme.SURFACE)
        }
        // MUS-M3: TalkBack reads which tab is currently open
        button.contentDescription = MusicA11yPolicy.tabDescription(button.text.toString(), active)
    }
    style(chartTabButton, currentTab == MusicTab.CHART)
    style(flowTabButton, currentTab == MusicTab.FLOW)
    style(forYouTabButton, currentTab == MusicTab.FOR_YOU)
    style(favoritesTabButton, currentTab == MusicTab.FAVORITES)
    style(playlistsTabButton, currentTab == MusicTab.PLAYLISTS)
    style(historyTabButton, currentTab == MusicTab.HISTORY)
    style(queueTabButton, currentTab == MusicTab.QUEUE)
}

/** Статусная строка экрана (live region POLITE, см. [buildView]). */
internal fun MusicActivity.showStatus(message: String?) {
    if (!isStatusTextReady()) return
    statusText.text = message.orEmpty()
    statusText.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
}
