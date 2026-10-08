package ru.mars.tv

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        TvFocusLift.attachAll(input, send, findViewById(R.id.backButton))
        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        input.requestFocus()
    }

    override fun onDestroy() {
        stream?.cancel()
        super.onDestroy()
    }

    private var currentSessionId: String? = null

    private fun sendMessage(): Boolean {
        val text = input.text.toString().trim()
        if (text.isEmpty() || stream?.isActive == true) return false
        val credential = store.read() ?: return false
        input.text.clear()
        append("Вы", text, isUser = true)
        val answer = append("Марс", "", isUser = false)
        send.isEnabled = false
        stream = lifecycleScope.launch {
            try {
                ChatRepository().sendMessage(BASE_URL, credential, text, currentSessionId).collect { event ->
                    when (event) {
                        is ChatEvent.SessionBound -> currentSessionId = event.sessionId
                        is ChatEvent.Chunk -> {
                            answer.text = "${answer.text.toString().removePrefix("Марс: ")}${event.text}"
                            scrollDown()
                        }
                        ChatEvent.Done -> Unit
                        is ChatEvent.Error -> answer.text = "Ошибка: ${event.message}"
                        else -> Unit
                    }
                }
            } finally {
                send.isEnabled = true
                input.requestFocus()
            }
        }
        return true
    }

    /**
     * WebUI-parity chat bubble: user side gets the deep-navy bubble
     * (USER_BUBBLE + USER_BORDER), Mars side gets the bot card
     * (BOT_CARD + BOT_BORDER). Both are focusable — under D-pad focus the
     * bg_bubble_* StateListDrawable cross-fades to a ruby border.
     */
    private fun append(author: String, text: String, isUser: Boolean): TextView = TextView(this).apply {
        this.text = "$author: $text"
        textSize = 22f
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
        isFocusable = true
        TvFocusLift.attach(this)
        messages.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun scrollDown() { findViewById<ScrollView>(R.id.messageScroll).post { findViewById<ScrollView>(R.id.messageScroll).fullScroll(View.FOCUS_DOWN) } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
    }
}
