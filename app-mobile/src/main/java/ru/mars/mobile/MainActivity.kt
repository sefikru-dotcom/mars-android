package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.PairingRepository

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
            view.setPadding(dp(24), bars.top + dp(24), dp(24), bars.bottom + dp(24))
            insets
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
            setBackgroundColor(COLOR_BG)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = TextView(this).apply {
            text = "МАРС"
            textSize = 32f
            setTextColor(COLOR_ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(8))
        }
        content.addView(logo)

        val subtitle = TextView(this).apply {
            text = "Авторизация устройства"
            textSize = 16f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(36))
        }
        content.addView(subtitle)

        passwordInput = EditText(this).apply {
            hint = "Пароль Марса"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHintTextColor(COLOR_MUTED)
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    performLogin()
                    true
                } else false
            }
            background = GradientDrawable().apply {
                setColor(COLOR_SURFACE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xff2f384a.toInt())
            }
        }
        val inputParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(16)
        }
        content.addView(passwordInput, inputParams)

        loginButton = Button(this).apply {
            text = "Войти"
            textSize = 17f
            setTextColor(COLOR_BG)
            background = GradientDrawable().apply {
                setColor(COLOR_ACCENT)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { performLogin() }
        }
        val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(20)
        }
        content.addView(loginButton, btnParams)

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        content.addView(statusText)

        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }
        root.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun performLogin() {
        val password = passwordInput.text.toString().trim()
        if (password.isEmpty()) {
            statusText.text = "Введите пароль"
            statusText.setTextColor(COLOR_ERROR)
            return
        }

        hideKeyboard()
        loginButton.isEnabled = false
        passwordInput.isEnabled = false
        statusText.text = "Вход…"
        statusText.setTextColor(COLOR_MUTED)

        loginJob = lifecycleScope.launch {
            val result = repository.loginWithPassword(password, "poco_f5")
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
            statusText.setTextColor(COLOR_ERROR)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(passwordInput.windowToken, 0)
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BASE_URL = "https://marscore.ru"
        private const val ROOT_ID = 2001
        private const val COLOR_BG = 0xff10131a.toInt()
        private const val COLOR_SURFACE = 0xff1b202b.toInt()
        private const val COLOR_ACCENT = 0xff7cb9ff.toInt()
        private const val COLOR_MUTED = 0xffaab2c0.toInt()
        private const val COLOR_ERROR = 0xffff7b72.toInt()
    }
}
