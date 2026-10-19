package ru.mars.mobile

import android.content.Context
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.cache.TodosOfflineCache
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.repository.SessionRepository

/**
 * Экран плана задач агента (wire-снимок `todo_state` сессии). Строится на общем shell
 * разделов ([SectionScaffold]: safe-area, заголовок «Задачи агента», «Назад», спиннер,
 * «Обновить»), пустое состояние и сбой без кэша — общие [SectionStateViews]; экран
 * рисует только сводку, баннер кэша, фильтр-чипы и карточки задач.
 *
 * Офлайн-семантика прежняя: при старте мгновенно показывается последний снимок
 * ([TodosOfflineCache], бейдж возраста без баннера), сеть обновляет его в фоне и
 * СОХРАНЯЕТ; сбой сети не затирает показанные задачи — экран остаётся на кэше с
 * бейджем и ненавязчивым баннером ([OfflineCacheUiPolicy]). И «Обновить» shell, и
 * retry общего error-состояния повторяют загрузку ТЕКУЩЕЙ сессии (currentSessionId);
 * no-auth путь (нет credentials) — прежний уход в сбой с той же кэш-логикой.
 */
class TodosActivity : ComponentActivity() {

    private val store by lazy { AndroidKeyStoreCredentialStore(this) }
    private val sessionRepo = SessionRepository()

    /**
     * Офлайн-кэш снимков todo_state (core-network): при старте мгновенно показываем
     * последний снимок, сеть обновляет его в фоне; сбой сети не затирает задачи —
     * экран остаётся на кэше с бейджем возраста (политика [OfflineCacheUiPolicy]).
     */
    private val offlineCache by lazy {
        TodosOfflineCache(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(TODOS_OFFLINE_PREFS, Context.MODE_PRIVATE),
            ),
        )
    }

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var todoList: LinearLayout
    private lateinit var cacheBanner: TextView

    /** Контейнер общих состояний раздела (пусто/сбой) — между чипами и списком. */
    private lateinit var stateContainer: LinearLayout

    /** Сводка экрана («Выполнено X из Y…») — общая статус-строка shell. */
    private val summaryText: TextView get() = scaffold.statusLine

    private var currentFilter: TodoFilter = TodoFilter.ALL

    /** Чипы фильтров по статусу; подсветка активного перерисовывается на каждый тап. */
    private val filterChips = LinkedHashMap<TodoFilter, Button>()
    private var allItems: List<TodoItem> = emptyList()
    private var currentSessionId: String? = null

    /** На экране сейчас данные из кэша (переживает сбой сети, не затирается). */
    private var cacheOnScreen = false

    /** Слот кэша (sessionId), из которого взят ПОКАЗАННЫЙ снимок; null — снимка нет. */
    private var cacheSlot: String? = null

    /** Guard: «Обновить» shell и retry не должны запускать параллельные загрузки. */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        val sessionId = intent.getStringExtra("EXTRA_SESSION_ID")
        currentSessionId = sessionId
        showCachedSnapshot(sessionId)
        loadTodos(sessionId)
    }

    /** Мгновенный показ последнего снимка из кэша до ответа сети (бейдж возраста, без баннера). */
    private fun showCachedSnapshot(sessionId: String?) {
        val snapshot = offlineCache.loadTodos(sessionId) ?: return
        allItems = snapshot.todos
        cacheOnScreen = true
        cacheSlot = sessionId
        updateSummary(snapshot)
        renderTodos()
        applyDecision(decisionFor(OfflineFetchState.PENDING, livePayloadEmpty = false))
    }

    private fun buildView(): View {
        // Общий shell разделов: safe-area, заголовок, «Назад», спиннер и «Обновить»
        // (перезагрузка задач текущей сессии).
        scaffold = SectionScaffold.create(this, "Задачи агента", onRefresh = { loadTodos(currentSessionId) })

        // Сводка сеанса — статус-строка shell (всегда видна: экран показывает статус).
        summaryText.visibility = View.VISIBLE
        summaryText.text = "Загрузка плана задач…"
        summaryText.setTextColor(MarsTheme.TEXT_MUTED)

        // Ненавязчивый баннер кэша: бейдж возраста + отметка о сбое обновления.
        cacheBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.STATUS_WARNING)
            background = MarsTheme.pill(this@TodosActivity, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        scaffold.content.addView(cacheBanner, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })

        // Filter chips
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(10))
        }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        filterChips.clear()
        TodoFilterPolicy.CHIPS.forEach { filter ->
            val chip = Button(this).apply {
                text = filter.label
                textSize = 12f
                isAllCaps = false
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { selectFilter(filter) }
            }
            applyChipStyle(chip, selected = filter == currentFilter)
            filterChips[filter] = chip
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        filterScroll.addView(filterRow)
        scaffold.content.addView(filterScroll)

        // Общие состояния раздела (пусто/сбой) — вместо самодельного emptyText.
        stateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scaffold.content.addView(stateContainer, LinearLayout.LayoutParams(-1, -2))

        // List
        todoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(todoList, LinearLayout.LayoutParams(-1, -2))
        }
        scaffold.content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        return scaffold.root
    }

    private fun loadTodos(sessionId: String?) {
        if (loading || isFinishing) return
        val cred = store.read()
        if (cred == null) {
            onFetchFailure()
            return
        }

        loading = true
        // Кэш уже показан (showCachedSnapshot) — запрос идёт в фоне, не затирая его.
        scaffold.progress.visibility = if (cacheOnScreen) View.GONE else View.VISIBLE
        lifecycleScope.launch {
            val snapshot = runCatching {
                if (!sessionId.isNullOrBlank()) {
                    sessionRepo.getSessionDetail(ChatActivity.BASE_URL, cred, sessionId)?.todoState
                } else {
                    // If no specific session, get the most recent session's todos
                    val sessions = sessionRepo.getSessions(ChatActivity.BASE_URL, cred)
                    val active = sessions.firstOrNull { it.activeTurn != null } ?: sessions.firstOrNull()
                    currentSessionId = active?.id
                    if (active != null) {
                        sessionRepo.getSessionDetail(ChatActivity.BASE_URL, cred, active.id)?.todoState
                    } else null
                }
            }.getOrNull()
            loading = false
            scaffold.progress.visibility = View.GONE
            if (snapshot != null) onFetchSuccess(currentSessionId, snapshot) else onFetchFailure()
        }
    }

    /** Сеть отработала: сохраняем снимок в кэш и переводим экран в LIVE (бейдж/баннер скрыты). */
    private fun onFetchSuccess(slotSessionId: String?, snapshot: TodoSnapshot) {
        offlineCache.saveTodos(slotSessionId, snapshot)
        allItems = snapshot.todos
        cacheOnScreen = false
        cacheSlot = slotSessionId
        updateSummary(snapshot)
        renderTodos()
        applyDecision(decisionFor(OfflineFetchState.SUCCESS, livePayloadEmpty = snapshot.todos.isEmpty()))
    }

    /**
     * Сбой сети: если кэш уже на экране — задачи НЕ затираем, лишь подсвечиваем
     * ненавязчивым баннером «работаем из кэша, обновление не удалось»; кэша нет —
     * честный экран ошибки.
     */
    private fun onFetchFailure() {
        val decision = decisionFor(OfflineFetchState.FAILURE, livePayloadEmpty = false)
        cacheOnScreen = decision.showCachedContent
        applyDecision(decision)
        if (!decision.showCachedContent) {
            allItems = emptyList()
            renderTodos()
            showFailureState()
            summaryText.text = "Нет сети, а сохранённый снимок отсутствует"
        }
    }

    /** Общее error-состояние: «Повторить» перезагружает ТЕКУЩУЮ сессию (currentSessionId). */
    private fun showFailureState() {
        stateContainer.removeAllViews()
        stateContainer.addView(
            SectionStateViews.error(this, "Не удалось загрузить задачи") { loadTodos(currentSessionId) }
        )
    }

    /**
     * Решение политики по текущим фактам экрана: фаза запроса + возраст снимка в кэше
     * (null — кэша нет или он битый; показывать ли снимок — решает политика).
     */
    private fun decisionFor(state: OfflineFetchState, livePayloadEmpty: Boolean): OfflineUiDecision =
        OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.TODOS,
            fetchState = state,
            livePayloadEmpty = livePayloadEmpty,
            snapshotTakenAtMs = offlineCache.getSnapshotTimestamp(cacheSlot ?: currentSessionId),
            nowMs = System.currentTimeMillis(),
        )

    /** Рендер решения политики: видимость и текст ненавязчивого баннера кэша. */
    private fun applyDecision(decision: OfflineUiDecision) {
        val badge = decision.badgeText
        val banner = decision.bannerText
        if (badge == null && banner == null) {
            cacheBanner.visibility = View.GONE
        } else {
            cacheBanner.visibility = View.VISIBLE
            cacheBanner.text = listOfNotNull(badge, banner).joinToString("  ·  ")
        }
    }

    private fun updateSummary(snapshot: TodoSnapshot?) {
        if (snapshot == null || snapshot.todos.isEmpty()) {
            summaryText.text = "В текущей сессии задачи не ставились"
            return
        }
        val total = snapshot.todos.size
        val done = snapshot.todos.count { it.status == TodoFilterPolicy.STATUS_COMPLETED }
        summaryText.text = "Выполнено $done из $total задач (${(done * 100 / total.coerceAtLeast(1))}%)"
    }

    /** Тап по чипу: фильтр переключается, подсветка ряда перерисовывается, список обновляется. */
    private fun selectFilter(filter: TodoFilter) {
        if (currentFilter == filter) return
        currentFilter = filter
        filterChips.forEach { (chipFilter, chip) -> applyChipStyle(chip, selected = chipFilter == filter) }
        renderTodos()
    }

    /** Стиль чипа: активный — акцентный без обводки, неактивный — surface с обводкой. */
    private fun applyChipStyle(chip: Button, selected: Boolean) {
        chip.background = MarsTheme.button(
            this,
            if (selected) MarsTheme.ACCENT else MarsTheme.SURFACE,
            radiusDp = 10,
            strokeColor = if (selected) 0 else MarsTheme.BORDER
        )
        chip.setTextColor(if (selected) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
    }

    private fun renderTodos() {
        todoList.removeAllViews()
        stateContainer.removeAllViews()

        val filtered = TodoFilterPolicy.visibleItems(allItems, currentFilter)

        if (filtered.isEmpty()) {
            // Общее empty-состояние; текст — прежняя фильтр-семантика
            // («нет задач вообще» против «фильтр ничего не выбрал»).
            stateContainer.addView(SectionStateViews.empty(this, TodoFilterPolicy.emptyText(allItems.isEmpty())))
            return
        }

        filtered.forEach { item ->
            val isDone = item.status == TodoFilterPolicy.STATUS_COMPLETED
            val inProgress = item.status == TodoFilterPolicy.STATUS_IN_PROGRESS

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
                    item.status == TodoFilterPolicy.STATUS_CANCELLED -> "Отменено"
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

    private companion object {
        /** Приватный prefs-файл офлайн-кэша задач (входит в logout-cleanup). */
        const val TODOS_OFFLINE_PREFS = "mars_todos_offline"
    }
}
