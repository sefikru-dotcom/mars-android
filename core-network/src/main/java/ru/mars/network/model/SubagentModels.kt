package ru.mars.network.model

/**
 * Состояние ребёнка-субагента на живой доске делегирования (P0-2, клиентская половина).
 *
 * Ровно пять значений — набор согласован с серверной классификацией исходов
 * (hermes-agent tools/delegate_tool_progress.py `SUBAGENT_FAILURE_STATUSES`,
 * hermes-webui api/worker_status.py): всё, что сервер не назвал успехом/ожиданием/
 * остановкой, считается провалом. Презентация (иконки, подписи, цвета) — работа
 * UI-слоя; модель данных состояний не расширяет.
 */
enum class SubagentState {
    /** Ребёнок запущен и работает. */
    Running,

    /** Ребёнок успешно завершён. */
    Completed,

    /** Ребёнок завершился ошибкой (failed/error/timeout/...). */
    Failed,

    /** Ребёнок жив, но ждёт (внешний ввод, подтверждение, очередь). */
    Waiting,

    /** Ребёнок остановлен (стеринг/отмена/остановка хода). */
    Stopped;

    /** Активен = ещё может производить события (Running/Waiting). Терминальные дети не активны. */
    val isActive: Boolean get() = this == Running || this == Waiting

    /** Терминален = исход зафиксирован, дальнейшие live-события состояние не меняют. */
    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Stopped
}

/**
 * Снимок состояния ОДНОГО ребёнка для доски субагентов.
 *
 * Все описательные поля опциональны: null означает «сервер не сообщил» —
 * транспорт никогда не подставляет догадки (модель из локального выбора,
 * имя из запроса и т.п.). [rawStatus] — дословный серверный статус кадра;
 * классификация в [state] выполнена [ru.mars.network.repository.SubagentStatusPolicy],
 * оригинал сохранён для отладки/презентации.
 */
data class SubagentStatus(
    val childId: String,
    val state: SubagentState,
    val displayName: String? = null,
    val model: String? = null,
    val phase: String? = null,
    val rawStatus: String? = null,
    val failureReason: String? = null,
) {
    val isActive: Boolean get() = state.isActive
    val isTerminal: Boolean get() = state.isTerminal
}

/**
 * Одно live-событие жизненного цикла субагента, разобранное из SSE-кадра
 * ([ru.mars.network.repository.StreamEventMapper.subagentUpdate]).
 *
 * Транспорт delivers факт как есть; состояние доски из последовательности таких
 * событий вычисляет чистый редьюсер [ru.mars.network.repository.SubagentStatusPolicy].
 * [childId] может быть null (кадр без идентификаторов) — такое событие
 * не адресуется ни одному ребёнку и политикой игнорируется.
 *
 * [Kind.TurnStopped] — служебное событие БЕЗ wire-кадра субагента: терминальные
 * кадры самого хода (`cancel`/`error`/`apperror`) означают, что активный ребёнок
 * продолжить не может и должен считаться остановленным.
 */
data class SubagentUpdate(
    val kind: Kind,
    val childId: String?,
    val displayName: String? = null,
    val model: String? = null,
    val phase: String? = null,
    val rawStatus: String? = null,
    val failureReason: String? = null,
) {
    enum class Kind {
        /** `subagent.start` / `delegate.task_spawned`. */
        Start,

        /** `subagent.progress` / `subagent_progress` / `delegate.task_progress`. */
        Progress,

        /** `subagent.complete` / `delegate.task_completed` / `delegate.task_failed`. */
        Complete,

        /** Прогресс-кадр со статусом ожидания (waiting/blocked/...). */
        Waiting,

        /** Кадр субагента, который политика не умеет интерпретировать как факт состояния. */
        Unknown,

        /** Ход завершён аварийно/остановлен (`cancel`/`error`/`apperror`). */
        TurnStopped,
    }
}
