package ru.mars.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import java.io.File

class SettingsActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }
    private lateinit var cacheSizeText: TextView
    private lateinit var pingStatusText: TextView
    private lateinit var pingStatusDot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        updateCacheSize()
        checkConnection()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(16))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val backBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_arrow_back,
            "Назад",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40,
            strokeColor = MarsTheme.BORDER
        ).apply {
            setOnClickListener { finish() }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this).apply {
            text = "Настройки"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        // Scrollable content
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }

        // SECTION: Сервер и подключение
        content.addView(buildSectionHeader("СЕРВЕР И СВЯЗЬ"))
        val serverCard = buildCard().apply {
            addView(buildInfoRow("Хост Марса", "https://marscore.ru"))
            val cred = store.read()
            val deviceId = cred?.deviceId ?: "Не привязано"
            addView(buildInfoRow("Идентификатор устройства", deviceId.take(16) + if (deviceId.length > 16) "…" else ""))
            pingStatusText = TextView(this@SettingsActivity).apply {
                text = "Проверка соединения…"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(dp(8), dp(8), dp(12), dp(10))
            }
            pingStatusDot = MarsTheme.statusDot(this@SettingsActivity, MarsTheme.TEXT_MUTED)
            val pingRow = LinearLayout(this@SettingsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(pingStatusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
                addView(pingStatusText, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(pingRow)
        }
        content.addView(serverCard)

        // SECTION: Кэш и память устройства
        content.addView(buildSectionHeader("ДАННЫЕ И КЭШ"))
        val cacheCard = buildCard().apply {
            val cacheRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val cacheLabel = TextView(this@SettingsActivity).apply {
                text = "Локальный кэш треков и сессий"
                textSize = 14f
                setTextColor(MarsTheme.TEXT_STRONG)
            }
            cacheRow.addView(cacheLabel, LinearLayout.LayoutParams(0, -2, 1f))

            cacheSizeText = TextView(this@SettingsActivity).apply {
                text = "Расчёт…"
                textSize = 13f
                setTextColor(MarsTheme.ACCENT_TEXT)
                setTypeface(null, Typeface.BOLD)
            }
            cacheRow.addView(cacheSizeText)
            addView(cacheRow)

            val clearCacheBtn = Button(this@SettingsActivity).apply {
                text = "Очистить кэш"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = MarsTheme.button(this@SettingsActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 8, strokeColor = MarsTheme.BORDER)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { clearCache() }
            }
            addView(clearCacheBtn, LinearLayout.LayoutParams(-1, dp(40)).apply {
                setMargins(dp(12), dp(4), dp(12), dp(12))
            })
        }
        content.addView(cacheCard)

        // SECTION: О приложении
        content.addView(buildSectionHeader("О ПРИЛОЖЕНИИ"))
        val aboutCard = buildCard().apply {
            addView(buildInfoRow("Версия", "Марс v1.2.0 (12)"))
            addView(buildInfoRow("Дизайн-система", "Obsidian Depth"))
            addView(buildInfoRow("Клиент", "Android / HyperOS (POCO F5)"))
            addView(buildInfoRow("Профиль", "Stef (Ставрополь)"))
        }
        content.addView(aboutCard)

        // SECTION: Выход
        val logoutRed = 0xFFEF4444.toInt()
        val logoutBtn = Button(this).apply {
            text = "Выйти из профиля"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(logoutRed)
            background = MarsTheme.button(this@SettingsActivity, MarsTheme.SURFACE, radiusDp = 10, strokeColor = logoutRed)
            setOnClickListener { confirmLogout() }
        }
        content.addView(logoutBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(24) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildSectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(MarsTheme.TEXT_MUTED)
        letterSpacing = 0.08f
        setPadding(dp(4), dp(16), 0, dp(6))
    }

    private fun buildCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = MarsTheme.card(this@SettingsActivity, MarsTheme.SURFACE, radiusDp = 12, strokeColor = MarsTheme.BORDER)
    }

    private fun buildInfoRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))

        val labelView = TextView(this@SettingsActivity).apply {
            text = label
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
        }
        addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))

        val valueView = TextView(this@SettingsActivity).apply {
            text = value
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            setTypeface(null, Typeface.BOLD)
        }
        addView(valueView)
    }

    private fun updateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheBytes = cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val sizeMb = String.format(java.util.Locale.US, "%.1f МБ", cacheBytes.toDouble() / (1024 * 1024))
            withContext(Dispatchers.Main) {
                cacheSizeText.text = sizeMb
            }
        }
    }

    private fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "Кэш успешно очищен", Toast.LENGTH_SHORT).show()
                updateCacheSize()
            }
        }
    }

    private fun checkConnection() {
        lifecycleScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val ok = runCatching {
                val url = java.net.URL("http://127.0.0.1:8787/api/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                code in 200..299
            }.getOrDefault(false)
            val elapsed = System.currentTimeMillis() - startTime
            withContext(Dispatchers.Main) {
                if (ok) {
                    pingStatusText.text = "Онлайн · Пинг $elapsed мс"
                    pingStatusDot.background = MarsTheme.statusDot(this@SettingsActivity, MarsTheme.STATUS_ONLINE).background
                    pingStatusText.setTextColor(MarsTheme.STATUS_ONLINE)
                } else {
                    pingStatusText.text = "Связь нестабильна или локальный шлюз"
                    pingStatusDot.background = MarsTheme.statusDot(this@SettingsActivity, MarsTheme.TEXT_MUTED).background
                    pingStatusText.setTextColor(MarsTheme.TEXT_MUTED)
                }
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Действительно выйти из профиля Марса на этом устройстве?")
            .setPositiveButton("Выйти") { _, _ ->
                store.clear()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
