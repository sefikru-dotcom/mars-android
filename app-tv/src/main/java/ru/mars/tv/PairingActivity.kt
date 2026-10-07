package ru.mars.tv

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.PairingStatus
import kotlin.coroutines.coroutineContext
import ru.mars.network.repository.PairingRepository

class PairingActivity : AppCompatActivity() {
    private lateinit var code: TextView
    private lateinit var timerText: TextView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val repository = PairingRepository(BASE_URL)
    private var operation: Job? = null
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        store = AndroidKeyStoreCredentialStore(this)
        code = findViewById(R.id.pairingCode)
        timerText = findViewById(R.id.pairingTimer)
        status = findViewById(R.id.pairingStatus)
        retry = findViewById(R.id.retryButton)
        retry.setOnClickListener { startPairing() }
        retry.requestFocus()
        startPairing()
    }

    override fun onDestroy() {
        operation?.cancel()
        timer?.cancel()
        super.onDestroy()
    }

    private fun startPairing() {
        operation?.cancel()
        timer?.cancel()
        retry.isEnabled = false
        code.text = "------"
        timerText.text = "Получение кода…"
        status.text = "Подключение к Марсу…"
        operation = lifecycleScope.launch {
            val created = repository.createPairing().getOrNull()
            if (created == null) {
                showError("Не удалось получить код. Проверьте сеть.")
                return@launch
            }
            code.text = created.code.chunked(3).joinToString(" ")
            retry.isEnabled = false
            timer = object : CountDownTimer(created.expiresIn * 1000L, 1000L) {
                override fun onTick(left: Long) {
                    timerText.text = "Код действителен %02d:%02d".format(left / 60_000, (left / 1000) % 60)
                }
                override fun onFinish() { showError("Код истёк. Нажмите OK, чтобы повторить") }
            }.also { it.start() }
            status.text = "Откройте marscore.ru/pair и введите код"
            poll(created.pairId, created.pollSecret, created.expiresIn)
        }
    }

    private suspend fun poll(pairId: String, pollSecret: String, expiresIn: Int) {
        val deadline = System.currentTimeMillis() + expiresIn * 1000L
        while (coroutineContext[Job]?.isActive == true && System.currentTimeMillis() < deadline) {
            val response = repository.pollPairingStatus(pairId, pollSecret).getOrNull()
            if (response == null) {
                showError("Ошибка сети. Нажмите OK, чтобы повторить")
                return
            }
            if (response.status == PairingStatus.CLAIMED || response.status == PairingStatus.APPROVED) {
                status.text = "Подключение подтверждено…"
                val credential = repository.exchangePairing(pairId, pollSecret).getOrNull()
                if (credential == null) {
                    showError("Не удалось завершить подключение. Повторите попытку")
                    return
                }
                store.save(credential)
                startActivity(Intent(this@PairingActivity, DashboardActivity::class.java))
                finish()
                return
            }
            delay(POLL_INTERVAL_MS)
        }
        if (coroutineContext[Job]?.isActive == true) showError("Код истёк. Нажмите OK, чтобы повторить")
    }

    private fun showError(message: String) {
        timer?.cancel()
        retry.isEnabled = true
        retry.requestFocus()
        status.text = message
        timerText.text = ""
    }

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
