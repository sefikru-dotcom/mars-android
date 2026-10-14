package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.tv.cron.TvCronActivity
import ru.mars.tv.insights.TvInsightsActivity
import ru.mars.tv.kanban.TvKanbanActivity
import ru.mars.tv.memory.TvMemoryActivity
import ru.mars.tv.projects.TvProjectsActivity
import ru.mars.tv.skills.TvSkillsActivity
import ru.mars.tv.spaces.TvSpacesActivity
import ru.mars.tv.todos.TvTodosActivity
import java.util.Date

/**
 * TV dashboard: the entry screen. Two live layers over the navigation grid:
 *
 * - CLOCK HEADER — big HH:mm plus a muted weekday+date line ([TvDashboardClockSpec]
 *   owns the formats); refreshed every 30 s, callbacks removed in onDestroy.
 * - NOW PLAYING PLATE — the global transport ([TvMusicPlayer]) keeps playing across
 *   screens, so the dashboard mirrors it exactly like the chat header mini player
 *   (shared pure rules: [TvMiniPlayerSpec]): visible only while a track is bound,
 *   caption «title — artist», and a D-pad-focusable Play/Pause toggle. The plate joins
 *   the focus grid (UP from «Чат», DOWN back), and hiding it never drops the focus —
 *   the grid below keeps its own stable chains.
 */
class DashboardActivity : AppCompatActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            val now = Date()
            findViewById<TextView>(R.id.time)?.text = TvDashboardClockSpec.timeText(now)
            findViewById<TextView>(R.id.dateLine)?.text = TvDashboardClockSpec.dateText(now)
            handler.postDelayed(this, 30_000L)
        }
    }

    private lateinit var nowPlayingRow: View
    private lateinit var nowPlayingText: TextView
    private lateinit var nowPlayingToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        // FCM push (волна 4): регистрация токена на сервере при входе на дашборд
        // (credential уже прочитан выше) + запрос POST_NOTIFICATIONS на Android 13+.
        ru.mars.tv.push.TvPushTokenSync.syncNow(this)
        ru.mars.tv.push.TvPushNotifications.ensurePostPermission(this)
        setContentView(R.layout.activity_dashboard)
        // Resuming the transport from the dashboard toggle needs the application context
        // (audio focus + wake mode live there — same entry the chat/music screens use).
        TvMusicPlayer.attachContext(this)
        nowPlayingRow = findViewById(R.id.nowPlayingRow)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        nowPlayingToggle = findViewById(R.id.nowPlayingToggle)
        nowPlayingToggle.setOnClickListener {
            TvMusicPlayer.togglePlayPause()
            renderNowPlaying()
        }
        findViewById<Button>(R.id.chatButton).setOnClickListener {
            startActivity(Intent(this, TvChatActivity::class.java))
        }
        findViewById<Button>(R.id.projectsButton).setOnClickListener {
            startActivity(Intent(this, TvProjectsActivity::class.java))
        }
        findViewById<Button>(R.id.todosButton).setOnClickListener {
            startActivity(Intent(this, TvTodosActivity::class.java))
        }
        findViewById<Button>(R.id.musicButton).setOnClickListener {
            startActivity(Intent(this, TvMusicActivity::class.java))
        }
        findViewById<Button>(R.id.cronButton).setOnClickListener {
            startActivity(Intent(this, TvCronActivity::class.java))
        }
        findViewById<Button>(R.id.kanbanButton).setOnClickListener {
            startActivity(Intent(this, TvKanbanActivity::class.java))
        }
        findViewById<Button>(R.id.insightsButton).setOnClickListener {
            startActivity(Intent(this, TvInsightsActivity::class.java))
        }
        findViewById<Button>(R.id.spacesButton).setOnClickListener {
            startActivity(Intent(this, TvSpacesActivity::class.java))
        }
        findViewById<Button>(R.id.memoryButton).setOnClickListener {
            startActivity(Intent(this, TvMemoryActivity::class.java))
        }
        findViewById<Button>(R.id.skillsButton).setOnClickListener {
            startActivity(Intent(this, TvSkillsActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { logout() }
        TvFocusLift.attachAll(
            findViewById(R.id.chatButton),
            findViewById(R.id.projectsButton),
            findViewById(R.id.todosButton),
            findViewById(R.id.musicButton),
            findViewById(R.id.cronButton),
            findViewById(R.id.kanbanButton),
            findViewById(R.id.insightsButton),
            findViewById(R.id.spacesButton),
            findViewById(R.id.memoryButton),
            findViewById(R.id.skillsButton),
            findViewById(R.id.settingsButton),
            nowPlayingToggle,
        )
        findViewById<Button>(R.id.chatButton).requestFocus()
        clock.run()
    }

    /**
     * Mirrors the global transport into the plate (same onResume/onPause slot contract
     * as the chat header — screens are exclusive, the last resumed one owns the mirror).
     */
    override fun onResume() {
        super.onResume()
        TvMusicPlayer.onTransportChanged = { runOnUiThread { renderNowPlaying() } }
        renderNowPlaying()
    }

    override fun onPause() {
        TvMusicPlayer.onTransportChanged = null
        super.onPause()
    }

    /** Plate rendering — every decision (visibility/caption/toggle) is [TvMiniPlayerSpec]'s. */
    private fun renderNowPlaying() {
        val track = TvMusicPlayer.currentTrack
        val visible = TvMiniPlayerSpec.visible(track)
        // Hiding a plate that holds the focus would drop it; the grid recovers it instead.
        if (!visible && nowPlayingToggle.hasFocus()) {
            findViewById<View>(R.id.chatButton)?.requestFocus()
        }
        nowPlayingRow.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        nowPlayingText.text = TvMiniPlayerSpec.line(track)
        val playing = TvMusicPlayer.isPlaying
        nowPlayingToggle.text = TvMiniPlayerSpec.toggleLabel(playing)
        nowPlayingToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
        nowPlayingToggle.compoundDrawableTintList =
            ContextCompat.getColorStateList(this, R.color.icon_focus_tint)
    }

    private fun logout() {
        store.clear()
        startActivity(Intent(this, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clock)
        super.onDestroy()
    }
}
