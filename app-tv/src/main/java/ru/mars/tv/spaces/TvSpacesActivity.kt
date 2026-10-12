package ru.mars.tv.spaces

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.WorkspacesResponse
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvOfflineCacheStatus

/**
 * TV-экран «Пространства» (read-only паритет мобильного SpacesActivity): каталог
 * рабочих пространств сервера — GET /api/workspaces ([WorkspaceRepository] в
 * core-network), автообновление раз в 60 с.
 *
 * СТРОГО READ-ONLY: маршрут входит в белый список нативного device-токена
 * (hermes-webui `NATIVE_BEARER_EXACT_PATHS`), мутации (/api/workspaces/add и пр.)
 * токеном не принимаются — экран показывает каталог, но не выбирает и не меняет
 * пространства; плашка это озвучивает.
 *
 * Состояния экрана: loading / error / data. Пустой каталог — валидное состояние
 * ([TvSpacesPolicy.EMPTY_LIST]), не ошибка. [R.id.statusView] — accessibility
 * live region (polite). D-pad: «Назад»/«Обновить» в шапке, строки фокусируемые
 * ([TvFocusLift]); в ошибке фокус на «Повторить».
 *
 * Data/policy слои отделены от UI: [RepositoryTvSpacesGateway] (транспорт),
 * [TvSpacesPolicy] (чистые решения), [TvSpacesController] (оркестрация,
 * generation guard, кэш последнего удачного ответа, destroy) — JVM-тесты.
 *
 * Offline: при сетевом сбое экран рисует последний удачный каталог со статусом
 * [TvOfflineCacheStatus.CACHED] и плашкой возраста вместо ошибки; явная ошибка —
 * только когда показывать нечего (кэш пуст или старше 24 ч).
 */
class TvSpacesActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var emptyText: TextView
    private lateinit var spacesContainer: LinearLayout
    private lateinit var controller: TvSpacesController

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentData: WorkspacesResponse? = null

    /** Плашка источника данных из контроллера (LIVE/CACHED); null — данные ещё не показывались. */
    private var currentBadge: String? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvSpacesSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: каталог остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка каталога…"
        }

        override fun showSpaces(response: WorkspacesResponse, status: TvOfflineCacheStatus, badgeText: String?) {
            // CACHED рисуется как обычные данные: видимость та же, фокус не трогаем.
            hasData = true
            currentData = response
            currentBadge = badgeText
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последний каталог остаётся на экране; сбой объявлен live region'ом.
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
        setContentView(R.layout.activity_tv_spaces)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        emptyText = findViewById(R.id.emptyText)
        spacesContainer = findViewById(R.id.spacesContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        findViewById<TextView>(R.id.noticeText).text = "Каталог только для чтения — выбор пространства доступен в мобильном клиенте"

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }

        controller = TvSpacesController(
            surface,
            RepositoryTvSpacesGateway(WorkspaceRepository(), MarsApi.BASE_URL) { store.read() },
        )
        TvFocusLift.attachAll(backButton, refreshButton, retryButton)
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    private fun render() {
        val response = currentData ?: return
        // Автообновление пересобирает список: запоминаем позицию D-pad фокуса и
        // возвращаем её (clamp по [TvSpacesPolicy.restoreSelection], без wrap).
        val focusedIndex = spacesContainer.indexOfChild(spacesContainer.findFocus())
        spacesContainer.removeAllViews()

        // Live-регион: плашка источника + счётчик каталога.
        val summary = TvSpacesPolicy.summaryLine(response)
        val badge = currentBadge
        statusView.text = if (badge != null) "$badge · $summary" else summary

        val rows = TvSpacesPolicy.rows(response)
        val emptyLine = TvSpacesPolicy.emptyLine(response)
        if (emptyLine != null) {
            emptyText.text = emptyLine
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        rows.forEach { row -> spacesContainer.addView(spaceRow(row)) }

        if (focusedIndex >= 0) {
            val target = TvSpacesPolicy.restoreSelection(focusedIndex, spacesContainer.childCount)
            if (target != TvSpacesPolicy.NO_SELECTION) {
                spacesContainer.getChildAt(target)?.requestFocus()
            }
        }
    }

    private fun spaceRow(row: TvSpacesPolicy.SpaceRow): View {
        val view = layoutInflater.inflate(R.layout.item_tv_space, spacesContainer, false)
        view.findViewById<TextView>(R.id.spaceName).text = row.name
        view.findViewById<TextView>(R.id.spacePath).text = row.path
        view.findViewById<TextView>(R.id.spaceBadge).visibility =
            if (row.defaultBadge) View.VISIBLE else View.GONE
        TvFocusLift.attach(view)
        return view
    }

    companion object {
        /** Интервал автообновления: каталог меняется редко — минута достаточна. */
        private const val REFRESH_MS = 60_000L
    }
}
