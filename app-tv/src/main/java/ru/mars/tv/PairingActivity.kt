package ru.mars.tv

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView

class PairingActivity : Activity() {
    private lateinit var timerText: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        timerText = findViewById(R.id.pairingTimer)
        findViewById<TextView>(R.id.pairingCode).text = "482 917"
        object : CountDownTimer(300_000, 1_000) {
            override fun onTick(ms: Long) { timerText.text = "Код действителен %02d:%02d".format(ms / 60_000, (ms / 1_000) % 60) }
            override fun onFinish() { timerText.text = "Код истёк — запросите новый"; findViewById<TextView>(R.id.pairingStatus).text = "Нажмите OK, чтобы повторить" }
        }.start()
    }
}
