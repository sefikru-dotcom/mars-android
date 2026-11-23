package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Длительность выезда раскрытого плеера (сдвиг по вертикали). */
private const val FULL_PLAYER_ANIM_MS = 220L

/**
 * Прямоугольник [view] в координатах предка [ancestor] (сумма смещений по цепочке родителей).
 * Пустой прямоугольник — если [view] не лежит внутри предка: такой контрол не блокирует жест.
 */
internal fun viewZoneIn(ancestor: View, view: View): MusicGesturePolicy.Zone {
    var left = 0
    var top = 0
    var current: View = view
    while (current !== ancestor) {
        left += current.left
        top += current.top
        current = current.parent as? View ?: return MusicGesturePolicy.Zone(0, 0, 0, 0)
    }
    return MusicGesturePolicy.Zone(left, top, left + view.width, top + view.height)
}

/**
 * Контейнер жестов раскрытого плеера (оверлей).
 *
 * Касание, начавшееся на контроле ([controls]: перемотка, кнопки, сердечко), принадлежит
 * контролу и никогда не становится свайпом. Остальное перехватывается, как только палец
 * ушёл дальше touch slop; на UP направление и порог решает [MusicGesturePolicy].
 *
 * Движение учитывается и в [onInterceptTouchEvent], и в [onTouchEvent]: если никакой
 * ребёнок не забрал DOWN, MOVE приходит сразу в onTouchEvent контейнера, и без проверки
 * там свайп по пустому месту не распознавался бы.
 */
internal class MusicGestureLayout(
    context: Context,
    private val controls: () -> List<View>,
    private val onSwipe: (MusicGesturePolicy.Swipe) -> Unit,
) : LinearLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startedOnControl = false
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // UP/CANCEL здесь не сбрасывают swiping: onTouchEvent получает тот же UP и должен
        // знать, был ли это свайп. Сброс делает onTouchEvent.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> followMove(ev)
        }
        return swiping
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Оверлей потребляет все касания: тап мимо контролов не должен проваливаться в список.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> followMove(ev)
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    val swipe = MusicGesturePolicy.classify(
                        (ev.x - downX).roundToInt(),
                        (ev.y - downY).roundToInt(),
                        MusicGesturePolicy.thresholdPx(resources.displayMetrics.density),
                    )
                    if (swipe != MusicGesturePolicy.Swipe.NONE) onSwipe(swipe)
                }
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        swiping = false
        startedOnControl = controls().any { control ->
            val zone = viewZoneIn(this, control)
            !zone.isEmpty && zone.contains(ev.x.toInt(), ev.y.toInt())
        }
    }

    private fun followMove(ev: MotionEvent) {
        if (!swiping && !startedOnControl &&
            (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
        ) {
            swiping = true
        }
    }
}

/** Ссылки на вьюхи раскрытого плеера и его локальное состояние. */
internal class FullPlayerHolder(
    val root: MusicGestureLayout,
    val artwork: ImageView,
    val title: TextView,
    val artist: TextView,
    val heart: ImageButton,
    val seek: SeekBar,
    val time: TextView,
    val shuffle: ImageButton,
    val play: ImageButton,
    val loop: ImageButton,
    val backCallback: OnBackPressedCallback,
) {
    /** True while the overlay is shown (or sliding in). */
    var open = false
    /** True while the finger owns the seek thumb; the ticker must not move it. */
    var isDragging = false
    /** Last dragged-to position; applied to the player once, on release. */
    var pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
    /** URL of the artwork currently shown: a reload happens only on a real change. */
    var artworkUrl: String? = null
    /** Track id whose heart is registered in [MusicActivity.heartButtons]. */
    var heartTrackId: String? = null
    /**
     * Сердечко погашено ИМЕННО потому, что трека нет. Без отдельного флага возврат
     * «включить обратно» не отличить от запроса в полёте (его debounce ведёт
     * [MusicActivity.onHeartClicked]), и сердце оставалось бы выключенным навсегда,
     * если плеер открыли пустым, а трек появился позже.
     */
    var heartDisabledForNoTrack = false
}

/**
 * Раскрытый плеер: оверлей поверх контента той же Activity (не новая Activity — иначе
 * воспроизведение прервалось бы). Создаётся лениво при первом открытии.
 *
 * Обложка — тап открывает текст песни ([onLyricsClicked]); сердечко — существующий
 * [onHeartClicked] и тот же набор [MusicActivity.favoriteIds]; свайпы — через
 * [MusicGesturePolicy.fullPlayerSwipe].
 */
internal fun MusicActivity.ensureFullPlayer(): FullPlayerHolder {
    fullPlayer?.let { return it }
    val controls = mutableListOf<View>()

    val root = MusicGestureLayout(this, { controls }, { swipe -> onFullPlayerSwipe(swipe) }).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MarsTheme.BG)
        isClickable = true
        setPadding(dp(20), dp(12), dp(20), dp(16))
        visibility = View.GONE
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(dp(20), bars.top + dp(8), dp(20), bars.bottom + dp(12))
        insets
    }

    val close = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_chevron_down,
        MusicFullPlayerPolicy.ACTION_CLOSE,
        bg = MarsTheme.SURFACE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34,
    )
    close.setOnClickListener { closeFullPlayer() }
    root.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    controls.add(close)

    val side = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(340))
    val artworkView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = MarsTheme.card(this@ensureFullPlayer, MarsTheme.SURFACE_SUBTLE, radiusDp = 16)
        clipToOutline = true
        contentDescription = MusicFullPlayerPolicy.ACTION_LYRICS
        // Обложка — не контрол: свайп, начатый на ней, переключает трек.
        setOnClickListener { onLyricsClicked() }
    }
    root.addView(artworkView, LinearLayout.LayoutParams(side, side).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(20)
    })

    val titleView = TextView(this).apply {
        textSize = 20f
        setTextColor(MarsTheme.TEXT_STRONG)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val artistView = TextView(this).apply {
        textSize = 14f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, 0)
    }
    val heart = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_favorite_border,
        MusicA11yPolicy.heartAction(false),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 34,
    )
    heart.setOnClickListener { onFullHeartClicked(heart) }
    controls.add(heart)

    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    info.addView(titleView)
    info.addView(artistView)
    val infoRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    infoRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
    infoRow.addView(heart, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) })
    root.addView(infoRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

    val seekBar = SeekBar(this).apply {
        max = 1
        progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
    }
    root.addView(seekBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    controls.add(seekBar)

    val timeView = TextView(this).apply {
        text = "00:00 / 00:00"
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
    }
    root.addView(timeView, LinearLayout.LayoutParams(-1, -2))

    val shuffle = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_shuffle,
        MusicA11yPolicy.shuffleAction(shuffleOn = false),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 38,
    )
    val prev = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_skip_prev,
        "Предыдущий трек",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT,
        sizeDp = 42,
    )
    val play = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_play,
        MusicA11yPolicy.playPauseAction(isPlaying = false),
        bg = MarsTheme.ACCENT,
        iconTint = MarsTheme.TEXT_STRONG,
        sizeDp = 50,
        strokeColor = 0,
    )
    val next = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_skip_next,
        "Следующий трек",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT,
        sizeDp = 42,
    )
    val loop = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_repeat,
        MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF),
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 38,
    )
    val transport = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, 0)
    }
    transport.addView(shuffle, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
    transport.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
    transport.addView(play, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
        marginStart = dp(10)
        marginEnd = dp(10)
    })
    transport.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
    transport.addView(loop, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
    root.addView(transport)
    controls.addAll(listOf(shuffle, prev, play, next, loop))

    val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeFullPlayer()
        }
    }
    onBackPressedDispatcher.addCallback(this, back)

    val holder = FullPlayerHolder(
        root = root,
        artwork = artworkView,
        title = titleView,
        artist = artistView,
        heart = heart,
        seek = seekBar,
        time = timeView,
        shuffle = shuffle,
        play = play,
        loop = loop,
        backCallback = back,
    )

    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            // Во время перетаскивания двигается только подпись времени; плеер — один раз, на отпускании.
            holder.pendingSeekMs = p
            timeView.text = "${fmt(p)} / ${fmt(MarsMusicPlayer.duration)}"
        }
        override fun onStartTrackingTouch(b: SeekBar?) {
            holder.isDragging = true
        }
        override fun onStopTrackingTouch(b: SeekBar?) {
            holder.isDragging = false
            SeekDragPolicy.pendingSeekTarget(holder.pendingSeekMs, MarsMusicPlayer.duration)
                ?.let { MarsMusicPlayer.seekTo(it) }
            holder.pendingSeekMs = SeekDragPolicy.NO_PENDING_SEEK
        }
    })
    shuffle.setOnClickListener {
        MarsMusicPlayer.toggleShuffle()
        updateQueueIcons()
    }
    prev.setOnClickListener { MarsMusicPlayer.previous() }
    play.setOnClickListener { MarsMusicPlayer.togglePlay() }
    next.setOnClickListener { MarsMusicPlayer.next() }
    loop.setOnClickListener {
        MarsMusicPlayer.cycleRepeatMode()
        updateQueueIcons()
    }
    fullPlayer = holder
    addContentView(root, ViewGroup.LayoutParams(-1, -1))
    return holder
}

/** Показывает раскрытый плеер: выезд снизу, без анимации при reduce motion. */
internal fun MusicActivity.openFullPlayer() {
    val h = ensureFullPlayer()
    if (h.open) return
    h.open = true
    h.backCallback.isEnabled = true
    updateFullPlayer()
    val root = h.root
    root.visibility = View.VISIBLE
    if (isReducedMotion()) {
        root.translationY = 0f
        return
    }
    root.translationY = resources.displayMetrics.heightPixels.toFloat()
    root.animate().translationY(0f).setDuration(FULL_PLAYER_ANIM_MS).start()
}

/**
 * Сворачивает раскрытый плеер. Возвращает false, если он уже закрыт.
 */
internal fun MusicActivity.closeFullPlayer(): Boolean {
    val h = fullPlayer ?: return false
    if (!h.open) return false
    h.open = false
    h.backCallback.isEnabled = false
    val root = h.root
    // Если за время выезда плеер открыли снова, скрывать его нельзя.
    fun hideIfStillClosed() {
        if (!h.open) {
            root.visibility = View.GONE
            root.translationY = 0f
        }
    }
    if (isReducedMotion()) {
        hideIfStillClosed()
        return true
    }
    root.animate()
        .translationY(resources.displayMetrics.heightPixels.toFloat())
        .setDuration(FULL_PLAYER_ANIM_MS)
        .withEndAction { hideIfStillClosed() }
        .start()
    return true
}

/**
 * Один кадр раскрытого плеера: заголовок, артист, обложка, сердечко, глиф play/pause и
 * shuffle/repeat. Вызывается из [renderPlayerState] на каждый стейт плеера — поэтому
 * автопереход на следующий трек обновляет всё, а не только при открытии.
 */
internal fun MusicActivity.updateFullPlayer() {
    val h = fullPlayer ?: return
    val track = MarsMusicPlayer.currentTrack
    h.title.text = MusicFullPlayerPolicy.title(track)
    h.artist.text = MusicFullPlayerPolicy.artist(track)
    h.artwork.contentDescription = MusicFullPlayerPolicy.artworkAction(track)

    val url = MusicFullPlayerPolicy.artworkUrl(track)
    if (MusicFullPlayerPolicy.shouldReloadArtwork(h.artworkUrl, url)) {
        h.artworkUrl = url
        if (url != null) ImageLoader.load(url, h.artwork) else h.artwork.setImageDrawable(null)
    }

    // Сердечко регистрируется в общем реестре heartButtons: обновление после
    // refreshHeart/onHeartClicked идёт по тому же пути, что и у строк списка.
    if (h.heartTrackId != track?.id) {
        h.heartTrackId?.let { old -> heartButtons[old]?.remove(h.heart) }
        h.heartTrackId = track?.id
    }
    track?.id?.let { id ->
        val list = heartButtons.getOrPut(id) { mutableListOf() }
        if (!list.contains(h.heart)) list.add(h.heart)
    }
    val state = MusicFullPlayerPolicy.heart(track?.id, favoriteIds, busy = false)
    h.heart.setImageResource(if (state.filled) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
    h.heart.imageTintList = ColorStateList.valueOf(if (state.filled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED)
    h.heart.contentDescription = state.description
    h.heart.isSelected = state.selected
    // Enabled НЕ берётся из политики напрямую: пока запрос в полёте, кнопку гасит и
    // возвращает [MusicActivity.onHeartClicked], и кадр состояния не должен включать её
    // обратно (двойной тап = два лайка). Здесь гасим только пустой плеер и возвращаем
    // кнопку, когда трек появился.
    if (!state.enabled) {
        h.heart.isEnabled = false
        h.heartDisabledForNoTrack = true
    } else if (h.heartDisabledForNoTrack) {
        h.heart.isEnabled = true
        h.heartDisabledForNoTrack = false
    }

    setPlayPauseGlyph(h.play, MarsMusicPlayer.isPlaying)
    h.play.contentDescription = MusicA11yPolicy.playPauseAction(MarsMusicPlayer.isPlaying)
    renderFullTransport(h)
    updateFullPlayerProgress(MarsMusicPlayer.currentPosition, MarsMusicPlayer.duration)
}

/** Shuffle/repeat раскрытого плеера — то же состояние очереди, что и в нижней полосе. */
internal fun MusicActivity.renderFullTransport(h: FullPlayerHolder) {
    val shuffled = MarsMusicPlayer.isShuffled
    h.shuffle.imageTintList = ColorStateList.valueOf(if (shuffled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED)
    h.shuffle.contentDescription = MusicA11yPolicy.shuffleAction(shuffled)
    val mode = MarsMusicPlayer.repeatMode
    h.loop.setImageResource(
        if (mode == MusicQueueManager.RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
    )
    h.loop.imageTintList = ColorStateList.valueOf(
        if (mode == MusicQueueManager.RepeatMode.OFF) MarsTheme.TEXT_MUTED else MarsTheme.ACCENT
    )
    h.loop.contentDescription = MusicA11yPolicy.repeatAction(mode)
}

/**
 * Прогресс раскрытого плеера. Вызывается из тикера экрана (раз в 500 мс) и из
 * [renderProgress]; пока палец владеет тумбом, позиция не трогается ([SeekDragPolicy]).
 */
internal fun MusicActivity.updateFullPlayerProgress(currentMs: Int, totalMs: Int) {
    val h = fullPlayer ?: return
    h.seek.max = totalMs.coerceAtLeast(1)
    // `selected` досинхронизируется на каждом кадре тикера: лайк меняет favoriteIds в
    // чужом файле ([MusicActivity.onHeartClicked]) и не перерисовывает раскрытый плеер,
    // а setSelected шлёт accessibility-событие только при РЕАЛЬНОЙ смене значения —
    // на неизменном состоянии это no-op.
    h.heart.isSelected = MarsMusicPlayer.currentTrack?.id?.let { favoriteIds.contains(it) } ?: false
    if (!SeekDragPolicy.tickerMayUpdate(h.isDragging)) return
    h.seek.progress = currentMs
    h.time.text = "${fmt(currentMs)} / ${fmt(totalMs)}"
}

/** Сердечко раскрытого плеера: тот же запрос и та же логика, что у строк списка. */
internal fun MusicActivity.onFullHeartClicked(button: ImageButton) {
    val track = MarsMusicPlayer.currentTrack ?: return
    onHeartClicked(track, button)
}

/** Свайп по раскрытому плееру: вниз — закрыть, влево/вправо — трек. */
private fun MusicActivity.onFullPlayerSwipe(swipe: MusicGesturePolicy.Swipe) {
    when (MusicGesturePolicy.fullPlayerSwipe(swipe)) {
        MusicGesturePolicy.Direction.CLOSE -> closeFullPlayer()
        MusicGesturePolicy.Direction.NEXT -> MarsMusicPlayer.next()
        MusicGesturePolicy.Direction.PREVIOUS -> MarsMusicPlayer.previous()
        else -> Unit
    }
}

/**
 * Контейнер нижней полосы, распознающий жест открытия раскрытого плеера.
 *
 * Почему не OnTouchListener на панели: касание, начавшееся на кликабельном ребёнке
 * (обложка, название, кнопка, перемотка), до слушателя панели не доходит вообще — жест
 * на обложке не открывал бы плеер. Поэтому MOVE перехватывается на уровне контейнера
 * ([onInterceptTouchEvent]) и только если жест НЕ начался на контроле: тогда ребёнок
 * получает CANCEL, а свайп остаётся за полосой. Тап без движения перехвату не подлежит —
 * он уходит ребёнку (обложка/название открывают плеер своим обработчиком).
 */
internal class MusicBarGestureLayout(context: Context) : LinearLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var controls: () -> List<View> = { emptyList() }
    private var onOpen: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f
    private var onControl = false

    /** Зоны контролов и действие открытия передаются после сборки полосы. */
    fun configure(controls: () -> List<View>, onOpen: () -> Unit) {
        this.controls = controls
        this.onOpen = onOpen
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                onControl = MusicGesturePolicy.startsOnControl(
                    ev.x.toInt(),
                    ev.y.toInt(),
                    controls().map { viewZoneIn(this, it) },
                )
            }
            MotionEvent.ACTION_MOVE ->
                if (!onControl && moved(ev)) return true
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> onControl = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Сюда попадает только жест, не забранный ребёнком: пустое место полосы.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                onControl = false
            }
            MotionEvent.ACTION_UP -> {
                val dx = (ev.x - downX).roundToInt()
                val dy = (ev.y - downY).roundToInt()
                val isTap = abs(dx) <= touchSlop && abs(dy) <= touchSlop
                if (isTap || MusicGesturePolicy.barSwipe(classify(dx, dy)) == MusicGesturePolicy.Direction.OPEN) {
                    onOpen()
                }
            }
        }
        return true
    }

    private fun moved(ev: MotionEvent): Boolean =
        abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop

    private fun classify(dx: Int, dy: Int): MusicGesturePolicy.Swipe =
        MusicGesturePolicy.classify(dx, dy, MusicGesturePolicy.thresholdPx(resources.displayMetrics.density))
}

/**
 * Подключает жест полосы: свайп вверх и тап по пустому месту открывают раскрытый плеер.
 * Касание, начавшееся на контроле ([controls]), не становится жестом — перемотка и
 * кнопки остаются за собой. Тап по обложке/названию обрабатывается ими самими: так у
 * TalkBack есть отдельный узел с подписью действия.
 */
internal fun MusicActivity.attachBarGesture(panel: MusicBarGestureLayout, controls: List<View>) {
    panel.configure({ controls }) { openFullPlayer() }
    // Обложка — только действие (у неё нет своего текста); у названия подпись
    // обновляется вместе с треком в renderPlayerState.
    artwork.isClickable = true
    artwork.isFocusable = true
    artwork.contentDescription = MusicFullPlayerPolicy.ACTION_OPEN
    artwork.setOnClickListener { openFullPlayer() }
    title.isClickable = true
    title.isFocusable = true
    title.setOnClickListener { openFullPlayer() }
}

/**
 * Название трека в нижней полосе: тап разворачивает плеер, а для TalkBack это отдельный
 * узел, который обязан называть и действие, и сам трек — иначе имя песни терялось бы
 * ровно там, где пользователь его ищет.
 */
internal fun MusicActivity.renderBarTitleTarget(track: MusicTrack?) {
    title.isClickable = true
    title.isFocusable = true
    title.contentDescription = MusicFullPlayerPolicy.openAction(track)
}
