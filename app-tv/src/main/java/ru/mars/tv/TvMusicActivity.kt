package ru.mars.tv

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    /**
     * True when the focus held by a disappearing affordance must be handed to the stable
     * header first: the view is on screen, it holds focus, and this render hides it.
     * A view that stays visible (restyle) or never held focus keeps focus where it is.
     */
    fun handoffBeforeHide(viewVisible: Boolean, viewHasFocus: Boolean, staysVisible: Boolean): Boolean =
        viewVisible && viewHasFocus && !staysVisible
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
        lyricsFlow.onStateChanged = ::renderLyricsPanel
        TvMusicPlayer.onTransportChanged = ::renderTransportState
        controller.load()
        render(controller.state)
        backButton.requestFocus()
    }

    /** Submits the field text as a search and dismisses the IME so results stay visible. */
    private fun submitSearch() {
        controller.search(searchInput.text.toString())
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
        lyricsDialog?.dismiss()
        lyricsDialog = null
        TvMusicPlayer.onTransportChanged = null
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
     * закрывает и запрос ([TvLyricsPanelFlow.close] в OnDismissListener).
     */
    private fun showLyricsDialog(track: MusicTrack) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_tv_lyrics)
        dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(track)
        dialog.findViewById<TextView>(R.id.lyricsBodyText).text = TvLyricsViewPolicy.LOADING_TEXT
        val closeButton = dialog.findViewById<Button>(R.id.lyricsCloseButton)
        closeButton.setOnClickListener { dialog.dismiss() }
        TvFocusLift.attach(closeButton)
        dialog.setOnDismissListener { lyricsFlow.close() }
        dialog.window?.apply {
            setLayout(dp(680), dp(620))
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        // D-pad-окно: диалог стартует с фокусом на «Закрыть» — навигация не глохнет.
        closeButton.requestFocus()
        lyricsDialog = dialog
    }

    /** Рендер состояния [TvLyricsPanelFlow] в открытый диалог (только UI-поток). */
    private fun renderLyricsPanel(state: TvLyricsPanelState) {
        val dialog = lyricsDialog?.takeIf { it.isShowing } ?: return
        val body = dialog.findViewById<TextView>(R.id.lyricsBodyText)
        val meta = dialog.findViewById<TextView>(R.id.lyricsMetaText)
        when (state) {
            TvLyricsPanelState.Idle -> Unit

            is TvLyricsPanelState.Loading -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = TvLyricsViewPolicy.LOADING_TEXT
                meta.visibility = View.GONE
            }

            is TvLyricsPanelState.Text -> {
                dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(state.track)
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_text_em))
                body.text = state.body
                val metaLine = TvLyricsViewPolicy.metaLine(state.synced, state.source)
                meta.text = metaLine
                meta.visibility = if (metaLine.isEmpty()) View.GONE else View.VISIBLE
                // Текст пришёл: фокус на скролл, куплеты листаются стрелками D-pad.
                dialog.findViewById<ScrollView>(R.id.lyricsScroll).requestFocus()
            }

            is TvLyricsPanelState.Missing -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = TvLyricsViewPolicy.MISSING_TEXT
                meta.visibility = View.GONE
            }

            is TvLyricsPanelState.Error -> {
                body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
                body.text = state.message
                meta.visibility = View.GONE
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
