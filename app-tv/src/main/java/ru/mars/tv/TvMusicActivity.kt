package ru.mars.tv

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
 * TV music screen (P1 vertical slice): the dashboard's «Музыка» button lands here,
 * [TvMusicScreenController] loads the real charts through [RepositoryTvMusicGateway]
 * (core-network MusicRepository over `https://marscore.ru/music`) and renders one of the
 * four states — loading / error (with a D-pad «Повторить» retry) / empty / content.
 * Selecting a track row routes playback to the process-global [TvMusicPlayer] transport,
 * which keeps playing after this screen closes; «Остановить» ends it explicitly.
 * Every interactive element is focusable for the D-pad; theme colors only, no emojis.
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
    private lateinit var nowText: TextView
    private lateinit var trackList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_music)
        stateText = findViewById(R.id.stateText)
        retryButton = findViewById(R.id.retryButton)
        stopButton = findViewById(R.id.stopButton)
        nowText = findViewById(R.id.nowText)
        trackList = findViewById(R.id.trackList)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        retryButton.setOnClickListener { controller.load() }
        stopButton.setOnClickListener {
            controller.stopPlayback()
            renderNowPlaying()
        }
        TvFocusLift.attach(findViewById(R.id.backButton))
        TvFocusLift.attach(retryButton)
        TvFocusLift.attach(stopButton)
        controller.onStateChanged = ::render
        controller.load()
        render(controller.state)
        findViewById<View>(R.id.backButton).requestFocus()
    }

    override fun onDestroy() {
        // The transport is global: teardown silences the controller but music keeps playing.
        controller.destroy()
        controller.onStateChanged = null
        super.onDestroy()
    }

    private fun render(state: TvMusicUiState) {
        trackList.removeAllViews()
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
            }
            is TvMusicUiState.Content -> {
                stateText.visibility = View.GONE
                renderTracks(state.tracks)
            }
        }
        stopButton.visibility = if (TvMusicPlayer.currentTrack != null) View.VISIBLE else View.GONE
        renderNowPlaying()
    }

    private fun renderTracks(tracks: List<MusicTrack>) {
        tracks.forEachIndexed { index, track ->
            val row = Button(this).apply {
                text = trackLabel(track)
                isAllCaps = false
                textSize = 20f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                setBackgroundResource(R.drawable.bg_focus_card)
                setTextColor(ContextCompat.getColor(context, R.color.mars_text))
                isFocusable = true
                setOnClickListener {
                    controller.onTrackActivated(index)
                    stopButton.visibility = View.VISIBLE
                    renderNowPlaying()
                }
            }
            TvFocusLift.attach(row)
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

    private fun renderNowPlaying() {
        val track = TvMusicPlayer.currentTrack
        nowText.text = when {
            track == null -> ""
            TvMusicPlayer.isPlaying -> "Сейчас играет: ${track.title} — ${track.artist}"
            TvMusicPlayer.lastError != null -> "Не играет: ${track.title}. ${TvMusicPlayer.lastError}"
            else -> "Загружаю: ${track.title}…"
        }
        nowText.visibility = if (nowText.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun trackLabel(track: MusicTrack): String {
        val base = "${track.title} — ${track.artist}"
        return if (track.duration.isNotBlank() && track.duration != "--:--") "$base · ${track.duration}" else base
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
