package ru.mars.mobile

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.CreateSessionOutcome
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionTurnOpOutcome

/**
 * Исполнение слэш-команд композера: сеть, корутины, строка статуса.
 *
 * Разбор и валидация — в чистой [SlashCommandPolicy] (JVM-тесты), здесь только эффекты.
 * Ответ команды идёт СТРОКОЙ СТАТУСА (как `/goal` в ChatStatusSection.kt), а не пузырём
 * транскрипта: это отклик на команду, а не сообщение диалога.
 *
 * Транспорты не дублируются: операции над сессией идут через [SessionRepository] и
 * мапперы [TurnOpsRunner], остановка/перехват хода — через [ChatRepository], уровень
 * мышления — через [ReasoningRepository].
 */

/** Сколько живёт ответ команды в строке статуса (как у `/goal`). */
private const val SLASH_REPLY_TTL_MS = 6_000L

/** Сессия и авторизация, нужные серверным командам. */
private class SlashSession(val sessionId: String, val credential: ConsumedDeviceCredential)

/**
 * Точка входа диспетчера (вызывается из `sendMessage` ДО отправки в модель).
 * Неизвестная команда и команда без транспорта получают честный ответ и в модель
 * не уходят.
 */
internal fun ChatActivity.handleSlashCommand(text: String) {
    when (val resolution = SlashCommandPolicy.resolve(text)) {
        null -> return // не команда — этот путь не должен был сюда попасть
        is SlashCommandResolution.Unknown ->
            replySlashCommand(SlashCommandPolicy.unknownMessage(resolution.command.name))
        is SlashCommandResolution.Unavailable ->
            replySlashCommand(SlashCommandPolicy.unavailableMessage(resolution.command.name))
        is SlashCommandResolution.Ready -> executeSlashCommand(text, resolution.command)
    }
    input.text.clear()
    updateControls()
}

/**
 * Транзиентный ответ команды. Слот `goalReplyJob` общий с `/goal`: в строке статуса
 * живёт ровно одно сообщение команды, поэтому новый ответ обязан снять прежний таймер
 * (иначе старый таймер погасил бы свежий текст).
 */
internal fun ChatActivity.replySlashCommand(message: String) {
    explicitStatus = message
    setStatus()
    goalReplyJob?.cancel()
    goalReplyJob = lifecycleScope.launch {
        delay(SLASH_REPLY_TTL_MS)
        if (explicitStatus == message) {
            explicitStatus = null
            setStatus()
        }
    }
}

private fun ChatActivity.executeSlashCommand(text: String, command: SlashCommand) {
    when (command.name) {
        "help" -> replySlashCommand(SlashCommandPolicy.helpText())
        // `/goal` остаётся владением GoalLoopPolicy: своя карточка, свои подкоманды.
        "goal" -> handleGoalCommand(text)
        "stop" -> slashStop()
        "new" -> slashNew()
        "retry" -> slashRetry()
        "undo" -> slashUndo()
        "branch" -> slashBranch(command.args)
        "clear" -> slashClear()
        "title" -> slashTitle(command.args)
        "truncate" -> slashTruncate(command.args)
        "steer" -> slashSteer(command.args)
        "reasoning" -> slashReasoning(command.args)
        else -> replySlashCommand(SlashCommandPolicy.unknownMessage(command.name))
    }
}

/**
 * Гейт серверных команд: read-only и субагентские диалоги менять нельзя (сервер отвечает
 * 400), а без сессии/авторизации операция бессмысленна. Отказ — тем же статусом, что и
 * у кнопок меню сообщений ([TurnOpsRunner.NO_SESSION]/[TurnOpsRunner.NO_CREDENTIAL]).
 */
private fun ChatActivity.slashSession(): SlashSession? {
    if (currentSessionReadOnly) {
        replySlashCommand("Сессия доступна только для чтения")
        return null
    }
    if (currentSessionIsSubagent) {
        replySlashCommand("Субагентский диалог доступен только для чтения")
        return null
    }
    val sessionId = currentSessionId ?: run {
        replySlashCommand(TurnOpsRunner.NO_SESSION)
        return null
    }
    val credential = store.read() ?: run {
        replySlashCommand(TurnOpsRunner.NO_CREDENTIAL)
        return null
    }
    return SlashSession(sessionId, credential)
}

/** Ход действительно идёт: стрим открыт или статус в TYPING (тот же признак, что у «Стоп»). */
private fun ChatActivity.isTurnRunning(): Boolean =
    uiState.status == ChatStatus.TYPING || currentStreamId != null

/** `/stop` — локальная остановка хода теми же вызовами, что кнопка «Стоп». */
private fun ChatActivity.slashStop() {
    if (!isTurnRunning()) {
        replySlashCommand("${SlashCommandPolicy.NO_TURN} — останавливать нечего")
        return
    }
    cancelStream()
    replySlashCommand("Ход остановлен")
}

/**
 * `/new` — диалог создаётся на сервере (`POST /api/session/new`), затем экран переходит
 * в него тем же путём, что кнопка «Новый чат» ([startNewChat]); при сбое сервера новый
 * чат всё равно открывается локально — человек не остаётся в старом диалоге.
 */
private fun ChatActivity.slashNew() {
    val credential = store.read() ?: run {
        replySlashCommand(TurnOpsRunner.NO_CREDENTIAL)
        return
    }
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            SessionRepository().createSession(ChatActivity.BASE_URL, credential)
        }
        when (outcome) {
            is CreateSessionOutcome.Success -> {
                startNewChat()
                loadSessionById(outcome.session.id)
                replySlashCommand("Новый диалог")
            }
            is CreateSessionOutcome.Failure -> {
                startNewChat()
                replySlashCommand("Сервер не создал диалог (${outcome.message}) — открыт новый чат")
            }
        }
    }
}

/**
 * `/retry` — `POST /api/session/retry`: сервер снимает последний ответ и возвращает
 * текст последнего запроса. Транскрипт перечитывается, текст возвращается в композер
 * (WebUI кладёт его туда же): повторная отправка — решение человека, а не тихий
 * автоматический ход от имени команды.
 */
private fun ChatActivity.slashRetry() {
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            SessionRepository().retrySession(ChatActivity.BASE_URL, target.credential, target.sessionId)
        }
        when (outcome) {
            is SessionTurnOpOutcome.Success -> {
                reloadCurrentSession()
                val text = outcome.result.lastUserText?.trim().orEmpty()
                if (text.isEmpty()) {
                    replySlashCommand("Ход снят, но сервер не вернул текст запроса — история обновлена")
                } else {
                    setComposerTextProgrammatically(text, TextApplyOrigin.DRAFT_RESTORE)
                    replySlashCommand("Ход снят — текст в поле, отправьте для повтора")
                }
            }
            is SessionTurnOpOutcome.Failure ->
                replySlashCommand(TurnOpsPresenter.refusalMessage(outcome.message))
        }
    }
}

/** `/undo` — `POST /api/session/undo` через общий [TurnOpsRunner]. */
private fun ChatActivity.slashUndo() {
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val outcome = TurnOpsRunner.run(
            kind = TurnOpKind.UNDO,
            sessionId = target.sessionId,
            credential = target.credential,
            baseUrl = ChatActivity.BASE_URL,
        )
        applySlashTurnOp(outcome)
    }
}

/** `/clear` — `POST /api/session/clear`: история стирается, диалог остаётся. */
private fun ChatActivity.slashClear() {
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val outcome = TurnOpsRunner.run(
            kind = TurnOpKind.CLEAR,
            sessionId = target.sessionId,
            credential = target.credential,
            baseUrl = ChatActivity.BASE_URL,
        )
        applySlashTurnOp(outcome)
    }
}

/**
 * `/branch [название]` — `POST /api/session/branch`. Без аргумента сервер сам выведет имя
 * (`<исходное> (fork)`) и скопирует всю историю; аргумент становится названием ветки.
 */
private fun ChatActivity.slashBranch(args: String) {
    val target = slashSession() ?: return
    val title = args.trim().takeIf { it.isNotEmpty() }
    lifecycleScope.launch {
        val outcome = TurnOpsRunner.fromBranch(
            SessionRepository().branchSession(
                baseUrl = ChatActivity.BASE_URL,
                credential = target.credential,
                sessionId = target.sessionId,
                title = title,
            )
        )
        applySlashTurnOp(outcome)
    }
}

/** `/truncate <N>` — `POST /api/session/truncate`: оставить N сообщений. */
private fun ChatActivity.slashTruncate(args: String) {
    val count = SlashCommandPolicy.truncateCount(args) ?: run {
        replySlashCommand(SlashCommandPolicy.TRUNCATE_USAGE)
        return
    }
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val outcome = TurnOpsRunner.run(
            kind = TurnOpKind.TRUNCATE,
            sessionId = target.sessionId,
            credential = target.credential,
            baseUrl = ChatActivity.BASE_URL,
            keepCount = count,
        )
        applySlashTurnOp(outcome)
    }
}

/** `/title <текст>` — `POST /api/session/rename`; заголовок в шапке обновляется сразу. */
private fun ChatActivity.slashTitle(args: String) {
    val title = args.trim()
    if (title.isEmpty()) {
        replySlashCommand(SlashCommandPolicy.TITLE_USAGE)
        return
    }
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val renamed = SessionRepository().renameSession(
            baseUrl = ChatActivity.BASE_URL,
            credential = target.credential,
            sessionId = target.sessionId,
            newTitle = title,
        )
        if (renamed) {
            uiState.sessionTitle = title
            applySessionTitle(title)
            replySlashCommand("Диалог переименован: $title")
        } else {
            replySlashCommand("Не удалось переименовать диалог")
        }
    }
}

/** `/steer <текст>` — `POST /api/chat/steer`; осмыслен только во время хода. */
private fun ChatActivity.slashSteer(args: String) {
    val text = args.trim()
    if (text.isEmpty()) {
        replySlashCommand(SlashCommandPolicy.STEER_USAGE)
        return
    }
    if (!isTurnRunning()) {
        replySlashCommand("${SlashCommandPolicy.NO_TURN} — /steer работает только во время ответа")
        return
    }
    val target = slashSession() ?: return
    lifecycleScope.launch {
        val accepted = withContext(Dispatchers.IO) {
            runCatching {
                val repository = ChatRepository()
                repository.executeControl(
                    repository.steerRequest(
                        ChatActivity.BASE_URL,
                        target.sessionId,
                        text,
                        target.credential.accessToken,
                    )
                )
            }.getOrDefault(false)
        }
        replySlashCommand(if (accepted) "Уточнение отправлено" else "Уточнение не принято сервером")
    }
}

/**
 * `/reasoning [show|hide|<уровень>]` — `GET/POST /api/reasoning`. Без аргумента показывает
 * текущее состояние; `show`/`hide` пишут `display.show_reasoning` (как CLI/WebUI), уровень
 * уходит через тот же [ReasoningRepository.setEffort], что и чип в шапке, поэтому чип
 * перерисовывается из ответа сервера, а не из локальной догадки.
 */
private fun ChatActivity.slashReasoning(args: String) {
    val request = SlashCommandPolicy.reasoningRequest(args) ?: run {
        replySlashCommand(SlashCommandPolicy.REASONING_USAGE)
        return
    }
    val credential = store.read() ?: run {
        replySlashCommand(TurnOpsRunner.NO_CREDENTIAL)
        return
    }
    val model = modelSelection.displayedChoice()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            val repository = ReasoningRepository()
            when (request) {
                ReasoningRequest.ShowCurrent -> repository.fetchStatus(
                    ChatActivity.BASE_URL, credential, model?.modelId, model?.providerId, currentSessionId,
                )
                ReasoningRequest.Show -> repository.setDisplay(ChatActivity.BASE_URL, credential, true)
                ReasoningRequest.Hide -> repository.setDisplay(ChatActivity.BASE_URL, credential, false)
                is ReasoningRequest.Effort -> repository.setEffort(
                    ChatActivity.BASE_URL, credential, request.effort, model?.modelId, model?.providerId, currentSessionId,
                )
            }
        }
        when (result) {
            is ReasoningStatusResult.Failed ->
                replySlashCommand("Уровень размышлений недоступен: ${result.message}")
            is ReasoningStatusResult.Status -> {
                val status = result.status
                if (request is ReasoningRequest.Effort) reasoningSelection.select(request.effort)
                reasoningSelection.applyStatus(status)
                updateReasoningButton()
                val visibility = if (status.showReasoning) "показ вкл" else "показ выкл"
                replySlashCommand(
                    "Размышления: $visibility · уровень ${reasoningSelection.label(status.reasoningEffort)}"
                )
            }
        }
    }
}

/**
 * Применение серверной операции к экрану — те же правила, что у меню сообщений
 * (`runTurnOp`): успех с новым диалогом переключает на него, иначе транскрипт
 * перечитывается; отказ сервера показывается его собственным текстом.
 */
private fun ChatActivity.applySlashTurnOp(outcome: TurnOpOutcome) {
    replySlashCommand(outcome.message)
    if (!outcome.ok) return
    val target = outcome.newSessionId
    if (target != null) {
        loadSessionById(target)
        outcome.newTitle?.let {
            uiState.sessionTitle = it
            applySessionTitle(it)
        }
    } else {
        reloadCurrentSession()
    }
}
