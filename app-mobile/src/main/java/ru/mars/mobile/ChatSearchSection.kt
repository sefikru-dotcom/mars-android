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
 * Поиск по транскрипту: панель, навигация, подсветка.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

internal fun ChatActivity.openTranscriptSearch() {
    searchPlate.visibility = View.VISIBLE
    searchInput.setText("")
    searchInput.requestFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    refreshTranscriptSearch(resetPosition = true, scrollToMatch = false)
}

internal fun ChatActivity.closeTranscriptSearch() {
    // Снятие подсветки и сброс состояния — задача контроллера поиска.
    transcriptSearch.clear()
    searchPlate.visibility = View.GONE
    searchInput.setText("")
    searchCounter.text = ""
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
}

/** Панель поиска открыта — Back сначала закрывает её (см. NavBackPolicy). */
internal fun ChatActivity.isTranscriptSearchOpen(): Boolean =
    ::searchPlate.isInitialized && searchPlate.visibility == View.VISIBLE

/**
 * Хост-контракт контроллера поиска: узкие адаптеры к транскрипту вместо передачи
 * `CardEntry` (он остаётся приватным внутренним классом Activity) и view-обвязки панели.
 * Индексы совпадают с `card.tag`: поиск адресует карточку тем же индексом, что и рендер.
 */
internal fun ChatActivity.transcriptSearchHost() = object : TranscriptSearchHighlightController.Host {
    override val messageTexts: List<String>
        get() = uiState.messages.map { it.text }

    override val query: String
        get() = searchInput.text?.toString().orEmpty()

    override fun setCounter(label: String) {
        searchCounter.text = label
    }

    override fun cardViewAt(index: Int): View? = messages.findViewWithTag(index)

    override fun markdownHostAt(index: Int): View? = cardEntries.getOrNull(index)?.markdownHost

    override val scrollContainer: View get() = scroll

    override fun scrollTo(y: Int) {
        scroll.smoothScrollTo(0, y)
    }

    override fun dp(value: Int) = this@transcriptSearchHost.dp(value)
}

/** Пересчёт совпадений и подсветка — целиком в контроллере поиска. */
internal fun ChatActivity.refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean) {
    transcriptSearch.refresh(resetPosition = resetPosition, scrollToMatch = scrollToMatch)
}

/** Кольцевой шаг «следующее/предыдущее совпадение» — целиком в контроллере поиска. */
internal fun ChatActivity.navigateTranscriptSearch(forward: Boolean) {
    transcriptSearch.navigate(forward)
}

/** Markdown-секция пузыря — реализация в [ChatMessageCardRenderer.renderMarkdownBlocks]. */
internal fun ChatActivity.renderMarkdownBlocks(
    value: String,
    parent: LinearLayout,
    user: Boolean,
    messageIndex: Int,
    state: ChatMarkdownRenderer.MarkdownRenderState,
) {
    ChatMessageCardRenderer.renderMarkdownBlocks(this, value, parent, user, messageIndex, cardRendererHost, state)
}

internal fun ChatActivity.toolDump(tool: ToolExecution): String =
    "Параметры:\n${tool.arguments}\n\nРезультат:\n${tool.result ?: "Выполняется…"}"
