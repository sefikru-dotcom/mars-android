package ru.mars.mobile

import android.app.Dialog
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionAutonomyRepository
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionToolsetsOutcome

/**
 * Индикаторы расхода: сессия, квота провайдера, наборы инструментов, автономность.
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; подписи и состояния считают
 * чистые презентеры ([SessionUsagePresenter], [ProviderQuotaPresenter],
 * [SessionToolsetsPresenter]).
 */

// --- A14: расход/стоимость сессии ----------------------------------------------------------

/**
 * Запрашивает расход сессии (`GET /api/session/usage`) и показывает компактную
 * пилюлю: токены и стоимость. Недоступный/нулевой расход пилюлю скрывает —
 * «$0.00» вместо неизвестной цены был бы выдумкой. Вызывается при открытии
 * диалога и по завершении хода (расход меняется только ходами).
 */
internal fun ChatActivity.refreshSessionUsage() {
    val id = currentSessionId
    val credential = store.read()
    if (id == null || credential == null) {
        sessionUsage = null
        updateUsageIndicator()
        return
    }
    lifecycleScope.launch {
        val usage = withContext(Dispatchers.IO) {
            runCatching { SessionRepository().getSessionUsage(ChatActivity.BASE_URL, credential, id) }.getOrNull()
        }
        // Диалог мог смениться, пока шёл запрос: чужой расход не показываем.
        if (currentSessionId != id) return@launch
        sessionUsage = usage
        updateUsageIndicator()
    }
}

/** Пилюля расхода: текст и видимость — из [SessionUsagePolicy], цвет — из палитры темы. */

/** Пилюля расхода: текст и видимость — из [SessionUsagePolicy], цвет — из палитры темы. */
internal fun ChatActivity.updateUsageIndicator() {
    val view = usageIndicator ?: return
    val state = SessionUsagePolicy.view(sessionUsage)
    if (!state.visible) {
        view.visibility = View.GONE
        return
    }
    view.visibility = View.VISIBLE
    view.text = state.text
    view.contentDescription = state.contentDescription
    view.setTextColor(MarsTheme.TEXT_MUTED)
}

// --- A9/A10: чипы композера ---------------------------------------------------------------

/** Обновляет подписи и видимость чипов A9 (набор инструментов) и A10 (квота). */

// --- A9/A10: чипы композера ---------------------------------------------------------------

/** Обновляет подписи и видимость чипов A9 (набор инструментов) и A10 (квота). */
internal fun ChatActivity.refreshComposerChips() {
    toolsetsChip?.apply {
        text = toolsetsPresenter.chipLabel()
        contentDescription = toolsetsPresenter.chipDescription()
        visibility = if (currentSessionId == null) View.GONE else View.VISIBLE
    }
    quotaChip?.apply {
        val model = quotaPresenter.model()
        text = model.label
        contentDescription = model.contentDescription
        visibility = if (model.visible) View.VISIBLE else View.GONE
    }
}

/**
 * A10: тянет квоту активного провайдера. Экран открытого диалога — единственное
 * место, где чип имеет смысл: без сессии провайдер неизвестен, а числа квоты
 * относятся к аккаунту, а не к диалогу.
 */

/**
 * A10: тянет квоту активного провайдера. Экран открытого диалога — единственное
 * место, где чип имеет смысл: без сессии провайдер неизвестен, а числа квоты
 * относятся к аккаунту, а не к диалогу.
 */
internal fun ChatActivity.refreshProviderQuota() {
    val credential = store.read() ?: run { quotaPresenter.reset(); refreshComposerChips(); return }
    quotaPresenter.onLoading()
    refreshComposerChips()
    lifecycleScope.launch {
        val outcome = runCatching { SessionRepository().getProviderQuota(ChatActivity.BASE_URL, credential) }
            .getOrElse { ProviderQuotaOutcome.Failure(it.message ?: "Network error") }
        quotaPresenter.apply(outcome)
        refreshComposerChips()
    }
}

/**
 * A9: множественный выбор набора инструментов сессии. Справочник статический
 * ([SessionToolsetsPresenter.dialogEntries]), поэтому список не «прыгает» между
 * открытиями. Пустой выбор трактуется как сброс на набор по умолчанию — сервер
 * отвергает пустой список, и пользователь не должен заходить в тупик.
 */

/**
 * A9: множественный выбор набора инструментов сессии. Справочник статический
 * ([SessionToolsetsPresenter.dialogEntries]), поэтому список не «прыгает» между
 * открытиями. Пустой выбор трактуется как сброс на набор по умолчанию — сервер
 * отвергает пустой список, и пользователь не должен заходить в тупик.
 */
internal fun ChatActivity.showToolsetsDialog() {
    val sessionId = currentSessionId ?: return
    val initial = toolsetsPresenter.selection
    val checked = initial.toMutableSet()
    var dialog: Dialog? = null
    dialog = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Инструменты сессии", null))
        root.addView(
            MarsDialog.paragraph(
                this,
                "Отметьте, что разрешено в этом диалоге. Ничего не отмечено — набор по умолчанию.",
            ),
        )
        val entries = SessionToolsetsPresenter.dialogEntries()
        val boxes = entries.map { option ->
            CheckBox(this).apply {
                text = option.title
                isChecked = option.name in checked
                setTextColor(MarsTheme.TEXT)
                setOnCheckedChangeListener { _, value ->
                    if (value) checked += option.name else checked -= option.name
                }
            }
        }
        boxes.forEach { root.addView(it) }
        root.addView(
            MarsDialog.buttonRow(
                this,
                MarsDialog.secondaryButton(this, "Отмена") { dialog?.dismiss() },
                MarsDialog.primaryButton(this, "Сохранить") {
                    dialog?.dismiss()
                    saveToolsets(sessionId, checked)
                },
            ),
        )
        emptyList()
    }
}

/** A9: отправляет выбор на сервер; чип показывает только ПОДТВЕРЖДЁННОЕ значение. */

/** A9: отправляет выбор на сервер; чип показывает только ПОДТВЕРЖДЁННОЕ значение. */
internal fun ChatActivity.saveToolsets(sessionId: String, selected: Set<String>) {
    val credential = store.read() ?: return
    toolsetsPresenter.onSaving()
    refreshComposerChips()
    lifecycleScope.launch {
        val payload = SessionToolsetsPolicy.payloadFor(selected)
        val outcome = runCatching {
            SessionRepository().updateSessionToolsets(ChatActivity.BASE_URL, credential, sessionId, payload)
        }.getOrElse { SessionToolsetsOutcome.Failure(it.message ?: "Network error") }
        toolsetsPresenter.apply(outcome)
        uiState.applyEnabledToolsets(toolsetsPresenter.enabledToolsets)
        refreshComposerChips()
        toolsetsPresenter.statusText()?.let { Toast.makeText(this@saveToolsets, it, Toast.LENGTH_SHORT).show() }
    }
}

/** A10: пояснение к чипу квоты — что за числа и почему их нет. */

/** A10: пояснение к чипу квоты — что за числа и почему их нет. */
internal fun ChatActivity.showQuotaDetails() {
    val model = quotaPresenter.model()
    val body = TextView(this).apply {
        text = listOfNotNull(model.label, model.detail.ifBlank { null }).joinToString("\n\n")
        setTextColor(MarsTheme.TEXT)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, dp(8))
        addView(body)
    }
    android.app.AlertDialog.Builder(this)
        .setTitle("Квота провайдера")
        .setView(container)
        .setPositiveButton("Закрыть", null)
        .show()
}

internal fun ChatActivity.showSessionUsageDetails() {
    val body = TextView(this).apply {
        text = SessionUsagePolicy.detailsText(sessionUsage)
        setTextColor(MarsTheme.TEXT)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, dp(8))
        addView(body)
    }
    android.app.AlertDialog.Builder(this)
        .setTitle("Расход сессии")
        .setView(container)
        .setPositiveButton("Закрыть", null)
        .show()
}

// --- Autonomous execution state --------------------------------------------------------------

/** Refreshes the server-side execution approval state without exposing internal terminology in the UI. */

// --- Autonomous execution state --------------------------------------------------------------

/** Refreshes the server-side execution approval state without exposing internal terminology in the UI. */
internal fun ChatActivity.refreshAutonomyState() {
    val sid = currentSessionId
    val credential = store.read()
    if (sid == null || credential == null) {
        uiState.setAutonomyEnabled(false)
        return
    }
    lifecycleScope.launch {
        val enabled = withContext(Dispatchers.IO) {
            when (val lookup = SessionAutonomyRepository().fetchState(ChatActivity.BASE_URL, credential, sid)) {
                is AutonomyLookup.State -> lookup.enabled
                is AutonomyLookup.Failed -> false
            }
        }
        if (currentSessionId == sid) {
            uiState.setAutonomyEnabled(enabled)
            }
    }
}

// --- Finish (graceful session close) ---------------------------------------------------------

/**
 * «Финиш»: graceful close of the current session — cancels the in-flight stream on the
 * server, denies every queued approval so the agent is not left parked, drops pending
 * dialogs, and returns the chat to the fresh-session state. Orchestration lives in the
 * pure [SessionFinishFlow] (unit-tested); this is only the Android wiring.
 */
