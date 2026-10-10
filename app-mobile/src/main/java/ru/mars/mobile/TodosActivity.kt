package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.repository.SessionRepository

class TodosActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }
    private val sessionRepo = SessionRepository()

    private lateinit var todoList: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyText: TextView

    private var currentFilter: String = "ALL" // ALL, IN_PROGRESS, COMPLETED, PENDING
    private var allItems: List<TodoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        val sessionId = intent.getStringExtra("EXTRA_SESSION_ID")
        loadTodos(sessionId)
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
            setPadding(0, 0, 0, dp(10))
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
            text = "Задачи агента"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        // Summary row
        summaryText = TextView(this).apply {
            text = "Загрузка плана задач…"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(4), 0, 0, dp(10))
        }
        root.addView(summaryText)

        // Filter chips
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(10))
        }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val filters = listOf(
            "ALL" to "Все",
            "IN_PROGRESS" to "В процессе",
            "COMPLETED" to "Выполнены",
            "PENDING" to "В очереди"
        )

        filters.forEach { (key, label) ->
            val chip = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                val isSelected = currentFilter == key
                background = MarsTheme.button(
                    this@TodosActivity,
                    if (isSelected) MarsTheme.ACCENT else MarsTheme.SURFACE,
                    radiusDp = 10,
                    strokeColor = if (isSelected) 0 else MarsTheme.BORDER
                )
                setTextColor(if (isSelected) Color.WHITE else MarsTheme.TEXT)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    currentFilter = key
                    renderTodos()
                }
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        filterScroll.addView(filterRow)
        root.addView(filterScroll)

        // Progress bar
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)).apply { bottomMargin = dp(8) })

        // Empty state
        emptyText = TextView(this).apply {
            text = "Задачи не запланированы"
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(32), 0, 0)
        }
        root.addView(emptyText)

        // List
        todoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(todoList, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        return root
    }

    private fun loadTodos(sessionId: String?) {
        val cred = store.read() ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val snapshot = if (!sessionId.isNullOrBlank()) {
                val detail = sessionRepo.getSessionDetail(ChatActivity.BASE_URL, cred, sessionId)
                detail?.todoState
            } else {
                // If no specific session, get the most recent session's todos
                val sessions = sessionRepo.getSessions(ChatActivity.BASE_URL, cred)
                val active = sessions.firstOrNull { it.activeTurn != null } ?: sessions.firstOrNull()
                if (active != null) {
                    sessionRepo.getSessionDetail(ChatActivity.BASE_URL, cred, active.id)?.todoState
                } else null
            }
            progressBar.visibility = View.GONE
            allItems = snapshot?.todos.orEmpty()
            updateSummary(snapshot)
            renderTodos()
        }
    }

    private fun updateSummary(snapshot: TodoSnapshot?) {
        if (snapshot == null || snapshot.todos.isEmpty()) {
            summaryText.text = "В текущей сессии задачи не ставились"
            return
        }
        val total = snapshot.todos.size
        val done = snapshot.todos.count { it.status == "completed" }
        summaryText.text = "Выполнено $done из $total задач (${(done * 100 / total.coerceAtLeast(1))}%)"
    }

    private fun renderTodos() {
        todoList.removeAllViews()

        val filtered = when (currentFilter) {
            "IN_PROGRESS" -> allItems.filter { it.status == "in_progress" }
            "COMPLETED" -> allItems.filter { it.status == "completed" }
            "PENDING" -> allItems.filter { it.status == "pending" }
            else -> allItems
        }

        if (filtered.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (allItems.isEmpty()) "Задачи отсутствуют" else "Нет задач с выбранным статусом"
            return
        }
        emptyText.visibility = View.GONE

        filtered.forEach { item ->
            val isDone = item.status == "completed"
            val inProgress = item.status == "in_progress"

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = MarsTheme.card(
                    this@TodosActivity,
                    if (inProgress) MarsTheme.SURFACE_CARD else MarsTheme.SURFACE,
                    radiusDp = 10,
                    strokeColor = if (inProgress) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            val iconDot = MarsTheme.glowingStatusDot(
                this,
                when {
                    isDone -> MarsTheme.STATUS_ONLINE
                    inProgress -> MarsTheme.STATUS_RUNNING
                    else -> MarsTheme.TEXT_MUTED
                }
            )
            card.addView(iconDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })

            val textView = TextView(this).apply {
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1f)
                if (isDone) {
                    val ssb = SpannableStringBuilder(item.content)
                    ssb.setSpan(StrikethroughSpan(), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = ssb
                    setTextColor(MarsTheme.TEXT_MUTED)
                } else {
                    text = item.content
                    setTextColor(if (inProgress) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
                    if (inProgress) setTypeface(null, Typeface.BOLD)
                }
            }
            card.addView(textView, LinearLayout.LayoutParams(0, -2, 1f))

            val badge = TextView(this).apply {
                text = when {
                    isDone -> "Готово"
                    inProgress -> "В работе"
                    item.status == "cancelled" -> "Отменено"
                    else -> "Ожидает"
                }
                textSize = 11f
                setTextColor(if (inProgress) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = MarsTheme.pill(this@TodosActivity, MarsTheme.SURFACE_0, strokeColor = 0)
            }
            card.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })

            todoList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
