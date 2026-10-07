package ru.mars.mobile

import androidx.activity.ComponentActivity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.PairingStatus
import ru.mars.network.repository.PairingRepository

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var action: Button
    private lateinit var revoke: Button
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var repository: PairingRepository
    private val flow = PairingFlowState()
    private var operation: Job? = null
    private var pairing: PairingData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        repository = PairingRepository(BASE_URL)
        setContentView(buildView())
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(ROOT_ID)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(24), bars.top + dp(24), dp(24), bars.bottom + dp(24))
            insets
        }
        store.read()?.let { flow.restoreConnected(); showConnected() }
    }

    override fun onDestroy() {
        operation?.cancel()
        operation = null
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { id = ROOT_ID; orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 19, 26)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(text(R.string.pairing_title, 26f, Color.WHITE))
        content.addView(text(R.string.pairing_description, 16f, MUTED, 12))
        code = text(R.string.code_empty, 36f, Color.WHITE, 28).apply { gravity = Gravity.CENTER }
        content.addView(code)
        status = text(R.string.status_not_connected, 16f, MUTED, 16)
        content.addView(status)
        action = Button(this).apply { setText(R.string.start_pairing); setOnClickListener { startPairing() } }
        content.addView(action, params(WRAP, 56, 24))
        revoke = Button(this).apply { setText(R.string.revoke); visibility = View.GONE; setOnClickListener { revokeCredential() } }
        content.addView(revoke, params(WRAP, 56, 8))
        val scroll = ScrollView(this).apply { addView(content, ViewGroup.LayoutParams(-1, -2)); isFillViewport = true }
        root.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        return root
    }

    private fun startPairing() {
        if (!flow.beginCreate()) return
        operation?.cancel()
        action.isEnabled = false
        status.setText(R.string.status_creating)
        code.setText(R.string.code_empty)
        operation = lifecycleScope.launch {
            val result = repository.createPairing()
            if (result.isFailure) { fail(R.string.error_unavailable); return@launch }
            val created = result.getOrNull() ?: run { fail(R.string.error_unavailable); return@launch }
            pairing = PairingData(created.pairId, created.pollSecret, created.expiresIn)
            flow.created()
            code.text = created.code
            status.setText(R.string.status_open_browser)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$BASE_URL/pair")))
            pollUntilClaimed()
        }
    }

    private suspend fun pollUntilClaimed() {
        val current = pairing ?: return
        val deadline = System.currentTimeMillis() + current.expiresIn * 1000L
        while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            val result = repository.pollPairingStatus(current.pairId, current.pollSecret)
            if (result.isFailure) { fail(R.string.error_unavailable); return }
            val response = result.getOrNull() ?: return
            if (response.status == PairingStatus.CLAIMED || response.status == PairingStatus.APPROVED) {
                exchange(current)
                return
            }
            delay(POLL_INTERVAL_MS)
        }
        if (currentCoroutineContext().isActive) fail(R.string.error_timeout)
    }

    private suspend fun exchange(current: PairingData) {
        if (!flow.beginExchange()) return
        status.setText(R.string.status_finishing)
            val result = repository.exchangePairing(current.pairId, current.pollSecret)
            if (result.isFailure) { fail(R.string.error_unavailable); return }
            val credential = result.getOrNull() ?: run { fail(R.string.error_unavailable); return }
            store.save(credential)
            flow.connected()
            showConnected()
    }

    private fun revokeCredential() {
        if (!flow.beginRevoke()) return
        val credential = store.read()
        if (credential == null) { flow.revoked(); showDisconnected(); return }
        operation?.cancel()
        action.isEnabled = false
        status.setText(R.string.status_revoking)
        operation = lifecycleScope.launch {
            val result = repository.revokeCredential(credential.deviceId, credential.accessToken)
            if (result.isSuccess) {
                store.clear()
                flow.revoked()
                showDisconnected()
            } else {
                flow.restoreConnected()
                revoke.isEnabled = true
                status.setText(R.string.error_revoke)
            }
        }
    }

    private fun showConnected() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
    private fun showDisconnected() { action.visibility = View.VISIBLE; action.isEnabled = true; revoke.visibility = View.GONE; code.setText(R.string.code_empty); status.setText(R.string.status_not_connected) }
    private fun fail(message: Int) { flow.failed(); action.isEnabled = true; status.setText(message) }

    private fun text(res: Int, size: Float, color: Int, top: Int = 0) = TextView(this).apply { setText(res); textSize = size; setTextColor(color); layoutParams = params(WRAP, WRAP, top) }
    private fun params(width: Int, height: Int, top: Int = 0) = LinearLayout.LayoutParams(width, if (height == WRAP) WRAP else dp(height)).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private data class PairingData(val pairId: String, val pollSecret: String, val expiresIn: Int)
    companion object { private const val BASE_URL = "https://marscore.ru"; private const val POLL_INTERVAL_MS = 2000L; private const val ROOT_ID = 1001; private const val WRAP = -2; private const val MUTED = 0xffaab2c0.toInt() }
}
