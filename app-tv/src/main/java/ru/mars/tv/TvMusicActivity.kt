package ru.mars.tv

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
 * Selecting a track row routes playback to the process-global [TvMusicPlayer] transport,
 * which keeps playing after this screen closes; «Остановить» ends it explicitly.
 * The screen observes the transport ([TvMusicPlayer.onTransportChanged]) so the header
 * line and the current track's row highlight follow asynchronous state transitions
 * (prepared / completion / stream error) without user interaction; row restyling never
 * rebuilds the list, so D-pad focus survives. Presentation rules are pure
 * ([TvMusicNowPlayingSpec]). Every interactive element is focusable for the D-pad, and
 * disappearing affordances never leave the window unfocused ([TvMusicFocusPolicy]);
 * theme colors only, no emojis.
 */
class TvMusicActivity : AppCompatActivity() {

    private val controller = TvMusicScreenController(
        gateway = RepositoryTvMusicGateway.create(),
        transport = TvMusicPlayer,
        uiDispatcher = MainThreadTvDispatcher(),
    )

    private lateinit var stateText: TextView
    private lateinit var retryButton: Button
    private lateinit var stopButton: Button
    private lateinit var backButton: Button
    private lateinit var nowText: TextView
    private lateinit var trackList: LinearLayout

    /** Rendered track rows (trackId to view) — restyled in place on transport changes. */
    private val trackRows = mutableListOf<Pair<String, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_music)
        stateText = findViewById(R.id.stateText)
        retryButton = findViewById(R.id.retryButton)
        stopButton = findViewById(R.id.stopButton)
        backButton = findViewById(R.id.backButton)
        nowText = findViewById(R.id.nowText)
        trackList = findViewById(R.id.trackList)
        backButton.setOnClickListener { finish() }
        retryButton.setOnClickListener { controller.load() }
        stopButton.setOnClickListener { controller.stopPlayback() }
        TvFocusLift.attach(backButton)
        TvFocusLift.attach(retryButton)
        TvFocusLift.attach(stopButton)
        controller.onStateChanged = ::render
        TvMusicPlayer.onTransportChanged = ::renderTransportState
        controller.load()
        render(controller.state)
        backButton.requestFocus()
    }

    override fun onDestroy() {
        // The transport is global: teardown silences the controller but music keeps playing.
        controller.destroy()
        controller.onStateChanged = null
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
                stateText.text = "Треков пока нет"
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
            val row = Button(this).apply {
                text = trackLabel(track)
                isAllCaps = false
                textSize = 20f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                compoundDrawablePadding = dp(14)
                setBackgroundResource(R.drawable.bg_focus_card)
                setTextColor(ContextCompat.getColor(context, R.color.mars_text))
                isFocusable = true
                setOnClickListener { controller.onTrackActivated(index) }
            }
            TvFocusLift.attach(row)
            applyRowState(row, track.id)
            trackRows += track.id to row
            trackList.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
                    bottomMargin = dp(12)
                },
            )
        }
        // Hand D-pad focus to the list when nothing meaningful is focused (retry may be GONE now).
        if (currentFocus == null || currentFocus === retryButton) {
            trackList.getChildAt(0)?.requestFocus()
        }
    }

    /** Transport snapshot to the UI: stop affordance, header line, row highlight. */
    private fun renderTransportState() {
        val stopVisible = TvMusicPlayer.currentTrack != null
        // «Остановить» disappears the moment the transport goes idle — in production this
        // fires synchronously from the D-pad press on the button itself (stop() notifies
        // observers), so hiding it under focus would leave the window unfocused and stall
        // D-pad navigation. Hand focus to the stable header BEFORE hiding ([TvMusicFocusPolicy]).
        if (
            TvMusicFocusPolicy.handoffBeforeHide(
                viewVisible = stopButton.visibility == View.VISIBLE,
                viewHasFocus = stopButton.hasFocus(),
                staysVisible = stopVisible,
            )
        ) {
            backButton.requestFocus()
        }
        stopButton.visibility = if (stopVisible) View.VISIBLE else View.GONE
        renderNowPlaying()
        trackRows.forEach { (trackId, row) -> applyRowState(row, trackId) }
    }

    private fun renderNowPlaying() {
        val line = TvMusicNowPlayingSpec.nowPlayingLine(
            track = TvMusicPlayer.currentTrack,
            isPlaying = TvMusicPlayer.isPlaying,
            error = TvMusicPlayer.lastError,
        )
        nowText.text = line
        nowText.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Highlights the transport's current row (accent text + icon), rest without a marker. */
    private fun applyRowState(row: Button, trackId: String) {
        if (TvMusicNowPlayingSpec.isCurrent(trackId, TvMusicPlayer.currentTrack?.id)) {
            row.setTextColor(ContextCompat.getColor(this, R.color.mars_accent_text))
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_music, 0, 0, 0)
            row.compoundDrawableTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mars_accent))
        } else {
            row.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            row.compoundDrawableTintList = null
        }
    }

    private fun trackLabel(track: MusicTrack): String {
        val base = "${track.title} — ${track.artist}"
        return if (track.duration.isNotBlank() && track.duration != "--:--") "$base · ${track.duration}" else base
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
