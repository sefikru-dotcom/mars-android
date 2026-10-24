package ru.mars.tv

import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.SessionRepository
import ru.mars.tv.chat.TvModelPickerEntry
import ru.mars.tv.chat.TvQuotaAmountsShape
import ru.mars.tv.chat.TvQuotaChip
import ru.mars.tv.chat.TvQuotaPolicy
import ru.mars.tv.chat.TvQuotaState
import ru.mars.tv.chat.TvQuotaTone
import ru.mars.tv.chat.TvTurnParamsPolicy

/**
 * Секция «Параметры хода» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Модель (GET /api/models), уровень размышлений (GET/POST /api/reasoning), квота
 * провайдера и модальный пикер: только живые серверные данные, без захардкоженных
 * списков — паритет мобильного чата.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

// ---------------------------------------------------------------------------------------------
// Параметры хода: модель (GET /api/models) и уровень размышлений (GET/POST /api/reasoning).
// Паритет мобильного ChatActivity (chooseModel / chooseReasoningEffort / applyReasoningEffort):
// только живые серверные данные, никаких захардкоженных списков. Выбор модели уходит в
// payload POST /api/chat/start; уровень размышлений — самостоятельная настройка сервера
// (chat/start reasoning не несёт: эффективный уровень сервер разрешает на каждый ход сам).
// ---------------------------------------------------------------------------------------------

/** Привязка пикера параметров: оверлей, «Закрыть» и BACK-колбэк. */
internal fun TvChatActivity.wireParamsPicker() {
    paramsOverlay = findViewById(R.id.paramsOverlay)
    paramsTitle = findViewById(R.id.paramsTitle)
    paramsList = findViewById(R.id.paramsList)
    closeParamsButton = findViewById(R.id.closeParamsButton)
    closeParamsButton.setOnClickListener { closeParamsPicker() }
    pickerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeParamsPicker() }
    }
}

/**
 * Чип «Модель»: живой каталог GET /api/models (мобильный chooseModel). Каталог
 * недоступен/пуст — честная строка статуса, пикер не открывается: выбирать не из
 * чего, а выдуманной модели не существует.
 */
internal fun TvChatActivity.chooseModel() {
    val credential = store.read()
    if (credential == null) {
        explicitStatus = getString(R.string.tv_status_models_no_auth)
        renderStatus()
        return
    }
    explicitStatus = getString(R.string.tv_status_models_loading)
    renderStatus()
    lifecycleScope.launch {
        // getModels — suspend и сам уходит на IO; сбой ловим в null (пустой каталог).
        val catalog = runCatching { ModelsRepository().getModels(TvChatActivity.BASE_URL, credential) }.getOrNull()
        val entries = catalog?.let { modelSelection.pickerEntries(it) }.orEmpty()
        if (entries.isEmpty()) {
            explicitStatus = getString(R.string.tv_status_models_unavailable)
            renderStatus()
            return@launch
        }
        explicitStatus = null
        openParamsPicker(
            title = getString(R.string.tv_picker_model_title),
            rows = TvTurnParamsPolicy.modelRows(entries, modelSelection.displayedChoice()?.modelId),
            returnFocus = modelButton,
        ) { index -> onModelPicked(entries, index) }
    }
}

/** Явный выбор модели: persist + немедленная привязка к сессии (payload chat/start). */
internal fun TvChatActivity.onModelPicked(entries: List<TvModelPickerEntry>, index: Int) {
    val entry = entries.getOrNull(index) ?: return
    val picked = modelSelection.select(entry.model) ?: return
    // Явный выбор сразу становится моделью сессии: следующий chat/start уходит с ним,
    // explicitPick=true — сервер фиксирует осознанный выбор (паритет с веб-клиентом).
    modelSelection.setSessionModel(picked.modelId, picked.providerId)
    renderModelChip()
    explicitStatus = getString(R.string.tv_status_model_picked, picked.modelId)
    renderStatus()
    // Лестница размышлений модельно-зависима — перечитываем после смены модели.
    refreshReasoningStatus()
}

/**
 * Чип «Мысли»: состояние GET /api/reasoning?model=&provider=&session_id= и пикер
 * уровней (мобильный chooseReasoningEffort). Отказ/пустая лестница — строка
 * статуса, пикер не открывается: уровней нет, выбирать нечего.
 */
internal fun TvChatActivity.chooseReasoningEffort() {
    val credential = store.read()
    if (credential == null) {
        explicitStatus = getString(R.string.tv_status_reasoning_no_auth)
        renderStatus()
        return
    }
    explicitStatus = getString(R.string.tv_status_reasoning_loading)
    renderStatus()
    lifecycleScope.launch {
        val model = modelSelection.displayedChoice()
        val fetched = withContext(Dispatchers.IO) {
            ReasoningRepository().fetchStatus(
                TvChatActivity.BASE_URL, credential, model?.modelId, model?.providerId, sessionFlow.sessionId,
            )
        }
        when (fetched) {
            is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
            is ReasoningStatusResult.Failed -> {
                reasoningSelection.applyStatus(null)
                renderReasoningChip()
                explicitStatus = getString(R.string.tv_status_reasoning_unavailable, fetched.message)
                renderStatus()
                return@launch
            }
        }
        renderReasoningChip()
        val options = reasoningSelection.options()
        if (options.isEmpty()) {
            explicitStatus = getString(R.string.tv_status_reasoning_no_ladder)
            renderStatus()
            return@launch
        }
        explicitStatus = null
        openParamsPicker(
            title = getString(R.string.tv_picker_reasoning_title),
            rows = TvTurnParamsPolicy.reasoningRows(options, reasoningSelection.effectiveEffort()),
            returnFocus = reasoningButton,
        ) { index ->
            options.getOrNull(index)?.let { option -> applyReasoningEffort(option.effort) }
        }
    }
}

/**
 * POST /api/reasoning; локально выбор сохраняется ТОЛЬКО после подтверждения сервера
 * (отказавшая запись не становится локальной правдой — мобильный applyReasoningEffort).
 */
internal fun TvChatActivity.applyReasoningEffort(effort: String?) {
    val credential = store.read()
    if (credential == null) {
        explicitStatus = getString(R.string.tv_status_reasoning_no_auth)
        renderStatus()
        return
    }
    explicitStatus = getString(R.string.tv_status_reasoning_saving)
    renderStatus()
    lifecycleScope.launch {
        val model = modelSelection.displayedChoice()
        val result = withContext(Dispatchers.IO) {
            ReasoningRepository().setEffort(
                TvChatActivity.BASE_URL, credential, effort, model?.modelId, model?.providerId, sessionFlow.sessionId,
            )
        }
        when (result) {
            is ReasoningStatusResult.Status -> {
                reasoningSelection.select(effort)
                reasoningSelection.applyStatus(result.status)
                renderReasoningChip()
                explicitStatus = getString(
                    R.string.tv_status_reasoning_saved,
                    reasoningSelection.label(result.status.reasoningEffort),
                )
            }
            is ReasoningStatusResult.Failed ->
                explicitStatus = getString(R.string.tv_status_reasoning_save_failed, result.message)
        }
        renderStatus()
    }
}

/** Тихое чтение каталога при старте: эффективная модель (persist-выбор или серверный дефолт). */
internal fun TvChatActivity.refreshModelCatalog() {
    val credential = store.read() ?: return
    lifecycleScope.launch {
        // getModels — suspend и сам уходит на IO; сбой сети молча пропускаем:
        // чип остаётся на восстановленном выборе/«Модель», выдуманных имён нет.
        val catalog = runCatching { ModelsRepository().getModels(TvChatActivity.BASE_URL, credential) }.getOrNull()
            ?: return@launch
        modelSelection.resolveEffective(catalog)
        renderModelChip()
    }
}

/** Тихая синхронизация состояния размышлений (старт, смена модели/диалога): сбой прячет чип. */
internal fun TvChatActivity.refreshReasoningStatus() {
    val credential = store.read() ?: return
    lifecycleScope.launch {
        val model = modelSelection.displayedChoice()
        val fetched = withContext(Dispatchers.IO) {
            ReasoningRepository().fetchStatus(
                TvChatActivity.BASE_URL, credential, model?.modelId, model?.providerId, sessionFlow.sessionId,
            )
        }
        when (fetched) {
            is ReasoningStatusResult.Status -> reasoningSelection.applyStatus(fetched.status)
            is ReasoningStatusResult.Failed -> reasoningSelection.applyStatus(null)
        }
        renderReasoningChip()
    }
}

/** Подпись чипа модели: серверное имя из каталога, иначе реальный id; без выбора — «Модель». */
internal fun TvChatActivity.renderModelChip() {
    if (!modelButtonReady) return
    modelButton.text = modelSelection.buttonLabel() ?: getString(R.string.tv_chip_model)
}

/**
 * Чип уровня размышлений: виден, только пока лестница известна (неизвестное состояние
 * скрывается вместо угадывания — мобильный паритет). Перед скрытием уводим фокус:
 * D-pad не должен оставаться на исчезающем элементе.
 */
internal fun TvChatActivity.renderReasoningChip() {
    if (!reasoningButtonReady) return
    val label = reasoningSelection.buttonLabel()
    if (label == null && reasoningButton.hasFocus()) modelButton.requestFocus()
    reasoningButton.visibility = if (label == null) View.GONE else View.VISIBLE
    reasoningButton.text = getString(R.string.tv_chip_reasoning, label.orEmpty())
}
internal fun TvChatActivity.bindTurnModel(event: ChatEvent.StreamStarted) {
    if (event.model.isNullOrBlank()) return
    modelSelection.setSessionModel(event.model, event.provider)
    renderModelChip()
}

/**
 * Открытие пикера параметров: модальный слой поверх чата (фокус заблокирован, как в
 * браузере диалогов), строки и начальный фокус — из [TvTurnParamsPolicy]. [onSelect]
 * получает индекс строки; закрытие и возврат фокуса — [closeParamsPicker].
 */
internal fun TvChatActivity.openParamsPicker(
    title: String,
    rows: List<TvTurnParamsPolicy.PickerRow>,
    returnFocus: View,
    onSelect: (Int) -> Unit,
) {
    pickerSelect = onSelect
    pickerReturnFocus = returnFocus
    paramsTitle.text = title
    pickerRowViews.clear()
    paramsList.removeAllViews()
    rows.forEachIndexed { index, row ->
        val card = buildPickerCard(index, row)
        pickerRowViews += card
        paramsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    chatRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    pickerBackCallback.isEnabled = true
    paramsOverlay.visibility = View.VISIBLE
    val selection = TvTurnParamsPolicy.initialSelection(rows)
    pickerRowViews.getOrNull(selection)?.requestFocus() ?: closeParamsButton.requestFocus()
}

/**
 * Строка пикера: карточка в стиле браузера диалогов (bg_focus_sidebar_item + lift).
 * Текущий выбор помечен рубиновым заголовком и подписью «текущий выбор» — без глифов
 * (дизайн-гейт ТВ запрещает символы-пиктограммы). UP/DOWN — явный шаг через
 * [TvTurnParamsPolicy.move] (зажатый на краях: фокус не уходит в чат за оверлеем).
 */
internal fun TvChatActivity.buildPickerCard(index: Int, row: TvTurnParamsPolicy.PickerRow): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_focus_sidebar_item)
        isFocusable = true
        isClickable = true
        setPadding(dp(22), dp(14), dp(22), dp(14))
        setOnClickListener { onPickerRowPicked(index) }
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> return@setOnKeyListener false
            }
            val target = TvTurnParamsPolicy.move(index, pickerRowViews.size, delta)
            val view = pickerRowViews.getOrNull(target)
            if (view != null && target != index) {
                view.requestFocus()
                true
            } else false
        }
        TvFocusLift.attach(this, reducedMotion)
    }
    card.addView(TextView(this).apply {
        text = row.label
        textSize = 20f
        setTextColor(ContextCompat.getColor(
            context, if (row.current) R.color.mars_accent_text else R.color.mars_text_strong))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    })
    if (row.current) {
        card.addView(TextView(this).apply {
            text = getString(R.string.tv_picker_current)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.mars_status_info))
            isSingleLine = true
            setPadding(0, dp(2), 0, 0)
        })
    }
    return card
}

/**
 * Строка пикера активирована: сначала закрываем пикер (фокус возвращается на чип),
 * затем выполняем выбор — сетевой исход ляжет в статус-полосу уже видимого чата.
 */
internal fun TvChatActivity.onPickerRowPicked(index: Int) {
    val select = pickerSelect ?: return
    closeParamsPicker()
    select(index)
}

/** Закрытие пикера: фокус возвращается на вызвавшую кнопку (страховка — чип «Модель»). */
internal fun TvChatActivity.closeParamsPicker() {
    if (!paramsOverlayReady || paramsOverlay.visibility != View.VISIBLE) return
    paramsOverlay.visibility = View.GONE
    pickerBackCallback.isEnabled = false
    pickerSelect = null
    pickerRowViews.clear()
    paramsList.removeAllViews()
    chatRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    val restore = pickerReturnFocus?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    pickerReturnFocus = null
    (restore ?: modelButton).requestFocus()
}
