package ru.mars.tv.memory

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvOfflineCacheStatus

/**
 * TV-экран «Память» (read-only паритет мобильного MemoryActivity): секции
 * MEMORY.md / USER.md / SOUL.md / проектного контекста из GET /api/memory,
 * автообновление раз в 60 с.
 *
 * СТРОГО READ-ONLY: маршрут входит в белый список нативного device-токена
 * (hermes-webui `NATIVE_BEARER_EXACT_PATHS`), мутация /api/memory/write токеном
 * не принимается — экран показывает секции, но не правит их; плашка это
 * озвучивает.
 *
 * Состояния экрана: loading / error / data. Пустая секция — валидное состояние
 * ([TvMemoryPolicy.emptyLine]), не ошибка. [R.id.statusView] — accessibility
 * live region (polite). D-pad: «Назад»/«Обновить» в шапке, табы секций
 * left/right, контент — фокусируемый ScrollView (листается D-pad-ом); в ошибке
 * фокус на «Повторить».
 *
 * Data/policy слои отделены от UI: [HttpTvMemoryGateway] (транспорт),
 * [TvMemoryPolicy] (чистые решения), [TvMemoryController] (оркестрация,
 * generation guard, кэш последнего удачного ответа, destroy) — JVM-тесты.
 *
 * Offline: при сетевом сбое экран рисует последний удачный снимок со статусом
 * [TvOfflineCacheStatus.CACHED] и плашкой возраста вместо ошибки; явная ошибка —
 * только когда показывать нечего (кэш пуст или старше 24 ч).
 */
class TvMemoryActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var bodyText: TextView
    private lateinit var metaText: TextView
    private lateinit var controller: TvMemoryController

    private val tabButtons = LinkedHashMap<TvMemoryTab, Button>()
    private var selectedTab = TvMemoryTab.MEMORY

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentData: TvMemorySnapshot? = null

    /** Плашка источника данных из контроллера (LIVE/CACHED); null — данные ещё не показывались. */
    private var currentBadge: String? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvMemorySurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: секция остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка памяти…"
        }

        override fun showMemory(snapshot: TvMemorySnapshot, status: TvOfflineCacheStatus, badgeText: String?) {
            // CACHED рисуется как обычные данные: видимость та же, фокус не трогаем.
            hasData = true
            currentData = snapshot
            currentBadge = badgeText
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последняя память остаётся на экране; сбой объявлен live region'ом.
                return
            }
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.VISIBLE
            dataScroll.visibility = View.GONE
            errorText.text = message
            backButton.nextFocusDownId = R.id.retryButton
            retryButton.requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_memory)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        bodyText = findViewById(R.id.bodyText)
        metaText = findViewById(R.id.metaText)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        findViewById<TextView>(R.id.noticeText).text =
            "Раздел только для чтения — правки памяти доступны в мобильном клиенте"

        tabButtons[TvMemoryTab.MEMORY] = findViewById(R.id.tabMemory)
        tabButtons[TvMemoryTab.USER] = findViewById(R.id.tabUser)
        tabButtons[TvMemoryTab.SOUL] = findViewById(R.id.tabSoul)
        tabButtons[TvMemoryTab.PROJECT] = findViewById(R.id.tabProject)
        tabButtons.forEach { (tab, button) ->
            button.text = tab.label
            button.setOnClickListener { selectTab(tab) }
        }
        renderTabs()

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }

        controller = TvMemoryController(
            surface,
            HttpTvMemoryGateway(MarsApi.BASE_URL) { store.read() },
        )
        TvFocusLift.attachAll(backButton, refreshButton, retryButton, *tabButtons.values.toTypedArray())
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    private fun selectTab(tab: TvMemoryTab) {
        if (tab == selectedTab) return
        selectedTab = tab
        renderTabs()
        render()
    }

    /** Выбранный таб — selected-состояние чипа (рубиновый фон bg_focus_chip). */
    private fun renderTabs() {
        tabButtons.forEach { (tab, button) -> button.isSelected = tab == selectedTab }
        // Вверх из контента — всегда в активный таб, а не в первый.
        dataScroll.nextFocusUpId = tabButtons.getValue(selectedTab).id
    }

    private fun render() {
        val snapshot = currentData ?: return

        // Live-регион: плашка источника + счётчик заполненных секций.
        val summary = TvMemoryPolicy.summaryLine(snapshot)
        val badge = currentBadge
        statusView.text = if (badge != null) "$badge · $summary" else summary

        metaText.text = TvMemoryPolicy.metaLine(selectedTab, snapshot, System.currentTimeMillis())
        // Пустая секция — валидное состояние: показываем её строку вместо текста.
        val body = TvMemoryPolicy.bodyText(selectedTab, snapshot)
        bodyText.text = body.ifEmpty { TvMemoryPolicy.emptyLine(selectedTab, snapshot).orEmpty() }
    }

    companion object {
        /** Интервал автообновления: память меняется редко — минута достаточна. */
        private const val REFRESH_MS = 60_000L
    }
}
