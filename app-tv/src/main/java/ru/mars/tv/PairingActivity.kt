package ru.mars.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.PairingRepository

class PairingActivity : AppCompatActivity() {
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val repository = PairingRepository(BASE_URL)
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)

        if (store.read() != null) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_pairing)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.pairingStatus)

        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                performLogin()
                true
            } else false
        }

        loginButton.setOnClickListener { performLogin() }
        loginButton.requestFocus()
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }

    private fun performLogin() {
        val password = passwordInput.text.toString().trim()
        if (password.isEmpty()) {
            statusText.text = "Введите пароль"
            statusText.setTextColor(Color.parseColor("#ff7b72"))
            passwordInput.requestFocus()
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(passwordInput.windowToken, 0)

        loginButton.isEnabled = false
        passwordInput.isEnabled = false
        statusText.text = "Подключение к Марсу…"
        statusText.setTextColor(Color.parseColor("#aab2c0"))

        loginJob = lifecycleScope.launch {
            val result = repository.loginWithPassword(password, "mi_tv")
            if (result.isSuccess) {
                val cred = result.getOrNull()
                if (cred != null) {
                    store.save(cred)
                    startActivity(Intent(this@PairingActivity, DashboardActivity::class.java))
                    finish()
                    return@launch
                }
            }

            loginButton.isEnabled = true
            passwordInput.isEnabled = true
            statusText.text = "Неверный пароль или ошибка сети"
            statusText.setTextColor(Color.parseColor("#ff7b72"))
            passwordInput.requestFocus()
        }
    }

    companion object {
        private const val BASE_URL = "https://marscore.ru"
    }
}
