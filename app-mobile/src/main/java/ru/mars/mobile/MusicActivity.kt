package ru.mars.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository
import java.net.HttpURLConnection
import java.net.URL

class MusicActivity : ComponentActivity(), MarsMusicPlayer.MarsMusicListener {
    private lateinit var repository: MusicRepository
    private lateinit var results: LinearLayout
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: Button
    private lateinit var artwork: ImageView
    private var tracks = emptyList<MusicTrack>()
    private var job: Job? = null
    private val ticker = object : Runnable {
        override fun run() { if (::seek.isInitialized) { seek.progress = MarsMusicPlayer.currentPosition; time.text = "${fmt(MarsMusicPlayer.currentPosition)} / ${fmt(MarsMusicPlayer.duration)}"; seek.postDelayed(this, 500) } }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); repository = MusicRepository(BASE_URL); setContentView(buildView())
        MarsMusicPlayer.registerListener(this); seek.post(ticker); loadHome()
    }
    override fun onDestroy() { MarsMusicPlayer.unregisterListener(this); seek.removeCallbacks(ticker); job?.cancel(); super.onDestroy() }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Mars Music"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } }); root.addView(header)
        val search = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = "Найти трек или исполнителя"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(MUTED) }
        search.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f)); search.addView(Button(this).apply { text = "Найти"; setOnClickListener { search(input.text.toString()) } }); root.addView(search)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(results); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), 0); background = bg(SURFACE) }
        artwork = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; panel.addView(artwork, LinearLayout.LayoutParams(dp(56), dp(56)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        title = TextView(this).apply { text = "Ничего не играет"; textSize = 16f; setTextColor(Color.WHITE) }; info.addView(title)
        time = TextView(this).apply { text = "00:00 / 00:00"; setTextColor(MUTED) }; info.addView(time)
        seek = SeekBar(this).apply { max = 1; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { if (u) MarsMusicPlayer.seekTo(p) }; override fun onStartTrackingTouch(b: SeekBar?) {}; override fun onStopTrackingTouch(b: SeekBar?) {} }) }; info.addView(seek)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply { text = "⏮"; setOnClickListener { MarsMusicPlayer.previous() } })
        playButton = Button(this).apply { text = "▶"; setOnClickListener { MarsMusicPlayer.togglePlay() } }; controls.addView(playButton)
        controls.addView(Button(this).apply { text = "⏭"; setOnClickListener { MarsMusicPlayer.next() } }); info.addView(controls)
        panel.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(panel); return root
    }

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) { runOnUiThread { title.text = track?.let { "${it.title} — ${it.artist}" } ?: "Ничего не играет"; playButton.text = if (isPlaying) "⏸" else "▶"; seek.max = MarsMusicPlayer.duration.coerceAtLeast(1); track?.thumbnail?.let { ImageLoader.load(it, artwork) } ?: run { artwork.visibility = View.GONE } } }
    override fun onProgress(currentMs: Int, totalMs: Int) { runOnUiThread { seek.max = totalMs.coerceAtLeast(1); seek.progress = currentMs; time.text = "${fmt(currentMs)} / ${fmt(totalMs)}" } }

    private fun loadHome() { job = lifecycleScope.launch { val found = withContext(Dispatchers.IO) { runCatching { fetch("$BASE_URL/api/charts") }.getOrNull() ?: runCatching { fetch("$BASE_URL/api/home") }.getOrNull() }; found?.let { showTracks(parseTracks(it)) } } }
    private fun search(q: String) { if (q.isBlank()) return; job?.cancel(); job = lifecycleScope.launch { runCatching { repository.search(q) }.onSuccess { showTracks(it) }.onFailure { title.text = "Поиск недоступен" } } }
    private fun showTracks(items: List<MusicTrack>) { tracks = items; results.removeAllViews(); if (items.isEmpty()) { results.addView(TextView(this).apply { text = "Треки не найдены"; setTextColor(MUTED); setPadding(0, dp(18), 0, dp(18)) }); return }; items.forEachIndexed { i, t -> val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), 0, dp(9)); background = bg(SURFACE) }; val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; t.thumbnail?.let { ImageLoader.load(it, image) } ?: run { image.visibility = View.GONE }; row.addView(image, LinearLayout.LayoutParams(dp(48), dp(48))); row.addView(TextView(this).apply { text = "${t.title}\n${t.artist} · ${t.duration}"; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(Button(this).apply { text = "▶"; setOnClickListener { MarsMusicPlayer.playTrack(i, tracks) } }); results.addView(row, LinearLayout.LayoutParams(-1, dp(68)).apply { setMargins(0, dp(4), 0, 0) }) } }
    private fun fetch(url: String): JSONObject { val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 10000; return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() } }
    private fun parseTracks(o: JSONObject): List<MusicTrack> { val a = o.optJSONArray("tracks") ?: o.optJSONArray("results") ?: o.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray(); return (0 until a.length()).mapNotNull { val x = a.optJSONObject(it) ?: return@mapNotNull null; MusicTrack(x.optString("id", x.optString("track_id")), x.optString("title", "Без названия"), x.optString("artist", "Неизвестный артист"), x.optString("duration", "--:--"), x.optString("thumbnail").takeIf { it.isNotBlank() }) }.filter { it.id.isNotBlank() } }
    private fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, (ms / 1000) % 60)
    private fun bg(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(14).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object { private const val BASE_URL = "https://marscore.ru/music"; private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val MUTED = 0xffaab2c0.toInt() }
}
