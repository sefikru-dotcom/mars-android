package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.tv.cron.TvCronActivity
import ru.mars.tv.projects.TvProjectsActivity
import ru.mars.tv.todos.TvTodosActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(R.layout.activity_dashboard)
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
        findViewById<Button>(R.id.settingsButton).setOnClickListener { logout() }
        TvFocusLift.attachAll(
            findViewById(R.id.chatButton),
            findViewById(R.id.projectsButton),
            findViewById(R.id.todosButton),
            findViewById(R.id.musicButton),
            findViewById(R.id.cronButton),
            findViewById(R.id.settingsButton),
        )
        findViewById<Button>(R.id.chatButton).requestFocus()
        clock.run()
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
