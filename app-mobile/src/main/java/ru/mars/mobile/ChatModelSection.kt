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
        // Checked row through the catalog-aware matcher: the session's model may be the
        // bare id (`claude-sonnet-5.5`) while the catalog row is `@provider:model`, and
        // a plain `id ==` left the picker with nothing selected.
        val checked = entries.indexOfFirst { modelSelection.matchesCatalogModel(it.model, modelSelection.displayedChoice()) }
        android.app.AlertDialog.Builder(this@chooseModel)
            .setTitle("Модель Марса")
            .setSingleChoiceItems(labels, checked) { d, which ->
                // The pick itself becomes the wire choice (explicit_model_pick=true).
                // Do NOT re-bind it through setSessionModel(): that wrote the same id
                // back as server truth with explicitPick=false, so the very next
                // chat/start lost the flag and the server repaired the model to the
                // profile default (haiku).
                val picked = modelSelection.select(entries[which].model)
                d.dismiss()
                if (picked != null) {
                    updateModelButton()
                    statusView.text = "Модель: ${picked.modelId}"
                    // The effort ladder is per-model: re-read it after a model change.
                    refreshReasoningStatus()
                    // An opened chat's model IS session state: the WebUI writes it with
                    // POST /api/session/update, so the phone does the same — otherwise the
                    // next browser turn would run on the model the server still holds.
                    val sid = currentSessionId
                    if (sid != null) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                SessionRepository().updateSessionModel(
                                    ChatActivity.BASE_URL,
                                    c,
                                    sid,
                                    picked.modelId,
                                    picked.providerId,
                                )
                            }
                        }
                    }
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
 * Кольцо и подпись контекста в кнопке-капсуле внизу композера (паритет с `.ctx-ring-svg`
 * WebUI): дуга + процент, цвет по порогам. Кольцо видно ВСЕГДА — без замера оно
 * показывает нейтральный 0%, иначе вместе с ним пропадал бы вход в окно контекста
 * ([showContextUsageDetails]). Подпись рядом — «25% · 50.0k/200.0k · сжатие при 160.0k ·
 * 80%»: «сколько занято» и «когда сожмёт» видны без тапа.
 * Полоса `contextRow` под шапкой больше не показывается (пункт 5 владельца) — она
 * держится GONE и нулевой высоты.
 * Решение (текст, процент, порог) принимает [ContextBudgetPolicy] (JVM-тесты);
 * этот слой только отдаёт его кольцу и подписи.
 */
internal fun ChatActivity.updateContextIndicator() {
    val pill = ContextBudgetPolicy.pill(uiState.contextUsage, compressionRunning)
    contextRing?.render(pill)
    // Подпись постоянная: без замера это честное «Контекст ещё не измерен»,
    // а не пустая строка и не выдуманный процент.
    val label = ContextBudgetPolicy.composerLabel(uiState.contextUsage, compressionRunning)
    val caption = contextCaption ?: return
    caption.text = label
    caption.contentDescription = pill.contentDescription ?: label
    caption.visibility = View.VISIBLE
    // Полоса под шапкой убрана с экрана совсем: держим её GONE при любом замере,
    // чтобы она не вылезала обратно и не загромождала экран.
    contextRow?.visibility = View.GONE
}

/**
 * Единое окно «Контекст и параметры диалога» — стеклянная шторка [MarsDialog] вместо
 * прежней россыпи мелких кнопок под полем ввода (пункты 3 и 5 владельца): «пусть внизу
 * будет одна кнопка окна контекста, в которой всё будет аккуратно по полочкам разложено».
 *
 * Три раздела:
 * 1. Окно контекста и расход — заполнение ([ContextBudgetPolicy]), расход/стоимость
 *    сессии ([SessionUsagePolicy]), фокус сжатия, автосжатие и ручное сжатие;
 * 2. Параметры сессии и модели — модель, уровень размышлений, рабочее пространство,
 *    набор инструментов, квота провайдера. Тап по строке закрывает окно и открывает
 *    соответствующий пикер (прежние обработчики, просто аккуратно разложены);
 * 3. Режим чата, поиск и озвучка — режим активности, поиск по диалогу, озвучка ответа
 *    и переключатель автоозвучки.
 *
 * Ничего не решается здесь: тексты/проценты — из чистых политик, действия — прежние
 * методы Activity. Окно только раскладывает их по полочкам.
 */
internal fun ChatActivity.showContextUsageDetails() {
    val activity = this
    val usage = uiState.contextUsage
    val config = autoCompressionStorage.load()
    val usageView = SessionUsagePolicy.view(sessionUsage)
    val percent = usage?.takeIf { it.maxTokens > 0 }?.percent
    var dialog: Dialog? = null

    fun closeWindow() {
        dialog?.dismiss()
    }

    /** Строка-карточка параметра: тап закрывает окно и открывает соответствующий пикер. */
    fun paramRow(title: String, value: String, action: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(activity, MarsTheme.SURFACE_SUBTLE, radiusDp = 12, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(14), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            contentDescription = "$title: $value"
            setOnClickListener { action() }
            addView(TextView(activity).apply {
                text = title
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                text = value
                textSize = 12f
                setTextColor(MarsTheme.ACCENT_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                maxWidth = dp(168)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            addView(ImageView(activity).apply {
                setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_arrow_forward))
                imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) })
        }

    /** Строка «подпись слева — значение справа» внутри карточки раздела. */
    fun statRow(title: String, value: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT_MUTED)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                text = value
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT_STRONG)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.END
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }

    /** Стеклянная карточка раздела с произвольной внутренней раскладкой. */
    fun glassCard(build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.glassSurface(activity, radiusDp = 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            build()
        }

    /** Заголовок раздела: маленькие капс-буквы акцентом, как в шторках WebUI. */
    fun sectionTitle(title: String): View =
        TextView(activity).apply {
            text = title
            textSize = 10f
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            includeFontPadding = false
            setPadding(0, dp(16), 0, dp(8))
        }

    // --- Раздел 1: окно контекста и расход ------------------------------------------------
    val barTone = when (percent?.let { ContextBudgetPolicy.level(it.coerceIn(0, 100)) }) {
        ContextBudgetLevel.CRITICAL -> MarsTheme.STATUS_ERROR
        ContextBudgetLevel.WARNING -> MarsTheme.STATUS_WARNING
        else -> MarsTheme.STATUS_ONLINE
    }
    val fillBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = (percent ?: 0).coerceIn(0, 100)
        progressTintList = ColorStateList.valueOf(barTone)
        progressBackgroundTintList = ColorStateList.valueOf(MarsTheme.SURFACE_SUBTLE)
    }
    // Локальное предпочтение приложения (AutoCompressionConfig), НЕ серверный
    // threshold_tokens: серверный порог показан строкой в деталях выше
    // («Сервер сожмёт при: 160.0k · 80%»), поэтому подписи не путаются.
    val autoBox = CheckBox(activity).apply {
        text = "Автосжатие контекста при ${config.thresholdPercent}%"
        textSize = 12.5f
        setTextColor(MarsTheme.TEXT_EM)
        isChecked = config.enabled
        buttonTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        setPadding(0, dp(4), 0, dp(4))
        setOnCheckedChangeListener { _, checked ->
            autoCompressionStorage.save(autoCompressionStorage.load().copy(enabled = checked))
        }
    }
    val focusInput = MarsDialog.textField(activity, hint = "Фокус сжатия (необязательно)").apply {
        minLines = 1
        maxLines = 2
    }
    val contextCard = glassCard {
        addView(statRow("Заполнение контекста", percent?.let { "$it%" } ?: "не измерено"))
        addView(fillBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        addView(TextView(activity).apply {
            text = ContextBudgetPolicy.detailsText(usage)
            textSize = 12f
            setTextColor(MarsTheme.TEXT_EM)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(View(activity).apply { setBackgroundColor(MarsTheme.BORDER_MUTED) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        addView(statRow("Расход и стоимость", usageView.text ?: SessionUsagePolicy.UNAVAILABLE_DETAILS))
        addView(TextView(activity).apply {
            text = SessionUsagePolicy.detailsText(sessionUsage)
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            setLineSpacing(dp(1).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(focusInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(autoBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    // --- Раздел 2: параметры сессии и модели ---------------------------------------------
    val modelRow = paramRow("Модель", modelSelection.buttonLabel() ?: "По умолчанию") {
        closeWindow()
        chooseModel()
    }
    val reasoningRow = paramRow("Уровень размышлений", reasoningSelection.buttonLabel() ?: "Авто") {
        closeWindow()
        chooseReasoningEffort()
    }
    val workspaceRow = paramRow("Рабочее пространство", workspaceSelection.buttonLabel() ?: "Авто") {
        closeWindow()
        chooseWorkspace()
    }
    val toolsetsRow = paramRow("Инструменты сессии", toolsetsPresenter.chipLabel()) {
        closeWindow()
        showToolsetsDialog()
    }
    val quotaRow = paramRow("Квота провайдера", quotaPresenter.model().label) {
        closeWindow()
        showQuotaDetails()
    }

    // --- Раздел 3: режим чата, поиск и озвучка -------------------------------------------
    val activityModeRow = paramRow("Режим активности", activityModeSelection.buttonLabel()) {
        closeWindow()
        chooseActivityMode()
    }
    val searchRow = paramRow("Поиск по диалогу", "Найти текст в сообщениях") {
        closeWindow()
        openTranscriptSearch()
    }
    val speakRow = paramRow("Озвучка ответа", "Прочитать последний ответ вслух") {
        closeWindow()
        speakLastMessage()
    }
    val autoSpeakBox = CheckBox(activity).apply {
        text = "Автоозвучивание ответов"
        textSize = 12.5f
        setTextColor(MarsTheme.TEXT_EM)
        isChecked = isVoiceOutputReady() && voiceOutput.autoSpeakEnabled
        buttonTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        setPadding(0, dp(4), 0, dp(4))
        setOnCheckedChangeListener { _, checked ->
            // Переключение — явное действие пользователя (режим persisted); лишний
            // toggle при программной установке isChecked гасим сравнением состояний.
            if (isVoiceOutputReady() && voiceOutput.autoSpeakEnabled != checked) toggleAutoSpeak()
        }
    }

    val compressBtn = MarsDialog.primaryButton(
        activity,
        if (compressionRunning) "Сжатие уже идёт…" else "Сжать контекст сейчас",
    ) {
        if (!compressionRunning) {
            closeWindow()
            compressionController.startManualCompression(focusInput.text.toString())
        }
    }.apply { isEnabled = !compressionRunning }

    dialog = MarsDialog.show(activity) { root ->
        root.addView(MarsDialog.header(activity, "Контекст и параметры диалога", null))

        root.addView(sectionTitle("ОКНО КОНТЕКСТА И РАСХОД"))
        root.addView(contextCard, LinearLayout.LayoutParams(-1, -2))
        root.addView(
            MarsDialog.buttonRow(
                activity,
                compressBtn,
                MarsDialog.quietButton(activity, "Закрыть") { closeWindow() },
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )

        root.addView(sectionTitle("ПАРАМЕТРЫ СЕССИИ И МОДЕЛИ"))
        listOf(modelRow, reasoningRow, workspaceRow, toolsetsRow, quotaRow).forEachIndexed { index, row ->
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
        }

        root.addView(sectionTitle("РЕЖИМ ЧАТА, ПОИСК И ОЗВУЧКА"))
        listOf(activityModeRow, searchRow, speakRow).forEachIndexed { index, row ->
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
        }
        root.addView(autoSpeakBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(
            MarsDialog.quietButton(activity, "Закрыть окно") { closeWindow() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        emptyList()
    }
}

/**
 * Переключатель автоозвучки для окна контекста: тот же путь, что у долгого нажатия на
 * динамик композера (persisted-режим «читать ответы вслух»). Возвращает новое состояние.
 */
internal fun ChatActivity.toggleAutoSpeak(): Boolean {
    if (!isVoiceOutputReady()) return false
    voiceOutput.setAutoSpeak(!voiceOutput.autoSpeakEnabled)
    updateVolumeBtn()
    return voiceOutput.autoSpeakEnabled
}
