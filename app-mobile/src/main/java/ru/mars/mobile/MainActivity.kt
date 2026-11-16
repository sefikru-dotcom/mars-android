package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.PushTapRouting

class MainActivity : ComponentActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var repository: PairingRepository
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        repository = PairingRepository(BASE_URL)

        if (store.read() != null) {
            openChat()
            return
        }

        setContentView(buildView())
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(ROOT_ID)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(24), bars.top + dp(32), dp(24), bars.bottom + dp(24))
            insets
        }
        // Forced re-authentication from ChatActivity (server rejected the stored credential):
        // say why the login screen is back instead of showing a silent empty form.
        intent.getStringExtra(EXTRA_AUTH_NOTICE)?.takeIf { it.isNotBlank() }?.let { notice ->
            statusText.text = notice
            statusText.setTextColor(MarsTheme.STATUS_ERROR)
        }
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            id = ROOT_ID
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.SCREEN_VEIL)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logoIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.mars_planet))
            setPadding(0, dp(48), 0, dp(16))
        }
        content.addView(logoIcon, LinearLayout.LayoutParams(dp(72), dp(136)))

        val logoText = TextView(this).apply {
            text = "МАРС"
            textSize = 28f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        content.addView(logoText)

        val subtitle = TextView(this).apply {
            text = "Авторизация устройства"
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        }
        content.addView(subtitle)

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@MainActivity, MarsTheme.SURFACE, radiusDp = 14, strokeColor = MarsTheme.BORDER)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }

        passwordInput = EditText(this).apply {
            hint = "Пароль Марса"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT_STRONG)
            textSize = 16f
            gravity = Gravity.CENTER
            background = null
            setPadding(dp(8), dp(14), dp(8), dp(14))
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    performLogin()
                    true
                } else false
            }
        }
        inputContainer.addView(passwordInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2))
        content.addView(inputContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2))

        loginButton = Button(this).apply {
            text = "Войти"
            textSize = 16f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            background = MarsTheme.button(this@MainActivity, MarsTheme.ACCENT, radiusDp = 14)
            setOnClickListener { performLogin() }
        }
        val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(20)
        }
        content.addView(loginButton, btnParams)

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        content.addView(statusText)

        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }
        root.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return MarsSpaceBackgroundView.wrap(this, root)
    }

    private fun performLogin() {
        val password = passwordInput.text.toString().trim()
        if (password.isEmpty()) {
            statusText.text = "Введите пароль"
            statusText.setTextColor(MarsTheme.STATUS_ERROR)
            return
        }

        hideKeyboard()
        loginButton.isEnabled = false
        passwordInput.isEnabled = false
        statusText.text = "Авторизация…"
        statusText.setTextColor(MarsTheme.TEXT_MUTED)

        loginJob = lifecycleScope.launch {
            val result = repository.loginWithPassword(password, DeviceIdentity.stableDeviceName())
            if (result.isSuccess) {
                val cred = result.getOrNull()
                if (cred != null) {
                    store.save(cred)
                    openChat()
                    return@launch
                }
            }

            loginButton.isEnabled = true
            passwordInput.isEnabled = true
            statusText.text = "Неверный пароль или ошибка сети"
            statusText.setTextColor(MarsTheme.STATUS_ERROR)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(passwordInput.windowToken, 0)
    }

    /**
     * Открывает следующий экран после авторизации и забирает с собой контекст запуска.
     *
     * Экран логина — единственный, через который проходит запуск при
     * отсутствующем credential, и он же LAUNCHER-цель для системного тапа по
     * FCM-уведомлению, когда процесс убит. Пустой `Intent(this, ChatActivity)`
     * терял адресат диалога из `extras`/`data`, поэтому после входа человек
     * попадал в «Новый чат» вместо чата, о котором сообщил push.
     *
     * Отсюда две ветки: тап по пушу (или кнопка «Ответить» — [PushReplyPolicy.matchesAction])
     * ведёт прямо в диалог, а обычный вход/запуск — на страницу «Диалоги»
     * ([ChatSessionsActivity]), чтобы человек начинал с выбора диалога, а не с
     * пустого «Нового чата».
     */
    private fun openChat() {
        val sessionId = pushSessionId()
        val fromPush = sessionId != null || PushReplyPolicy.matchesAction(intent?.action)
        val target = if (fromPush) ChatActivity::class.java else ChatSessionsActivity::class.java
        val outgoing = Intent(this, target)
        if (fromPush) {
            intent?.extras?.let { outgoing.putExtras(it) }
            intent?.data?.let { outgoing.data = it }
            sessionId?.let { outgoing.putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, it) }
        }
        startActivity(outgoing)
        finish()
    }

    /**
     * Адресат push-тапа из текущего интента; null — обычный запуск.
     *
     * Ключи читаются «сырыми» (`session_id`, `url`, `tag`): системный трей
     * переносит data-payload FCM без переименования. Разбор — общий с
     * заставкой и чатом, чтобы все три точки понимали один и тот же payload
     * одинаково.
     */
    private fun pushSessionId(): String? = PushTapRouting.extractSessionId(
        explicitExtra = intent?.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID),
        sessionId = intent?.getStringExtra(KEY_SESSION_ID)
            ?: intent?.getStringExtra(PushReplyPolicy.EXTRA_SESSION_ID),
        url = intent?.getStringExtra(KEY_URL) ?: intent?.dataString,
        tag = intent?.getStringExtra(KEY_TAG),
    )

    private fun dp(value: Int) = MarsTheme.dp(this, value)

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val ROOT_ID = 2001
        /** Intent extra (String): notice to show in the status line, e.g. after a forced re-auth. */
        const val EXTRA_AUTH_NOTICE = "auth_notice"

        /**
         * Ключи data-payload FCM как их переносит системный трей в extras
         * LAUNCHER-интента (`session_id`, `url`, `tag` — без переименования).
         */
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_URL = "url"
        private const val KEY_TAG = "tag"
    }
}
