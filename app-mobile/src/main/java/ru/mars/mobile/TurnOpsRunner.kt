package ru.mars.mobile

import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionBranchResult
import ru.mars.network.repository.SessionBranchOutcome
import ru.mars.network.repository.SessionMutationOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionTitleRegenerateOutcome
import ru.mars.network.repository.SessionTurnOpOutcome

/**
 * Готовый к показу результат серверной операции над ходом или сессией (A13).
 *
 * Одна модель на все виды операций: Activity только показывает [message] в Toast,
 * перезагружает транскрипт ([reloadSessionId]) и переключается на новую сессию
 * ([newSessionId]) — решение о тексте принято здесь, в тестируемом коде.
 */
data class TurnOpOutcome(
    val kind: TurnOpKind,
    val ok: Boolean,
    val message: String,
    val removedCount: Int = 0,
    /** Диалог, который надо открыть после успеха (branch/duplicate создают новый). */
    val newSessionId: String? = null,
    /** Новое название сессии (regenerate title). */
    val newTitle: String? = null,
    /** Диалог, чей транскрипт изменился сервером и требует перезагрузки. */
    val reloadSessionId: String? = null,
)

/**
 * Выполнение операций над ходом и сессией (A13) — единственная точка, где Activity
 * касается [SessionRepository] для этих операций.
 *
 * Разделение намеренное: `run(...)` делает сетевой вызов, а чистые мапперы
 * ([fromTurnOp], [fromMutation], [fromBranch], [fromTitle]) превращают outcome-типы
 * репозитория в [TurnOpOutcome] без сети и Android — они и покрыты JVM-тестами.
 *
 * Разрушительность проверяется вызывающим ([TurnOpsPolicy.needsConfirmation]) ДО
 * входа сюда: подтверждение пользователя не может быть частью сетевого слоя.
 */
object TurnOpsRunner {

    const val NO_SESSION = "Операции доступны внутри диалога"
    const val NO_CREDENTIAL = "Нет авторизации"

    /**
     * Выполняет операцию [kind] над сессией [sessionId].
     *
     * [keepCount] обязателен для `truncate` (сколько сообщений оставить) и для
     * `branch` (сколько скопировать); для остальных операций игнорируется.
     */
    suspend fun run(
        kind: TurnOpKind,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
        keepCount: Int? = null,
        repository: SessionRepository = SessionRepository(),
    ): TurnOpOutcome = when (kind) {
        TurnOpKind.UNDO -> fromTurnOp(kind, repository.undoSession(baseUrl, credential, sessionId))
        TurnOpKind.RETRY -> fromTurnOp(kind, repository.retrySession(baseUrl, credential, sessionId))
        TurnOpKind.TRUNCATE -> fromMutation(
            kind,
            sessionId,
            repository.truncateSession(baseUrl, credential, sessionId, keepCount ?: 0),
        )
        TurnOpKind.BRANCH -> fromBranch(repository.branchSession(baseUrl, credential, sessionId, keepCount))
        TurnOpKind.DUPLICATE -> fromMutation(kind, sessionId, repository.duplicateSession(baseUrl, credential, sessionId))
        TurnOpKind.CLEAR -> fromMutation(kind, sessionId, repository.clearSession(baseUrl, credential, sessionId))
        TurnOpKind.REGENERATE_TITLE -> fromTitle(repository.regenerateSessionTitle(baseUrl, credential, sessionId))
    }

    /** `undo`/`retry`: сервер отдаёт счётчик снятых сообщений и текст отказа в `error`. */
    fun fromTurnOp(kind: TurnOpKind, outcome: SessionTurnOpOutcome): TurnOpOutcome = when (outcome) {
        is SessionTurnOpOutcome.Success -> TurnOpOutcome(
            kind = kind,
            ok = true,
            message = TurnOpsPresenter.successMessage(kind, outcome.result.removedCount),
            removedCount = outcome.result.removedCount,
            reloadSessionId = null,
        )
        is SessionTurnOpOutcome.Failure -> TurnOpOutcome(
            kind = kind,
            ok = false,
            message = TurnOpsPresenter.refusalMessage(outcome.message),
        )
    }

    /**
     * `truncate`/`clear`/`duplicate`: мутирующие операции отвечают строкой сессии.
     * Для `duplicate` эта строка — НОВЫЙ диалог, на который надо переключиться.
     */
    fun fromMutation(
        kind: TurnOpKind,
        sessionId: String,
        outcome: SessionMutationOutcome,
    ): TurnOpOutcome = when (outcome) {
        is SessionMutationOutcome.Success -> TurnOpOutcome(
            kind = kind,
            ok = true,
            message = TurnOpsPresenter.successMessage(kind),
            newSessionId = if (kind.spawnsSession) outcome.session?.id else null,
            reloadSessionId = if (kind.spawnsSession) null else sessionId,
        )
        is SessionMutationOutcome.Failure -> TurnOpOutcome(
            kind = kind,
            ok = false,
            message = TurnOpsPresenter.refusalMessage(outcome.message),
        )
    }

    /** `branch`: ответ несёт id и название форка — после успеха экран переходит в него. */
    fun fromBranch(outcome: SessionBranchOutcome): TurnOpOutcome = when (outcome) {
        is SessionBranchOutcome.Success -> TurnOpOutcome(
            kind = TurnOpKind.BRANCH,
            ok = true,
            message = TurnOpsPresenter.successMessage(TurnOpKind.BRANCH),
            newSessionId = outcome.branch.sessionId,
            newTitle = branchTitle(outcome.branch),
        )
        is SessionBranchOutcome.Failure -> TurnOpOutcome(
            kind = TurnOpKind.BRANCH,
            ok = false,
            message = TurnOpsPresenter.refusalMessage(outcome.message),
        )
    }

    /** `title/regenerate`: название меняется у ТОЙ ЖЕ сессии — транскрипт перечитывать незачем. */
    fun fromTitle(outcome: SessionTitleRegenerateOutcome): TurnOpOutcome = when (outcome) {
        is SessionTitleRegenerateOutcome.Success -> TurnOpOutcome(
            kind = TurnOpKind.REGENERATE_TITLE,
            ok = true,
            message = TurnOpsPresenter.successMessage(TurnOpKind.REGENERATE_TITLE),
            newTitle = outcome.result.title,
        )
        is SessionTitleRegenerateOutcome.Failure -> TurnOpOutcome(
            kind = TurnOpKind.REGENERATE_TITLE,
            ok = false,
            message = TurnOpsPresenter.refusalMessage(outcome.message),
        )
    }

    private fun branchTitle(branch: SessionBranchResult): String? =
        branch.title?.trim()?.takeUnless { it.isEmpty() }
}
