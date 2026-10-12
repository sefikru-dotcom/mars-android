package ru.mars.mobile

import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTaskDetail

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
 * - успех -> [KanbanPhase.READY] с колонками в серверном порядке.
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
    private val fetchBoard: suspend (since: Long?) -> KanbanBoard?,
    private val fetchStats: suspend () -> KanbanStats?,
    private val fetchTaskDetail: suspend (taskId: String) -> KanbanTaskDetail?,
    private val isOffline: () -> Boolean = { false },
    private val nowEpochSeconds: () -> Long? = { null },
) {

    var state: KanbanUiState = KanbanUiState(
        phase = KanbanPhase.LOADING,
        message = KanbanPolicy.MSG_LOADING,
    )
        private set

    /** Последний полный (changed:true) board-конверт — основа offline-safe и polling. */
    private var cachedBoard: KanbanBoard? = null
    private var cachedStats: KanbanStats? = null
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
                message = if (hadColumns) null else KanbanPolicy.MSG_LOADING,
            )
        )
        applyBoard(runCatching { fetchBoard(null) }.getOrNull())
        // Stats — best-effort: отдельный try, отказ не меняет фазу доски.
        val stats = runCatching { fetchStats() }.getOrNull()
        if (stats != null) {
            cachedStats = stats
            if (state.phase == KanbanPhase.READY || state.phase == KanbanPhase.EMPTY) {
                publish(state.copy(stats = KanbanPolicy.statsFor(stats)))
            }
        }
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
        val board = runCatching { fetchBoard(since) }.getOrNull()
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
        val columns = KanbanPolicy.columnsFor(board)
        val taskCount = columns.sumOf { it.count }
        if (columns.isEmpty()) {
            publish(
                KanbanUiState(
                    phase = KanbanPhase.EMPTY,
                    message = KanbanPolicy.MSG_EMPTY,
                    serverReadOnly = board.readOnly,
                    latestEventId = board.latestEventId,
                    taskCount = taskCount,
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
        val staleColumns = cachedBoard?.let { KanbanPolicy.columnsFor(it) } ?: emptyList()
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
