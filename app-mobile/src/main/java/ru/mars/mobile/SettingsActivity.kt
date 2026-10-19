package ru.mars.mobile

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
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
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.http.HttpClients
import ru.mars.network.repository.ServerHealthRepository
import java.io.File

/**
 * Экран настроек приложения: сервер и связь (проба боевого хоста), локальный кэш
 * (реальная очистка и честный замер объёма), сведения о приложении (факты
 * устройства/credential/пакета, без захардкоженных имён) и выход из профиля.
 *
 * Решения вынесены в чистые политики — [SettingsCachePolicy] (набор кэшей, размер,
 * формат), [SettingsInfoPolicy] (строки профиль/клиент/сервер) и
 * [ConnectionStatusPolicy] (тексты вердикта пробы); поведение покрыто JVM-тестами
 * (`SettingsCachePolicyTest`, `SettingsInfoPolicyTest`, `SettingsSourceContractTest`),
 * экран только собирает UI и вызывает их.
 */
class SettingsActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }

    /**
     * Cleanup локальных plaintext-данных (черновики, история музыки, офлайн-кэши
     * сессий/задач/проектов, очередь неотправленных) — единая фабрика [AuthExitStores],
     * тот же набор хранилищ, что и на других путях выхода из авторизации.
     */
    private val authExitCleanup by lazy { AuthExitStores.cleanup(this) }

    /**
     * Проба лёгкости/задержки боевого хоста Марса. Путь `health` публичный
     * (см. `ConnectionStatusPolicy.PROBE_PATH`), поэтому токен для проверки
     * связи не нужен.
     */
    private val healthRepository by lazy {
        ServerHealthRepository(client = HttpClients.rest)
    }
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
        val credential = runCatching { store.read() }.getOrNull()
        val serverCard = buildCard().apply {
            // Хост — из единого источника MarsApi.BASE_URL, а не из литерала экрана.
            addView(buildInfoRow("Хост Марса", SettingsInfoPolicy.serverLabel(MarsApi.BASE_URL)))
            addView(
                buildInfoRow(
                    "Идентификатор устройства",
                    credential?.deviceId?.let { SettingsInfoPolicy.shortId(it) } ?: "—",
                ),
            )
            pingStatusText = TextView(this@SettingsActivity).apply {
                text = ConnectionStatusPolicy.PROBING
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

            // Повторная диагностика: проба лёгкая и публичная (health без токена),
            // перезапуск безопасен и позволяет перепроверить связь без перезахода на экран.
            val recheckBtn = Button(this@SettingsActivity).apply {
                text = "Проверить ещё раз"
                textSize = 13f
                setTextColor(MarsTheme.ACCENT_TEXT)
                background = MarsTheme.button(this@SettingsActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 8, strokeColor = MarsTheme.BORDER)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { checkConnection() }
            }
            addView(recheckBtn, LinearLayout.LayoutParams(-1, dp(40)).apply {
                setMargins(dp(12), dp(8), dp(12), dp(12))
            })

            // Профили сервера — единственный вход в read-only экран профилей
            // (паритет с панелью профилей WebUI; переключение профиля не выполняется).
            addView(buildNavRow(getString(R.string.settings_profiles_open), getString(R.string.settings_profiles_hint)) {
                startActivity(Intent(this@SettingsActivity, ProfilesActivity::class.java))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
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
                text = "Офлайн-кэш и временные файлы"
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
                setTextColor(MarsTheme.TEXT_STRONG)
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
            addView(buildInfoRow("Версия", appVersionLabel()))
            addView(buildInfoRow("Дизайн-система", "Obsidian Depth"))
            // Реальные факты устройства/credential/пакета вместо захардкоженных имён.
            addView(
                buildInfoRow(
                    "Клиент",
                    SettingsInfoPolicy.clientLabel(Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL),
                ),
            )
            addView(buildInfoRow("Профиль", SettingsInfoPolicy.profileLabel(credential?.deviceId)))
            addView(buildInfoRow("Пакет", packageName))
        }
        content.addView(aboutCard)

        // SECTION: Выход
        val logoutRed = MarsTheme.ACCENT
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

    /**
     * Строка-переход: подпись слева, действие справа, кликабельна вся строка.
     * Раньше в настройках переходы делались кнопками во всю ширину — здесь
     * строка, чтобы вход в экран профилей не выглядел основным действием.
     */
    private fun buildNavRow(label: String, hint: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = "$label. $hint"
        setOnClickListener { onClick() }

        addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        addView(TextView(this@SettingsActivity).apply {
            text = hint
            textSize = 13f
            setTextColor(MarsTheme.ACCENT_TEXT)
            setTypeface(null, Typeface.BOLD)
        })
    }

    /**
     * Размер локального кэша: файлы cacheDir И prefs-файлы офлайн-кэшей
     * ([SettingsCachePolicy.CACHE_PREFS_FILES]). Раньше измерялся только cacheDir,
     * а реальные офлайн-данные живут в SharedPreferences — строка показывала объём,
     * не связанный с настоящим содержимым кэшей.
     */
    private fun updateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFileSizes = runCatching {
                cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.toList()
            }.getOrDefault(emptyList())
            val prefsDir = File(applicationInfo.dataDir, SettingsCachePolicy.SHARED_PREFS_DIR)
            val prefsSizes = SettingsCachePolicy.CACHE_PREFS_FILES.map { name ->
                val file = File(prefsDir, SettingsCachePolicy.prefsFileName(name))
                if (file.isFile) runCatching { file.length() }.getOrDefault(0L) else 0L
            }
            val label = SettingsCachePolicy.formatSize(
                SettingsCachePolicy.totalSizeBytes(cacheFileSizes + prefsSizes),
            )
            withContext(Dispatchers.Main) {
                cacheSizeText.text = label
            }
        }
    }

    /**
     * «Очистить кэш» стирает РЕАЛЬНЫЕ кэши приложения:
     *  - офлайн-копии диалогов/задач/проектов/знаний и очередь неотправленных —
     *    prefs-хранилища из [SettingsCachePolicy.CACHE_PREFS_FILES], построенные
     *    единой фабрикой [AuthExitStores.defaultStore] (очередь чистится своим
     *    ключом по контракту `PrefsPendingMessageStore`);
     *  - временные файлы cacheDir;
     *  - process-кэши: битмапы миниатюр [ImageLoader.trimMemoryCache] и разбор
     *    Markdown [MarkdownFormatter.trimCache] (восстанавливаются лениво).
     *
     * Черновики композитора и история поиска музыки — введённый пользователем
     * текст, а не кэш: они сохраняются. Credential устройства очисткой кэша не
     * стирается — единственный путь [performLogout]/[LogoutFlow].
     */
    private fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            SettingsCachePolicy.CACHE_PREFS_FILES.forEach { name ->
                runCatching { AuthExitStores.defaultStore(this@SettingsActivity, name).clearAll() }
            }
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            ImageLoader.trimMemoryCache()
            MarkdownFormatter.trimCache()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "Кэш очищен успешно", Toast.LENGTH_SHORT).show()
                updateCacheSize()
            }
        }
    }

    private fun checkConnection() {
        lifecycleScope.launch {
            pingStatusText.text = ConnectionStatusPolicy.PROBING
            val report = healthRepository.checkHealth(
                endpointPath = ConnectionStatusPolicy.PROBE_PATH,
            )
            pingStatusText.text = ConnectionStatusPolicy.statusText(report.status, report.latencyMs)
            val dotColor = if (ConnectionStatusPolicy.isOnline(report.status)) {
                MarsTheme.STATUS_ONLINE
            } else {
                MarsTheme.TEXT_MUTED
            }
            pingStatusDot.background = MarsTheme.statusDot(this@SettingsActivity, dotColor).background
            pingStatusText.setTextColor(dotColor)
        }
    }

    /**
     * Версия берётся из манифеста, а не из литерала: раньше строка «О приложении»
     * была захардкожена и отставала от реальной сборки (показывала v1.2.0 (12)
     * при работающей 1.4.0 (24)).
     */
    private fun appVersionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "Марс v${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("Марс")

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Действительно выйти из профиля Марса на этом устройстве?")
            .setPositiveButton("Выйти") { _, _ -> performLogout() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Единственный путь logout (продуктовое правило: выход только из настроек).
     *
     * Порядок фиксирован контрактом [AuthExitCleanup]/[LogoutFlow]:
     *  1. локальный plaintext-cleanup синхронно ДО finish() — следующая авторизация на
     *     этом устройстве не видит данные предыдущей;
     *  2. server-side revoke best-effort через существующий контракт
     *     [ru.mars.network.repository.PairingRepository.revokeCredential]; его исход не
     *     влияет на выход — [LogoutFlow] чистит локальный credential безусловно
     *     (401/сеть не могут запереть клиента в сломанном auth-состоянии);
     *  3. переход на экран авторизации с очисткой стека (прежний контракт intent-флагов).
     */
    private fun performLogout() {
        authExitCleanup.run()
        lifecycleScope.launch {
            AuthExitStores.logoutFlow(store, MarsApi.BASE_URL).logout()
            val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
