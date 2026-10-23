package ru.mars.mobile

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionUsage
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionToolsetsOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionAutonomyRepository
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.TranscribeOutcome
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.network.repository.readBoundedUploadPayload

/**
 * Статусная строка, плитки контекста/цели/live-run, колбэки событий.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

internal fun ChatActivity.todoCallback(sessionId: String?): (ru.mars.network.model.TodoSnapshot) -> Unit = { snapshot ->
    runOnUiThread {
        if ((sessionId == null || currentSessionId == sessionId) && (snapshot.sessionId == null || currentSessionId == snapshot.sessionId)) {
            uiState.setTodoState(snapshot)
            setStatus()
            updateTodoWidget()
        }
    }
}

/**
 * Side-канал SSE-метаданных ([ru.mars.network.model.StreamSideEvent]) живого хода:
 * мобильный чат показывает из него только `context_status` (статус префилл-контекста);
 * остальные носители доставляются, но остаются без реакции. Кадр приходит на IO-контексте
 * потока — прыжок на UI-поток здесь. [boundSessionId] фиксирует сессию на момент старта
 * хода: кадр, опоздавший к сменившемуся экрану, не перекрасит новый диалог (тот же
 * guard, что в [todoCallback]); сверка самого кадра с активной сессией — в
 * [ContextStatusPresenter].
 */
internal fun ChatActivity.sideEventCallback(boundSessionId: String?): (ru.mars.network.model.StreamSideEvent) -> Unit = { event ->
    if (event is ContextStatusUpdate) {
        runOnUiThread {
            if (boundSessionId == null || currentSessionId == boundSessionId) {
                onContextStatus(event)
            }
        }
    } else if (event is SubagentStatusUpdate) {
        // Доска живых статусов субагентов (волна C, фича B): кадр проходит
        // через редьюсер SubagentStatusPolicy в состоянии хода; изменилась
        // доска — внеочередная перерисовка карточки родителя (дети читаются
        // без открытия дочернего экрана).
        runOnUiThread {
            if (boundSessionId == null || currentSessionId == boundSessionId) {
                if (uiState.applySubagentUpdate(event.update)) scheduleStreamRender()
            }
        }
    } else if (event is ru.mars.network.model.SessionTitleUpdate) {
        // Авто-заголовок сессии приходит посреди хода: шапка диалога и строка в
        // списке получают новый текст сразу, без перезапроса `GET /api/session`.
        // Кому именно достаётся кадр (пустой текст, чужая сессия, повтор) —
        // решает чистая [SessionTitlePolicy]; здесь только применение на UI-потоке.
        runOnUiThread {
            if (boundSessionId == null || currentSessionId == boundSessionId) {
                onSessionTitle(event, boundSessionId)
            }
        }
    } else if (event is ru.mars.network.model.GoalUpdate) {
        // Карточка статуса цели (O14): состояние и текст кадра `goal` показываются
        // над композером. Кадр чужой сессии карточку не трогает.
        runOnUiThread {
            if (boundSessionId == null || currentSessionId == boundSessionId) {
                goalCard = GoalLoopPolicy.card(event)
                updateGoalCard()
            }
        }
    } else if (event is ru.mars.network.model.GoalContinuation) {
        // Кадр `goal_continue`: сервер поставил продолжение. WebUI отправляет его
        // следующим пользовательским сообщением, отдавая приоритет вводу человека;
        // здесь для этого переиспользуется офлайн-очередь (FIFO + эхо + автофлаш),
        // а не второй путь отправки.
        runOnUiThread {
            if (boundSessionId == null || currentSessionId == boundSessionId) {
                goalCard = GoalLoopPolicy.continuationCard(event)
                updateGoalCard()
                enqueueGoalContinuation(event.continuationPrompt)
            }
        }
    }
}

/**
 * Применение кадра `title` к экрану: обновляет шапку и (если список диалогов уже
 * загружен) строку сессии в нём. Заголовок текущего чата не зависит от того,
 * открыт ли список, поэтому шапка обновляется всегда.
 */
internal fun ChatActivity.onSessionTitle(update: ru.mars.network.model.SessionTitleUpdate, boundSessionId: String?) {
    val decision = SessionTitlePolicy.decide(
        update = update,
        boundSessionId = boundSessionId,
        currentSessionId = currentSessionId,
        currentTitle = uiState.sessionTitle,
    ) ?: return
    if (uiState.applySessionTitle(decision.sessionId, decision.title)) {
        applySessionTitle(decision.title)
    }
}

/** Единственная точка записи живого заголовка в шапку (пусто — строки нет). */
internal fun ChatActivity.applySessionTitle(title: String?) {
    val view = sessionTitleView ?: return
    val text = title?.trim().orEmpty()
    view.text = text
    view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
}

/**
 * Строка статуса префилл-контекста: каждый кадр ПЕРЕЗАПИСЫВАЕТ предыдущую строку
 * (не накапливает), видна [ContextStatusPresenter.DISPLAY_DURATION_MS] и гаснет,
 * возвращая обычную статус-цепочку («Марс печатает…» и т.п.). Кадр, который
 * показывать нечего (`not_configured`/чужая сессия), гасит строку сразу.
 */
internal fun ChatActivity.onContextStatus(update: ContextStatusUpdate) {
    // Turn-start model attribution (волна C, фича A): открывающий кадр несёт
    // фактическую модель хода — штамп на текущем пузыре важнее статус-строки и
    // не зависит от неё (кадр «чужой» сессии штамп не ставит). Изменилась
    // подпись — внеочередная перерисовка карточки через общий троттлинг стрима.
    if (update.sessionId == null || update.sessionId == currentSessionId) {
        if (uiState.stampTurnModel(update.model, update.modelProvider)) scheduleStreamRender()
    }
    contextStatusJob?.cancel()
    val line = ContextStatusPresenter.statusLine(update, currentSessionId)
    if (line == null) {
        contextStatusJob = null
        if (contextStatusLine != null) {
            contextStatusLine = null
            setStatus()
        }
        return
    }
    contextStatusLine = line
    setStatus()
    contextStatusJob = lifecycleScope.launch {
        delay(ContextStatusPresenter.DISPLAY_DURATION_MS)
        contextStatusLine = null
        contextStatusJob = null
        setStatus()
    }
}

/** Сброс строки статуса контекста при любом уходе с контекста диалога (без setStatus — вызывающий путь обновляет статус сам). */
internal fun ChatActivity.clearContextStatus() {
    contextStatusJob?.cancel()
    contextStatusJob = null
    contextStatusLine = null
    // Карточка цели принадлежит диалогу: при уходе с контекста она гаснет, иначе
    // статус чужой цели остался бы висеть над композером.
    if (goalCard.visible) {
        goalCard = GoalLoopPolicy.hidden()
        lastGoalContinuation = null
        updateGoalCard()
    }
}

/**
 * Отрисовка карточки статуса цели. Тон мапится на цвет темы: в работе — «бегущая»
 * точка, пауза — предупреждение, закрытая цель — «онлайн». Карточка ничего не
 * запрашивает у сервера: текст приходит кадрами `goal`/`goal_continue`.
 */
internal fun ChatActivity.updateGoalCard() {
    if (!isGoalPlateReady()) return
    if (!goalCard.visible) {
        goalPlate.visibility = View.GONE
        return
    }
    goalPlate.visibility = View.VISIBLE
    goalTitle.text = goalCard.title
    val detail = goalCard.detail
    goalDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
    goalDetail.text = detail.orEmpty()
    val tone = when (goalCard.tone) {
        GoalCardTone.ACTIVE -> MarsTheme.STATUS_RUNNING
        GoalCardTone.PAUSED -> MarsTheme.STATUS_WARNING
        GoalCardTone.DONE -> MarsTheme.STATUS_ONLINE
    }
    (goalDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(tone)
    goalPlate.contentDescription = goalCard.contentDescription
}

/**
 * Промпт продолжения цели уходит в обычную очередь отправки: FIFO сохраняет
 * приоритет введённого человеком текста, эхо сразу видно в транскрипте, а
 * отправкой занимается тот же автофлаш, что и у офлайн-сообщений.
 */
internal fun ChatActivity.enqueueGoalContinuation(prompt: String) {
    val text = prompt.trim()
    if (text.isEmpty() || !isOfflineQueueReady()) return
    if (lastGoalContinuation == text) return
    lastGoalContinuation = text
    val queued = offlineQueue.enqueue(text, currentSessionId)
    uiState.beginQueuedEcho(queued.text)
    renderMessages()
    updateNetworkBanner()
    flushOfflineQueue()
}

/**
 * Команда `/goal` в композере (O14).
 *
 * `/goal` и `/goal status` обслуживаются локально по последнему кадру карточки.
 * Управляющие подкоманды требуют `POST /api/goal`: маршрут device-токену открыт
 * (`api/auth.py:106`, NATIVE_BEARER_EXACT_PATHS), но метода в транспортном слое
 * пока нет, поэтому отправлять их обычным сообщением нельзя (сервер поймёт это
 * как текст промпта и цель не изменится) — отвечаем прямо и не тратим ход.
 */
internal fun ChatActivity.handleGoalCommand(text: String) {
    val args = GoalLoopPolicy.commandArgs(text)
    val reply = if (GoalLoopPolicy.isStatusCommand(args)) {
        GoalLoopPolicy.statusReply(goalCard)
    } else {
        GoalLoopPolicy.controlReply(args)
    }
    // Ответ идёт строкой статуса (а не пузырём транскрипта): это отклик команды,
    // а не сообщение диалога. Статус цели уже виден на карточке, строке хватает
    // короткой переформулировки и самоскрытия.
    explicitStatus = reply
    setStatus()
    goalReplyJob?.cancel()
    goalReplyJob = lifecycleScope.launch {
        delay(GOAL_REPLY_TTL_MS)
        if (explicitStatus == reply) {
            explicitStatus = null
            setStatus()
        }
    }
    input.text.clear()
    updateControls()
}

internal fun ChatActivity.updateLiveRunPlate() {
    if (!isLiveRunPlateReady()) return
    val runningTool = uiState.messages.lastOrNull()?.tools?.lastOrNull { it.result == null }?.name
    val lastAssistant = uiState.messages.lastOrNull { it.role == "assistant" }
    val plate = LiveRunStatusPresenter.resolve(
        status = uiState.status,
        phase = uiState.phase,
        runningToolName = runningTool,
        hasText = lastAssistant?.text?.isNotBlank() == true,
        hasThoughts = lastAssistant?.thoughts?.isNotEmpty() == true
    )
    if (plate.visible) {
        liveRunPlate.visibility = View.VISIBLE
        liveRunLabel.text = plate.label
        liveRunLabel.setTextColor(plate.labelColor)
        if (plate.detailLabel != null) {
            liveRunDetail.visibility = View.VISIBLE
            liveRunDetail.text = plate.detailLabel
            liveRunDetail.setTextColor(plate.detailColor)
        } else {
            liveRunDetail.visibility = View.GONE
        }
    } else {
        liveRunPlate.visibility = View.GONE
    }
}

internal fun ChatActivity.setStatus() {
    val quiet = uiState.activityMode == ActivityDisplayMode.STREAM_QUIET
    // Пока голосовой диалог активен, строка состояния принадлежит ему: человек в режиме
    // «слушаю/думаю/говорю» смотрит именно туда, и «Марс печатает…» вместо «Слушаю…»
    // означало бы, что микрофон открыт непонятно зачем. explicitStatus (обрыв связи,
    // восстановление, readonly) остаётся сильнее — он про то, что блокирует сам диалог.
    statusView.text = explicitStatus ?: voiceStatusLabel() ?: contextStatusLine ?: when {
        uiState.todoState?.todos?.isNotEmpty() == true && uiState.status == ChatStatus.TYPING -> {
            val todos = requireNotNull(uiState.todoState).todos
            val done = todos.count { it.status == "completed" }
            val current = todos.firstOrNull { it.status == "in_progress" }
            "Задачи: $done/${todos.size}" + (current?.let { " — ${it.content}" } ?: "")
        }
        quiet && uiState.status == ChatStatus.TYPING -> "Марс работает…"
        uiState.status != ChatStatus.TYPING && uiState.attention != null ->
            attentionLabel(requireNotNull(uiState.attention))
        uiState.status != ChatStatus.TYPING && uiState.hasPendingUserMessage ->
            "Сообщение в очереди — ждём запуска хода"
        else -> uiState.phase?.let { "Марс: ${phaseLabel(it)}" } ?: when (uiState.status) {
            ChatStatus.TYPING -> "Марс печатает…"
            ChatStatus.RECEIVED -> "Ответ получен"
            ChatStatus.ERROR -> "Ошибка сети"
            else -> ""
        }
    }
    updateLiveRunPlate()
    // Кнопка микрофона живёт по тому же источнику правды, что и строка состояния:
    // один вызов setStatus() обновляет оба, рассинхрона «красная, а уже выключено» нет.
    renderVoiceButton()
}

/** Human banner for the session's server-side attention (WebUI `_session_attention_summary` parity). */
internal fun ChatActivity.attentionLabel(attention: SessionAttention): String {
    val kind = when (attention.kind) {
        "approval" -> "подтверждения"
        "clarify" -> "уточнения"
        else -> attention.kind
    }
    val count = if (attention.count > 1) " x${attention.count}" else ""
    // Textual severity marker — the exclamation emoji renders font-dependent across skins.
    return if (attention.severity == "critical") {
        "Критично — ожидает $kind$count"
    } else {
        "Ожидает $kind$count"
    }
}

internal fun ChatActivity.phaseLabel(value: String) = when {
    value.contains("tool", true) || value.contains("command", true) -> "Выполняет команду…"
    value.contains("think", true) || value.contains("reason", true) -> "Анализирует задачу…"
    else -> value
}
