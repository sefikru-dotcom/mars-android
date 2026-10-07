package ru.mars.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus
import ru.mars.network.repository.MusicRepository

class MusicActivity : ComponentActivity() {
    private lateinit var repository: MusicRepository
    private lateinit var results: LinearLayout
    private lateinit var nowPlaying: TextView
    private lateinit var playButton: Button
    private lateinit var volume: SeekBar
    private var state = MusicPlayerState()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MusicRepository(BASE_URL)
        setContentView(buildView())
        refreshStatus()
    }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Mars Music"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } })
        root.addView(header)
        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val query = EditText(this).apply { hint = "Найти трек или исполнителя"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(MUTED) }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(54), 1f))
        searchRow.addView(Button(this).apply { text = "Найти"; setOnClickListener { search(query.text.toString()) } })
        root.addView(searchRow)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(results); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        val player = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), 0); background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(14).toFloat() } }
        nowPlaying = TextView(this).apply { text = "Ничего не играет"; textSize = 16f; setTextColor(Color.WHITE) }
        player.addView(nowPlaying)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply { text = "⏮"; setOnClickListener { command { repository.previous() } } })
        playButton = Button(this).apply { text = "▶"; setOnClickListener { command { if (state.playback == PlaybackStatus.PLAYING) repository.pause() else repository.play() } } }
        controls.addView(playButton)
        controls.addView(Button(this).apply { text = "⏭"; setOnClickListener { command { repository.next() } } })
        player.addView(controls)
        val volumeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        volumeRow.addView(TextView(this).apply { text = "Громкость"; setTextColor(MUTED) })
        volume = SeekBar(this).apply { max = 100; progress = 80; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) command { repository.setVolume(value / 100.0) } }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit; override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }) }
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, -2, 1f)); player.addView(volumeRow)
        root.addView(player)
        return root
    }
    private fun search(query: String) { if (query.isBlank()) return; job?.cancel(); job = lifecycleScope.launch { runCatching { repository.search(query) }.onSuccess { tracks -> results.removeAllViews(); tracks.forEach { addTrack(it) } }.onFailure { showError("Поиск недоступен") } } }
    private fun addTrack(track: MusicTrack) { val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), 0, dp(10)) }; row.addView(TextView(this).apply { text = "${track.title}\n${track.artist} · ${track.duration}"; textSize = 16f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(Button(this).apply { text = "▶"; setOnClickListener { command { repository.play() } } }); results.addView(row) }
    private fun refreshStatus() { job = lifecycleScope.launch { runCatching { repository.getStatus() }.onSuccess { state = it; renderState() } } }
    private fun command(action: suspend () -> Unit) { job?.cancel(); job = lifecycleScope.launch { runCatching { action() }.onSuccess { refreshStatus() }.onFailure { showError("Музыкальный сервис недоступен") } } }
    private fun renderState() { nowPlaying.text = state.track?.let { "${it.title} — ${it.artist}" } ?: "Ничего не играет"; playButton.text = if (state.playback == PlaybackStatus.PLAYING) "⏸" else "▶"; volume.progress = (state.volume * 100).toInt().coerceIn(0, 100) }
    private fun showError(message: String) { nowPlaying.text = message }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val BASE_URL = "https://marscore.ru"; private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val MUTED = 0xffaab2c0.toInt() }
}
