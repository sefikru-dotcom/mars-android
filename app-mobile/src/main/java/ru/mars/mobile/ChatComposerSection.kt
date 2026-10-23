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
 * Композер: поле ввода, кнопки, вложения, возврат steer-текста.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

/**
 * O7: возврат фокуса композеру после обрыва связи. Решение — [ComposerInputGuardPolicy]:
 * во время флаппинга (серия drop/restore в коротком окне) восстановление откладывается
 * до тишины, иначе клавиатура мигает на каждом переходе. Одноразовость — на политике.
 */
internal fun ChatActivity.restoreComposerFocusIfNeeded() {
    if (!isInputReady()) return
    when (composerGuard.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED)) {
        ComposerFocusDecision.RestoreWithKeyboard -> {
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        ComposerFocusDecision.DeferUntilQuiet -> {
            val wait = composerGuard.quietMillisRemaining()
            input.postDelayed(
                { if (!isFinishing && !isDestroyed) restoreComposerFocusIfNeeded() },
                wait,
            )
        }
        ComposerFocusDecision.Keep, ComposerFocusDecision.Suppress -> Unit
    }
}

internal fun ChatActivity.updateControls() {
    val active = uiState.status == ChatStatus.TYPING
    sendButton.setImageDrawable(
        ContextCompat.getDrawable(this, if (active) R.drawable.ic_stop else R.drawable.ic_send)
    )
    sendButton.imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
    // Доступность: подпись кнопки соответствует ДЕЙСТВИЮ клика (stop/send), а не иконке.
    sendButton.contentDescription = ChatA11yPolicy.sendButtonDescription(active)
    updateContextIndicator()
    updateLiveRunPlate()
    updateQuotePlate()
    updateTodoWidget()
    renderSmartChips()
}

/**
 * Делегат для секций-расширений (`Chat*Section.kt`): сам [updateControls] остаётся
 * приватным членом Activity — контракт a11y проверяет его тело в этом файле.
 */
internal fun ChatActivity.updateControlsFromSection() = updateControls()

internal fun ChatActivity.updateQuotePlate() {
    if (!isComposerQuoteViewReady()) return
    val plate = composerLayoutPresenter.quotePlate
    if (plate.visible) {
        composerQuoteView.visibility = View.VISIBLE
        composerQuoteTitle.text = plate.title
        composerQuoteExcerpt.text = plate.excerpt
    } else {
        composerQuoteView.visibility = View.GONE
    }
}

internal fun ChatActivity.renderAttachmentChips() {
    attachmentChips.removeAllViews()
    attachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    attachments.forEachIndexed { i, a ->
        val previewable = a.isImage == true || a.mime.startsWith("image/")
        attachmentChips.addView(Button(this).apply {
            text = a.name
            textSize = 11f
            setTextColor(MarsTheme.TEXT_STRONG)
            contentDescription = if (previewable) {
                "Убрать вложение ${a.name}, долгое нажатие — просмотр"
            } else {
                "Убрать вложение ${a.name}"
            }
            // Vector `ic_close` as the trailing affordance — replaces the cross text glyph.
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, ContextCompat.getDrawable(this@renderAttachmentChips, R.drawable.ic_close), null
            )
            compoundDrawableTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
            compoundDrawablePadding = dp(6)
            background = MarsTheme.pill(this@renderAttachmentChips, MarsTheme.SURFACE, strokeColor = MarsTheme.ACCENT)
            setOnClickListener { attachments.removeAt(i); renderAttachmentChips() }
            if (previewable) {
                // Long-press previews the uploaded image in the fullscreen lightbox.
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    lightbox.open(a.path)
                    true
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) })
    }
}

internal fun ChatActivity.chooseAttachment() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false); addCategory(Intent.CATEGORY_OPENABLE)
    }, pickAttachment)
}

internal fun ChatActivity.uploadAttachment(uri: Uri) {
    val credential = store.read() ?: return
    statusView.text = "Прикрепляю файл…"
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(uri) ?: "attachment"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = contentResolver.openInputStream(uri)
                    ?.use { readBoundedUploadPayload(it) }
                    ?: return@runCatching UploadOutcome.Failure("Файл недоступен")
                ChatRepository().uploadAttachment(ChatActivity.BASE_URL, credential, name, mime, bytes, currentSessionId)
            }.getOrElse { error ->
                if (error is UploadTooLargeException) {
                    UploadOutcome.TooLarge(error.message ?: "Файл слишком большой")
                } else {
                    UploadOutcome.Failure(error.message ?: "Network error")
                }
            }
        }
        when (outcome) {
            is UploadOutcome.Success -> {
                attachments += outcome.attachment
                renderAttachmentChips()
                statusView.text = "Прикреплено: ${outcome.attachment.name}"
            }
            is UploadOutcome.TooLarge ->
                statusView.text = "Файл не прикреплён: ${outcome.message}"
            is UploadOutcome.Failure ->
                statusView.text = "Не удалось прикрепить файл: ${outcome.message}"
        }
    }
}

internal fun ChatActivity.queryDisplayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/**
 * Кнопка микрофона: тап — начать запись, тап во время записи — остановить, распознать и
 * отправить. Записываем сами ([VoiceRecorder], WAV 16 кГц моно) и распознаём на сервере
 * (`POST /api/transcribe`): системный `RecognizerIntent` больше не используется — он есть
 * не на каждой сборке, а качество записи им не управляется (живой вывод ломался ровно на
 * этом: «Распознавание речи недоступно» и пустой результат).
 *
 * История переходов — в [VoiceSessionController] (LISTENING → PROCESSING → IDLE).
 * Здесь только обвязка: разрешение, микрофон, статус и отправка распознанного текста.
 */

/**
 * Undelivered mid-turn steer (SSE `pending_steer_leftover`): the correction is returned to
 * the composer so the user can resend it as a normal message (WebUI parity); the transcript
 * trace is recorded by [ChatUiState.onEvent].
 */
internal fun ChatActivity.restoreSteerText(text: String) {
    if (text.isNotBlank() && input.text.isNullOrBlank()) input.setText(text)
    explicitStatus = "Направление не доставлено — текст возвращён в поле ввода."
    setStatus()
}
