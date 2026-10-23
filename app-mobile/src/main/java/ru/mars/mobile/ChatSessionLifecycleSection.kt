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
 * Жизненный цикл диалога: новый чат, завершение, выход, реавторизация.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

internal fun ChatActivity.startNewChat() {
    // Черновик текущего диалога сохраняется до сброса session_id.
    persistComposerDraft()
    // Pending-загрузка диалога больше не актуальна: её callback не должен переключить
    // композитор и состояние обратно на старый session_id после «нового чата».
    sessionNavigation.invalidatePending()
    // Новый чат убивает живой ход предыдущего диалога: токен send-пути, collect и recovery-цикл.
    teardownLiveTurn()
    interactiveController.dismissAll()
    compressionController.onSessionChanged()
    uiState.startNewSession()
    composerGuard.onSessionSwitch()
    applySessionTitle(uiState.sessionTitle)
    modelSelection.clearSessionModel()
    modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
    currentSessionId = null
    explicitStatus = null
    // A15/A14: у нового чата нет ни серверного черновика, ни измеренного расхода.
    serverDraftSync.onSessionChanged()
    sessionUsage = null
    updateUsageIndicator()
    restoreComposerDraft()
    // Буферизованные сообщения нового чата (sessionId == null) возвращают эхо;
    // при наличии сети они сразу уходят в отправку.
    echoPendingOfflineMessages()
    initialRestoreDone = true
    autoScroll.pinToBottom()
    renderMessages()
    setStatus()
    updateControls()
    flushOfflineQueue()
}

/**
 * «Финиш»: graceful close of the current session — cancels the in-flight stream on the
 * server, denies every queued approval so the agent is not left parked, drops pending
 * dialogs, and returns the chat to the fresh-session state. Orchestration lives in the
 * pure [SessionFinishFlow] (unit-tested); this is only the Android wiring.
 */
internal fun ChatActivity.finishSession() {
    val credential = store.read()
    val sid = currentSessionId
    val streamId = currentStreamId
    val wasStreaming = uiState.status == ChatStatus.TYPING
    val capturedApproval = uiState.pendingApproval
    val capturedClarify = uiState.pendingClarify
    val textAtStart = if (isInputReady()) input.text.toString() else ""
    // Pending-загрузка закрываемой сессии инвалидируется: её callback не должен
    // «воскресить» диалог, который сейчас завершается.
    sessionNavigation.invalidatePending()

    // Local stream teardown first so no more events land while the close is in flight.
    // Recovery-цикл и токен send-пути завершаемого хода тоже умирают: reattach после
    // finish не нужен, поздние кадры на закрывающийся экран не пускаются.
    teardownLiveTurn()

    if (credential == null || sid == null) {
        closeSessionLocally(null)
        return
    }
    explicitStatus = "Завершаю сессию…"
    setStatus()
    lifecycleScope.launch {
        val report = withContext(Dispatchers.IO) {
            val chat = ChatRepository()
            val interactive = InteractiveRequestsRepository()
            val flow = SessionFinishFlow(object : SessionFinishFlow.Actions {
                override val isStreaming: Boolean get() = wasStreaming
                override fun cancelStream(): Boolean =
                    streamId?.let { runCatching { chat.executeControl(chat.cancelRequest(ChatActivity.BASE_URL, it, credential.accessToken)) }.getOrDefault(false) } ?: false

                private var firstHead = true
                override fun nextPendingApproval(): ApprovalPayload? {
                    if (firstHead) {
                        firstHead = false
                        return capturedApproval
                    }
                    val lookup = interactive.fetchPendingApproval(ChatActivity.BASE_URL, credential, sid)
                    return (lookup as? PendingApprovalLookup.Found)?.payload
                }

                override fun denyApproval(payload: ApprovalPayload) =
                    interactive.respondApproval(ChatActivity.BASE_URL, credential, sid, payload, ApprovalChoice.DENY)

                override fun nextPendingClarify() = capturedClarify
                    ?: (interactive.fetchPendingClarify(ChatActivity.BASE_URL, credential, sid) as? PendingClarifyLookup.Found)?.payload
            })
            runCatching { flow.finish() }.getOrNull()
        }
        if (currentSessionId == sid) {
            // Сессия не сменилась, пока шёл финиш: старый черновик удаляется по контракту,
            // а текст, набранный ПОСЛЕ начала операции, переживает закрытие как черновик
            // нового чата (не перетирается).
            if (authExitCleanup.draftPersistenceAllowed) {
                drafts.onSessionFinished(sid, textAtStart, input.text.toString())
            }
            // Завершённая сессия закрывается и на сервере: её буферизованные
            // сообщения уже некому отправлять.
            offlineQueue.removeMessagesFor(sid)
            closeSessionLocally(report)
            restoreComposerDraft()
            // Autonomy does not outlive the session being closed; make the pill reflect reality.
            if (report != null) uiState.setAutonomyEnabled(false)
            } else {
            // Пользователь ушёл в другой диалог во время финиша — его состояние и
            // композитор не трогаем; черновик закрытой сессии чистим в любом случае.
            drafts.onSessionRemoved(sid)
            offlineQueue.removeMessagesFor(sid)
        }
    }
}

internal fun ChatActivity.closeSessionLocally(report: SessionFinishFlow.Report?) {
    interactiveController.dismissAll()
    uiState.cancel()
    uiState.startNewSession()
    composerGuard.onSessionSwitch()
    applySessionTitle(uiState.sessionTitle)
    currentSessionId = null
    currentSessionReadOnly = false
    currentSessionIsSubagent = false
    modelSelection.clearSessionModel()
    explicitStatus = when {
        report == null -> "Сессия завершена"
        report.succeeded -> buildString {
            append("Сессия завершена")
            if (report.streamCancelled) append(" · поток остановлен")
            if (report.approvalsDenied > 0) append(" · отклонено подтверждений: ${report.approvalsDenied}")
        }
        else -> "Сессия завершена с ошибками: ${report.approvalFailures.first()}"
    }
    renderMessages()
    setStatus()
    updateControls()
    // Сброс транскрипта возвращает эхо буферизованных сообщений нового чата;
    // баннер отражает изменившийся размер очереди.
    echoPendingOfflineMessages()
    updateNetworkBanner()
    flushOfflineQueue()
}

internal fun ChatActivity.echoClarifyAnswer(answer: String) {
    uiState.messages += ChatMessage("user", answer)
    renderMessages()
}

internal fun ChatActivity.leaveChat() {
    // Logout убивает живой ход локально: цикл не должен дёргать API после отзыва
    // credentials, а поздние SSE-кадры убитого хода не должны трогать экран.
    teardownLiveTurn()
    // Локальные plaintext-данные (черновики, история поиска музыки) стираются синхронно
    // ДО finish() и независимо от асинхронного best-effort revoke; pending-навигация
    // инвалидируется, а повторное сохранение черновика в onPause блокируется.
    sessionNavigation.invalidatePending()
    authExitCleanup.run()
    lifecycleScope.launch {
        // Best-effort server revoke; the local credential is cleared regardless of the
        // outcome so a 401/network failure can never lock the user in a stale auth state.
        // Wiring — единая фабрика AuthExitStores (P0), та же, что в SettingsActivity.
        AuthExitStores.logoutFlow(store, ChatActivity.BASE_URL).logout()
        finish()
    }
}

/**
 * Forced re-authentication after the server rejected the stored credential (401,
 * [AuthRejectionPolicy]): drop it locally and return to the login screen with an
 * explicit notice. No remote revoke — the server just rejected this very token, so
 * calling revoke with it is pointless. Without this path a revoked/expired credential
 * loops every action into the same 401 with no way back to login.
 */
internal fun ChatActivity.requestReauthentication() {
    // Принудительная реавторизация убивает живой ход: attach с отозванным credential
    // бессмыслен, поздние кадры убитого хода не должны трогать экран.
    teardownLiveTurn()
    // Черновики и история поиска стираются до finish(): новая авторизация на этом
    // устройстве не должна видеть plaintext-данные предыдущей; onPause после этого
    // черновик уже не сохраняет.
    sessionNavigation.invalidatePending()
    authExitCleanup.run()
    runCatching { store.clear() }
    startActivity(
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTH_NOTICE, AuthRejectionPolicy.REAUTH_NOTICE)
    )
    finish()
}
