package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var artistView: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: ImageButton
    private lateinit var artwork: ImageView
    private var tracks = emptyList<MusicTrack>()
    private var job: Job? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (::seek.isInitialized) {
                seek.progress = MarsMusicPlayer.currentPosition
                time.text = "${fmt(MarsMusicPlayer.currentPosition)} / ${fmt(MarsMusicPlayer.duration)}"
                seek.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        repository = MusicRepository(BASE_URL)
        setContentView(buildView())
        MarsMusicPlayer.registerListener(this)
        seek.post(ticker)
        loadHome()
    }

    override fun onDestroy() {
        MarsMusicPlayer.unregisterListener(this)
        seek.removeCallbacks(ticker)
        job?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val backBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_arrow_back,
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
        root.addView(header)

        // Search Bar (Floating Capsule)
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 14)
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        val searchInput = EditText(this).apply {
            hint = "Поиск трека или исполнителя…"
            setSingleLine()
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 15f
            background = null
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        searchContainer.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))

        val searchBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_search,
            "Найти",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 36
        ).apply {
            setOnClickListener { search(searchInput.text.toString()) }
        }
        searchContainer.addView(searchBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(searchContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Track list
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                addView(results, ViewGroup.LayoutParams(-1, -2))
                isFillViewport = true
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Bottom Player Bar
        val playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_CARD, radiusDp = 18, strokeColor = MarsTheme.BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 8)
            clipToOutline = true
        }
        trackRow.addView(artwork, LinearLayout.LayoutParams(dp(52), dp(52)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            text = "Ничего не играет"
            textSize = 15f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(title)

        artistView = TextView(this).apply {
            text = "Mars Audio"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        info.addView(artistView)

        trackRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        playerPanel.addView(trackRow)

        // Progress Seek Bar
        seek = SeekBar(this).apply {
            max = 1
            progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) {
                    if (u) MarsMusicPlayer.seekTo(p)
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        playerPanel.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val timeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        time = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
        }
        timeRow.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        playerPanel.addView(timeRow)

        // Controls (Previous, Play/Pause, Next)
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        val prevBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_prev,
            "Предыдущий трек",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 42
        ).apply {
            setOnClickListener { MarsMusicPlayer.previous() }
        }
        controls.addView(prevBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        playButton = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_play,
            "Воспроизведение",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 50,
            strokeColor = 0
        ).apply {
            setOnClickListener { MarsMusicPlayer.togglePlay() }
        }
        val playParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        controls.addView(playButton, playParams)

        val nextBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_skip_next,
            "Следующий трек",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 42
        ).apply {
            setOnClickListener { MarsMusicPlayer.next() }
        }
        controls.addView(nextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        playerPanel.addView(controls)
        root.addView(playerPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        return root
    }

    override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) {
        runOnUiThread {
            title.text = track?.title ?: "Ничего не играет"
            artistView.text = track?.artist ?: "Mars Audio"
            playButton.setImageDrawable(
                ContextCompat.getDrawable(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            )
            seek.max = MarsMusicPlayer.duration.coerceAtLeast(1)
            track?.thumbnail?.let {
                artwork.visibility = View.VISIBLE
                ImageLoader.load(it, artwork)
            } ?: run {
                artwork.visibility = View.GONE
            }
        }
    }

    override fun onProgress(currentMs: Int, totalMs: Int) {
        runOnUiThread {
            seek.max = totalMs.coerceAtLeast(1)
            seek.progress = currentMs
            time.text = "${fmt(currentMs)} / ${fmt(totalMs)}"
        }
    }

    private fun loadHome() {
        job = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { fetch("$BASE_URL/api/charts") }.getOrNull()
                    ?: runCatching { fetch("$BASE_URL/api/home") }.getOrNull()
            }
            found?.let { showTracks(parseTracks(it)) }
        }
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        job?.cancel()
        job = lifecycleScope.launch {
            runCatching { repository.search(q) }
                .onSuccess { showTracks(it) }
                .onFailure { title.text = "Поиск недоступен" }
        }
    }

    private fun showTracks(items: List<MusicTrack>) {
        tracks = items
        results.removeAllViews()
        if (items.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "Треки не найдены"
                setTextColor(MarsTheme.TEXT_MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            })
            return
        }

        items.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE, radiusDp = 12)
                setOnClickListener { MarsMusicPlayer.playTrack(i, tracks) }
            }

            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = MarsTheme.card(this@MusicActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 6)
                clipToOutline = true
            }
            val thumb = t.thumbnail
            if (!thumb.isNullOrBlank()) {
                ImageLoader.load(thumb, image)
            }
            row.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))

            val trackInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            trackInfo.addView(TextView(this).apply {
                text = t.title
                textSize = 15f
                setTextColor(MarsTheme.TEXT_STRONG)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            trackInfo.addView(TextView(this).apply {
                text = "${t.artist} · ${t.duration}"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))

            val playTrackBtn = MarsTheme.circleIconButton(
                this,
                R.drawable.ic_play,
                "Воспроизвести",
                bg = MarsTheme.SURFACE_SUBTLE,
                iconTint = MarsTheme.ACCENT,
                sizeDp = 34
            ).apply {
                setOnClickListener { MarsMusicPlayer.playTrack(i, tracks) }
            }
            row.addView(playTrackBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

            results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun fetch(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() }
    }

    private fun parseTracks(o: JSONObject): List<MusicTrack> {
        val a = o.optJSONArray("tracks") ?: o.optJSONArray("results") ?: o.optJSONObject("data")?.optJSONArray("tracks") ?: JSONArray()
        return (0 until a.length()).mapNotNull {
            val x = a.optJSONObject(it) ?: return@mapNotNull null
            MusicTrack(
                x.optString("id", x.optString("track_id")),
                x.optString("title", "Без названия"),
                x.optString("artist", "Неизвестный артист"),
                x.optString("duration", "--:--"),
                x.optString("thumbnail").takeIf { s -> s.isNotBlank() }
            )
        }.filter { it.id.isNotBlank() }
    }

    private fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, (ms / 1000) % 60)
    private fun dp(v: Int) = MarsTheme.dp(this, v)

    companion object {
        private const val BASE_URL = "https://marscore.ru/music"
    }
}
