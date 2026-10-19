package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.mars.network.model.AssigneeStat
import ru.mars.network.model.BoardSummary
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanConfig
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTaskDetail
import ru.mars.network.repository.KanbanStreamFrame

/**
 * Презентер read-only экрана «Канбан»: оркестрирует загрузку доски
 * (порт [fetchBoard] — в app это `KanbanRepository().getBoard(...)`),
 * статистику ([fetchStats], best-effort) и детальную карточку задачи
 * ([fetchTaskDetail]). Чистый JVM-класс без Android-импортов: сеть,
 * credential-проверка и детектор офлайна инжектятся, поэтому переходы
 * состояний полностью покрыты unit-тестами.
 *
 * Контракт состояний:
 * - нет credential -> [KanbanPhase.NO_AUTH] (загрузка даже не начинается);
 * - первая загрузка упала -> [KanbanPhase.ERROR] (offline-тексты при
 *   [isOffline]); если уже был кэш колонок — ERROR со stale-колонками и
 *   notice «нет сети» (offline-safe, экран не пустует);
 * - пустая доска (changed:true, нет колонок) -> [KanbanPhase.EMPTY];
 * - успех -> [KanbanPhase.READY]; порядок и скелет колонок — по
 *   `GET /api/kanban/config` (порт [fetchConfig], best-effort), без него —
 *   серверный порядок.
 *
 * Polling-конверт (docs/API-CONTRACT.md «Канбан»): ответ с `changed:false`
 * НЕ содержит колонок — рендерить его как пустую доску нельзя. [refresh]
 * с `changed:false` молча сохраняет текущую доску (без LOADING-мигания),
 * обновляя только курсор `latest_event_id`. Дефектный `changed:false` без
 * кэша трактуется как пустая доска (EMPTY), а не как ошибка.
 *
 * Контракт read-only: здесь нет и не может появиться операций
 * create/edit/move/delete/dispatch — серверный Bearer allowlist допускает
 * только GET/HEAD /api/kanban/, а экран является витриной данных.
 */
class KanbanPresenter(
    private val hasCredential: () -> Boolean,
    /**
     * `GET /api/kanban/board`. [includeArchived] — серверный дефолт фильтра
     * архива из конфига (`include_archived_by_default`, паритет с WebUI):
     * true добавляет к запросу `include_archived=1`, и бридж дописывает lane
     * `archived` к колонкам доски.
     */
    private val fetchBoard: suspend (since: Long?, board: String?, assignee: String?, includeArchived: Boolean) -> KanbanBoard?,
    private val fetchStats: suspend () -> KanbanStats?,
    private val fetchTaskDetail: suspend (taskId: String) -> KanbanTaskDetail?,
    private val isOffline: () -> Boolean = { false },
    private val nowEpochSeconds: () -> Long? = { null },
    /**
     * `GET /api/kanban/boards` — список досок для переключателя. Отказ или пустой
     * ответ не ломает экран: строка переключателя просто не рисуется.
     */
    private val fetchBoards: suspend () -> List<BoardSummary> = { emptyList() },
    /**
     * `GET /api/kanban/assignees` — подробный справочник исполнителей. Нужен
     * только когда сама доска их не перечислила; best-effort, как и [fetchStats].
     */
    private val fetchAssignees: suspend () -> List<AssigneeStat> = { emptyList() },
    /**
     * `GET /api/kanban/config` — конфигурация отображения доски: канонический
     * порядок дорожек (`columns`) и серверный дефолт `include_archived_by_default`.
     * Best-effort, как [fetchStats]: отказ (старый бридж без эндпоинта, сеть)
     * оставляет доску в серверном порядке и без lane архива — экран не ломается.
     */
    private val fetchConfig: suspend (board: String?) -> KanbanConfig? = { _ -> null },
    /**
     * Живой поток событий канбана. Каждое событие — повод перечитать доску через
     * [refresh] (события несут только идентификаторы, не содержимое карточек),
     * поэтому подписка не парсит payload, а дёргает поллинг-контур. Отсутствие
     * потока не ошибка: экран продолжает жить на polling-таймере Activity.
     */
    private val events: suspend (since: Long?, board: String?) -> Flow<KanbanStreamFrame> =
        { _, _ -> emptyFlow() },
) {

    var state: KanbanUiState = KanbanUiState(
        phase = KanbanPhase.LOADING,
        message = KanbanPolicy.MSG_LOADING,
    )
        private set

    /** Последний полный (changed:true) board-конверт — основа offline-safe и polling. */
    private var cachedBoard: KanbanBoard? = null
    private var cachedStats: KanbanStats? = null
    private var cachedBoards: List<BoardSummary> = emptyList()
    private var cachedAssignees: List<AssigneeStat> = emptyList()

    /** Конфиг доски `/api/kanban/config`; null — бридж без конфига или отказ (best-effort). */
    private var cachedConfig: KanbanConfig? = null

    /**
     * Выбранная доска. null = «как решит сервер»: параметр `board` в запрос не
     * уходит, и сервер отвечает текущей доской. Явный выбор оператора всегда
     * перекрывает серверный.
     */
    private var selectedBoard: String? = null

    /** Фильтр исполнителя. null = все задачи доски (без параметра `assignee`). */
    private var selectedAssignee: String? = null
    private var onChanged: (KanbanUiState) -> Unit = {}

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (KanbanUiState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /**
     * Полная загрузка доски (since = null). Повторные вызовы идемпотентны;
     * при наличии колонок LOADING показывается без центрального message
     * (refresh без мигания пустотой). Статистика загружается best-effort:
     * её отказ не топит доску.
     */
    suspend fun load() {
        if (!hasCredential()) {
            publish(
                KanbanUiState(
                    phase = KanbanPhase.NO_AUTH,
                    message = KanbanPolicy.MSG_NO_AUTH,
                )
            )
            return
        }
        val hadColumns = state.columns.isNotEmpty()
        publish(
            KanbanUiState(
                phase = KanbanPhase.LOADING,
                columns = state.columns,
                stats = state.stats,
                latestEventId = state.latestEventId,
                boards = state.boards,
                currentBoardSlug = state.currentBoardSlug,
                assignees = state.assignees,
                selectedAssignee = state.selectedAssignee,
                live = state.live,
                message = if (hadColumns) null else KanbanPolicy.MSG_LOADING,
            )
        )
        loadBoards()
        loadConfig(selectedBoard)
        applyBoard(runCatching { fetchBoard(null, selectedBoard, selectedAssignee, includeArchived()) }.getOrNull())
        // Stats — best-effort: отдельный try, отказ не меняет фазу доски.
        val stats = runCatching { fetchStats() }.getOrNull()
        if (stats != null) {
            cachedStats = stats
            if (state.phase == KanbanPhase.READY || state.phase == KanbanPhase.EMPTY) {
                publish(state.copy(stats = KanbanPolicy.statsFor(stats)))
            }
        }
        // Справочник исполнителей — тоже best-effort и только как дополнение:
        // доска уже могла перечислить своих, и тогда внешний список не нужен.
        ensureAssignees()
    }

    /**
     * Конфиг доски `GET /api/kanban/config` — best-effort, как [loadBoards].
     * Перечитывается на полной загрузке и на переключении доски/исполнителя
     * (конфиг board-scoped); polling-[refresh] пользуется кэшем — `columns` и
     * `include_archived_by_default` меняются только правкой серверной конфигурации,
     * для которой достаточно ручного обновления. Отказ сохраняет предыдущее
     * значение: разовый сбой запроса не переставляет колонки и не гасит архив.
     */
    private suspend fun loadConfig(board: String?) {
        val config = runCatching { fetchConfig(board) }.getOrNull() ?: return
        cachedConfig = config
    }

    /** Просить ли у доски архивные задачи: серверный дефолт фильтра из конфига. */
    private fun includeArchived(): Boolean = cachedConfig?.includeArchivedByDefault == true

    /** Канонический порядок дорожек из конфига; без конфига — пустой (серверный порядок). */
    private fun configColumns(): List<String> = cachedConfig?.columns.orEmpty()

    /**
     * Переключатель досок: `GET /api/kanban/boards`. Best-effort — пустой ответ
     * или отказ оставляют строку скрытой, доска при этом грузится как обычно.
     * Названия досок не меняют адресацию задачи: она живёт на своей доске.
     */
    private suspend fun loadBoards() {
        val boards = runCatching { fetchBoards() }.getOrNull() ?: return
        if (boards.isEmpty()) return
        cachedBoards = boards
        publish(
            state.copy(
                boards = KanbanPolicy.boardChips(boards, selectedBoard),
                currentBoardSlug = selectedBoard,
            )
        )
    }

    /**
     * Справочник `/api/kanban/assignees` догружается только если доска не
     * перечислила исполнителей сама: два источника одного списка дали бы
     * расхождение в плашках. [cachedAssignees] кэшируется между переключениями.
     */
    private suspend fun ensureAssignees() {
        val board = cachedBoard ?: return
        if (board.assignees.isNotEmpty()) return
        if (cachedAssignees.isEmpty()) {
            cachedAssignees = runCatching { fetchAssignees() }.getOrNull() ?: emptyList()
        }
        if (cachedAssignees.isEmpty()) return
        val chips = KanbanPolicy.assigneeChips(
            names = cachedAssignees.map { it.name },
            selected = selectedAssignee,
            counts = cachedAssignees.associate { it.name to it.total },
        )
        // Тот же порог, что в `applyBoard`: один исполнитель — не фильтр.
        if (chips.size <= 2) return
        publish(state.copy(assignees = chips))
    }

    /**
     * Polling-refresh: `GET /api/kanban/board?since=<latest_event_id>`.
     * `changed:false` -> доска не изменилась: НЕ публикуем ничего, кроме
     * тихого обновления курсора (нет LOADING-мигания, нет пустых колонок).
     * `changed:true` -> полный перерендер READY/EMPTY. Отказ сети при живом
     * кэше -> stale-доска + notice; без кэша -> ERROR.
     */
    suspend fun refresh() {
        if (!hasCredential()) return
        if (state.phase == KanbanPhase.LOADING) return // load() уже в полёте
        val since = state.latestEventId.takeIf { it > 0L }
        val board = runCatching { fetchBoard(since, selectedBoard, selectedAssignee, includeArchived()) }.getOrNull()
        if (board == null) {
            publish(staleOrErrorState())
            return
        }
        if (!board.changed && cachedBoard != null) {
            // Короткий конверт: доска прежняя, обновляем только курсор.
            if (board.latestEventId > state.latestEventId) {
                publish(state.copy(latestEventId = board.latestEventId))
            }
            return
        }
        applyBoard(board)
    }

    /**
     * Переключение доски: выбор оператора перекрывает серверный `current`.
     * Кэш доски сбрасывается, курсор обнуляется — события одной доски не имеют
     * смысла для другой, и `since` из прошлой доски дал бы вечный `changed:false`.
     * Статистика тоже перечитывается: она у каждой доски своя.
     */
    suspend fun selectBoard(slug: String?) {
        if (slug == selectedBoard) return
        selectedBoard = slug
        cachedBoard = null
        cachedStats = null
        publish(
            state.copy(
                columns = emptyList(),
                stats = null,
                latestEventId = 0L,
                message = KanbanPolicy.MSG_LOADING,
                phase = KanbanPhase.LOADING,
                boards = KanbanPolicy.boardChips(cachedBoards, selectedBoard),
                currentBoardSlug = selectedBoard,
            )
        )
        reload()
    }

    /**
     * Фильтр исполнителя: null — «Все». Доска перечитывается целиком, потому что
     * фильтрует её сервер, а не клиент: без перезапроса список остался бы полным.
     */
    suspend fun selectAssignee(name: String?) {
        if (name == selectedAssignee) return
        selectedAssignee = name
        publish(state.copy(assignees = assigneesForCurrentBoard(), selectedAssignee = name))
        reload()
    }

    /** Список имён текущей доски, если сервер его отдал (иначе — из справочника). */
    private fun assigneesForCurrentBoard(): List<KanbanAssigneeChipUi> {
        val boardNames = cachedBoard?.assignees.orEmpty()
        val names = if (boardNames.isNotEmpty()) boardNames else cachedAssignees.map { it.name }
        val counts = if (boardNames.isNotEmpty()) emptyMap()
        else cachedAssignees.associate { it.name to it.total }
        return KanbanPolicy.assigneeChips(names = names, selected = selectedAssignee, counts = counts)
    }

    /** Перечитать доску заново, не трогая список досок (он не зависит от фильтра). */
    private suspend fun reload() {
        loadConfig(selectedBoard)
        applyBoard(runCatching { fetchBoard(null, selectedBoard, selectedAssignee, includeArchived()) }.getOrNull())
    }

    /**
     * Живой поток событий: каждое событие — сигнал «доска изменилась», после
     * которого выполняется обычный [refresh]. Поток может оборваться (сеть,
     * перезапуск WebUI) — это нормальная деградация до поллинга, поэтому отказ
     * снимает флаг [KanbanUiState.live] и НЕ переводит экран в ERROR: данные
     * пользователя остаются на экране.
     *
     * Пока экран на переднем плане, поток поднимается сам с паузой из
     * [KanbanStreamReconnectPolicy]: без этого после первого же обрыва доска
     * жила бы только на таймере до следующего onResume. [reconnect] = false
     * оставлен для тестов, которым нужен один проход.
     */
    fun bindLiveStream(scope: CoroutineScope, reconnect: Boolean = true): Job {
        return scope.launch {
            var attempt = 0
            while (isActive) {
                val since = state.latestEventId.takeIf { it > 0L }
                var failed = false
                try {
                    events(since, selectedBoard).collect { frame -> applyStreamFrame(frame) }
                } catch (cancelled: CancellationException) {
                    // Отмена = уход с экрана: гасить индикатор и планировать ретрай нечего.
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
                // Поток закрылся или упал: индикатор гаснет, экран живёт на поллинге Activity.
                if (state.live) publish(state.copy(live = false))
                if (!reconnect) break
                attempt = KanbanStreamReconnectPolicy.nextAttempt(attempt, failed)
                delay(KanbanStreamReconnectPolicy.delayMs(attempt))
            }
        }
    }

    /**
     * Один кадр живого потока. Вынесено из `collect` ради синхронных JVM-тестов:
     * [KanbanStreamFrame.Hello] поднимает индикатор «в эфире», [KanbanStreamFrame.Events]
     * перечитывает доску только при непустом батче (пустой батч — это keep-alive пула,
     * перечитывать нечего).
     */
    internal suspend fun applyStreamFrame(frame: KanbanStreamFrame) {
        when (frame) {
            is KanbanStreamFrame.Hello -> if (!state.live) publish(state.copy(live = true))
            is KanbanStreamFrame.Events -> {
                if (frame.events.isNotEmpty()) refresh()
                if (!state.live) publish(state.copy(live = true))
            }
            is KanbanStreamFrame.Unknown -> Unit
        }
    }

    /**
     * Read-only детальная карточка задачи: возвращает готовую UI-модель
     * диалога или null (сеть/сервер/парсинг) — Activity показывает
     * [KanbanPolicy.MSG_DETAIL_ERROR]. Никаких операций изменения задачи.
     */
    suspend fun loadTaskDetail(taskId: String): KanbanTaskDetailUi? {
        if (!hasCredential()) return null
        val detail = runCatching { fetchTaskDetail(taskId) }.getOrNull() ?: return null
        return KanbanPolicy.detailUi(detail, nowEpochSeconds())
    }

    /** Общая воронка board-конверта -> состояние (load и refresh с changed:true). */
    private fun applyBoard(board: KanbanBoard?) {
        if (board == null) {
            // Нет сети/сервера: при живом кэше — stale-доска с notice,
            // без кэша — чистый ERROR с retry (offline-safe в обоих ветках).
            publish(staleOrErrorState())
            return
        }
        if (!board.changed) {
            // Дефектный/преждевременный changed:false без кэша: безопасно
            // трактуем как пустую доску (никогда не ERROR и не crash).
            if (cachedBoard == null) {
                publish(
                    KanbanUiState(
                        phase = KanbanPhase.EMPTY,
                        message = KanbanPolicy.MSG_EMPTY,
                        serverReadOnly = board.readOnly,
                        latestEventId = board.latestEventId,
                    )
                )
            }
            return
        }
        cachedBoard = board
        val columns = KanbanPolicy.columnsFor(board, configColumns())
        val taskCount = columns.sumOf { it.count }
        val boardOwnAssignees = board.assignees.isNotEmpty()
        val assignees = KanbanPolicy.assigneeChips(
            names = board.assignees.ifEmpty { cachedAssignees.map { it.name } },
            selected = selectedAssignee,
            counts = if (boardOwnAssignees) {
                emptyMap()
            } else {
                cachedAssignees.associate { it.name to it.total }
            },
        ).let { chips ->
            // Отданная доской строка показывается всегда: она описывает саму доску.
            // А резервный справочник с единственным именем — это не фильтр, а
            // занятая строка ради одной опции, поэтому он скрывается.
            if (boardOwnAssignees || chips.size > 2) chips else emptyList()
        }
        // Серверная доска без явного выбора оператора: показываем её как текущую
        // (или серверную) — переключатель обязан подсветить то, что реально открыто.
        val boards = if (cachedBoards.isEmpty()) emptyList()
        else KanbanPolicy.boardChips(cachedBoards, selectedBoard)
        if (columns.isEmpty()) {
            publish(
                KanbanUiState(
                    phase = KanbanPhase.EMPTY,
                    message = KanbanPolicy.MSG_EMPTY,
                    serverReadOnly = board.readOnly,
                    latestEventId = board.latestEventId,
                    taskCount = taskCount,
                    boards = boards,
                    currentBoardSlug = selectedBoard,
                    assignees = assignees,
                    selectedAssignee = selectedAssignee,
                    live = state.live,
                )
            )
            return
        }
        publish(
            KanbanUiState(
                phase = KanbanPhase.READY,
                columns = columns,
                stats = cachedStats?.let { KanbanPolicy.statsFor(it) },
                serverReadOnly = board.readOnly,
                latestEventId = board.latestEventId,
                taskCount = taskCount,
                boards = boards,
                currentBoardSlug = selectedBoard,
                assignees = assignees,
                selectedAssignee = selectedAssignee,
                live = state.live,
            )
        )
    }

    /** Отказ первой загрузки: кэша нет -> чистый ERROR с retry. */
    private fun initialFailureState(): KanbanUiState {
        val offline = isOffline()
        return KanbanUiState(
            phase = KanbanPhase.ERROR,
            message = if (offline) KanbanPolicy.MSG_ERROR_OFFLINE else KanbanPolicy.MSG_ERROR,
            notice = if (offline) KanbanPolicy.NOTICE_OFFLINE else null,
            canRetry = true,
        )
    }

    /**
     * Отказ при живом кэше (refresh/polling): offline-safe — сохраняем
     * последние колонки и статистику, добавляем notice; без кэша — ERROR.
     */
    private fun staleOrErrorState(): KanbanUiState {
        val offline = isOffline()
        val staleColumns = cachedBoard?.let { KanbanPolicy.columnsFor(it, configColumns()) } ?: emptyList()
        if (staleColumns.isEmpty()) return initialFailureState()
        return KanbanUiState(
            phase = KanbanPhase.ERROR,
            columns = staleColumns,
            stats = cachedStats?.let { KanbanPolicy.statsFor(it) },
            notice = if (offline) KanbanPolicy.NOTICE_STALE else KanbanPolicy.NOTICE_OFFLINE,
            message = null,
            canRetry = true,
            serverReadOnly = cachedBoard?.readOnly ?: false,
            latestEventId = state.latestEventId,
            taskCount = staleColumns.sumOf { it.count },
        )
    }

    private fun publish(next: KanbanUiState) {
        state = next
        onChanged(next)
    }
}
