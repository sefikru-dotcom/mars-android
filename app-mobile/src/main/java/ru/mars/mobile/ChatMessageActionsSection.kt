package ru.mars.mobile

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.text.ClipboardManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Действия над сообщением и операциями хода (копирование, повтор, ветвление).
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; решения — в
 * [MessageActionPolicy].
 */

internal fun ChatActivity.copyMessage(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setText(text)
    statusView.text = "Скопировано"
}

/**
 * Message-actions sheet (WebUI parity): copy / quote / share for one message,
 * плюс серверные операции над ходом (A13): откат/ответвление/повтор.
 * Opened by the «⋮» button and by a long-press anywhere on the bubble.
 * Labels, quote formatting and the empty-text guard live in [MessageActionPolicy].
 * Доступность серверных операций решает [TurnOpsPolicy] — по индексу сообщения,
 * роли и признакам read-only/субагента.
 */

/**
 * Message-actions sheet (WebUI parity): copy / quote / share for one message,
 * плюс серверные операции над ходом (A13): откат/ответвление/повтор.
 * Opened by the «⋮» button and by a long-press anywhere on the bubble.
 * Labels, quote formatting and the empty-text guard live in [MessageActionPolicy].
 * Доступность серверных операций решает [TurnOpsPolicy] — по индексу сообщения,
 * роли и признакам read-only/субагента.
 */
internal const val EDIT_LABEL: String = "Изменить и отправить заново"

internal fun ChatActivity.showMessageActions(message: ChatMessage, index: Int) {
    val actions = MessageActionPolicy.actionsFor(message.text)
    val turnOps = TurnOpsPolicy.messageOps(
        messageIndex = index,
        messageCount = uiState.messages.size,
        role = message.role,
        readOnly = currentSessionReadOnly,
        subagent = currentSessionIsSubagent,
    )
    if (actions.isEmpty() && turnOps.isEmpty()) return
    var dialog: Dialog? = null
    dialog = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Действия с сообщением", null))
        val stack = MarsDialog.pillStack(this, actions.map { kind ->
            kind.label to {
                dialog?.dismiss()
                performMessageAction(kind, message)
            }
        })
        root.addView(stack)
        // Серверные операции отдельным блоком: они меняют историю диалога,
        // а не работают с текстом сообщения, поэтому визуально не смешиваются.
        if (turnOps.isNotEmpty()) {
            val opsStack = MarsDialog.pillStack(this, turnOps.map { kind ->
                kind.label to {
                    dialog?.dismiss()
                    confirmTurnOp(kind, index)
                }
            })
            root.addView(opsStack)
        }
        // Правка своей реплики: удалить её и всё после, отправить заново.
        // Отдельной строкой от серверных операций: это действие человека над ТЕКСТОМ,
        // а не операция над историей — «Откатить до этого хода» сообщение оставляет,
        // правка заменяет его новым и переспрашивает модель.
        if (message.role == "user" && !currentSessionReadOnly && !currentSessionIsSubagent) {
            val editStack = MarsDialog.pillStack(
                this,
                listOf(EDIT_LABEL to { dialog?.dismiss(); editUserMessage(message, index) }),
            )
            root.addView(editStack)
        }
        listOf(stack)
    }
    dialog?.window?.setDimAmount(0.8f)
}

/**
 * A13: серверная операция над ходом. Разрушительные проходят через подтверждение —
 * «откат истории» нельзя выполнить одним случайным тапом.
 */

/**
 * A13: серверная операция над ходом. Разрушительные проходят через подтверждение —
 * «откат истории» нельзя выполнить одним случайным тапом.
 */
internal fun ChatActivity.confirmTurnOp(kind: TurnOpKind, messageIndex: Int) {
    val sessionId = currentSessionId ?: run {
        Toast.makeText(this, TurnOpsRunner.NO_SESSION, Toast.LENGTH_SHORT).show()
        return
    }
    val keepCount = TurnOpsPolicy.keepCountForTruncate(messageIndex)
    if (!TurnOpsPolicy.needsConfirmation(kind)) {
        runTurnOp(kind, sessionId, keepCount)
        return
    }
    var dialog: Dialog? = null
    dialog = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, TurnOpsPresenter.confirmTitle(kind), null))
        root.addView(MarsDialog.paragraph(this, TurnOpsPresenter.confirmBody(kind, keepCount)))
        root.addView(
            MarsDialog.buttonRow(
                this,
                MarsDialog.secondaryButton(this, "Отмена") { dialog?.dismiss() },
                MarsDialog.primaryButton(this, "Выполнить") {
                    dialog?.dismiss()
                    runTurnOp(kind, sessionId, keepCount)
                },
            ),
        )
        emptyList()
    }
}

/** A13: выполняет операцию и применяет [TurnOpOutcome] к экрану. */

/** A13: выполняет операцию и применяет [TurnOpOutcome] к экрану. */
internal fun ChatActivity.runTurnOp(kind: TurnOpKind, sessionId: String, keepCount: Int?) {
    val credential = store.read() ?: run {
        Toast.makeText(this, TurnOpsRunner.NO_CREDENTIAL, Toast.LENGTH_SHORT).show()
        return
    }
    lifecycleScope.launch {
        val outcome = TurnOpsRunner.run(
            kind = kind,
            sessionId = sessionId,
            credential = credential,
            baseUrl = ChatActivity.BASE_URL,
            keepCount = keepCount,
        )
        Toast.makeText(this@runTurnOp, outcome.message, Toast.LENGTH_SHORT).show()
        if (!outcome.ok) return@launch
        // Порядок важен: ветка/копия создаёт НОВЫЙ диалог, поэтому переключение
        // на него отменяет перезагрузку исходного (его транскрипт не изменился).
        val target = outcome.newSessionId
        if (target != null) {
            loadSessionById(target)
            outcome.newTitle?.let {
                uiState.sessionTitle = it
                applySessionTitle(it)
            }
        } else {
            (outcome.reloadSessionId ?: sessionId).let { reloadCurrentSession() }
        }
    }
}

/**
 * Правка своей реплики в серверном чате.
 *
 * Почему через truncate плюс отдельная отправка, а не «отредактировать сообщение на
 * сервере»: ручки правки сообщения у сервера нет — есть только обрезка истории
 * (`POST /api/session/truncate` с `keep_count`). Поэтому правка выражается честно: срезать
 * всё до правимой реплики (саму реплику тоже), затем отправить новый текст обычным путём.
 * Тот же приём, что в локальном чате, — разница лишь в том, кто хранит историю.
 *
 * `keepCount` считается по индексу в транскрипте, а не по «числу сообщений после»: индекс
 * уже учитывает карточки инструментов, которые сервер считает частью истории.
 *
 * @param message правимая реплика (нужна для проверки, что она пользовательская);
 * @param index её позиция в текущем транскрипте.
 */
internal fun ChatActivity.editUserMessage(message: ChatMessage, index: Int) {
    val sessionId = currentSessionId ?: run {
        Toast.makeText(this, TurnOpsRunner.NO_SESSION, Toast.LENGTH_SHORT).show()
        return
    }
    if (message.role != "user") return
    val keepCount = TurnOpsPolicy.keepCountForTruncate(index)
    ChatMessageEditSheet.show(this, message.text, keepCount) { newText ->
        val credential = store.read() ?: run {
            Toast.makeText(this, TurnOpsRunner.NO_CREDENTIAL, Toast.LENGTH_SHORT).show()
            return@show
        }
        lifecycleScope.launch {
            // Сначала обрезка: если она не прошла, новый текст отправлять нельзя —
            // модель получила бы его поверх старой пары «вопрос-ответ», и правка
            // выглядела бы как второй вопрос.
            val outcome = TurnOpsRunner.run(
                kind = TurnOpKind.TRUNCATE,
                sessionId = sessionId,
                credential = credential,
                baseUrl = ChatActivity.BASE_URL,
                keepCount = keepCount,
            )
            if (!outcome.ok) {
                Toast.makeText(this@editUserMessage, outcome.message, Toast.LENGTH_SHORT).show()
                return@launch
            }
            reloadCurrentSession()
            quickSend(newText)
        }
    }
}

internal fun ChatActivity.performMessageAction(kind: MessageActionKind, message: ChatMessage) {
    when (kind) {
        MessageActionKind.COPY -> {
            copyMessage(message.text)
            Toast.makeText(this, MessageActionPolicy.COPY_TOAST, Toast.LENGTH_SHORT).show()
        }
        MessageActionKind.QUOTE -> {
            val author = if (message.role == "user") "Вы" else "Марс"
            composerLayoutPresenter.setQuote(author, message.text)
            updateQuotePlate()
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            statusView.text = "Цитата прикреплена к сообщению"
        }
        MessageActionKind.SHARE -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, MessageActionPolicy.SHARE_SUBJECT)
                putExtra(Intent.EXTRA_TEXT, MessageActionPolicy.shareBody(message.text))
            }
            runCatching { startActivity(Intent.createChooser(intent, "Поделиться сообщением")) }
                .onFailure { statusView.text = "Некому открыть сообщение для шаринга" }
        }
    }
}
