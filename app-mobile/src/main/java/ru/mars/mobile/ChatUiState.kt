package ru.mars.mobile

import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentUpdate
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.repository.StreamBufferGuard
import ru.mars.network.repository.SubagentStatusPolicy

/** UI model for the rich chat transcript. Kept platform independent for unit tests. */
enum class ChatStatus { IDLE, TYPING, RECEIVED, ERROR }
enum class MessageStatus { PENDING, STREAMING, COMPLETE, ERROR }

data class ToolExecution(
    val id: String?,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val expanded: Boolean = false,
)

data class ChatMessage(
    val role: String,
    val text: String = "",
    val attachments: List<String> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val thoughtsExpanded: Boolean = false,
    val tools: List<ToolExecution> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETE,
    /**
     * Якорь «старые сообщения подрезаны» ([TranscriptCapPolicy.capMessages]):
     * служебная строка вместо скрытой головы транскрипта, не обычная карточка.
     */
    val isTrimAnchor: Boolean = false,
    /** Сколько сообщений скрыто над якорем — для его подписи. */
    val trimmedCount: Int = 0,
    /**
     * Фактическая модель хода (turn-start attribution, волна C): серверное значение —
     * ранний кадр `context_status` (`model`), фоллбэк — `effective_model` HTTP-ответа
     * chat/start. null = сервер не сообщил; локальный выбор пикера сюда не пишется
     * никогда (подпись — [TurnModelPolicy]). Живёт на пузыре СВОЕГО хода: новый ход
     * открывает чистый пузырь, прежний штамп в него не протекает.
     */
    val model: String? = null,
    /** Провайдер фактической модели хода (`model_provider`); null = сервер не сообщил. */
    val modelProvider: String? = null,
    /**
     * Доска живых статусов субагентов хода (волна C, фича B): результат редьюсера
     * [ru.mars.network.repository.SubagentStatusPolicy] — childId -> состояние.
     * Пустая карта = детей не было. Живёт на пузыре СВОЕГО хода (новый ход
     * открывает чистую доску); презентация — [SubagentBoardPresenter].
     */
    val subagents: Map<String, SubagentStatus> = emptyMap(),
)

class ChatUiState(
    /**
     * Hard memory limits for streamed text accumulation (frame clamp + sliding window with
     * a single truncation marker). Injectable for tests; production uses the defaults:
     * скользящее окно ужато до [TranscriptCapPolicy.MAX_BUBBLE_CHARS] (96K вместо 256K) —
     * один пузырь больше не может держать сотни КБ текста, рвущие рендер и кучу.
     */
    private val bufferGuard: StreamBufferGuard = StreamBufferGuard(
        maxAccumulatedChars = TranscriptCapPolicy.MAX_BUBBLE_CHARS,
    ),
) {
    var status: ChatStatus = ChatStatus.IDLE
        private set
    val messages: MutableList<ChatMessage> = mutableListOf()
    var activeSessionId: String? = null
        private set
    var sessionTitle: String? = null
    var sessions: List<SessionItem> = emptyList()
        private set
    var phase: String? = null
        private set
    /** Approval prompt awaiting the user's decision (dialog is driven by InteractiveRequestController). */
    var pendingApproval: ApprovalPayload? = null
        private set
    /** Clarify prompt awaiting the user's answer (dialog is driven by InteractiveRequestController). */
    var pendingClarify: ClarifyPayload? = null
        private set
    /** Streaming activity display mode (WebUI parity: stream_full / stream_window / stream_quiet). */
    var activityMode: ActivityDisplayMode = ActivityDisplayMode.DEFAULT
        private set
    /** Latest context-window fill level from `metering` frames; null until the server reports one. */
    var contextUsage: ChatEvent.ContextUsage? = null
        private set

    /** Latest todo snapshot, shared by cold session load and live SSE updates. */
    var todoState: TodoSnapshot? = null
        private set

    fun setTodoState(snapshot: TodoSnapshot?) { todoState = snapshot }

    /**
     * Session-scoped autonomy mode (the agent runs without blocking confirmations). Mirrors the
     * server-side per-session flag (toggle endpoint mapped by the core-network wire adapter);
     * never persisted locally and reset on every session switch/new chat — exactly the WebUI
     * session-flag lifecycle.
     */
    var autonomyEnabled: Boolean = false
        private set

    /** Applies the settled autonomy state reported by the server (toggle result or fresh lookup). */
    fun setAutonomyEnabled(value: Boolean) { autonomyEnabled = value }

    /**
     * Active-turn metadata of the loaded session (`GET /api/session` → `activeTurn`):
     * a server-side attention request (approval/clarify waiting) and a queued user message
     * that has not reached the runtime yet. Cleared on every session swap/turn settle so a
     * stale banner never survives the state it describes.
     */
    var attention: SessionAttention? = null
        private set
    var hasPendingUserMessage: Boolean = false
        private set

    /** True while the transcript streams a REATTACHED turn (vs one started from this device). */
    var reattachedTurn: Boolean = false

    /**
     * A9: серверный набор инструментов открытого диалога (`enabled_toolsets`).
     * `null` — набор по умолчанию, это НЕ пустой набор: пустой список сервер отвергает.
     * Источник истины — ответ сервера, локальный выбор до подтверждения не показывается.
     */
    var enabledToolsets: List<String>? = null

    /** A9: применяет серверное значение набора инструментов. */
    fun applyEnabledToolsets(value: List<String>?) { enabledToolsets = value }

    /**
     * Telemetry for the current turn: how many streamed characters were dropped by
     * [bufferGuard] (frame clamp + window slide). Reset on every turn/session boundary.
     */
    var streamDroppedChars: Long = 0
        private set

    /** Projects the loaded session's [ActiveTurnInfo] into the banner state. */
    fun setActiveTurn(turn: ActiveTurnInfo) {
        attention = turn.attention
        hasPendingUserMessage = turn.hasPendingUserMessage
    }

    /**
     * Opens the streaming bubble for a reattached active turn (no user message is appended —
     * the prompt already lives in the loaded history). Returns false when a turn is already
     * streaming so a double attach can never create two bubbles.
     */
    fun beginReattachedTurn(): Boolean {
        if (status == ChatStatus.TYPING) return false
        status = ChatStatus.TYPING
        reattachedTurn = true
        streamDroppedChars = 0
        clearSubagentBoard()
        messages += ChatMessage("assistant", status = MessageStatus.STREAMING)
        return true
    }

    /**
     * Abandons a reattached turn that died before delivering anything (e.g. the stream 404'd
     * and settled history is about to replace the transcript): the placeholder bubble is
     * removed when still empty, otherwise it is finalized so no content is lost.
     */
    fun cancelReattachedTurn() {
        val index = messages.indexOfLast { it.role == "assistant" }
        if (reattachedTurn && index >= 0) {
            val bubble = messages[index]
            if (bubble.status == MessageStatus.STREAMING &&
                bubble.text.isBlank() && bubble.thoughts.isEmpty() && bubble.tools.isEmpty()
            ) {
                messages.removeAt(index)
            } else {
                messages[index] = bubble.copy(status = MessageStatus.COMPLETE)
            }
        }
        reattachedTurn = false
        status = ChatStatus.IDLE
        phase = null
        clearPendingInteractive()
    }

    /**
     * Expanded long code listings, keyed «messageIndex:blockIndex». Long blocks are
     * collapsed by default (CodeBlockPresenter); a tap on the toggle flips the key.
     * Cleared on every session swap so indices never leak across transcripts.
     */
    private val expandedCodeBlocks = mutableSetOf<String>()

    fun isCodeBlockExpanded(messageIndex: Int, blockIndex: Int): Boolean =
        "$messageIndex:$blockIndex" in expandedCodeBlocks

    fun toggleCodeBlock(messageIndex: Int, blockIndex: Int) {
        val key = "$messageIndex:$blockIndex"
        if (!expandedCodeBlocks.remove(key)) expandedCodeBlocks.add(key)
    }

    /**
     * Ручные переопределения разворота ГРУППЫ инструментов, ключ — индекс сообщения.
     *
     * Наличие ключа означает «пользователь сам решил»: `true` держит группу
     * развёрнутой, `false` — свёрнутой, независимо от режима активности (паритет с
     * `data-mars-turn-manual` в WebUI). Отсутствие ключа отдаёт начальное состояние
     * режиму ([ToolGroupPresenter]). Чистится при смене сессии, чтобы индексы не
     * переезжали на другой транскрипт.
     */
    private val expandedToolGroups = mutableMapOf<Int, Boolean>()

    fun isToolGroupExpanded(messageIndex: Int): Boolean? = expandedToolGroups[messageIndex]

    fun hasToolGroupOverride(messageIndex: Int): Boolean = expandedToolGroups.containsKey(messageIndex)

    /** Переключает группу: первое нажатие задаёт противоположное текущему состоянию. */
    fun toggleToolGroup(messageIndex: Int, currentlyExpanded: Boolean) {
        expandedToolGroups[messageIndex] = !currentlyExpanded
    }

    /**
     * Switches the display mode and re-applies the expansion policy to the whole
     * transcript so the change is immediately visible.
     */
    fun setActivityMode(mode: ActivityDisplayMode) {
        activityMode = mode
        messages.replaceAll { if (it.role == "assistant") applyModePolicy(it) else it }
    }

    fun begin(message: String, attachmentNames: List<String> = emptyList()): Boolean {
        if (message.isBlank() && attachmentNames.isEmpty() || status == ChatStatus.TYPING) return false
        status = ChatStatus.TYPING
        streamDroppedChars = 0
        clearSubagentBoard()
        messages += ChatMessage("user", message, attachments = attachmentNames, status = MessageStatus.COMPLETE)
        messages += ChatMessage("assistant", status = MessageStatus.STREAMING)
        return true
    }

    /**
     * Локальное эхо сообщения, буферизованного офлайн-очередью
     * ([OfflineMessageQueuePolicy]): пользовательский пузырь появляется сразу со
     * статусом PENDING (визуальный признак «ждёт связи»), но ход НЕ начинается —
     * assistant-пузырь не открывается, статус чата не меняется. False при активном
     * ходе (TYPING) или пустом тексте.
     */
    fun beginQueuedEcho(text: String): Boolean {
        if (text.isBlank() || status == ChatStatus.TYPING) return false
        messages += ChatMessage("user", text, status = MessageStatus.PENDING)
        return true
    }

    /**
     * Снятие PENDING-эха сообщения, удалённого из офлайн-очереди или из карточки
     * неотправленных: пузырь «ждёт связи» не должен остаться в транскрипте без
     * самого сообщения. True, если эхо действительно нашлось.
     */
    fun dropQueuedEcho(text: String): Boolean {
        val index = messages.indexOfLast {
            it.role == "user" && it.status == MessageStatus.PENDING && it.text == text
        }
        if (index < 0) return false
        messages.removeAt(index)
        return true
    }

    /**
     * Начало отправки сообщения из офлайн-очереди после восстановления связи:
     * PENDING-эхо (если оно живо в транскрипте) становится COMPLETE — иначе пузырь
     * добавляется заново, — и открывается стримящийся assistant-пузырь. False при
     * активном ходе: flush очереди дождётся его завершения.
     */
    fun beginQueuedSend(text: String): Boolean {
        if (text.isBlank() || status == ChatStatus.TYPING) return false
        status = ChatStatus.TYPING
        streamDroppedChars = 0
        clearSubagentBoard()
        val echoIndex = messages.indexOfLast {
            it.role == "user" && it.status == MessageStatus.PENDING && it.text == text
        }
        if (echoIndex >= 0) {
            messages[echoIndex] = messages[echoIndex].copy(status = MessageStatus.COMPLETE)
        } else {
            messages += ChatMessage("user", text, status = MessageStatus.COMPLETE)
        }
        messages += ChatMessage("assistant", status = MessageStatus.STREAMING)
        return true
    }

    fun onEvent(event: ChatEvent) {
        // Attention requests are recorded even without a live assistant bubble (e.g. history
        // replay races) so the controller hook never misses a prompt. The context meter is
        // session-level UI (composer indicator), so it is recorded here too.
        when (event) {
            is ChatEvent.ApprovalRequired -> pendingApproval = event.payload
            is ChatEvent.ClarifyRequired -> pendingClarify = event.payload
            is ChatEvent.ContextUsage -> contextUsage = event
            else -> Unit
        }
        val index = messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        val current = messages[index]
        messages[index] = when (event) {
            is ChatEvent.SessionBound -> { activeSessionId = event.sessionId; current }
            is ChatEvent.StreamStarted -> {
                // Фоллбэк turn-start attribution: `effective_model` присутствует в
                // HTTP-ответе chat/start только если сервер нормализовал модель —
                // основной источник (кадр context_status) штампует хост через
                // [stampTurnModel]. Запрошенная клиентом модель НЕ подставляется.
                if (!event.model.isNullOrBlank()) {
                    current.copy(model = event.model, modelProvider = event.provider)
                } else {
                    current
                }
            }
            // OOM/ANR guard: streamed text is accumulated through StreamBufferGuard, so a
            // runaway frame is clamped and the transcript keeps only the freshest tail
            // (prefixed with exactly one truncation marker) instead of growing unbounded.
            is ChatEvent.Chunk -> {
                val guarded = bufferGuard.append(current.text, event.text)
                if (guarded.droppedChars > 0) streamDroppedChars += guarded.droppedChars
                current.copy(text = guarded.text)
            }
            is ChatEvent.Thought -> current.copy(
                thoughts = current.thoughts + event.text,
                thoughtsExpanded = current.thoughtsExpanded || activityMode == ActivityDisplayMode.STREAM_FULL,
            )
            is ChatEvent.ToolCall -> current.copy(
                tools = applyToolPolicy(current.tools + ToolExecution(event.id, event.name, event.arguments), activityMode)
            )
            is ChatEvent.ToolResult -> current.copy(tools = applyToolPolicy(current.tools.map { tool ->
                if ((event.id != null && tool.id == event.id) || (event.id == null && tool.name == event.name && tool.result == null)) tool.copy(result = event.result) else tool
            }, activityMode))
            is ChatEvent.Phase -> { phase = event.name; current }
            // Transcript parity with the pre-typed mapper: keep a readable trace of the prompt
            // while the structured payload drives the interactive dialog.
            is ChatEvent.ApprovalRequired -> current.copy(thoughts = current.thoughts + approvalThought(event.payload))
            is ChatEvent.ClarifyRequired -> current.copy(thoughts = current.thoughts + clarifyThought(event.payload))
            // Transcript trace for the undelivered steer; ChatActivity additionally returns the
            // text to the composer input (WebUI pending_steer_leftover parity).
            is ChatEvent.SteerLeftover -> current.copy(thoughts = current.thoughts + "Направление не доставлено: ${event.text}")
            // Session-level meter (composer indicator) — the transcript itself is untouched.
            is ChatEvent.ContextUsage -> current
            ChatEvent.Done -> current.copy(
                status = MessageStatus.COMPLETE,
                thoughtsExpanded = activityMode == ActivityDisplayMode.STREAM_FULL,
                tools = current.tools.map { tool ->
                    if (activityMode == ActivityDisplayMode.STREAM_FULL) tool else tool.copy(expanded = false)
                },
            )
            is ChatEvent.Error -> current.copy(text = current.text + "\nОшибка: " + event.message, status = MessageStatus.ERROR)
        }
        when (event) {
            ChatEvent.Done -> complete()
            is ChatEvent.Error -> fail()
            else -> Unit
        }
    }

    fun complete() { status = ChatStatus.RECEIVED; phase = null; reattachedTurn = false; clearActiveTurnFlags(); clearPendingInteractive(); markAssistant(MessageStatus.COMPLETE) }

    /**
     * Фактическая модель хода из открывающего кадра `context_status` (волна C,
     * фича A) — ОСНОВНОЙ источник подписи карточки ответа: сервер сообщает
     * resolved_model/resolved_provider, с которыми конструируется агент. Штамп
     * ложится на ТЕКУЩИЙ assistant-пузырь; новый ход открывает чистый пузырь,
     * прежняя модель в него не протекает. Пустое/пробельное значение = «сервер
     * не сообщил» — штамп не ставится и прежний не затирается.
     *
     * @return true — штамп изменил состояние (хост перерисовывает карточку).
     */
    fun stampTurnModel(model: String?, provider: String?): Boolean {
        val name = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val vendor = provider?.trim()?.takeIf { it.isNotEmpty() }
        val index = messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return false
        val current = messages[index]
        if (current.model == name && current.modelProvider == vendor) return false
        messages[index] = current.copy(model = name, modelProvider = vendor)
        return true
    }

    /**
     * Доска живых статусов субагентов ТЕКУЩЕГО хода (волна C, фича B): редьюсер
     * [SubagentStatusPolicy] — единственная правда о состояниях детей; каждый
     * кадр `subagent_*`/`delegate.*` проходит через него, а результат штампует
     * ся на текущий assistant-пузырь (карточка родителя показывает доску без
     * открытия дочернего экрана). Доска живёт в рамках хода: [begin]/
     * [beginQueuedSend]/[startNewSession] открывают чистую.
     */
    private var subagentBoard: Map<String, SubagentStatus> = emptyMap()

    /**
     * Применяет кадр статуса субагента к доске хода.
     *
     * @return true — доска изменилась (хост перерисовывает карточку).
     */
    fun applySubagentUpdate(update: SubagentUpdate): Boolean {
        val next = SubagentStatusPolicy.apply(subagentBoard, update)
        if (next == subagentBoard) return false
        subagentBoard = next
        val index = messages.indexOfLast { it.role == "assistant" }
        if (index >= 0 && messages[index].subagents != next) {
            messages[index] = messages[index].copy(subagents = next)
        }
        return true
    }

    /** Сброс доски субагентов на границе хода/сессии (новый ход — чистая доска). */
    private fun clearSubagentBoard() { subagentBoard = emptyMap() }

    fun fail() { status = ChatStatus.ERROR; phase = null; reattachedTurn = false; clearActiveTurnFlags(); clearPendingInteractive(); markAssistant(MessageStatus.ERROR) }
    fun cancel() { status = ChatStatus.IDLE; phase = null; reattachedTurn = false; clearPendingInteractive(); markAssistant(MessageStatus.COMPLETE) }
    fun startNewSession() { activeSessionId = null; sessionTitle = null; messages.clear(); status = ChatStatus.IDLE; phase = null; contextUsage = null; todoState = null; expandedCodeBlocks.clear(); expandedToolGroups.clear(); autonomyEnabled = false; reattachedTurn = false; streamDroppedChars = 0; clearSubagentBoard(); clearActiveTurnFlags(); clearPendingInteractive() }
    fun setSessions(value: List<SessionItem>) { sessions = value }

    /**
     * Живой заголовок сессии (SSE-кадр `title`, авто-титлинг на ходу): шапка диалога
     * получает новый текст, строка в уже загруженном списке — тоже, без
     * `GET /api/session`. Возвращает true, если картинка реально изменилась —
     * вызывающий перерисовывает шапку только в этом случае.
     */
    fun applySessionTitle(sessionId: String, title: String): Boolean {
        // Шапка — только у активной сессии; чужой кадр правит лишь строку списка.
        val headerChanged = sessionId == activeSessionId && sessionTitle != title
        if (sessionId == activeSessionId) sessionTitle = title
        sessions = SessionTitlePolicy.renamed(sessions, sessionId, title)
        return headerChanged
    }

    /**
     * Rows for the sessions drawer (WebUI parity #5244/#5305): delegated
     * subagent children ([SessionItem.isSubagent]) are hidden from the main
     * list by default — the WebUI sidebar stacks them under the parent's
     * lineage and suppresses them when the parent is out of scope, so the
     * flat mobile drawer must not promote them to top-level rows.
     * [showSubagents] reveals them (drawer toggle, marked with a «субагент»
     * badge); the currently-open session always stays visible so the active
     * row never disappears mid-delegation.
     */
    /** Rows for the drawer, with subagents attached immediately after their parent. */
    fun drawerSessions(showSubagents: Boolean = false, currentSessionId: String? = null): List<SessionItem> {
        val visible = sessions.filter { showSubagents || !it.isSubagent || it.id == currentSessionId }
        if (!showSubagents) return visible
        val byId = visible.associateBy { it.id }
        val byParent = visible.filter { it.parentSessionId != null }
            .groupBy { it.parentSessionId }
        val emitted = mutableSetOf<String>()
        val result = mutableListOf<SessionItem>()

        fun append(node: SessionItem) {
            if (!emitted.add(node.id)) return
            result += node
            byParent[node.id].orEmpty()
                .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
                .forEach(::append)
        }

        val roots = visible.filter { it.parentSessionId == null }
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
        roots.filterNot { it.isSubagent }.forEach(::append)
        roots.filter { it.isSubagent }.forEach(::append)
        // Orphans (parent out of scope) never compete with rooted rows: tail, newest first.
        visible.filter { it.parentSessionId != null && it.parentSessionId !in byId }
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
            .forEach(::append)
        // Cycles have no natural root; keep every remaining row visible exactly once.
        visible.filter { it.id !in emitted }
            .sortedByDescending { it.updatedAt ?: it.createdAt ?: "" }
            .forEach(::append)
        return result
    }

    fun bindSession(id: String?, title: String?, history: List<ChatMessage>) { todoState = null; activeSessionId = id; sessionTitle = title; messages.clear(); messages.addAll(TranscriptCapPolicy.capMessages(history)); status = ChatStatus.IDLE; phase = null; contextUsage = null; expandedCodeBlocks.clear(); expandedToolGroups.clear(); autonomyEnabled = false; reattachedTurn = false; streamDroppedChars = 0; clearSubagentBoard(); clearActiveTurnFlags(); clearPendingInteractive() }
    fun toggleThoughts(index: Int) { messages.getOrNull(index)?.let { messages[index] = it.copy(thoughtsExpanded = !it.thoughtsExpanded) } }
    fun toggleTool(messageIndex: Int, toolIndex: Int) { messages.getOrNull(messageIndex)?.let { m -> if (toolIndex in m.tools.indices) messages[messageIndex] = m.copy(tools = m.tools.mapIndexed { i, t -> if (i == toolIndex) t.copy(expanded = !t.expanded) else t }) } }

    /** Called by the interactive controller once an approval was answered or dismissed. */
    fun resolveApproval() { pendingApproval = null }

    /** Called by the interactive controller once a clarify was answered or dismissed. */
    fun resolveClarify() { pendingClarify = null }

    private fun clearPendingInteractive() { pendingApproval = null; pendingClarify = null }

    /** Drops the active-turn banner state (attention / queued message) — the turn has settled or the session was swapped. */
    private fun clearActiveTurnFlags() { attention = null; hasPendingUserMessage = false }

    /** Re-applies the current [activityMode] expansion policy to one assistant message. */
    private fun applyModePolicy(message: ChatMessage): ChatMessage = message.copy(
        thoughtsExpanded = activityMode == ActivityDisplayMode.STREAM_FULL && message.thoughts.isNotEmpty(),
        tools = applyToolPolicy(message.tools, activityMode),
    )

    /**
     * Streaming-window policy for the tool list (WebUI parity):
     * - STREAM_FULL: every tool stays expanded.
     * - STREAM_WINDOW: only still-running tools (no result yet) are expanded;
     *   finished ones collapse into the one-line «Готово» plate.
     * - STREAM_QUIET: every tool stays collapsed.
     */
    private fun applyToolPolicy(tools: List<ToolExecution>, mode: ActivityDisplayMode): List<ToolExecution> = when (mode) {
        ActivityDisplayMode.STREAM_FULL -> tools.map { it.copy(expanded = true) }
        ActivityDisplayMode.STREAM_WINDOW -> tools.map { it.copy(expanded = it.result == null) }
        ActivityDisplayMode.STREAM_QUIET -> tools.map { it.copy(expanded = false) }
    }

    private fun approvalThought(payload: ApprovalPayload): String {
        val detail = payload.description.ifBlank { payload.command.ifBlank { payload.tool.orEmpty() } }
        return if (detail.isBlank()) "Требуется подтверждение" else "Требуется подтверждение: $detail"
    }

    private fun clarifyThought(payload: ClarifyPayload): String {
        val suffix = if (payload.choices.isEmpty()) "" else " (варианты: ${payload.choices.joinToString(" / ")})"
        return "Требуется уточнение: ${payload.question}$suffix"
    }

    private fun markAssistant(value: MessageStatus) {
        val i = messages.indexOfLast { it.role == "assistant" }
        if (i >= 0) messages[i] = messages[i].copy(status = value)
    }
}
