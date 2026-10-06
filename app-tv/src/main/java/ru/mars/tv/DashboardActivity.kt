package ru.mars.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable { override fun run() { findViewById<TextView>(R.id.time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); handler.postDelayed(this, 30_000) } }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_dashboard); findViewById<TextView>(R.id.chatButton).requestFocus(); clock.run() }
    override fun onDestroy() { handler.removeCallbacks(clock); super.onDestroy() }
}
