package ru.mars.network.repository

import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentUpdate

/**
 * Чистый редьюсер доски субагентов (P0-2, клиентская половина).
 *
 * Из последовательности live-событий [SubagentUpdate] (доставляются сайд-каналом
 * `ChatRepository.onSideEvent` → [ru.mars.network.model.SubagentStatusUpdate])
 * вычисляет состояние КАЖДОГО ребёнка и обновляет его в реальном времени.
 * Без Android-зависимостей — тестируется на JVM.
 *
 * Обязательные правила (закреплены SubagentStatusPolicyTest):
 * - завершённый/упавший/остановленный ребёнок НЕ выглядит активным
 *   ([SubagentState.isActive] у терминальных состояний false);
 * - терминальное состояние не «воскрешается»: повторный старт/прогресс того же id
 *   после исхода игнорируются (реплей журнала при reattach доставляет события повторно);
 * - повторный старт того же id не плодит дубли (доска адресована по childId);
 * - неизвестное событие ([SubagentUpdate.Kind.Unknown]) никогда не переводит ребёнка
 *   в Running и не создаёт записей;
 * - прогресс/ожидание про НЕУЧТЁННОГО ребёнка не выдумывают активного ребёнка
 *   (запись появляется только по старту или по терминальному исходу — reattach
 *   мог пропустить старт, но исход обязан приземлиться);
 * - [SubagentUpdate.Kind.TurnStopped] (стеринг/отмена/остановка/смерть хода)
 *   переводит всех АКТИВНЫХ детей в [SubagentState.Stopped], терминальных не трогает;
 * - null-поля события не затирают известные данные ребёнка (merge, а не replace).
 */
object SubagentStatusPolicy {

    /**
     * Сворачивает всю последовательность событий в итоговую доску
     * (childId -> снимок состояния). Пустой вход — пустая доска.
     */
    fun reduce(events: List<SubagentUpdate>): Map<String, SubagentStatus> {
        var board: Map<String, SubagentStatus> = emptyMap()
        for (event in events) board = apply(board, event)
        return board
    }

    /**
     * Одно пошаговое применение события к доске (real-time обновление).
     * Чистая функция: входная доска не мутируется, при отсутствии изменений
     * возвращается тот же снимок.
     */
    fun apply(board: Map<String, SubagentStatus>, event: SubagentUpdate): Map<String, SubagentStatus> {
        if (event.kind == SubagentUpdate.Kind.TurnStopped) return stopActive(board)
        val childId = event.childId?.trim()?.takeIf { it.isNotBlank() } ?: return board
        val existing = board[childId]
        val updated = when (event.kind) {
            SubagentUpdate.Kind.Start -> onStart(existing, event, childId)
            SubagentUpdate.Kind.Progress -> onProgress(existing, event)
            SubagentUpdate.Kind.Waiting -> onWaiting(existing, event)
            SubagentUpdate.Kind.Complete -> onComplete(existing, event, childId)
            // Неизвестное событие не меняет ничего: ни состояния, ни полей.
            SubagentUpdate.Kind.Unknown -> existing
            SubagentUpdate.Kind.TurnStopped -> existing // недостижимо (обработано выше)
        }
        if (updated == null || updated == existing) return board
        return board + (childId to updated)
    }

    // ---- переходы ---------------------------------------------------------------------------------

    private fun onStart(existing: SubagentStatus?, event: SubagentUpdate, childId: String): SubagentStatus? {
        val classified = classify(event.rawStatus)
        if (existing == null) {
            return merge(null, event, childId, classified ?: SubagentState.Running)
        }
        if (existing.state.isTerminal) return existing // терминальный ребёнок не воскрешается
        // Повторный старт того же id: без явного статуса состояние сохраняется (не дубль, не скачок).
        return merge(existing, event, childId, classified ?: existing.state)
    }

    private fun onProgress(existing: SubagentStatus?, event: SubagentUpdate): SubagentStatus? {
        if (existing == null) return null // прогресс-шум без старта не создаёт ребёнка
        if (existing.state.isTerminal) return existing
        return merge(existing, event, existing.childId, classify(event.rawStatus) ?: SubagentState.Running)
    }

    private fun onWaiting(existing: SubagentStatus?, event: SubagentUpdate): SubagentStatus? {
        if (existing == null) return null
        if (existing.state.isTerminal) return existing
        return merge(existing, event, existing.childId, SubagentState.Waiting)
    }

    private fun onComplete(existing: SubagentStatus?, event: SubagentUpdate, childId: String): SubagentStatus? {
        // Терминальный исход фиксируется один раз: повторный complete (реплей журнала)
        // не переклассифицирует уже записанный результат.
        if (existing != null && existing.state.isTerminal) return existing
        val state = classify(event.rawStatus)
            ?: if (!event.failureReason.isNullOrBlank()) SubagentState.Failed else SubagentState.Completed
        return merge(existing, event, childId, state)
    }

    private fun stopActive(board: Map<String, SubagentStatus>): Map<String, SubagentStatus> {
        if (board.values.none { it.state.isActive }) return board
        return board.mapValues { (_, child) ->
            if (child.state.isActive) child.copy(state = SubagentState.Stopped) else child
        }
    }

    // ---- классификация и merge ---------------------------------------------------------------------

    /** Успешные исходы (серверные `completed`/`success`/`done`/...). */
    private val SUCCESS_STATUSES = setOf("completed", "complete", "done", "success", "succeeded", "ok")

    /** Остановки (стеринг/отмена/прерывание) — не ошибка, но и не успех. */
    private val STOPPED_STATUSES = setOf("stopped", "cancelled", "canceled", "interrupted", "aborted")

    /** Ожидание: ребёнок жив, но заблокирован внешним условием. */
    private val WAITING_STATUSES = setOf("waiting", "awaiting", "blocked", "paused", "pending_approval")

    /** Явные провалы (серверные SUBAGENT_FAILURE_STATUSES + «failure»). */
    private val FAILED_STATUSES = setOf("failed", "error", "failure", "timeout")

    /**
     * Классификация дословного серверного статуса в состояние.
     * Пустой/отсутствующий статус — не событие классификации (null): состояние
     * решает контекст ([SubagentUpdate.Kind]). НЕраспознанный непустой статус —
     * тоже null («мнения нет»): на live-кадрах (start/progress) значения вроде
     * «running»/«working» нормальны и не должны ронять ребёнка в Failed, а на
     * терминальном кадре неизвестный исход досчитывается в [onComplete]
     * (fail-safe: ребёнок с неясным исходом не выглядит активным).
     */
    private fun classify(rawStatus: String?): SubagentState? {
        val key = rawStatus?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (key) {
            in SUCCESS_STATUSES -> SubagentState.Completed
            in STOPPED_STATUSES -> SubagentState.Stopped
            in WAITING_STATUSES -> SubagentState.Waiting
            in FAILED_STATUSES -> SubagentState.Failed
            else -> null
        }
    }

    /** Новый снимок: непустые поля события обновляют данные, пустые — сохраняют известные. */
    private fun merge(
        existing: SubagentStatus?,
        event: SubagentUpdate,
        childId: String,
        state: SubagentState,
    ): SubagentStatus = SubagentStatus(
        childId = childId,
        state = state,
        displayName = event.displayName?.takeIf { it.isNotBlank() } ?: existing?.displayName,
        model = event.model?.takeIf { it.isNotBlank() } ?: existing?.model,
        phase = event.phase?.takeIf { it.isNotBlank() } ?: existing?.phase,
        rawStatus = event.rawStatus?.takeIf { it.isNotBlank() } ?: existing?.rawStatus,
        failureReason = event.failureReason?.takeIf { it.isNotBlank() } ?: existing?.failureReason,
    )
}
