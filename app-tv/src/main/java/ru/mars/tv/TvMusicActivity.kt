package ru.mars.tv

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.model.MusicTrack

/**
 * Pure D-pad focus policy of the TV music screen (no Android, no I/O — JVM-testable,
 * [TvMusicNowPlayingSpec] parity).
 *
 * An Android TV window navigates only while some view holds focus: hiding the focused
 * view leaves `currentFocus == null` and, with no touch fallback, further D-pad presses
 * go nowhere. Both disappearing affordances of this screen are transport/state-driven:
 *
 * - «Остановить» vanishes the moment the transport goes idle — typically as the direct
 *   result of a D-pad press on the button itself ([TvMusicPlayer.stop] notifies its
 *   observers synchronously). [handoffBeforeHide] is the rule: focus must move to the
 *   stable header («Назад») BEFORE the focused affordance is hidden, never after.
 * - «Повторить» vanishes on every re-render; content and error states re-hand focus
 *   themselves, but the terminal empty state must not stay unfocused either.
 */
internal object TvMusicFocusPolicy {

    /** [View.NO_ID] mirror for JVM tests: «нет цели — фокус остаётся где был». */
    const val NO_FOCUS = -1

    /**
     * True when the focus held by a disappearing affordance must be handed to the stable
     * header first: the view is on screen, it holds focus, and this render hides it.
     * A view that stays visible (restyle) or never held focus keeps focus where it is.
     */
    fun handoffBeforeHide(viewVisible: Boolean, viewHasFocus: Boolean, staysVisible: Boolean): Boolean =
        viewVisible && viewHasFocus && !staysVisible

    /** What BACK does on the music screen: the IME absorbs the first press, the screen the next. */
    enum class BackAction { HIDE_IME, CLOSE_SCREEN }

    /**
     * BACK при открытой экранной клавиатуре закрывает ТОЛЬКО её (фокус остаётся на
     * searchInput); без клавиатуры — штатно закрывает экран.
     */
    fun backAction(imeVisible: Boolean): BackAction =
        if (imeVisible) BackAction.HIDE_IME else BackAction.CLOSE_SCREEN

    /**
     * LEFT/RIGHT focus pairs for the header buttons that are VISIBLE right now:
     * neighbours reference each other, both ends self-loop, so the D-pad never leaves
     * the row and never chases into a GONE view. Caller passes ids in visual order.
     */
    fun horizontalChain(ids: List<Int>): Map<Int, Pair<Int, Int>> {
        if (ids.isEmpty()) return emptyMap()
        return ids.mapIndexed { index, id ->
            val left = if (index == 0) ids.first() else ids[index - 1]
            val right = if (index == ids.lastIndex) ids.last() else ids[index + 1]
            id to (left to right)
        }.toMap()
    }

    /**
     * DOWN target from the search row: the first rendered track row wins; while the list
     * is empty a visible «Повторить» catches the press; otherwise there is no target
     * ([NO_FOCUS]) and focus honestly stays on the search row.
     */
    fun downTargetFromSearchRow(firstTrackRowId: Int?, retryButtonId: Int?, retryVisible: Boolean): Int =
        when {
            firstTrackRowId != null -> firstTrackRowId
            retryVisible && retryButtonId != null -> retryButtonId
            else -> NO_FOCUS
        }
}

/**
 * TV music screen (P1 vertical slice): the dashboard's «Музыка» button lands here,
 * [TvMusicScreenController] loads the real charts through [RepositoryTvMusicGateway]
 * (core-network MusicRepository over `https://marscore.ru/music`) and renders one of the
 * four states — loading / error (with a D-pad «Повторить» retry) / empty / content.
 * The search row queries the SAME repository seam (`/api/search`): the field's IME
 * «search» action and the explicit «Найти» button submit, «Чарт» returns to the charts
 * without stopping playback, and «Повторить» retries exactly the last operation — so
 * the remote user never depends on the soft keyboard being reachable or dismissable.
 * Selecting a track row binds the shown list as the process-global [TvMusicPlayer]
 * transport's playlist and starts it on that row; the transport keeps playing after this
 * screen closes, auto-advances to the next track when one completes ([TvPlaylistPolicy])
 * and goes idle at the end of the list. The header transport pair keeps non-destructive
 * and destructive control apart: «Пауза/Играть» ([TvMusicPlayer.togglePlayPause],
 * captions/icons delegated to [TvMiniPlayerSpec] — one voice with the chat mini player)
 * quiets the audio while KEEPING the playlist binding, «Остановить» ends playback
 * explicitly; both are shown only while a track is bound
 * ([TvMusicNowPlayingSpec.transportControls]). The header
 * «Повтор» toggle cycles the repeat preference OFF -> ALL -> ONE
 * ([TvMusicPlayer.cycleRepeatMode], label per [TvMusicNowPlayingSpec.repeatButtonLabel]) —
 * ALL loops the queue, ONE replays the current track.
 * The screen observes the transport ([TvMusicPlayer.onTransportChanged]) so the header
 * line and the current track's row highlight follow asynchronous state transitions
 * (prepared / completion / auto-advance / stream error) without user interaction; row
 * restyling never rebuilds the list, so D-pad focus survives. Presentation rules are pure
 * ([TvMusicNowPlayingSpec], [TvMusicSearchSpec]). Every interactive element is focusable
 * for the D-pad, and disappearing affordances never leave the window unfocused
 * ([TvMusicFocusPolicy]); theme colors only, no emojis.
 *
 * Обложки: серверные thumbnail треков (относительные `/music/api/thumb?v=ID`) грузит
 * [TvImageLoader] — без внешних библиотек, с byte-based LruCache и даунсэмплингом
 * (числовые инварианты — в чистой [TvImageLoaderPolicy]); URL резолвится от origin сайта,
 * а не от baseUrl музыкального API ([TvThumbnailUrlResolver]) — иначе двойной `/music/music/…`.
 * Обложка видна в строке каждого трека и рядом с «Сейчас играет» в шапке.
 *
 * Тексты песен: кнопка «Текст песни» на текущем треке открывает диалог
 * ([R.layout.dialog_tv_lyrics]); запрос идёт через [TvLyricsPanelFlow] (чистый конечный
 * автомат: Loading -> Text/Missing/Error, защита от двойного тапа, generation-guard)
 * и [RepositoryTvMusicGateway.fetchLyrics] (title/artist обязательны — живой сервер без них
 * отдаёт заглушку «Текст не найден.», которую [TvLyricsViewPolicy] распознаёт как Missing).
 * Переключатель «Перевод»/«Оригинал» — паритет с mobile: кнопка появляется только при
 * настоящем тексте, запрос `POST /api/lyrics/translate` — чистый автомат
 * [TvLyricsTranslationFlow] (своя очередь, Job-guard на двойное нажатие, generation-guard
 * на сброс), подписи/тело/заметку выбирает [TvLyricsTranslationPolicy] (строки из
 * ресурсов). Ошибка или пустой ответ панель не роняют — на экране остаётся оригинал,
 * понятная строка уходит в мету; сброс при смене трека и закрытии панели.
 */
class TvMusicActivity : AppCompatActivity() {

    /** Один прод-гейтвей на экран: чарты/поиск контроллеру + тексты панели [lyricsFlow]. */
    private val musicGateway = RepositoryTvMusicGateway.create()

    private val controller = TvMusicScreenController(
        gateway = musicGateway,
        transport = TvMusicPlayer,
        uiDispatcher = MainThreadTvDispatcher(),
    )

    /** Конечный автомат панели «Текст песни» (чистый; Android здесь — только рендер). */
    private val lyricsFlow = TvLyricsPanelFlow(gateway = musicGateway)

    /** Переключатель «Перевод»/«Оригинал» — надстройка над [lyricsFlow] (чистый автомат). */
    private val lyricsTranslationFlow = TvLyricsTranslationFlow(gateway = musicGateway)

    private lateinit var stateText: TextView
    private lateinit var retryButton: Button
    private lateinit var stopButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var repeatButton: Button
    private lateinit var backButton: Button
    private lateinit var nowText: TextView
    private lateinit var nowArt: ImageView
    private lateinit var lyricsButton: Button
    private lateinit var trackList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var chartsButton: Button

    /** Открытый диалог текста песни (один на экран; пересоздаётся на каждый показ). */
    private var lyricsDialog: Dialog? = null

    /**
     * Видимость экранной клавиатуры: её высота съедает низ окна, [globalLayoutListener]
     * считает разницу по [View.getWindowVisibleDisplayFrame]. Нужна для BACK: сначала
     * закрывается IME ([TvMusicFocusPolicy.backAction]), и только потом экран.
     */
    private var imeVisible = false

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = window.decorView
        val visible = Rect()
        root.getWindowVisibleDisplayFrame(visible)
        // 80dp порога: системные бары ТВ не дают такой разницы, а панель IME — даёт.
        imeVisible = root.height - visible.bottom > dp(80)
    }

    /**
     * Rendered track rows — restyled in place on transport changes.
     * [label] красится акцентом у текущего трека, [marker] (ic_music) у него же виден.
     */
    private class TrackRow(val trackId: String, val label: TextView, val marker: ImageView)

    private val trackRows = mutableListOf<TrackRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_music)
        // Global music transport: audio focus + network wake mode need an application
        // context; playback started from this screen is a good audio citizen.
        TvMusicPlayer.attachContext(this)
        stateText = findViewById(R.id.stateText)
        retryButton = findViewById(R.id.retryButton)
        stopButton = findViewById(R.id.stopButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        repeatButton = findViewById(R.id.repeatButton)
        backButton = findViewById(R.id.backButton)
        nowText = findViewById(R.id.nowText)
        nowArt = findViewById(R.id.nowArt)
        lyricsButton = findViewById(R.id.lyricsButton)
        trackList = findViewById(R.id.trackList)
        searchInput = findViewById(R.id.searchInput)
        searchButton = findViewById(R.id.searchButton)
        chartsButton = findViewById(R.id.chartsButton)
        backButton.setOnClickListener { finish() }
        // Retry repeats the LAST operation (charts or the failed search query).
        retryButton.setOnClickListener { controller.retry() }
        stopButton.setOnClickListener { controller.stopPlayback() }
        // D-pad play/pause toggle: the non-destructive way to quiet the audio — the
        // playlist binding survives, «Играть» resumes the same queue. Same transport
        // action as the chat mini player's toggle ([TvMusicPlayer.togglePlayPause]).
        playPauseButton.setOnClickListener {
            TvMusicPlayer.togglePlayPause()
            renderTransportState()
        }
        // D-pad repeat toggle: cycles the transport preference OFF -> ALL -> ONE
        // ([TvMusicPlayer.cycleRepeatMode]); the label mirrors the new mode immediately.
        repeatButton.setOnClickListener {
            TvMusicPlayer.cycleRepeatMode()
            renderRepeatMode()
        }
        // «Текст песни»: диалог открывается сразу (тело — загрузка), [TvLyricsPanelFlow]
        // заполняет его по ответу сервера; двойной тап глушит сам flow (Job-guard parity).
        lyricsButton.setOnClickListener {
            val track = TvMusicPlayer.currentTrack ?: return@setOnClickListener
            if (lyricsDialog?.isShowing != true) showLyricsDialog(track)
            // Новый запрос текста: перевод прошлого трека не переносится (сброс при смене трека).
            lyricsTranslationFlow.reset()
            lyricsFlow.open(track)
        }
        // The IME «search» action and the explicit «Найти» button are the same submit;
        // «Чарт» empties the field and returns to the charts WITHOUT touching the
        // transport — music that is playing keeps playing across the list swap.
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        searchButton.setOnClickListener { submitSearch() }
        chartsButton.setOnClickListener {
            searchInput.setText("")
            controller.clearSearch()
        }
        // Клавиатура НЕ всплывает на фокус сама (setShowSoftInputOnFocus(false), у XML-
        // атрибута нет публичного статуса): D-pad проходит через поле поиска вниз к
        // трекам без залипания. Открываем её только явным нажатием (DPAD_CENTER/клик).
        searchInput.setShowSoftInputOnFocus(false)
        searchInput.setOnClickListener { showIme() }
        // Android EditText ПОТРЕБЛЯЕТ стрелки D-pad сам (UP/DOWN — переход по строкам,
        // LEFT/RIGHT — курсор), поэтому nextFocusDown поля не срабатывает никогда:
        // DOWN залипал в поиске и до списка треков пультом было не добраться. Явный
        // перехват: DOWN — на цель из rewireSearchDownFocus() (первый трек или
        // «Повторить»), UP — обратно в шапку. При открытой клавиатуре не вмешиваемся:
        // стрелки принадлежат IME.
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (imeVisible) {
                        false
                    } else {
                        val target = searchInput.nextFocusDownId
                        val view = if (target != View.NO_ID) findViewById<View>(target) else null
                        view?.requestFocus() ?: false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (imeVisible) false else backButton.requestFocus()
                }
                else -> false
            }
        }
        // BACK: сначала закрываем экранную клавиатуру (фокус остаётся на searchInput),
        // и только без IME — штатно закрываем экран. Решение — чистая
        // [TvMusicFocusPolicy.backAction], видимость IME считает [globalLayoutListener].
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (TvMusicFocusPolicy.backAction(imeVisible)) {
                    TvMusicFocusPolicy.BackAction.HIDE_IME -> {
                        hideIme()
                        searchInput.requestFocus()
                    }
                    TvMusicFocusPolicy.BackAction.CLOSE_SCREEN -> {
                        // Штатный путь: на мгновение снимаем callback и пропускаем BACK дальше.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        TvFocusLift.attach(backButton)
        TvFocusLift.attach(retryButton)
        TvFocusLift.attach(stopButton)
        TvFocusLift.attach(playPauseButton)
        TvFocusLift.attach(repeatButton)
        TvFocusLift.attach(lyricsButton)
        TvFocusLift.attach(searchInput)
        TvFocusLift.attach(searchButton)
        TvFocusLift.attach(chartsButton)
        controller.onStateChanged = ::render
        lyricsFlow.onStateChanged = { state ->
            // Фокус на скролл — только когда пришёл НОВЫЙ текст: перерисовки перевода
            // не должны вырывать D-pad у кнопки/скролла (требование панели).
            renderLyricsPanel(state, focusText = state is TvLyricsPanelState.Text)
        }
        lyricsTranslationFlow.onStateChanged = { renderLyricsPanel(lyricsFlow.state, focusText = false) }
        TvMusicPlayer.onTransportChanged = ::renderTransportState
        controller.load()
        render(controller.state)
        backButton.requestFocus()
    }

    /** Submits the field text as a search and dismisses the IME so results stay visible. */
    private fun submitSearch() {
        controller.search(searchInput.text.toString())
        hideIme()
    }

    /** Явный показ экранной клавиатуры (DPAD_CENTER на searchInput). */
    private fun showIme() {
        searchInput.requestFocus()
        val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        ime.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Скрыть экранную клавиатуру; видимость пересчитает [globalLayoutListener]. */
    private fun hideIme() {
        val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        ime.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    override fun onDestroy() {
        // The transport is global: teardown silences the controller but music keeps playing.
        controller.destroy()
        controller.onStateChanged = null
        // Панель текста: глушим запрос в пути и снимаем хук рендера (окно умирает).
        lyricsFlow.destroy()
        lyricsFlow.onStateChanged = null
        lyricsTranslationFlow.destroy()
        lyricsTranslationFlow.onStateChanged = null
        lyricsDialog?.dismiss()
        lyricsDialog = null
        TvMusicPlayer.onTransportChanged = null
        window.decorView.viewTreeObserver.takeIf { it.isAlive }
            ?.removeOnGlobalLayoutListener(globalLayoutListener)
        super.onDestroy()
    }

    private fun render(state: TvMusicUiState) {
        trackList.removeAllViews()
        trackRows.clear()
        retryButton.visibility = View.GONE
        when (state) {
            is TvMusicUiState.Loading -> {
                stateText.visibility = View.VISIBLE
                stateText.text = "Загрузка треков…"
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
        renderTransportState()
        // DOWN из строки поиска ведёт к списку треков: список динамический, поэтому
        // цель переставляется кодом после каждого рендера (XML nextFocusDown — лишь
        // стартовый fallback на @+id/trackList).
        rewireSearchDownFocus()
    }

    private fun renderTracks(tracks: List<MusicTrack>) {
        tracks.forEachIndexed { index, track ->
            // Обложка трека: серверный thumbnail грузит TvImageLoader; при пустом/нерезолвящемся
            // URL или ошибке загрузки вьюха остаётся GONE (строка читается как раньше).
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                contentDescription = null
            }
            val label = TextView(this).apply {
                text = trackLabel(track)
                textSize = 20f
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
                setOnClickListener { controller.onTrackActivated(index) }
            }
            row.addView(
                cover,
                LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = dp(14)
                },
            )
            row.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            row.addView(
                marker,
                LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(12)
                },
            )
            TvFocusLift.attach(row)
            val trackRow = TrackRow(track.id, label, marker)
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
        // Hand D-pad focus to the list when nothing meaningful is focused (retry may be GONE now).
        if (currentFocus == null || currentFocus === retryButton) {
            trackList.getChildAt(0)?.requestFocus()
        }
    }

    /** Transport snapshot to the UI: transport pair, header line, row highlight. */
    private fun renderTransportState() {
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

    /**
     * Взаимные nextFocus* всех кнопок верхней панели + строки поиска — только между
     * ВИДИМЫМИ элементами; края самопетлят (фокус остаётся в панели). Вызывается
     * после каждой смены видимости транспорта ([renderTransportState]).
     */
    private fun rewireHeaderFocusChain() {
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
        // Вверх из строки поиска — только в видимые кнопки шапки.
        searchInput.nextFocusUpId = backButton.id
        searchButton.nextFocusUpId = repeatButton.id
        chartsButton.nextFocusUpId = (visible.lastOrNull() ?: backButton).id
    }

    /**
     * Код-механизм «поиск -> динамический список треков»: после рендера наводим
     * nextFocusDown строки поиска (и соседей) на ПЕРВУЮ строку [trackList]; если списка
     * нет — на видимую «Повторить», иначе цели нет и фокус честно остаётся на месте.
     * Первый трек получает nextFocusUp обратно в поле поиска.
     */
    private fun rewireSearchDownFocus() {
        val firstRow = trackList.getChildAt(0)
        val target = TvMusicFocusPolicy.downTargetFromSearchRow(
            firstTrackRowId = firstRow?.id?.takeIf { it != View.NO_ID },
            retryButtonId = R.id.retryButton,
            retryVisible = retryButton.visibility == View.VISIBLE,
        )
        searchInput.nextFocusDownId = target
        searchButton.nextFocusDownId = target
        chartsButton.nextFocusDownId = target
        // «Повторить» вниз — в первый трек, а без треков цели нет (фокус остаётся на месте;
        // самопетлю GONE-кнопке не ставим, чтобы чужой focusSearch не зациклился о невидимое).
        retryButton.nextFocusDownId = firstRow?.id ?: TvMusicFocusPolicy.NO_FOCUS
        retryButton.nextFocusUpId = R.id.searchInput
        firstRow?.nextFocusUpId = R.id.searchInput
    }

    /** Repeat-toggle label mirrors the transport preference (pure: [TvMusicNowPlayingSpec]). */
    private fun renderRepeatMode() {
        repeatButton.text = TvMusicNowPlayingSpec.repeatButtonLabel(TvMusicPlayer.repeatMode)
    }

    private fun renderNowPlaying() {
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
    private fun applyRowState(row: TrackRow) {
        if (TvMusicNowPlayingSpec.isCurrent(row.trackId, TvMusicPlayer.currentTrack?.id)) {
            row.label.setTextColor(ContextCompat.getColor(this, R.color.mars_accent_text))
            row.marker.visibility = View.VISIBLE
        } else {
            row.label.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
            row.marker.visibility = View.GONE
        }
    }

    private fun trackLabel(track: MusicTrack): String {
        val base = "${track.title} — ${track.artist}"
        return if (track.duration.isNotBlank() && track.duration != "--:--") "$base · ${track.duration}" else base
    }

    /**
     * Показывает тематический диалог текста песни с телом-загрузкой; содержимое заполняет
     * [renderLyricsPanel] по состояниям [lyricsFlow]. Back/«Закрыть» — dismiss, который
     * закрывает и запрос ([TvLyricsPanelFlow.close] в OnDismissListener) и сбрасывает
     * переключатель перевода: следующее открытие начинает с оригинала.
     */
    private fun showLyricsDialog(track: MusicTrack) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_tv_lyrics)
        dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(track)
        dialog.findViewById<TextView>(R.id.lyricsBodyText).text = TvLyricsViewPolicy.LOADING_TEXT
        val closeButton = dialog.findViewById<Button>(R.id.lyricsCloseButton)
        closeButton.setOnClickListener { dialog.dismiss() }
        // «Перевод»/«Оригинал»: нажатие уходит в [TvLyricsTranslationFlow] (двойное он
        // глушит сам), подпись/видимость/тело рисует [renderLyricsPanel]. Кнопка остаётся
        // фокусируемой и во время запроса — на TV disabled-элемент роняет D-pad-фокус.
        val translateButton = dialog.findViewById<Button>(R.id.lyricsTranslateButton)
        translateButton.setOnClickListener {
            val panel = lyricsFlow.state
            if (panel is TvLyricsPanelState.Text) lyricsTranslationFlow.toggle(panel.body)
        }
        TvFocusLift.attach(closeButton)
        TvFocusLift.attach(translateButton)
        dialog.setOnDismissListener {
            lyricsFlow.close()
            lyricsTranslationFlow.reset()
        }
        dialog.window?.apply {
            setLayout(dp(680), dp(620))
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        // D-pad-окно: диалог стартует с фокусом на «Закрыть» — навигация не глохнет.
        closeButton.requestFocus()
        lyricsDialog = dialog
    }

    /**
     * Рендер состояния панели ([lyricsFlow]) в открытый диалог (только UI-поток).
     * [focusText] — переносить ли фокус на скролл: только при приходе нового текста;
     * перерисовки перевода ([lyricsTranslationFlow]) фокус не двигают. Тело — наложение
     * перевода ([TvLyricsTranslationPolicy.bodyFor]), кнопка «Перевод»/«Оригинал» живёт
     * только при настоящем тексте ([TvLyricsTranslationPolicy.buttonVisible]).
     */
    private fun renderLyricsPanel(state: TvLyricsPanelState, focusText: Boolean) {
        val dialog = lyricsDialog?.takeIf { it.isShowing } ?: return
        val body = dialog.findViewById<TextView>(R.id.lyricsBodyText)
        val meta = dialog.findViewById<TextView>(R.id.lyricsMetaText)
        val translation = lyricsTranslationFlow.state
        when (state) {
            TvLyricsPanelState.Idle -> Unit

            is TvLyricsPanelState.Loading -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = TvLyricsViewPolicy.LOADING_TEXT
                meta.visibility = View.GONE
                renderTranslateButton(dialog, visible = false)
            }

            is TvLyricsPanelState.Text -> {
                dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(state.track)
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_text_em))
                body.text = TvLyricsTranslationPolicy.bodyFor(state.body, translation)
                // Под заголовком: мета панели и заметка перевода (понятная строка неудачи).
                val metaLine = TvLyricsTranslationPolicy.metaLine(
                    panelMeta = TvLyricsViewPolicy.metaLine(state.synced, state.source),
                    note = translationNote(translation),
                )
                meta.text = metaLine
                meta.visibility = if (metaLine.isEmpty()) View.GONE else View.VISIBLE
                renderTranslateButton(dialog, visible = TvLyricsTranslationPolicy.buttonVisible(state))
                if (focusText) {
                    // Текст пришёл: фокус на скролл, куплеты листаются стрелками D-pad.
                    dialog.findViewById<ScrollView>(R.id.lyricsScroll).requestFocus()
                }
            }

            is TvLyricsPanelState.Missing -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = TvLyricsViewPolicy.MISSING_TEXT
                meta.visibility = View.GONE
                renderTranslateButton(dialog, visible = false)
            }

            is TvLyricsPanelState.Error -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = state.message
                meta.visibility = View.GONE
                renderTranslateButton(dialog, visible = false)
            }
        }
    }

    /**
     * Видимость и подпись кнопки «Перевод»/«Оригинал». Перед скрытием фокус (если кнопка
     * его держала) передаётся стабильному «Закрыть» ([TvMusicFocusPolicy.handoffBeforeHide] —
     * D-pad-окно не должно оставаться без фокуса); цепочка LEFT/RIGHT переподключается по
     * видимости — без прыжков в GONE-соседа (образец: [TvMusicFocusPolicy.horizontalChain]).
     */
    private fun renderTranslateButton(dialog: Dialog, visible: Boolean) {
        val translateButton = dialog.findViewById<Button>(R.id.lyricsTranslateButton)
        val closeButton = dialog.findViewById<Button>(R.id.lyricsCloseButton)
        if (TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = translateButton.visibility == View.VISIBLE,
                viewHasFocus = translateButton.hasFocus(),
                staysVisible = visible,
            )
        ) {
            closeButton.requestFocus()
        }
        translateButton.text = translationLabel(TvLyricsTranslationPolicy.buttonLabel(lyricsTranslationFlow.state))
        translateButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            translateButton.nextFocusLeftId = translateButton.id
            translateButton.nextFocusRightId = closeButton.id
            closeButton.nextFocusLeftId = translateButton.id
        } else {
            closeButton.nextFocusLeftId = closeButton.id
        }
        // UP с обеих кнопок — в скролл текста; DOWN из скролла — штатный поиск (листается).
        translateButton.nextFocusUpId = R.id.lyricsScroll
        closeButton.nextFocusUpId = R.id.lyricsScroll
    }

    /** Подпись кнопки из ресурсов: policy выбирает смысл, текст живёт в strings_tv_lyrics.xml. */
    private fun translationLabel(label: TvLyricsTranslationPolicy.Label): String = getString(
        when (label) {
            TvLyricsTranslationPolicy.Label.TRANSLATE -> R.string.tv_lyrics_action_translate
            TvLyricsTranslationPolicy.Label.ORIGINAL -> R.string.tv_lyrics_action_original
            TvLyricsTranslationPolicy.Label.TRANSLATING -> R.string.tv_lyrics_label_translating
        },
    )

    /** Заметка перевода для строки метаданных; пустая — когда перевод не неудачен. */
    private fun translationNote(state: TvLyricsTranslationState): String {
        val reason = TvLyricsTranslationPolicy.failureOf(state) ?: return ""
        return getString(
            when (reason) {
                TvLyricsTranslationFailure.EMPTY_ANSWER -> R.string.tv_lyrics_translation_empty
                TvLyricsTranslationFailure.NETWORK -> R.string.tv_lyrics_translation_failed
            },
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
