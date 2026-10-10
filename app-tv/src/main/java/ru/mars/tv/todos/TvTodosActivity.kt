package ru.mars.tv.todos

import android.graphics.Paint
import android.graphics.Typeface
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
import ru.mars.network.repository.TodosRepository
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.todos.TvTodosPolicy.TodosFilter
import ru.mars.tv.todos.TvTodosPolicy.StatusToken

/**
 * TV-экран «Задачи» (паритет мобильного TodosActivity): todo_state текущей сессии
 * Марса — GET /api/sessions, затем GET /api/session с `todo_state`
 * ([TodosRepository] в core-network), автообновление раз в 30 с.
 *
 * Состояния экрана: loading / error / data. Пустые состояния честные и различимые
 * («задачи не ставились», «пустой список», «сессия не найдена») — копию выбирает
 * [TvTodosPolicy], Activity только рисует. [R.id.statusView] — accessibility live
 * region (polite). D-pad: «Назад»/«Обновить» в шапке, ряд фильтров (left/right),
 * карточки задач фокусируемые ([TvFocusLift]), фокус восстанавливается после
 * автообновления и смены фильтра; в ошибке фокус переводится на «Повторить».
 *
 * Data/policy слои отделены от UI: [RepositoryTvTodosGateway] (транспорт),
 * [TvTodosPolicy] (чистые решения), [TvTodosController] (оркестрация, generation
 * guard, destroy) — покрыты JVM-тестами.
 */
class TvTodosActivity : AppCompatActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var loadingGroup: View
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var dataScroll: View
    private lateinit var emptyText: TextView
    private lateinit var todosContainer: LinearLayout
    private lateinit var chips: List<Button>

    private val handler = Handler(Looper.getMainLooper())
    private var hasData = false
    private var currentData: TvTodosData? = null
    private var filter = TodosFilter.ALL
    private lateinit var controller: TvTodosController

    private val refreshTick = object : Runnable {
        override fun run() {
            controller.load()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvTodosSurface {
        override fun showLoading() {
            if (hasData) {
                // Фоновое обновление: список остаётся на экране, live region объявит статус.
                statusView.text = "Обновление…"
                return
            }
            loadingGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.GONE
            statusView.text = "Загрузка задач…"
        }

        override fun showTodos(data: TvTodosData) {
            hasData = true
            currentData = data
            loadingGroup.visibility = View.GONE
            errorGroup.visibility = View.GONE
            dataScroll.visibility = View.VISIBLE
            render()
        }

        override fun showError(message: String) {
            statusView.text = message
            if (hasData) {
                // Последние данные остаются на экране; сбой объявлен live region'ом.
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
        setContentView(R.layout.activity_tv_todos)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        statusView = findViewById(R.id.statusView)
        loadingGroup = findViewById(R.id.loadingGroup)
        errorGroup = findViewById(R.id.errorGroup)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        dataScroll = findViewById(R.id.dataScroll)
        emptyText = findViewById(R.id.emptyText)
        todosContainer = findViewById(R.id.todosContainer)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        chips = listOf(
            findViewById(R.id.filterAll),
            findViewById(R.id.filterInProgress),
            findViewById(R.id.filterCompleted),
            findViewById(R.id.filterPending),
        )

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { controller.load() }
        retryButton.setOnClickListener { controller.load() }
        chips.forEachIndexed { index, chip -> chip.setOnClickListener { selectFilter(index) } }

        controller = TvTodosController(
            surface,
            RepositoryTvTodosGateway(TodosRepository(), MarsApi.BASE_URL) { store.read() },
        )
        TvFocusLift.attachAll(backButton, refreshButton, retryButton, *chips.toTypedArray())
        selectFilter(0)
        backButton.requestFocus()

        controller.load()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    /** Переключение фильтра — локальное решение ([TvTodosPolicy]); перезагрузка не нужна. */
    private fun selectFilter(index: Int) {
        filter = TodosFilter.entries[index]
        chips.forEachIndexed { i, chip -> chip.isSelected = i == index }
        if (hasData) render()
    }

    private fun render() {
        val data = currentData ?: return
        // Автообновление и смена фильтра пересобирают список: запоминаем позицию D-pad
        // фокуса и возвращаем её (clamp по [TvTodosPolicy.restoreSelection], без wrap).
        val focusedIndex = todosContainer.indexOfChild(todosContainer.findFocus())
        todosContainer.removeAllViews()
        statusView.text = TvTodosPolicy.summaryLine(data.sessionTitle, data.snapshot)

        val rows = TvTodosPolicy.rows(data.snapshot?.todos.orEmpty(), filter)
        val emptyLine = TvTodosPolicy.emptyStateLine(data.sessionTitle, data.snapshot, filter)
        if (emptyLine != null) {
            emptyText.text = emptyLine
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }
        rows.forEach { row -> todosContainer.addView(todoCard(row)) }

        val first = todosContainer.getChildAt(0)
        if (first != null) {
            if (first.id == View.NO_ID) first.id = View.generateViewId()
            // Явная D-pad цепочка: вверх из списка — на выбранный чип фильтра.
            first.nextFocusUpId = chips[filter.ordinal].id
        }
        if (focusedIndex >= 0) {
            val target = TvTodosPolicy.restoreSelection(focusedIndex, todosContainer.childCount)
            if (target != TvTodosPolicy.NO_SELECTION) todosContainer.getChildAt(target)?.requestFocus()
        }
    }

    private fun todoCard(row: TvTodosPolicy.TodoRow): View {
        val card = layoutInflater.inflate(R.layout.item_tv_todo, todosContainer, false)
        val color = statusColor(row.token)
        card.findViewById<ImageView>(R.id.todoDot).setColorFilter(color)
        val content = card.findViewById<TextView>(R.id.todoContent)
        content.text = row.content
        content.paintFlags = if (row.struckThrough) {
            content.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            content.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        content.setTypeface(null, if (row.emphasized) Typeface.BOLD else Typeface.NORMAL)
        val badge = card.findViewById<TextView>(R.id.todoBadge)
        badge.text = row.badge
        badge.setTextColor(color)
        TvFocusLift.attach(card)
        return card
    }

    /** Семантика точки: done — зелёный, in_progress — синий, ожидание/отмена — приглушённый. */
    private fun statusColor(token: StatusToken): Int = ContextCompat.getColor(
        this,
        when (token) {
            StatusToken.DONE -> R.color.mars_status_online
            StatusToken.ACTIVE -> R.color.mars_status_info
            StatusToken.WAITING, StatusToken.CANCELLED -> R.color.mars_muted
        },
    )

    companion object {
        /** Интервал автообновления — паритет TvProjectsActivity. */
        private const val REFRESH_MS = 30_000L
    }
}
