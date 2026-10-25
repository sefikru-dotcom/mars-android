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
 * Выбор модели/усилия/режима/воркспейса, индикатор контекста, сжатие.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

internal fun ChatActivity.chooseModel() {
    val c = store.read()
    if (c == null) { statusView.text = "Модели недоступны: нет авторизации"; return }
    lifecycleScope.launch {
        statusView.text = "Загружаю модели…"
        val catalog = withContext(Dispatchers.IO) {
            runCatching { ModelsRepository().getModels(ChatActivity.BASE_URL, c) }.getOrNull()
        }
        val entries = catalog?.let { modelSelection.pickerEntries(it) }.orEmpty()
        if (entries.isEmpty()) {
            statusView.text = "Каталог моделей недоступен"
            return@launch
        }
        val labels = entries.map { it.label }.toTypedArray()
        val checked = entries.indexOfFirst { it.model.id == modelSelection.displayedChoice()?.modelId }
        android.app.AlertDialog.Builder(this@chooseModel)
            .setTitle("Модель Марса")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val picked = modelSelection.select(entries[which].model)
                modelSelection.setSessionModel(picked?.modelId, picked?.providerId)
                d.dismiss()
                if (picked != null) {
                    updateModelButton()
                    statusView.text = "Модель: ${picked.modelId}"
                    // The effort ladder is per-model: re-read it after a model change.
                    refreshReasoningStatus()
                }
            }
            .show()
    }
}

internal fun ChatActivity.refreshModelCatalog() {
    val c = store.read() ?: return
    lifecycleScope.launch {
        val catalog = withContext(Dispatchers.IO) {
            runCatching { ModelsRepository().getModels(ChatActivity.BASE_URL, c) }.getOrNull()
        } ?: return@launch
        modelSelection.resolveEffective(catalog)
        updateModelButton()
    }
}

internal fun ChatActivity.updateModelButton() {
    modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
}

/**
 * Reasoning-effort chip (WebUI composer-chip parity, static/ui.js). The ladder is
 * server-driven (`GET /api/reasoning` -> `supported_efforts` for the active model);
 * an explicit pick is persisted server-side via `POST /api/reasoning` and only then
 * recorded locally — `chat/start` itself carries NO reasoning field: the server
 * resolves the effort per turn from its own session/override/config state.
 */
internal fun ChatActivity.chooseReasoningEffort() {
    val c = store.read()
    if (c == null) { statusView.text = "Уровень размышлений недоступен: нет авторизации"; return }
    lifecycleScope.launch {
        statusView.text = "Загружаю уровни размышлений…"
        val model = modelSelection.displayedChoice()
        val fetched = withContext(Dispatchers.IO) {
            ReasoningRepository().fetchStatus(ChatActivity.BASE_URL, c, model?.modelId, model?.providerId, currentSessionId)
        }
        when (fetched) {
            is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
            is ReasoningStatusResult.Failed -> {
                reasoningSelection.applyStatus(null)
                updateReasoningButton()
                statusView.text = "Уровень размышлений недоступен: ${fetched.message}"
                return@launch
            }
        }
        updateReasoningButton()
        val options = reasoningSelection.options()
        if (options.isEmpty()) {
            statusView.text = "У этой модели нет уровней размышлений"
            return@launch
        }
        val labels = options.map { it.label }.toTypedArray()
        val effective = reasoningSelection.effectiveEffort()
        val checked = options.indexOfFirst { it.effort == effective }
        android.app.AlertDialog.Builder(this@chooseReasoningEffort)
            .setTitle("Уровень размышлений")
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                applyReasoningEffort(options[which].effort)
            }
            .show()
    }
}

/** POST /api/reasoning; the pick is persisted locally only after the server confirmed it. */
internal fun ChatActivity.applyReasoningEffort(effort: String?) {
    val c = store.read()
    if (c == null) { statusView.text = "Уровень размышлений недоступен: нет авторизации"; return }
    lifecycleScope.launch {
        statusView.text = "Сохраняю уровень размышлений…"
        val model = modelSelection.displayedChoice()
        val result = withContext(Dispatchers.IO) {
            ReasoningRepository().setEffort(ChatActivity.BASE_URL, c, effort, model?.modelId, model?.providerId, currentSessionId)
        }
        when (result) {
            is ReasoningStatusResult.Status -> {
                reasoningSelection.select(effort)
                reasoningSelection.applyStatus(result.status)
                updateReasoningButton()
                statusView.text = "Уровень размышлений: ${reasoningSelection.label(result.status.reasoningEffort)}"
            }
            is ReasoningStatusResult.Failed ->
                statusView.text = "Не удалось изменить уровень размышлений: ${result.message}"
        }
    }
}

/**
 * Silent status sync (startup and after model picks): on any failure the chip hides —
 * an unknown reasoning state must never show a stale ladder.
 */
internal fun ChatActivity.refreshReasoningStatus() {
    val c = store.read() ?: return
    lifecycleScope.launch {
        val model = modelSelection.displayedChoice()
        val fetched = withContext(Dispatchers.IO) {
            ReasoningRepository().fetchStatus(ChatActivity.BASE_URL, c, model?.modelId, model?.providerId, currentSessionId)
        }
        when (fetched) {
            is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
            is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)
        }
        updateReasoningButton()
    }
}

internal fun ChatActivity.updateReasoningButton() {
    val label = reasoningSelection.buttonLabel()
    reasoningBtn?.apply {
        visibility = if (label == null) View.GONE else View.VISIBLE
        text = label
    }
}

/** Activity display mode picker — parity with the WebUI header switch. */
internal fun ChatActivity.chooseActivityMode() {
    val modes = ActivityDisplayMode.values()
    val labels = modes.map { "${it.label} — ${it.description}" }.toTypedArray()
    val checked = modes.indexOf(activityModeSelection.current)
    android.app.AlertDialog.Builder(this)
        .setTitle("Режим активности")
        .setSingleChoiceItems(labels, checked) { d, which ->
            val picked = activityModeSelection.select(modes[which])
            d.dismiss()
            uiState.setActivityMode(picked)
            updateActivityModeButton()
            // Режим активности меняет видимость мыслей/инструментов во ВСЕХ карточках:
            // эпоха хрома → FullRebuild вместо «снимки равны, ничего не делать».
            transcriptChromeEpoch++
            renderMessages()
            setStatus()
        }
        .show()
}

internal fun ChatActivity.updateActivityModeButton() {
    activityModeBtn?.text = activityModeSelection.buttonLabel()
}

/**
 * Workspace picker (WebUI parity with the composer workspace chip): the first
 * row is "Авто" (server default, no persisted pick), then the live catalog
 * rows `name — path`. The catalog comes from GET /api/workspaces.
 */
internal fun ChatActivity.chooseWorkspace() {
    val credential = store.read()
    if (credential == null) {
        statusView.text = "Workspaces недоступны: нет авторизации"
        return
    }
    lifecycleScope.launch {
        statusView.text = "Загружаю рабочие пространства…"
        val catalog = runCatching { WorkspaceRepository().getWorkspaces(ChatActivity.BASE_URL, credential) }.getOrNull()
        if (catalog == null || catalog.workspaces.isEmpty()) {
            statusView.text = "Каталог рабочих пространств недоступен"
            return@launch
        }
        workspaceSelection.pickerEntries(catalog)
        val items = catalog.workspaces
        val labels = (listOf("Авто — пространство сервера по умолчанию") +
            items.map { "${it.name} — ${it.path}" }).toTypedArray()
        val checkedIndex = items.indexOfFirst { it.path == workspaceSelection.current?.path } + 1
        android.app.AlertDialog.Builder(this@chooseWorkspace)
            .setTitle("Рабочее пространство")
            .setSingleChoiceItems(labels, checkedIndex) { d, which ->
                workspaceSelection.select(if (which == 0) null else items[which - 1])
                d.dismiss()
                updateWorkspaceButton()
                statusView.text = workspaceSelection.current?.let { "Workspace: ${it.name}" } ?: "Workspace: авто"
            }
            .show()
    }
}

internal fun ChatActivity.updateWorkspaceButton() {
    workspaceBtn?.text = workspaceSelection.buttonLabel() ?: "Workspace"
}

/**
 * Loads GET /api/workspaces once at startup: adopts the server default
 * (`last`) when nothing is persisted and refreshes the chip label.
 */
internal fun ChatActivity.loadWorkspaces() {
    val credential = store.read() ?: return
    lifecycleScope.launch {
        val catalog = runCatching { WorkspaceRepository().getWorkspaces(ChatActivity.BASE_URL, credential) }.getOrNull() ?: return@launch
        workspaceSelection.resolveEffective(catalog)
        updateWorkspaceButton()
    }
}

/**
 * Кольцо контекста в композере (паритет с `.ctx-ring-svg` WebUI): дуга + процент,
 * цвет по порогам. Скрыто, пока замер неизвестен — нерешённое окно не превращается
 * в фейковый процент (#1436 parity). Рядом с кольцом — подпись с объёмом токенов и
 * порогом автосжатия, чтобы «сколько занято» и «когда сожмёт» были видны без тапа.
 * Решение (видимость, процент, порог, тексты) принимает [ContextBudgetPolicy]
 * (JVM-тесты); этот слой только отдаёт его кольцу и подписи.
 */
internal fun ChatActivity.updateContextIndicator() {
    val pill = ContextBudgetPolicy.pill(uiState.contextUsage, compressionRunning)
    contextRing?.render(pill)
    val label = ContextBudgetPolicy.composerLabel(uiState.contextUsage, compressionRunning)
    val caption = contextCaption ?: return
    caption.text = label
    caption.contentDescription = pill.contentDescription
    caption.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
    // Строка целиком скрыта, когда измерять нечего: пустой подписи и «нулевого» порога
    // пользователь видеть не должен (ContextBudgetPolicy возвращает null, а не нули).
    contextRow?.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
}

/**
 * Context pill dialog: fill details + quick manual compression (WebUI «быстрое сжатие
 * сессии» parity) with the optional focus topic, plus the persisted auto-compression switch
 * ([PrefsAutoCompressionStorage]).
 */
internal fun ChatActivity.showContextUsageDetails() {
    if (compressionRunning) return
    val usage = uiState.contextUsage
    val config = autoCompressionStorage.load()
    val details = TextView(this).apply {
        text = ContextBudgetPolicy.detailsText(usage)
        setTextColor(MarsTheme.TEXT_STRONG)
    }
    val topicInput = EditText(this).apply {
        hint = "Фокус сжатия (необязательно)"
        setSingleLine(true)
        setTextColor(MarsTheme.TEXT_STRONG)
        setHintTextColor(MarsTheme.TEXT_MUTED)
    }
    val autoBox = CheckBox(this).apply {
        // Локальное предпочтение приложения (AutoCompressionConfig), НЕ серверный
        // threshold_tokens: серверный порог показан строкой в деталях выше
        // («Сервер сожмёт при: 160.0k · 80%»), поэтому подписи не путаются.
        text = "Локальный авто-режим при ${config.thresholdPercent}% заполнения"
        isChecked = config.enabled
        setTextColor(MarsTheme.TEXT_STRONG)
        setOnCheckedChangeListener { _, checked ->
            autoCompressionStorage.save(autoCompressionStorage.load().copy(enabled = checked))
        }
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, dp(8))
        addView(details)
        addView(topicInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        addView(autoBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }
    android.app.AlertDialog.Builder(this)
        .setTitle("Окно контекста")
        .setView(container)
        .setPositiveButton("Сжать контекст") { _, _ ->
            compressionController.startManualCompression(topicInput.text.toString())
        }
        .setNegativeButton("Закрыть", null)
        .show()
}
