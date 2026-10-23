package ru.mars.mobile

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.KanbanAssigneesResult
import ru.mars.network.repository.KanbanBoardResult
import ru.mars.network.repository.KanbanBoardsResult
import ru.mars.network.repository.KanbanBulkResult
import ru.mars.network.repository.KanbanCommentResult
import ru.mars.network.repository.KanbanConfigResult
import ru.mars.network.repository.KanbanLinkResult
import ru.mars.network.repository.KanbanRepository
import ru.mars.network.repository.KanbanStatsResult
import ru.mars.network.repository.KanbanTaskDetailResult
import ru.mars.network.repository.KanbanTaskWriteResult

/**
 * Read-only экран «Канбан»: доска `GET /api/kanban/board` (колонки, задачи,
 * статусы) + статистика `GET /api/kanban/stats` + детальная карточка задачи
 * `GET /api/kanban/task/:id` в модальном диалоге.
 *
 * Слои: UI (этот класс) -> [KanbanPresenter] (состояния, polling-конверт
 * `changed:false`, offline-safe) -> [KanbanPolicy] (чистые строки/тексты/тоны,
 * JVM-тесты) -> [KanbanRepository] (сеть, core-network, без правок).
 *
 * Волна 3: каркас экрана — общий shell разделов [SectionScaffold] (safe-area,
 * заголовок, «Назад», спиннер, «Обновить»), статусы notice — его статус-строка,
 * центральные фазовые состояния — общие [SectionStateViews] (loading/empty/
 * error+retry/offline). Presenter/network/polling-семантика не менялись:
 * polling стартует в onResume и гаснет в onPause, `changed:false` не мигает,
 * offline-safe stale-доска сохраняется.
 *
 * Запись: экран не только показывает доску, но и меняет её — создаёт карточки
 * (кнопка «Новая задача»), двигает их по статусам, комментирует, блокирует и
 * связывает из диалога задачи, а также делает массовые операции (режим выбора
 * карточек -> архив/перемещение). Все мутации идут через [KanbanWritePort]
 * презентера, который Activity собирает из [KanbanRepository]; маршруты — ровно
 * те, что `api/auth.py` допускает для device-Bearer (`/api/kanban/tasks`,
 * `.../{id}/patch|comments|block|unblock`, `/tasks/bulk`, `/links`).
 * Управление досками и dispatch из приложения недоступны.
 *
 * Переход из меню: раздел левого бара ([AppDestination.KANBAN]) через
 * [AppNavIntents.sectionIntent]. Публичная точка входа для интегратора —
 * [KanbanActivity.newIntent]; документ — docs/kanban-screen.md.
 * Кнопка «Назад»/системный back — finish() и возврат в вызвавший экран.
 */
class KanbanActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var presenter: KanbanPresenter

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var summaryView: TextView
    private lateinit var statsRow: LinearLayout
    private lateinit var boardRow: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var readOnlyBadge: TextView
    private lateinit var liveBadge: TextView
    private lateinit var headerBadges: LinearLayout
    private lateinit var boardsLabel: TextView
    private lateinit var boardsRow: LinearLayout
    private lateinit var boardsScroll: HorizontalScrollView
    private lateinit var assigneesLabel: TextView
    private lateinit var assigneesRow: LinearLayout
    private lateinit var assigneesScroll: HorizontalScrollView

    // Панель действий записи: «Новая задача» и вход в режим массового выбора.
    private lateinit var actionsRow: LinearLayout
    private lateinit var createButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionLabel: TextView

    private var pollJob: Job? = null
    private var liveJob: Job? = null

    /**
     * Режим выбора карточек: тап по карточке выбирает её вместо открытия
     * диалога, выбранные id живут здесь и переживают перерисовку доски
     * (render() пересобирает колонки на каждый кадр).
     */
    private var selectionMode = false
    private val selectedIds = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        val repository = KanbanRepository()
        presenter = KanbanPresenter(
            hasCredential = { store.read() != null },
            fetchBoard = { since, board, assignee, includeArchived ->
                val credential = store.read()
                if (credential == null) null
                else when (
                    val result = repository.getBoard(
                        MarsApi.BASE_URL,
                        credential,
                        since = since,
                        board = board,
                        assignee = assignee,
                        includeArchived = includeArchived,
                    )
                ) {
                    is KanbanBoardResult.Board -> result.board
                    else -> null
                }
            },
            fetchConfig = { board ->
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getConfig(MarsApi.BASE_URL, credential, board = board)) {
                    is KanbanConfigResult.Config -> result.config
                    else -> null
                }
            },
            fetchStats = {
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getStats(MarsApi.BASE_URL, credential)) {
                    is KanbanStatsResult.Stats -> result.stats
                    else -> null
                }
            },
            fetchTaskDetail = { taskId ->
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getTaskDetail(MarsApi.BASE_URL, credential, taskId)) {
                    is KanbanTaskDetailResult.Detail -> result.detail
                    else -> null
                }
            },
            isOffline = ::isDeviceOffline,
            nowEpochSeconds = { System.currentTimeMillis() / 1000L },
            fetchBoards = {
                val credential = store.read()
                if (credential == null) emptyList()
                else when (val result = repository.getBoards(MarsApi.BASE_URL, credential)) {
                    is KanbanBoardsResult.Boards -> result.boards.boards
                    else -> emptyList()
                }
            },
            fetchAssignees = {
                val credential = store.read()
                if (credential == null) emptyList()
                else when (val result = repository.getAssignees(MarsApi.BASE_URL, credential)) {
                    is KanbanAssigneesResult.Assignees -> result.assignees.assignees
                    else -> emptyList()
                }
            },
            events = { since, board ->
                val credential = store.read()
                if (credential == null) emptyFlow()
                else repository.streamEvents(MarsApi.BASE_URL, credential, board = board, since = since)
            },
            // Порт записи. Доску презентер передаёт сам (его выбранный slug), поэтому
            // карточка всегда меняется на той доске, которую видит оператор, а не на
            // серверной «активной». Без сохранённой пары — честный отказ вместо тихого
            // 401: экран уже в фазе NO_AUTH и действия не рисуются.
            writes = object : KanbanWritePort {
                override suspend fun createTask(
                    board: String?,
                    title: String,
                    body: String?,
                    assignee: String?,
                ): KanbanTaskWriteResult {
                    val credential = store.read()
                        ?: return KanbanTaskWriteResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.createTask(
                        MarsApi.BASE_URL, credential,
                        title = title, body = body, assignee = assignee, board = board,
                    )
                }

                override suspend fun patchTask(
                    board: String?,
                    taskId: String,
                    status: String?,
                    title: String?,
                    body: String?,
                    assignee: String?,
                ): KanbanTaskWriteResult {
                    val credential = store.read()
                        ?: return KanbanTaskWriteResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.patchTask(
                        MarsApi.BASE_URL, credential, taskId,
                        status = status, title = title, body = body, assignee = assignee, board = board,
                    )
                }

                override suspend fun addComment(
                    board: String?,
                    taskId: String,
                    text: String,
                ): KanbanCommentResult {
                    val credential = store.read()
                        ?: return KanbanCommentResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.addComment(
                        MarsApi.BASE_URL, credential, taskId, text, board = board,
                    )
                }

                override suspend fun setBlocked(
                    board: String?,
                    taskId: String,
                    blocked: Boolean,
                    reason: String?,
                ): KanbanTaskWriteResult {
                    val credential = store.read()
                        ?: return KanbanTaskWriteResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.setBlocked(
                        MarsApi.BASE_URL, credential, taskId,
                        blocked = blocked, reason = reason, board = board,
                    )
                }

                override suspend fun bulkUpdate(
                    board: String?,
                    ids: List<String>,
                    status: String?,
                    archive: Boolean?,
                ): KanbanBulkResult {
                    val credential = store.read()
                        ?: return KanbanBulkResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.bulkUpdate(
                        MarsApi.BASE_URL, credential, ids,
                        status = status, archive = archive, board = board,
                    )
                }

                override suspend fun linkTasks(
                    board: String?,
                    parentId: String,
                    childId: String,
                ): KanbanLinkResult {
                    val credential = store.read()
                        ?: return KanbanLinkResult.Failed(KanbanPolicy.MSG_WRITE_UNAVAILABLE)
                    return repository.linkTasks(
                        MarsApi.BASE_URL, credential, parentId, childId, board = board,
                    )
                }
            },
        )
        setContentView(buildView())
        presenter.subscribe { render(it) }
        lifecycleScope.launch { presenter.load() }
        // Живой поток — поверх поллинга: события лишь ускоряют перечитку доски,
        // а пока он молчит или оборван, экран обновляется по таймеру onResume.
    }

    /** Лёгкий polling доски: пока экран видим, `since=<курсор>` раз в интервал. */
    override fun onResume() {
        super.onResume()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // Пока живой поток открыт, изменения приходят кадрами SSE и таймер
                // молчит: доска не опрашивается дважды за один и тот же промежуток.
                // Поток оборвался/не поднялся — polling снова единственный источник.
                if (!presenter.state.live) presenter.refresh()
            }
        }
        // Поток живёт ровно столько, сколько экран на переднем плане: при уходе
        // в фон он снимается вместе с поллингом, при возврате — поднимается снова.
        liveJob?.cancel()
        liveJob = presenter.bindLiveStream(lifecycleScope)
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        liveJob?.cancel()
        liveJob = null
        super.onPause()
    }

    private fun buildView(): View {
        // Волна 3: safe-area, шапка («Назад», заголовок, спиннер, «Обновить») и
        // статус-строка — общий shell разделов; экран добавляет только контент.
        scaffold = SectionScaffold.create(this, KanbanPolicy.SCREEN_TITLE, ::load)

        // Бейдж «доска только для чтения»: только серверный флаг read_only=true
        // (старый мост без маршрутов записи). При обычной доске бейджа нет —
        // экран умеет писать, и постоянная плашка «только чтение» была бы ложью.
        readOnlyBadge = TextView(this).apply {
            visibility = View.GONE
            text = KanbanPolicy.NOTICE_READ_ONLY
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_SUBTLE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        // Индикатор живого потока: показывается, пока SSE-канал канбана открыт.
        // При обрыве экран не ломается — просто гаснет индикатор и работает поллинг.
        liveBadge = TextView(this).apply {
            visibility = View.GONE
            text = KanbanPolicy.LIVE_BADGE
            textSize = 11f
            setTextColor(MarsTheme.ACCENT_TEXT)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_BORDER)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        headerBadges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(readOnlyBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            addView(liveBadge, LinearLayout.LayoutParams(-2, -2))
        }
        scaffold.content.addView(headerBadges, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Панель записи: «Новая задача» и вход в режим выбора карточек. Скрывается
        // целиком, когда сервер объявил доску read-only: предлагать действие,
        // которое мост гарантированно отклонит, нельзя.
        actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        createButton = actionChip(KanbanPolicy.CREATE_LABEL, R.drawable.ic_add) { showCreateTaskDialog() }
        selectButton = actionChip(KanbanPolicy.SELECT_LABEL, null) { toggleSelectionMode() }
        actionsRow.addView(createButton, LinearLayout.LayoutParams(-2, -2))
        actionsRow.addView(selectButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        scaffold.content.addView(actionsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Панель массовых действий: только в режиме выбора — иначе она занимала бы
        // место на экране, где ничего не выбрано.
        selectionLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_EM)
            setPadding(dp(2), 0, 0, dp(6))
        }
        val selectionButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectionButtons.addView(actionChip(KanbanPolicy.MOVE_LABEL, null) { showBulkMoveDialog() })
        selectionButtons.addView(
            actionChip(KanbanPolicy.ARCHIVE_LABEL, null) { archiveSelected() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
        selectionButtons.addView(
            MarsDialog.quietButton(this, KanbanPolicy.CANCEL_LABEL) { exitSelectionMode() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(selectionLabel, LinearLayout.LayoutParams(-1, -2))
            addView(
                HorizontalScrollView(this@KanbanActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(selectionButtons, ViewGroup.LayoutParams(-2, -2))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        scaffold.content.addView(selectionBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Переключатель досок. Строка скрыта целиком, пока сервер не отдал список:
        // пустая «Доски» на старом мосте была бы шумом на экране.
        boardsLabel = sectionLabel(KanbanPolicy.BOARDS_LABEL)
        scaffold.content.addView(boardsLabel, LinearLayout.LayoutParams(-1, -2))
        boardsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boardsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(boardsRow, ViewGroup.LayoutParams(-2, -2))
        }
        scaffold.content.addView(boardsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Фильтр исполнителей. Как и доски, скрыт, когда сервер их не перечислил.
        assigneesLabel = sectionLabel(KanbanPolicy.ASSIGNEES_LABEL)
        scaffold.content.addView(assigneesLabel, LinearLayout.LayoutParams(-1, -2))
        assigneesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        assigneesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(assigneesRow, ViewGroup.LayoutParams(-2, -2))
        }
        scaffold.content.addView(assigneesScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Статистика: summary + горизонтальная лента чипов by_status/by_assignee.
        summaryView = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(MarsTheme.TEXT_EM)
            maxLines = 2
        }
        scaffold.content.addView(summaryView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scaffold.content.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(statsRow, ViewGroup.LayoutParams(-2, -2))
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        // Доска: горизонтальный скролл колонок, каждая колонка — свой
        // вертикальный скролл карточек (мобильный канбан-паттерн).
        boardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scaffold.content.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(boardRow, ViewGroup.LayoutParams(-2, -1))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Статус-блок: центральное сообщение фазы (общие SectionStateViews)
        // + «Повторить» для offline-safe stale-доски.
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scaffold.content.addView(statusBox, LinearLayout.LayoutParams(-1, -2))
        return scaffold.root
    }

    /** Полный reload доски: «Обновить» shell, retry ошибки, первая загрузка. */
    private fun load() { lifecycleScope.launch { presenter.load() } }

    private fun render(state: KanbanUiState) {
        scaffold.progress.visibility = if (state.phase == KanbanPhase.LOADING) View.VISIBLE else View.GONE
        readOnlyBadge.visibility = if (state.serverReadOnly) View.VISIBLE else View.GONE
        liveBadge.visibility = if (state.live) View.VISIBLE else View.GONE
        headerBadges.visibility =
            if (state.serverReadOnly || state.live) View.VISIBLE else View.GONE

        // Запись доступна, пока сервер не объявил доску read-only и есть пара.
        val writable = isWritable()
        actionsRow.visibility = if (writable) View.VISIBLE else View.GONE
        if (!writable && selectionMode) {
            selectionMode = false
            selectedIds.clear()
        }
        if (selectionMode) {
            // Карточка могла исчезнуть (архив/чужая правка между кадрами) — выбор
            // чистим по факту, иначе массовая операция ушла бы по мёртвому id.
            val visible = state.columns.flatMap { it.cards }.mapTo(mutableSetOf()) { it.id }
            selectedIds.retainAll(visible)
        }
        selectionBar.visibility = if (writable && selectionMode) View.VISIBLE else View.GONE
        selectButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
        selectionLabel.text = KanbanPolicy.selectionLabel(selectedIds.size)

        when (val notice = state.notice) {
            null -> scaffold.statusLine.visibility = View.GONE
            else -> {
                scaffold.statusLine.visibility = View.VISIBLE
                scaffold.statusLine.text = notice
                scaffold.statusLine.setTextColor(MarsTheme.STATUS_WARNING)
            }
        }

        renderStats(state.stats)
        renderSelectors(state)

        val revealed = state.columns.isNotEmpty() && boardRow.childCount == 0
        boardRow.removeAllViews()
        state.columns.forEach { column ->
            boardRow.addView(columnView(column, reveal = revealed), columnMargin())
        }

        statusBox.removeAllViews()
        val screen = KanbanPolicy.statusStateFor(state)
        if (screen != null) {
            val view = when (screen) {
                SectionScreenState.Loading -> SectionStateViews.loading(this, state.message ?: KanbanPolicy.MSG_LOADING)
                is SectionScreenState.Empty -> SectionStateViews.empty(this, screen.message)
                is SectionScreenState.Error -> SectionStateViews.error(this, screen.message, ::load)
                is SectionScreenState.Offline -> SectionStateViews.offline(this, screen.message)
            }
            statusBox.addView(view)
        } else if (state.canRetry) {
            // Stale-доска offline-safe: колонки из кэша показаны, центрального
            // сообщения нет — «Повторить» отдельным чипом под доской.
            statusBox.addView(TextView(this).apply {
                text = KanbanPolicy.RETRY_LABEL
                textSize = 14f
                setTextColor(MarsTheme.ACCENT_TEXT)
                gravity = Gravity.CENTER
                background = MarsTheme.button(this@KanbanActivity, MarsTheme.ACCENT_BG, radiusDp = 999, strokeColor = MarsTheme.ACCENT_BORDER)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                // Тап-цель «Повторить»: 14sp + 10dp давали ~41dp, ниже минимума 48dp.
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener { load() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
    }

    private fun renderStats(stats: KanbanStatsUi?) {
        statsRow.removeAllViews()
        if (stats == null || stats.chips.isEmpty()) {
            summaryView.visibility = View.GONE
            return
        }
        summaryView.visibility = View.VISIBLE
        summaryView.text = stats.summary ?: ""
        stats.chips.forEach { chip ->
            statsRow.addView(chipView(chip), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    /** Мелкий заголовок секции над лентой плашек («Доски», «Исполнители»). */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        visibility = View.GONE
        this.text = text
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
        setPadding(dp(2), 0, 0, dp(4))
    }

    /**
     * Переключатель досок и фильтр исполнителей. Обе строки целиком скрыты, когда
     * сервер их не отдал: на старом мосте их нет, и подпись над пустой лентой
     * выглядела бы поломкой. Клик по плашке доски переключает доску, по плашке
     * имени — фильтрует задачи; «Все» снимает фильтр.
     */
    private fun renderSelectors(state: KanbanUiState) {
        boardsRow.removeAllViews()
        val showBoards = state.boards.size > 1
        boardsScroll.visibility = if (showBoards) View.VISIBLE else View.GONE
        boardsLabel.visibility = if (showBoards) View.VISIBLE else View.GONE
        if (showBoards) {
            state.boards.forEach { board ->
                boardsRow.addView(
                    selectorChip(
                        label = board.label,
                        count = board.countLabel,
                        selected = board.isCurrent,
                    ) { lifecycleScope.launch { presenter.selectBoard(board.slug) } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                )
            }
        }

        assigneesRow.removeAllViews()
        val showAssignees = state.assignees.size > 1
        assigneesScroll.visibility = if (showAssignees) View.VISIBLE else View.GONE
        assigneesLabel.visibility = if (showAssignees) View.VISIBLE else View.GONE
        if (showAssignees) {
            state.assignees.forEach { assignee ->
                assigneesRow.addView(
                    selectorChip(
                        label = assignee.label,
                        count = assignee.countLabel,
                        selected = assignee.isSelected,
                    ) {
                        val next = assignee.name.takeIf { it != KanbanPolicy.ALL_ASSIGNEES_TOKEN }
                        lifecycleScope.launch { presenter.selectAssignee(next) }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                )
            }
        }
    }

    /** Плашка выбора: акцентная рамка у активной, серое — у остальных. */
    private fun selectorChip(
        label: String,
        count: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = TextView(this).apply {
        text = if (count != null) "$label · $count" else label
        textSize = 12f
        maxLines = 1
        setTextColor(if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM)
        background = if (selected) {
            MarsTheme.pill(this@KanbanActivity, MarsTheme.ACCENT_BG, MarsTheme.ACCENT_BORDER)
        } else {
            MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER)
        }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        gravity = Gravity.CENTER
        // Тап-цель плашки: 12sp + 6dp давали ~30dp, а по такой полосе палец
        // промахивается. 48dp — минимум по гайдлайну доступности.
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        // TalkBack: имя плашки + состояние выбора одной нодой (текст ребёнка
        // иначе читается отдельно от «выбрано»).
        isSelected = selected
        contentDescription = if (selected) "$text, выбрано" else text.toString()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Чип статистики: цветной dot + «N · label» в тон статуса. */
    private fun chipView(chip: KanbanStatChipUi): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            addView(MarsTheme.statusDot(this@KanbanActivity, toneColor(chip.tone)),
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            addView(TextView(this@KanbanActivity).apply {
                text = "${chip.value} · ${chip.label}"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_EM)
                maxLines = 1
            })
        }

    /** Колонка доски: заголовок (имя + счётчик) и вертикальный список карточек. */
    private fun columnView(column: KanbanColumnUi, reveal: Boolean): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@KanbanActivity, MarsTheme.SURFACE, radiusDp = 14, strokeColor = MarsTheme.BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        head.addView(TextView(this).apply {
            text = column.name
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = column.count.toString()
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_MUTED)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        shell.addView(head, LinearLayout.LayoutParams(-1, -2))

        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (column.cards.isEmpty()) {
            cards.addView(TextView(this).apply {
                text = KanbanPolicy.COLUMN_EMPTY_HINT
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(dp(6), dp(4), dp(6), dp(4))
            })
        } else {
            column.cards.forEach { card ->
                cards.addView(taskCardView(card), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        shell.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(cards, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        if (reveal) applyRevealMotion(shell)
        return shell
    }

    /**
     * Карточка задачи. В обычном режиме тап открывает диалог задачи; в режиме
     * выбора тап включает/снимает выбор, а долгое нажатие включает режим выбора
     * на этой карточке (жест, привычный по спискам с массовыми действиями).
     */
    private fun taskCardView(card: KanbanTaskCardUi): View {
        val selected = selectionMode && card.id in selectedIds
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(
                this@KanbanActivity,
                if (selected) MarsTheme.ACCENT_BG
                else if (card.isBlocked) MarsTheme.BLOCKED_CARD_BG
                else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = when {
                    selected -> MarsTheme.ACCENT
                    card.isBlocked -> MarsTheme.ACCENT_BORDER
                    else -> MarsTheme.BORDER_SUBTLE
                },
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // Тап-цель карточки: минимум 48dp по высоте даже у карточки без футера.
            minimumHeight = dp(48)
            // Карточка — одна нода для TalkBack: заголовок, статус и возраст
            // читаются вместе с подсказкой о тапе. Без contentDescription фокус
            // идёт по каждому TextView отдельно и контекст «это задача» теряется.
            contentDescription = buildString {
                append(card.title)
                append(", ")
                append(card.status.ifBlank { KanbanPolicy.STATUS_UNKNOWN_LABEL })
                card.ageLabel?.let { append(", ").append(it) }
                if (selected) append(", ").append(KanbanPolicy.SELECTED_BADGE)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectionMode) toggleSelection(card.id) else showTaskDetail(card)
            }
            setOnLongClickListener {
                enterSelectionMode(card.id)
                true
            }
        }
        view.addView(TextView(this).apply {
            text = card.title
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })

        // Строка статуса: dot + статус + возраст.
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        statusLine.addView(MarsTheme.statusDot(this, toneColor(card.tone)),
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        statusLine.addView(TextView(this).apply {
            text = card.status.ifBlank { KanbanPolicy.STATUS_UNKNOWN_LABEL }
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.ageLabel?.let { age ->
            statusLine.addView(TextView(this).apply {
                text = age
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
            })
        }
        view.addView(statusLine, LinearLayout.LayoutParams(-1, -2))

        // Футер: исполнитель + бейджи (приоритет/цель/блокировка/комментарии/дети).
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        footer.addView(TextView(this).apply {
            text = card.assigneeLabel
            textSize = 11f
            setTextColor(if (card.assigneeLabel == KanbanPolicy.UNASSIGNED_LABEL) MarsTheme.TEXT_MUTED else MarsTheme.TEXT_EM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.priorityLabel?.let { footer.addView(cardBadge(it, MarsTheme.STATUS_INFO)) }
        if (card.isGoalMode) footer.addView(cardBadge(KanbanPolicy.GOAL_BADGE, MarsTheme.ACCENT_TEXT))
        if (card.isBlocked) footer.addView(cardBadge(KanbanPolicy.BLOCKED_BADGE, MarsTheme.STATUS_ERROR))
        if (card.commentCount > 0) {
            footer.addView(cardBadge(KanbanPolicy.commentsLabel(card.commentCount), MarsTheme.TEXT_MUTED))
        }
        if (card.childCount > 0) {
            footer.addView(cardBadge(KanbanPolicy.childrenLabel(card.childCount), MarsTheme.TEXT_MUTED))
        }
        if (selectionMode) {
            footer.addView(
                cardBadge(
                    if (selected) KanbanPolicy.SELECTED_BADGE else KanbanPolicy.SELECT_HINT,
                    if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED,
                )
            )
        }
        view.addView(footer, LinearLayout.LayoutParams(-1, -2))
        return view
    }

    /** Мелкий бейдж футера карточки (приоритет/goal/блокировка/счётчики). */
    private fun cardBadge(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        setTextColor(color)
        background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE, MarsTheme.BORDER_MUTED)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
    }

    /**
     * Диалог задачи (тап по карточке): заголовок, статус, тело, факты,
     * комментарии, связи, хвост ленты событий. Действия записи (перемещение,
     * комментарий, блокировка, связь) появляются только на записываемой доске и
     * каждая уходит отдельным запросом — сам диалог остаётся чистым GET.
     */
    private fun showTaskDetail(card: KanbanTaskCardUi) = openTaskDetail(card.id, card.title)

    /**
     * Диалог задачи по id (`GET /api/kanban/tasks/{id}`). [fallbackTitle] —
     * заголовок на случай, когда сервер не ответил (карточка исчезла из доски).
     * Связи задачи (`links {parents, children}`) показаны реальными id:
     * тап/фокус по пилюле открывает диалог связанной задачи (навигация — GET).
     */
    private fun openTaskDetail(taskId: String, fallbackTitle: String) {
        lifecycleScope.launch {
            val detail = presenter.loadTaskDetail(taskId)
            if (detail == null) {
                MarsDialog.show(this@KanbanActivity) { root ->
                    root.addView(MarsDialog.header(this@KanbanActivity, fallbackTitle, null))
                    root.addView(MarsDialog.paragraph(this@KanbanActivity, KanbanPolicy.MSG_DETAIL_ERROR))
                    listOf(MarsDialog.quietButton(this@KanbanActivity, KanbanPolicy.CLOSE_LABEL) {})
                }
                return@launch
            }
            var dialogRef: Dialog? = null
            dialogRef = MarsDialog.show(this@KanbanActivity) { root ->
                root.addView(MarsDialog.header(this@KanbanActivity, detail.title, detail.status.ifBlank { null }))
                detail.body?.let { body -> root.addView(MarsDialog.paragraph(this@KanbanActivity, body)) }
                detail.rows.forEach { row ->
                    root.addView(MarsDialog.caption(this@KanbanActivity, row.label))
                    root.addView(detailValue(row.value))
                }
                if (detail.comments.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity,
                        KanbanPolicy.commentsLabel(detail.comments.size)))
                    detail.comments.forEach { comment ->
                        root.addView(commentBlock(comment))
                    }
                }
                if (detail.parentIds.isNotEmpty() || detail.childIds.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity, KanbanPolicy.LINKS_LABEL))
                    if (detail.parentIds.isNotEmpty()) {
                        root.addView(MarsDialog.caption(this@KanbanActivity, KanbanPolicy.LINK_PARENTS_LABEL))
                        root.addView(MarsDialog.pillRow(this@KanbanActivity, detail.parentIds) { id ->
                            openTaskDetail(id, id)
                        })
                    }
                    if (detail.childIds.isNotEmpty()) {
                        root.addView(MarsDialog.caption(this@KanbanActivity, KanbanPolicy.LINK_CHILDREN_LABEL))
                        root.addView(MarsDialog.pillRow(this@KanbanActivity, detail.childIds) { id ->
                            openTaskDetail(id, id)
                        })
                    }
                }
                if (detail.eventLabels.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity, KanbanPolicy.EVENTS_LABEL))
                    root.addView(MarsDialog.pillRow(this@KanbanActivity, detail.eventLabels))
                }

                // Действия записи. Каждое закрывает диалог и уходит своим запросом:
                // после мутации данные карточки уже не те, что показаны здесь, а
                // доска перечитывается презентером.
                val controls = mutableListOf<View>()
                fun action(button: View) {
                    controls += button
                    root.addView(MarsDialog.block(this@KanbanActivity, button, bottomDp = 8))
                }
                if (isWritable()) {
                    action(
                        MarsDialog.primaryButton(this@KanbanActivity, KanbanPolicy.MOVE_LABEL) {
                            dialogRef?.dismiss()
                            showMoveDialog(detail.id, detail.status)
                        }
                    )
                    action(
                        MarsDialog.secondaryButton(this@KanbanActivity, KanbanPolicy.COMMENT_LABEL) {
                            dialogRef?.dismiss()
                            showCommentDialog(detail.id)
                        }
                    )
                    val blocked = detail.tone == KanbanStatusTone.BLOCKED
                    action(
                        MarsDialog.secondaryButton(
                            this@KanbanActivity,
                            if (blocked) KanbanPolicy.UNBLOCK_LABEL else KanbanPolicy.BLOCK_LABEL,
                        ) {
                            dialogRef?.dismiss()
                            if (blocked) runWrite { presenter.setBlocked(detail.id, blocked = false) }
                            else showBlockDialog(detail.id)
                        }
                    )
                    action(
                        MarsDialog.secondaryButton(this@KanbanActivity, KanbanPolicy.LINK_LABEL) {
                            dialogRef?.dismiss()
                            showLinkDialog(detail.id)
                        }
                    )
                }
                action(MarsDialog.quietButton(this@KanbanActivity, KanbanPolicy.CLOSE_LABEL) { dialogRef?.dismiss() })
                controls
            }
        }
    }

    // ── запись: общие помощники ──

    /**
     * Запись разрешена, пока сервер не объявил доску read-only и сохранена пара.
     * Без пары запрос ушёл бы без Bearer и вернул 401 — такие кнопки не нужны.
     */
    private fun isWritable(): Boolean =
        !presenter.state.serverReadOnly && presenter.state.phase != KanbanPhase.NO_AUTH

    /**
     * Один вызов записи с единым показом итога: тост с готовой строкой презентера
     * (успех и причина отказа формулируются в [KanbanPolicy], не здесь) и выход из
     * режима выбора после успеха — выбранных карточек в прежнем виде уже нет.
     */
    private fun runWrite(block: suspend () -> KanbanActionOutcome) {
        lifecycleScope.launch {
            val outcome = block()
            toast(outcome.message)
            if (outcome.ok && selectionMode) exitSelectionMode()
        }
    }

    private fun toast(message: String?) {
        message?.takeIf { it.isNotBlank() }?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    /** ── запись: режим выбора карточек ── */

    private fun toggleSelectionMode() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        selectionMode = true
        selectedIds.clear()
        render(presenter.state)
    }

    /** Долгое нажатие по карточке: включить выбор и сразу отметить эту карточку. */
    private fun enterSelectionMode(taskId: String) {
        if (!isWritable()) {
            return
        }
        selectionMode = true
        selectedIds.add(taskId)
        render(presenter.state)
    }

    private fun toggleSelection(taskId: String) {
        if (!selectedIds.add(taskId)) selectedIds.remove(taskId)
        render(presenter.state)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        render(presenter.state)
    }

    /** ── запись: диалоги ── */

    /**
     * Новая задача: название обязательно, описание и исполнитель — нет (без
     * исполнителя сервер ставит `kanban.default_assignee`, карточка не остаётся
     * бесхозной). Отказ показывается в error-strip диалога, чтобы поле можно было
     * поправить, не открывая форму заново.
     */
    private fun showCreateTaskDialog() {
        var dialogRef: Dialog? = null
        val titleField = MarsDialog.textField(this, KanbanPolicy.CREATE_HINT_TITLE)
        val bodyField = MarsDialog.textField(this, KanbanPolicy.CREATE_HINT_BODY)
        val assigneeField = MarsDialog.textField(this, KanbanPolicy.CREATE_HINT_ASSIGNEE)
        val errorStrip = MarsDialog.errorStrip(this)
        val controls = listOf<View>(titleField, bodyField, assigneeField)
        fun submit() {
            MarsDialog.setResponding(controls, true)
            MarsDialog.setError(errorStrip, null)
            lifecycleScope.launch {
                val outcome = presenter.createTask(
                    title = titleField.text?.toString().orEmpty(),
                    body = bodyField.text?.toString()?.trim()?.takeIf(String::isNotEmpty),
                    assignee = assigneeField.text?.toString()?.trim()?.takeIf(String::isNotEmpty),
                )
                MarsDialog.setResponding(controls, false)
                if (outcome.ok) {
                    dialogRef?.dismiss()
                    toast(outcome.message)
                } else {
                    MarsDialog.setError(errorStrip, outcome.message)
                }
            }
        }
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, KanbanPolicy.CREATE_LABEL, null))
            root.addView(MarsDialog.paragraph(this, KanbanPolicy.CREATE_HINT))
            controls.forEach { root.addView(MarsDialog.block(this, it, bottomDp = 8)) }
            // Известные исполнители — плашками: свободный ввод остаётся (можно
            // назначить нового), но опечатку в существующем имени не сделать.
            val knownAssignees = presenter.state.assignees.map { it.name }
            if (knownAssignees.isNotEmpty()) {
                root.addView(MarsDialog.caption(this, KanbanPolicy.ASSIGNEES_LABEL))
                root.addView(MarsDialog.pillRow(this, knownAssignees) { name ->
                    assigneeField.setText(name)
                })
            }
            root.addView(errorStrip)
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, KanbanPolicy.CANCEL_LABEL) { dialogRef?.dismiss() },
                    MarsDialog.primaryButton(this, KanbanPolicy.CREATE_LABEL) { submit() },
                )
            )
            controls
        }
    }

    /**
     * Пикер колонки. Цели строятся из фактических дорожек доски
     * ([KanbanPolicy.moveTargets]), поэтому статус, которого на доске нет,
     * предложить невозможно. [excludeStatus] — текущий статус карточки: «переместить
     * туда, где она уже лежит» не предлагается (сервер всё равно ответил бы no-op).
     */
    private fun pickMoveTarget(
        excludeStatus: String?,
        title: String,
        onPick: (KanbanMoveTargetUi) -> Unit,
    ) {
        val targets = KanbanPolicy.moveTargets(presenter.state.columns)
            .filterNot { excludeStatus != null && it.status.equals(excludeStatus, ignoreCase = true) }
        if (targets.isEmpty()) {
            toast(KanbanPolicy.MSG_NO_MOVE_TARGETS)
            return
        }
        var dialogRef: Dialog? = null
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, title, null))
            val items = targets.map { target ->
                val action: () -> Unit = {
                    dialogRef?.dismiss()
                    onPick(target)
                }
                target.label to action
            }
            root.addView(MarsDialog.pillStack(this, items))
            listOf(MarsDialog.quietButton(this, KanbanPolicy.CANCEL_LABEL) { dialogRef?.dismiss() })
        }
    }

    private fun showMoveDialog(taskId: String, currentStatus: String) {
        pickMoveTarget(currentStatus, KanbanPolicy.MOVE_LABEL) { target ->
            runWrite { presenter.moveTask(taskId, target.status) }
        }
    }

    /** Массовое перемещение: одна цель применяется ко всем выбранным карточкам. */
    private fun showBulkMoveDialog() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toast(KanbanPolicy.MSG_SELECTION_EMPTY)
            return
        }
        pickMoveTarget(null, KanbanPolicy.MOVE_LABEL) { target ->
            runWrite { presenter.moveTasks(ids, target.status) }
        }
    }

    /** Массовый архив: подтверждения нет — карточки обратимо скрываются с доски. */
    private fun archiveSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toast(KanbanPolicy.MSG_SELECTION_EMPTY)
            return
        }
        runWrite { presenter.archiveTasks(ids) }
    }

    private fun showCommentDialog(taskId: String) {
        var dialogRef: Dialog? = null
        val field = MarsDialog.textField(this, KanbanPolicy.COMMENT_HINT)
        val errorStrip = MarsDialog.errorStrip(this)
        val controls = listOf<View>(field)
        fun submit() {
            MarsDialog.setResponding(controls, true)
            MarsDialog.setError(errorStrip, null)
            lifecycleScope.launch {
                val outcome = presenter.comment(taskId, field.text?.toString().orEmpty())
                MarsDialog.setResponding(controls, false)
                if (outcome.ok) {
                    dialogRef?.dismiss()
                    toast(outcome.message)
                } else {
                    MarsDialog.setError(errorStrip, outcome.message)
                }
            }
        }
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, KanbanPolicy.COMMENT_LABEL, null))
            root.addView(field)
            root.addView(errorStrip)
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, KanbanPolicy.CANCEL_LABEL) { dialogRef?.dismiss() },
                    MarsDialog.primaryButton(this, KanbanPolicy.COMMENT_LABEL) { submit() },
                )
            )
            controls
        }
    }

    /** Блокировка с необязательной причиной — её увидит тот, кто возьмёт задачу. */
    private fun showBlockDialog(taskId: String) {
        var dialogRef: Dialog? = null
        val reasonField = MarsDialog.textField(this, KanbanPolicy.BLOCK_REASON_HINT)
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, KanbanPolicy.BLOCK_LABEL, null))
            root.addView(reasonField)
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, KanbanPolicy.CANCEL_LABEL) { dialogRef?.dismiss() },
                    MarsDialog.primaryButton(this, KanbanPolicy.BLOCK_LABEL) {
                        val reason = reasonField.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                        dialogRef?.dismiss()
                        runWrite { presenter.setBlocked(taskId, blocked = true, reason = reason) }
                    },
                )
            )
            listOf(reasonField)
        }
    }

    /** Связь «текущая задача → потомок»: id вводится вручную (в карточке их нет). */
    private fun showLinkDialog(parentId: String) {
        var dialogRef: Dialog? = null
        val childField = MarsDialog.textField(this, KanbanPolicy.LINK_HINT)
        val errorStrip = MarsDialog.errorStrip(this)
        val controls = listOf<View>(childField)
        fun submit() {
            MarsDialog.setResponding(controls, true)
            MarsDialog.setError(errorStrip, null)
            lifecycleScope.launch {
                val outcome = presenter.linkTasks(parentId, childField.text?.toString().orEmpty())
                MarsDialog.setResponding(controls, false)
                if (outcome.ok) {
                    dialogRef?.dismiss()
                    toast(outcome.message)
                } else {
                    MarsDialog.setError(errorStrip, outcome.message)
                }
            }
        }
        dialogRef = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, KanbanPolicy.LINK_LABEL, null))
            root.addView(MarsDialog.paragraph(this, KanbanPolicy.LINK_PARENT_HINT))
            root.addView(childField)
            root.addView(errorStrip)
            root.addView(
                MarsDialog.buttonRow(
                    this,
                    MarsDialog.secondaryButton(this, KanbanPolicy.CANCEL_LABEL) { dialogRef?.dismiss() },
                    MarsDialog.primaryButton(this, KanbanPolicy.LINK_LABEL) { submit() },
                )
            )
            controls
        }
    }

    /**
     * Крупная плашка действия (тап-цель 48dp): акцентная рамка и, если задана,
     * иконка compound-drawable. Подпись остаётся текстом кнопки — TalkBack читает
     * её, а не глиф.
     */
    private fun actionChip(label: String, iconRes: Int?, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(MarsTheme.ACCENT_TEXT)
        background = MarsTheme.button(
            this@KanbanActivity,
            MarsTheme.ACCENT_BG,
            radiusDp = 999,
            strokeColor = MarsTheme.ACCENT_BORDER,
        )
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        if (iconRes != null) {
            val size = dp(16)
            ContextCompat.getDrawable(this@KanbanActivity, iconRes)?.mutate()?.let { icon ->
                icon.setBounds(0, 0, size, size)
                icon.setTint(MarsTheme.ACCENT_TEXT)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        }
        setOnClickListener { onClick() }
    }

    private fun detailValue(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(MarsTheme.TEXT)
        setPadding(0, 0, 0, dp(8))
    }

    private fun commentBlock(comment: KanbanDetailCommentUi): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 10, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(this@KanbanActivity).apply {
                text = buildString {
                    append(comment.author)
                    comment.whenLabel?.let { append(" · ").append(it) }
                }
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@KanbanActivity).apply {
                text = comment.body
                textSize = 13f
                setTextColor(MarsTheme.TEXT_EM)
            })
        }.let { MarsDialog.block(this, it, bottomDp = 6) }

    private fun toneColor(tone: KanbanStatusTone): Int = when (tone) {
        KanbanStatusTone.READY -> MarsTheme.STATUS_INFO
        KanbanStatusTone.RUNNING -> MarsTheme.STATUS_WARNING
        KanbanStatusTone.BLOCKED -> MarsTheme.STATUS_ERROR
        KanbanStatusTone.DONE -> MarsTheme.STATUS_ONLINE
        KanbanStatusTone.NEUTRAL -> MarsTheme.TEXT_MUTED
    }

    /**
     * Короткий reveal колонок (fade + slide-up) строго через [MotionPolicy]:
     * при системном «уменьшить движение» длительность схлопывается в 0 и
     * анимация не запускается вовсе. Кривая — decelerate (MarsMotion stance).
     */
    private fun applyRevealMotion(view: View) {
        val duration = MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, isReducedMotion())
        if (duration <= 0) return
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration.toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Settings.Global.ANIMATOR_DURATION_SCALE — тот же источник, что в ChatActivity. */
    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Синхронный снимок «нет сети» для offline-safe состояний: нет активного
     * network или у него нет INTERNET-способности.
     */
    private fun isDeviceOffline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun columnMargin() = LinearLayout.LayoutParams(dp(COLUMN_WIDTH_DP), -1).apply { marginEnd = dp(10) }
    private fun dp(v: Int) = MarsTheme.dp(this, v)

    companion object {
        /** Интервал фонового polling доски (GET board?since=...) пока экран видим. */
        const val POLL_INTERVAL_MS = 30_000L

        /** Ширина колонки: мобильный канбан — горизонтальный свайп между колонками. */
        const val COLUMN_WIDTH_DP = 272

        /**
         * Публичная точка входа для интегратора (левый бар запускает через
         * [AppNavIntents.sectionIntent]):
         * `startActivity(KanbanActivity.newIntent(this))`. Back из KanbanActivity
         * возвращает в вызвавший экран.
         */
        fun newIntent(context: Context): Intent = Intent(context, KanbanActivity::class.java)
    }
}
