package ru.mars.tv

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ChatRepository

class TvChatActivity : AppCompatActivity() {
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private var stream: Job? = null
    private val store by lazy { AndroidKeyStoreCredentialStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_chat)
        messages = findViewById(R.id.messages)
        input = findViewById(R.id.messageInput)
        send = findViewById(R.id.sendButton)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        input.requestFocus()
    }

    override fun onDestroy() {
        stream?.cancel()
        super.onDestroy()
    }

    private fun sendMessage(): Boolean {
        val text = input.text.toString().trim()
        if (text.isEmpty() || stream?.isActive == true) return false
        val credential = store.read() ?: run { finish(); return false }
        input.text.clear()
        append("Стеф", text)
        val answer = append("Марс", "")
        send.isEnabled = false
        stream = lifecycleScope.launch {
            try {
                ChatRepository().sendMessage(BASE_URL, credential, text).collect { event ->
                    when (event) {
                        is ChatEvent.Chunk -> { answer.text = "Марс: ${answer.text.toString().removePrefix("Марс: ")}${event.text}"; scrollDown() }
                        ChatEvent.Done -> Unit
                        is ChatEvent.Error -> answer.text = "Марс: Ошибка: ${event.message}"
                    }
                }
            } finally {
                send.isEnabled = true
                input.requestFocus()
            }
        }
        return true
    }

    private fun append(author: String, text: String): TextView = TextView(this).apply {
        this.text = "$author: $text"
        textSize = 22f
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setBackgroundColor(SURFACE)
        isFocusable = true
        messages.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun scrollDown() { findViewById<ScrollView>(R.id.messageScroll).post { findViewById<ScrollView>(R.id.messageScroll).fullScroll(View.FOCUS_DOWN) } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val SURFACE = 0xff1b202b.toInt()
    }
}
