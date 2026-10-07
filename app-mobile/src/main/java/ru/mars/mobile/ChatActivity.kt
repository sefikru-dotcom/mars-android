package ru.mars.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.model.ChatEvent

class ChatActivity : ComponentActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private var stream: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
    }

    override fun onDestroy() {
        stream?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply { text = "Чат с Марсом"; textSize = 22f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Выйти"; setOnClickListener { leaveChat() } })
        root.addView(header)
        val scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply { hint = "Сообщение…"; setTextColor(Color.WHITE); setHintTextColor(MUTED); setSingleLine(true) }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = Button(this).apply { text = "Отправить"; setOnClickListener { sendMessage() } }
        composer.addView(send)
        root.addView(composer)
        return root
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || stream?.isActive == true) return
        val credential = store.read() ?: run { leaveChat(); return }
        input.text.clear()
        append("Вы", text)
        val answer = append("Марс", "")
        send.isEnabled = false
        stream = lifecycleScope.launch {
            ChatRepository().sendMessage(CHAT_ENDPOINT, credential, text).collect { event ->
                when (event) {
                    is ChatEvent.Chunk -> answer.append(event.text)
                    ChatEvent.Done -> Unit
                    is ChatEvent.Error -> answer.append("\nОшибка: ${event.message}")
                }
            }
            send.isEnabled = true
        }
    }

    private fun append(author: String, text: String): TextView {
        val view = TextView(this).apply {
            this.text = "$author: $text"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(SURFACE)
        }
        messages.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return view
    }

    private fun leaveChat() {
        stream?.cancel()
        lifecycleScope.launch {
            val credential = store.read()
            if (credential != null) {
                val result = PairingRepository(BASE_URL).revokeCredential(credential.deviceId, credential.accessToken)
                if (result.isFailure) return@launch
                store.clear()
            }
            finish()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val CHAT_ENDPOINT = "$BASE_URL/api/v1/chat"
        private const val BACKGROUND = 0xff10131a.toInt()
        private const val SURFACE = 0xff1b202b.toInt()
        private const val MUTED = 0xffaab2c0.toInt()
    }
}
