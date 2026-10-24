package ru.mars.tv.kanban

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.KanbanRepository
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.kanban.TvKanbanPolicy.StatusToken
import ru.mars.tv.tvTextSize

/**
 * TV-экран «Канбан» (read-only паритет мобильного KanbanActivity): доска задач
 * Марса — GET /api/kanban/board + best-effort GET /api/kanban/stats
 * ([KanbanRepository] в core-network), автообновление раз в 30 с.
 *
 * СТРОГО READ-ONLY: нативный device-Bearer принимается сервером на /api/kanban/...
 * только для GET/HEAD (hermes-webui `NATIVE_BEARER_READONLY_...`) — никаких
 * dispatch/создания/переноса карточек на экране нет, плашка это озвучивает.
 *
 * Состояния экрана: loading / error / data. Пустая доска — валидное состояние
 * («На доске пока нет колонок», [TvKanbanPolicy.EMPTY_BOARD]), не ошибка.
 * [R.id.statusView] — accessibility live region (polite). D-pad: «Назад»/
 * «Обновить» в шапке, карточки фокусируемые ([TvFocusLift]), фокус
 * восстанавливается после автообновления; в ошибке фокус на «Повторить».
 *
 * Data/policy слои отделены от UI: [RepositoryTvKanbanGateway] (транспорт),
 * [TvKanbanPolicy] (чистые решения), [TvKanbanController] (оркестрация,
 * generation guard, кэш последнего удачного ответа, destroy) — покрыты JVM-тестами.
 *
 * Offline: при сетевом сбое экран рисует последний удачный снимок доски со
 * статусом [TvOfflineCacheStatus.CACHED] и плашкой возраста («Снимок из кэша
 * (N мин. назад)») вместо ошибки; явная ошибка — только когда показывать нечего.
 */
class TvKanbanActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var statsLine: TextView
    private lateinit var emptyText: TextView
    private lateinit var columnsContainer: LinearLayout
    private lateinit var controller: TvKanbanController

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentData: TvKanbanData? = null

    /** Плашка источника данных из контроллера (LIVE/CACHED); null — данные ещё не показывались. */
    private var currentBadge: String? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvKanbanSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: доска остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка доски…"
        }

        override fun showBoard(data: TvKanbanData, status: TvOfflineCacheStatus, badgeText: String?) {
            // CACHED рисуется как обычные данные: видимость та же, фокус не трогаем.
            hasData = true
            currentData = data
            currentBadge = badgeText
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последняя доска остаётся на экране; сбой объявлен live region'ом.
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
        setContentView(R.layout.activity_tv_kanban)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        statsLine = findViewById(R.id.statsLine)
        emptyText = findViewById(R.id.emptyText)
        columnsContainer = findViewById(R.id.columnsContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        findViewById<TextView>(R.id.noticeText).text = TvKanbanPolicy.NOTICE_READ_ONLY

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }

        controller = TvKanbanController(
            surface,
            RepositoryTvKanbanGateway(KanbanRepository(), MarsApi.BASE_URL) { store.read() },
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
        val data = currentData ?: return
        // Автообновление пересобирает доску: запоминаем позицию D-pad фокуса и
        // возвращаем её (clamp по [TvKanbanPolicy.restoreSelection], без wrap).
        val focusedIndex = columnsContainer.indexOfChild(columnsContainer.findFocus())
        columnsContainer.removeAllViews()

        // Live-регион: плашка источника + сводка («Всего N · старейшая ready: …»).
        val taskCount = TvKanbanPolicy.taskCount(data.board)
        val summary = TvKanbanPolicy.statsSummary(data.stats)
            ?: "Задач на доске: $taskCount"
        val badge = currentBadge
        statusView.text = if (badge != null) "$badge · $summary" else summary

        val stats = TvKanbanPolicy.statsLine(data.stats)
        if (stats != null) {
            statsLine.text = stats
            statsLine.visibility = View.VISIBLE
        } else {
            statsLine.visibility = View.GONE
        }

        val columns = TvKanbanPolicy.columns(data.board)
        val emptyLine = TvKanbanPolicy.emptyLine(data.board)
        if (emptyLine != null) {
            emptyText.text = emptyLine
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        columns.forEach { column ->
            columnsContainer.addView(columnHeader(column))
            if (column.cards.isEmpty()) {
                columnsContainer.addView(columnEmptyHint())
            } else {
                column.cards.forEach { card -> columnsContainer.addView(taskCard(card)) }
            }
        }

        if (focusedIndex >= 0) {
            val target = TvKanbanPolicy.restoreSelection(focusedIndex, columnsContainer.childCount)
            if (target != TvKanbanPolicy.NO_SELECTION) {
                columnsContainer.getChildAt(target)?.requestFocus()
            }
        }
    }

    /** Заголовок колонки: «Название · N» — структура доски читается даже без карточек. */
    private fun columnHeader(column: TvKanbanPolicy.ColumnGroup): View {
        val header = TextView(this)
        header.text = "${column.name} · ${column.count}"
        header.setTextColor(ContextCompat.getColor(this, R.color.mars_text_strong))
        header.tvTextSize(R.dimen.tv_text_title)
        header.setTypeface(null, android.graphics.Typeface.BOLD)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dpToPx(HEADER_MARGIN_TOP_DP)
        header.layoutParams = params
        return header
    }

    /** Пустая колонка: честное «Пусто» вместо дыры в списке. */
    private fun columnEmptyHint(): View {
        val hint = TextView(this)
        hint.text = TvKanbanPolicy.COLUMN_EMPTY_HINT
        hint.setTextColor(ContextCompat.getColor(this, R.color.mars_muted_on_glass))
        hint.tvTextSize(R.dimen.tv_text_body)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dpToPx(HINT_MARGIN_TOP_DP)
        hint.layoutParams = params
        return hint
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun taskCard(card: TvKanbanPolicy.CardRow): View {
        val view = layoutInflater.inflate(R.layout.item_tv_kanban_card, columnsContainer, false)
        val color = statusColor(card.token)
        view.findViewById<ImageView>(R.id.kanbanCardDot).setColorFilter(color)
        view.findViewById<TextView>(R.id.kanbanCardTitle).text = card.title
        val status = view.findViewById<TextView>(R.id.kanbanCardStatus)
        status.text = card.statusLabel
        status.setTextColor(color)
        view.findViewById<TextView>(R.id.kanbanCardMeta).text = card.metaLine
        val badges = view.findViewById<TextView>(R.id.kanbanCardBadges)
        if (card.badgesLine != null) {
            badges.text = card.badgesLine
            badges.visibility = View.VISIBLE
        } else {
            badges.visibility = View.GONE
        }
        TvFocusLift.attach(view)
        return view
    }

    /** Семантика точки: ready — синий, running/done — зелёный, blocked — красный, прочее — приглушённый. */
    private fun statusColor(token: StatusToken): Int = ContextCompat.getColor(
        this,
        when (token) {
            StatusToken.READY -> R.color.mars_status_info
            StatusToken.RUNNING, StatusToken.DONE -> R.color.mars_status_online
            StatusToken.BLOCKED -> R.color.mars_status_error
            StatusToken.NEUTRAL -> R.color.mars_muted_on_glass
        },
    )

    companion object {
        /** Интервал автообновления — паритет TvTodosActivity/TvProjectsActivity. */
        private const val REFRESH_MS = 30_000L

        /** Отступы заголовка/подсказки колонки в dp (конвертируются через density). */
        private const val HEADER_MARGIN_TOP_DP = 22
        private const val HINT_MARGIN_TOP_DP = 8
    }
}
