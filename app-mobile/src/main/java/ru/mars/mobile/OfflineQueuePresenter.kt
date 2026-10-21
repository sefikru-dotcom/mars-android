package ru.mars.mobile

/**
 * Презентационное состояние офлайн-очереди ([OfflineMessageQueuePolicy]) — чистая JVM-модель
 * без Android-импортов; хост (ChatActivity) лишь маппит состояния на views и форвардит нажатия.
 *
 * Зона ответственности (данные очереди и сети остаются в политике и [NetworkStatePolicy]):
 * - панель неотправленных сообщений текущего диалога: строки WAITING/SENDING из FIFO-снимка
 *   очереди плюс FAILED-ходы, у которых исчерпан лимит попыток (политика их уже удалила —
 *   презентер хранит их отдельно, чтобы «не доставлено» не исчезало бесследно);
 * - бейдж очереди: суммарный счётчик (очередь + недоставленные) с тоном — ERROR при наличии
 *   недоставленных, WARNING без сети, OK при автоотправке на восстановленной связи;
 * - ретрай недоставленного хода: [requestRetry] выдаёт [RetryOutcome.TriggerSend] ровно один
 *   раз (повторный тап до завершения отправки отклоняется — double-trigger guard);
 * - discard-поток недоставленных ходов: двухшаговое подтверждение
 *   ([requestDiscard]/[requestDiscardAll] → промпт → [confirmDiscard]/[cancelDiscard]),
 *   чтобы случайный тап не удалял текст безвозвратно.
 *
 * Контракт хоста по in-flight статусу: каждый [onSendStarted]/[RetryOutcome.TriggerSend]
 * обязан завершиться ровно одним [onSendSucceeded] (успех), [onTurnFailed] (провал) или
 * [onVoiceModeRejected] (сервер отверг голосовой режим) с тем же id — иначе строка
 * останется в статусе SENDING.
 */

/**
 * Недоставленный ход очереди: лимит попыток исчерпан или сервер отверг голосовой режим —
 * сообщение живёт в презентере, текст сохраняется.
 *
 * [voiceMode] — намерение хода (был ли он голосовым), чтобы осознанный повтор не потерял
 * его и не подменил режим молча.
 *
 * [voiceModeRejected] — отказ именно по `409 unsupported_voice_mode_backend`. Такой ход
 * НЕ ретраится автоматически (сервер отвергнет его детерминированно) и не считается
 * отправленным: человеку показывается отдельная причина, а повтор предлагается как
 * «Отправить текстом» — это его решение, а не тихая деградация.
 */
data class FailedQueuedTurn(
    val id: String,
    val text: String,
    val sessionId: String?,
    val attempts: Int,
    val failedAtEpochMillis: Long,
    val voiceMode: Boolean = false,
    val voiceModeRejected: Boolean = false,
)

/** Строка панели неотправленных сообщений. */
enum class OfflineQueueRowStatus { WAITING, SENDING, FAILED }

/** Строка панели: идентификатор, однострочный отрывок, статус и доступные действия. */
data class OfflineQueueRowUi(
    val id: String,
    val preview: String,
    val status: OfflineQueueRowStatus,
    val statusLabel: String,
    val retryAvailable: Boolean,
    /** Подпись кнопки повтора: для отказа голосовому режиму — «Отправить текстом». */
    val retryLabel: String,
    val discardAvailable: Boolean,
    /** Строка отмечена в текущем промпте подтверждения удаления. */
    val awaitingDiscardConfirm: Boolean,
    val contentDescription: String,
)

/** Цель discard-подтверждения: один недоставленный ход или все сразу. */
sealed interface DiscardTarget {
    data class Single(val messageId: String) : DiscardTarget
    data object AllFailed : DiscardTarget
}

/** Спека диалога подтверждения удаления (тексты готовит презентер, хост только рендерит). */
data class DiscardPromptUi(
    val target: DiscardTarget,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

/** Причина отказа ручного ретрая. */
enum class RetryRejectReason { UNKNOWN_TURN, ALREADY_IN_FLIGHT }

sealed interface RetryOutcome {
    /**
     * Хост повторно отправляет ход (обычно через свежий [OfflineMessageQueuePolicy.enqueue],
     * сбрасывающий счётчик попыток). Презентер уже отметил ход как SENDING — повторный
     * [OfflineQueuePresenter.requestRetry] до завершения отправки будет отклонён.
     *
     * [voiceMode] — намерение повтора: `true` только если ход был голосовым и сервер его
     * не отвергал. После отказа (`voiceModeRejected`) повтор идёт текстом (`false`), и это
     * осознанное действие человека (кнопка «Отправить текстом»), а не молчаливая подмена.
     */
    data class TriggerSend(
        val messageId: String,
        val text: String,
        val sessionId: String?,
        val voiceMode: Boolean = false,
    ) : RetryOutcome

    data class Rejected(val reason: RetryRejectReason) : RetryOutcome
}

sealed interface DiscardOutcome {
    /** Подтверждено удаление одного хода; хост снимает его локальное эхо из транскрипта. */
    data class Discard(val turn: FailedQueuedTurn) : DiscardOutcome

    /** Подтверждена очистка всех недоставленных; презентер уже опустошил список. */
    data class DiscardAll(val turns: List<FailedQueuedTurn>) : DiscardOutcome

    /** Подтверждать нечего (промпт не запрашивали или ход исчез). */
    data object NothingToDiscard : DiscardOutcome
}

/** Бейдж очереди: счётчик неотправленных сообщений для пилюли/иконки. */
data class OfflineQueueBadgeUi(
    val visible: Boolean,
    /** Точный счётчик (очередь + недоставленные) — для логики и доступности. */
    val count: Int,
    /** Подпись бейджа с потолком «99+». */
    val label: String,
    val tone: Tone,
    val contentDescription: String,
) {
    /** Смысловой тон → цвет токена дизайн-системы на стороне хоста. */
    enum class Tone { OK, WARNING, ERROR }

    companion object {
        val Hidden = OfflineQueueBadgeUi(
            visible = false,
            count = 0,
            label = "",
            tone = Tone.OK,
            contentDescription = "",
        )
    }
}

/** Полное состояние панели неотправленных сообщений для рендера. */
data class OfflineQueuePanelUi(
    val visible: Boolean,
    val title: String,
    val rows: List<OfflineQueueRowUi>,
    val badge: OfflineQueueBadgeUi,
    /** Кнопка «удалить все недоставленные» имеет смысл от двух ходов. */
    val discardAllVisible: Boolean,
    /** Активный промпт подтверждения удаления (null, пока discard не запрошен). */
    val discardPrompt: DiscardPromptUi?,
)

/**
 * Чистая презентационная логика очереди (контракт — в шапке файла). Состояние:
 * недоставленные ходы, in-flight id активных отправок и активный discard-промпт.
 */
class OfflineQueuePresenter(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** id → недоставленный ход (LinkedHashMap сохраняет порядок провалов). */
    private val failedTurns = LinkedHashMap<String, FailedQueuedTurn>()
    private val sendingIds = LinkedHashSet<String>()
    private var pendingDiscard: DiscardPromptUi? = null

    /** Снимок недоставленных ходов в порядке провала. */
    val failed: List<FailedQueuedTurn> get() = failedTurns.values.toList()

    /** Текущий промпт подтверждения удаления (для перерисовки панели без нового запроса). */
    val discardPrompt: DiscardPromptUi? get() = pendingDiscard

    /** Хост начал отправку сообщения очереди — строка переходит в SENDING. */
    fun onSendStarted(messageId: String) {
        sendingIds += messageId
    }

    /**
     * Отправка успешна: снимаем in-flight статус и забываем ход (включая повторный ретрай
     * ранее недоставленного). Заодно гасим промпт удаления этого хода — удалять уже нечего.
     */
    fun onSendSucceeded(messageId: String) {
        sendingIds -= messageId
        failedTurns -= messageId
        val target = pendingDiscard?.target
        if (target is DiscardTarget.Single && target.messageId == messageId) pendingDiscard = null
    }

    /**
     * Ход окончательно не доставлен (лимит попыток [OfflineMessageQueuePolicy] исчерпан или
     * ретрай снова провалился): презентер забирает его себе — строка FAILED с ретраем и
     * удалением. Повторный вызов с тем же id заменяет запись (дублей не бывает).
     */
    fun onTurnFailed(message: QueuedMessage) {
        sendingIds -= message.id
        failedTurns[message.id] = FailedQueuedTurn(
            id = message.id,
            text = message.text,
            sessionId = message.sessionId,
            attempts = message.attempts,
            failedAtEpochMillis = clock(),
            voiceMode = message.voiceMode,
        )
    }

    /**
     * Сервер отверг голосовой режим хода (`409 unsupported_voice_mode_backend`).
     *
     * Отдельный вход, а не [onTurnFailed], потому что последствия разные:
     * 1. Это **не транспортный провал** — повтор с тем же `voice_mode` даст тот же 409,
     *    поэтому строка не возвращается в автоотправку вовсе (никаких бесконечных попыток).
     * 2. Это **не успех** — [onSendSucceeded] здесь запрещён: очередь не имеет права
     *    считать ход доставленным, иначе текст исчез бы вместе с причиной.
     * 3. Текст **сохраняется** в карточке недоставленных: человек либо повторит его
     *    осознанно как текст ([LABEL_SEND_AS_TEXT]), либо удалит.
     */
    fun onVoiceModeRejected(message: QueuedMessage) {
        sendingIds -= message.id
        failedTurns[message.id] = FailedQueuedTurn(
            id = message.id,
            text = message.text,
            sessionId = message.sessionId,
            attempts = message.attempts,
            failedAtEpochMillis = clock(),
            // Голосовое намерение больше не применимо: бэкенд его не принимает.
            voiceMode = false,
            voiceModeRejected = true,
        )
    }

    /** True, пока сообщение отмечено как actively отправляемое. */
    fun isSending(messageId: String): Boolean = messageId in sendingIds

    /**
     * Бейдж очереди: [queueSize] — размер FIFO-очереди (все диалоги), тон по наличию
     * недоставленных и состоянию сети. Пустая очередь без провалов — скрытый бейдж.
     */
    fun badgeFor(queueSize: Int, network: NetworkState): OfflineQueueBadgeUi {
        val failedCount = failedTurns.size
        val count = (queueSize.coerceAtLeast(0) + failedCount)
        if (count == 0) return OfflineQueueBadgeUi.Hidden
        val tone = when {
            failedCount > 0 -> OfflineQueueBadgeUi.Tone.ERROR
            !network.sendAllowed -> OfflineQueueBadgeUi.Tone.WARNING
            else -> OfflineQueueBadgeUi.Tone.OK
        }
        return OfflineQueueBadgeUi(
            visible = true,
            count = count,
            label = if (count > BADGE_MAX_COUNT) "$BADGE_MAX_COUNT+" else count.toString(),
            tone = tone,
            contentDescription = "Неотправленные сообщения: ${messagesLabel(count)}",
        )
    }

    /**
     * Панель неотправленных для диалога [sessionId] (null — новый чат): FIFO-строки очереди
     * этого диалога плюс его недоставленные ходы. Бейдж при этом всегда глобальный.
     * Строки очереди, уже учтённые как недоставленные, не дублируются.
     */
    fun panelFor(queue: List<QueuedMessage>, network: NetworkState, sessionId: String?): OfflineQueuePanelUi {
        // Снимок может по гонке содержать уже провалившееся сообщение — в бейдже оно учитывается один раз.
        val badge = badgeFor(queue.count { it.id !in failedTurns }, network)
        val prompt = pendingDiscard
        val queued = queue.filter { it.sessionId == sessionId && it.id !in failedTurns }
        val failedRows = failedTurns.values.filter { it.sessionId == sessionId }.toList()
        if (queued.isEmpty() && failedRows.isEmpty()) {
            return OfflineQueuePanelUi(
                visible = false,
                title = PANEL_TITLE,
                rows = emptyList(),
                badge = badge,
                discardAllVisible = false,
                discardPrompt = prompt,
            )
        }
        val rows = queued.map { queuedRow(it, network) } +
            failedRows.map { failedRow(it, prompt) }
        return OfflineQueuePanelUi(
            visible = true,
            title = PANEL_TITLE,
            rows = rows,
            badge = badge,
            discardAllVisible = failedRows.size >= DISCARD_ALL_MIN_FAILED,
            discardPrompt = prompt,
        )
    }

    /**
     * Ручной ретрай недоставленного хода. Grant убирает ход из списка FAILED и отмечает
     * in-flight — повторный запрос того же id отклоняется ([RetryRejectReason.ALREADY_IN_FLIGHT])
     * до [onSendSucceeded]/[onTurnFailed]. Ретрай хода, который ещё в FIFO-очереди, не нужен:
     * такие строки автоотправляются ([RetryRejectReason.UNKNOWN_TURN]).
     */
    fun requestRetry(messageId: String): RetryOutcome {
        if (messageId in sendingIds) return RetryOutcome.Rejected(RetryRejectReason.ALREADY_IN_FLIGHT)
        val turn = failedTurns[messageId]
            ?: return RetryOutcome.Rejected(RetryRejectReason.UNKNOWN_TURN)
        failedTurns -= messageId
        sendingIds += messageId
        // Отвергнутый голосовой ход повторяется ТОЛЬКО текстом: кнопка называется
        // «Отправить текстом», то есть человек уже прочитал причину и согласился.
        return RetryOutcome.TriggerSend(
            messageId = turn.id,
            text = turn.text,
            sessionId = turn.sessionId,
            voiceMode = if (turn.voiceModeRejected) false else turn.voiceMode,
        )
    }

    /**
     * Шаг 1 discard-потока одного хода: запоминает промпт подтверждения и возвращает его
     * (null — ход неизвестен или ещё не провалился: WAITING/SENDING строки не удаляются здесь).
     */
    fun requestDiscard(messageId: String): DiscardPromptUi? {
        val turn = failedTurns[messageId] ?: return null
        val prompt = DiscardPromptUi(
            target = DiscardTarget.Single(messageId),
            title = "Удалить сообщение?",
            message = "«${previewOf(turn.text)}» не доставлено. Удалить из списка неотправленных?",
            confirmLabel = "Удалить",
            cancelLabel = "Отмена",
        )
        pendingDiscard = prompt
        return prompt
    }

    /** Шаг 1 discard-потока всех недоставленных (null — провалившихся ходов нет). */
    fun requestDiscardAll(): DiscardPromptUi? {
        val turns = failed
        if (turns.isEmpty()) return null
        val prompt = DiscardPromptUi(
            target = DiscardTarget.AllFailed,
            title = "Удалить недоставленные?",
            message = "Не доставлено ${messagesLabel(turns.size)}. Удалить их из списка неотправленных?",
            confirmLabel = "Удалить все",
            cancelLabel = "Отмена",
        )
        pendingDiscard = prompt
        return prompt
    }

    /**
     * Шаг 2: пользователь подтвердил удаление. Презентер сразу очищает своё состояние —
     * хост по [DiscardOutcome] снимает локальные эха из транскрипта.
     */
    fun confirmDiscard(): DiscardOutcome {
        val prompt = pendingDiscard ?: return DiscardOutcome.NothingToDiscard
        pendingDiscard = null
        return when (prompt.target) {
            is DiscardTarget.Single -> {
                val turn = failedTurns.remove(prompt.target.messageId)
                    ?: return DiscardOutcome.NothingToDiscard
                DiscardOutcome.Discard(turn)
            }
            DiscardTarget.AllFailed -> {
                val turns = failed
                if (turns.isEmpty()) return DiscardOutcome.NothingToDiscard
                failedTurns.clear()
                DiscardOutcome.DiscardAll(turns)
            }
        }
    }

    /** Шаг 2 (отмена): промпт сброшен, ходы остались в списке недоставленных. */
    fun cancelDiscard() {
        pendingDiscard = null
    }

    /** Диалог удалён/завершён — его недоставленные ходы больше не релевантны. */
    fun removeFailedFor(sessionId: String) {
        val doomed = failedTurns.values.filter { it.sessionId == sessionId }.map { it.id }
        if (doomed.isEmpty()) return
        doomed.forEach { failedTurns -= it }
        val target = pendingDiscard?.target
        if (target is DiscardTarget.Single && target.messageId in doomed) pendingDiscard = null
        if (target is DiscardTarget.AllFailed && failedTurns.isEmpty()) pendingDiscard = null
    }

    /** Полный сброс (logout/реавторизация): состояние презентера не переживает смену пользователя. */
    fun reset() {
        failedTurns.clear()
        sendingIds.clear()
        pendingDiscard = null
    }

    private fun queuedRow(message: QueuedMessage, network: NetworkState): OfflineQueueRowUi {
        val sending = message.id in sendingIds
        val status = if (sending) OfflineQueueRowStatus.SENDING else OfflineQueueRowStatus.WAITING
        val statusLabel = when {
            sending -> LABEL_SENDING
            network.sendAllowed -> LABEL_AUTO_SEND
            else -> LABEL_WAITING
        }
        val preview = previewOf(message.text)
        return OfflineQueueRowUi(
            id = message.id,
            preview = preview,
            status = status,
            statusLabel = statusLabel,
            retryAvailable = false,
            retryLabel = LABEL_RETRY,
            discardAvailable = false,
            awaitingDiscardConfirm = false,
            contentDescription = "$statusLabel. $preview",
        )
    }

    private fun failedRow(turn: FailedQueuedTurn, prompt: DiscardPromptUi?): OfflineQueueRowUi {
        val preview = previewOf(turn.text)
        val awaiting = prompt?.target.let { it is DiscardTarget.Single && it.messageId == turn.id }
        // Отказ голосовому режиму — не «не доставлено по связи»: причина называется прямо,
        // а кнопка повтора честно обещает отправку текстом, а не повтор той же попытки.
        val statusLabel = if (turn.voiceModeRejected) LABEL_VOICE_UNSUPPORTED else LABEL_FAILED
        val retryLabel = if (turn.voiceModeRejected) LABEL_SEND_AS_TEXT else LABEL_RETRY
        return OfflineQueueRowUi(
            id = turn.id,
            preview = preview,
            status = OfflineQueueRowStatus.FAILED,
            statusLabel = statusLabel,
            retryAvailable = turn.id !in sendingIds,
            retryLabel = retryLabel,
            discardAvailable = true,
            awaitingDiscardConfirm = awaiting,
            contentDescription = "$statusLabel. $preview",
        )
    }

    companion object {
        const val PANEL_TITLE = "Неотправленные сообщения"
        const val LABEL_WAITING = "Ждёт связи"
        const val LABEL_AUTO_SEND = "Отправится автоматически"
        const val LABEL_SENDING = "Отправка…"
        const val LABEL_FAILED = "Не доставлено"
        /** Причина отказа именно голосового режима: сервер такой бэкенд не поддерживает. */
        const val LABEL_VOICE_UNSUPPORTED = "Сервер не поддерживает голосовой режим"
        /** Кнопка повтора обычного провала. */
        const val LABEL_RETRY = "Повторить"
        /** Кнопка повтора отвергнутого голосового хода — осознанная отправка текстом. */
        const val LABEL_SEND_AS_TEXT = "Отправить текстом"

        /** Потолок подписи бейджа: трёхзначные счётчики схлопываются в «99+». */
        const val BADGE_MAX_COUNT = 99

        /** Предел однострочного отрывка в строке панели и в промпте удаления. */
        const val PREVIEW_MAX_CHARS = 80

        /** Кнопка «удалить все» появляется от этого числа недоставленных в диалоге. */
        const val DISCARD_ALL_MIN_FAILED = 2

        /** Однострочный отрывок текста: переводы строк схлопываются, хвост помечается «…». */
        fun previewOf(text: String): String {
            val collapsed = text.trim().lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (collapsed.length <= PREVIEW_MAX_CHARS) return collapsed
            return collapsed.take(PREVIEW_MAX_CHARS).trimEnd() + "…"
        }

        /** Русская плюрализация без библиотек: 1 сообщение, 2 сообщения, 5 сообщений. */
        internal fun messagesLabel(count: Int): String {
            val form = when {
                count % 100 in 11..14 -> "сообщений"
                count % 10 == 1 -> "сообщение"
                count % 10 in 2..4 -> "сообщения"
                else -> "сообщений"
            }
            return "$count $form"
        }
    }
}
