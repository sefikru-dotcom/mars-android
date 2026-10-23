package ru.mars.mobile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск: строка ввода с подсказками и историей, guarded-прогон запроса.
 *
 * Вынесено из [MusicActivity] (W1, шаг 14) как extension-функции на Activity. Проводка
 * прежняя: [TextWatcher] живёт внутри [buildSearchBar], панель истории/подсказок —
 * оверлей над списком (z-order собирает оболочка экрана), решения принимают чистые
 * политики [SearchHistoryPanelPolicy], [SearchSuggestionsPolicy], [SearchRunGuard],
 * [MusicSearchHistoryPolicy].
 */

internal fun MusicActivity.buildSearchBar(): View {
    val searchContainer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = MarsTheme.card(this@buildSearchBar, MarsTheme.SURFACE, radiusDp = 14)
        setPadding(dp(12), dp(4), dp(8), dp(4))
    }
    searchInput = EditText(this).apply {
        hint = "Поиск трека или исполнителя…"
        setSingleLine()
        setTextColor(MarsTheme.TEXT_STRONG)
        setHintTextColor(MarsTheme.TEXT_MUTED)
        textSize = 15f
        background = null
        setPadding(dp(4), dp(10), dp(4), dp(10))
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(searchInput.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        // The history overlay follows the focus: shown only while the
        // field is empty and focused (pure SearchHistoryPanelPolicy).
        setOnFocusChangeListener { _, _ -> updateSearchHistoryOverlay() }
    }
    searchContainer.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))

    // Clear-search (visible only while the field has text); reloads the active tab
    clearSearchBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_ui_close,
        "Очистить поиск",
        bg = MarsTheme.SURFACE_SUBTLE,
        iconTint = MarsTheme.TEXT_MUTED,
        sizeDp = 28
    ).apply {
        visibility = View.GONE
        setOnClickListener {
            searchInput.setText("")
            visibility = View.GONE
            hideKeyboard()
            reloadActiveTab()
        }
    }
    searchContainer.addView(
        clearSearchBtn,
        LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) }
    )
    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            clearSearchBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            // Живые подсказки: дебаунс-запрос /api/suggestions на каждую паузу ввода.
            scheduleSuggestions(s?.toString())
            updateSearchHistoryOverlay()
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    val searchBtn = MarsTheme.circleIconButton(
        this,
        R.drawable.ic_ui_search,
        "Найти",
        bg = MarsTheme.ACCENT,
        iconTint = MarsTheme.TEXT_STRONG,
        sizeDp = 36
    ).apply {
        setOnClickListener { search(searchInput.text.toString()) }
    }
    searchContainer.addView(searchBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

    return searchContainer
}

/**
 * Runs one search as a guarded run: supersedes the previous job and tags the
 * new one with a monotonic [searchRunId]. Only a live, current run may touch
 * the UI or the history ([SearchRunGuard]) — a cancelled/superseded run never
 * shows an error, never overwrites a stale tab and never enters the history.
 * Only a *successful* search is recorded ([MusicSearchHistory.recordSuccess]).
 */
internal fun MusicActivity.search(rawQuery: String) {
    val q = MusicSearchHistoryPolicy.normalize(rawQuery) ?: return
    job?.cancel()
    // Подсказки больше не нужны: запрос отправлен, панель скрывается.
    suggestionsJob?.cancel()
    searchSuggestions = emptyList()
    updateSearchHistoryOverlay()
    val runId = ++searchRunId
    showSearchLoading(q)
    job = lifecycleScope.launch {
        val found = try {
            repository.search(q)
        } catch (error: Exception) {
            // Cancellation (new query, tab switch, clear-search) is not a failure.
            if (SearchRunGuard.isCancellation(error)) throw error
            // HTTP/parse failure: the previous (chart) cards must not stay
            // clickable — drop them plus the tracks list and show the same
            // error/retry state, with a retry for this very query.
            if (SearchRunGuard.mayApplyResult(runId, searchRunId, !isActive)) {
                showStatus(null)
                showListError(
                    message = "Поиск недоступен — проверьте сеть",
                    retryDescription = "Повторить поиск",
                    onRetry = { search(q) },
                )
            }
            return@launch
        }
        if (!SearchRunGuard.mayApplyResult(runId, searchRunId, !isActive)) return@launch
        searchHistory.recordSuccess(q)
        showStatus(null)
        showTracks(found)
    }
}

/** Explicit «searching» placeholder so the list never looks stale/empty mid-search. */
internal fun MusicActivity.showSearchLoading(query: String) {
    showStatus(null)
    resetList()
    results.addView(TextView(this).apply {
        text = "Ищем «$query»…"
        setTextColor(MarsTheme.TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(0, dp(32), 0, dp(32))
    })
}

/**
 * Re-evaluates the search overlay: it shows the stored history while the field is
 * empty and focused (pure [SearchHistoryPanelPolicy]) and live suggestions while
 * the user types (pure [SearchSuggestionsPolicy]) — the two are mutually exclusive
 * by the field text. The overlay floats above the list, so tab content and the
 * player stay untouched underneath.
 */
internal fun MusicActivity.updateSearchHistoryOverlay() {
    if (!isHistoryOverlayReady() || !isSearchHistoryReady()) return
    val fieldText = searchInput.text?.toString()
    val focused = searchInput.hasFocus()
    val historyVisible = SearchHistoryPanelPolicy.isVisible(
        fieldText = fieldText,
        fieldFocused = focused,
        historyEmpty = searchHistory.entries.isEmpty(),
    )
    val suggestionsVisible = SearchSuggestionsPolicy.isPanelVisible(
        fieldText = fieldText,
        fieldFocused = focused,
        suggestionsEmpty = searchSuggestions.isEmpty(),
    )
    when {
        suggestionsVisible -> renderSearchSuggestions()
        historyVisible -> renderSearchHistory()
    }
    historyOverlay.visibility = if (historyVisible || suggestionsVisible) View.VISIBLE else View.GONE
}

/**
 * Живые подсказки поиска: после паузы ввода ([SearchSuggestionsPolicy.DEBOUNCE_MS])
 * запрашивает GET /api/suggestions. Каждый новый символ отменяет предыдущий запуск
 * (дебаунс), устаревший ответ не попадает в панель ([SearchRunGuard]). Сбой сети —
 * это «подсказок нет» (тихо), а не ошибка поиска: подсказки вспомогательный UX.
 */
internal fun MusicActivity.scheduleSuggestions(raw: String?) {
    suggestionsJob?.cancel()
    if (!SearchSuggestionsPolicy.shouldFetch(raw)) {
        searchSuggestions = emptyList()
        return
    }
    val q = SearchSuggestionsPolicy.normalize(raw) ?: return
    val runId = ++suggestionsRunId
    suggestionsJob = lifecycleScope.launch {
        delay(SearchSuggestionsPolicy.DEBOUNCE_MS)
        val fetched = runCatching {
            withContext(Dispatchers.IO) { repository.getSuggestions(q) }
        }
        if (!SearchRunGuard.mayApplyResult(runId, suggestionsRunId, !isActive)) return@launch
        searchSuggestions = SearchSuggestionsPolicy.visibleSuggestions(
            fetched.getOrDefault(emptyList()),
            q,
        )
        updateSearchHistoryOverlay()
    }
}

/** Fills the overlay with live suggestions: one tappable row per suggestion. */
internal fun MusicActivity.renderSearchSuggestions() {
    historyPanel.removeAllViews()
    historyPanel.addView(TextView(this).apply {
        text = getString(R.string.music_suggestions_header)
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), 0, dp(8), dp(4))
    }, LinearLayout.LayoutParams(-1, -2))
    searchSuggestions.forEach { suggestion ->
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(6), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSuggestionClicked(suggestion) }
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@renderSearchSuggestions, R.drawable.ic_ui_search))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
        row.addView(TextView(this).apply {
            text = suggestion
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        historyPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}

/** Тап по подсказке: текст в поле и сразу поиск (как по строке истории). */
internal fun MusicActivity.onSuggestionClicked(suggestion: String) {
    searchInput.setText(suggestion) // watcher обновит кнопку очистки; поиск ниже гасит панель
    hideKeyboard()
    search(suggestion)
}

/** Fills the overlay: header with «Очистить» plus one tappable row per stored query. */
internal fun MusicActivity.renderSearchHistory() {
    historyPanel.removeAllViews()
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), dp(4))
    }
    header.addView(TextView(this).apply {
        text = "История поиска"
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        typeface = Typeface.DEFAULT_BOLD
    }, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(TextView(this).apply {
        text = "Очистить"
        textSize = 13f
        setTextColor(MarsTheme.ACCENT_TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = MarsTheme.button(this@renderSearchHistory, MarsTheme.ACCENT_BG, 10, MarsTheme.ACCENT)
        isClickable = true
        isFocusable = true
        contentDescription = "Очистить историю поиска"
        setOnClickListener {
            searchHistory.clear()
            showStatus("История поиска очищена")
            updateSearchHistoryOverlay()
        }
    }, LinearLayout.LayoutParams(-2, -2))
    historyPanel.addView(header, LinearLayout.LayoutParams(-1, -2))

    searchHistory.entries.forEach { entry ->
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(6), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { onHistoryEntryClicked(entry) }
        }
        row.addView(TextView(this).apply {
            text = entry
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val deleteBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_trash,
            "Удалить из истории: $entry",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 30
        ).apply {
            setOnClickListener {
                searchHistory.delete(entry)
                showStatus("Удалено из истории: $entry")
                updateSearchHistoryOverlay()
            }
        }
        row.addView(deleteBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        historyPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}

/** Tapping a history row repeats that search: text back into the field, overlay hides. */
internal fun MusicActivity.onHistoryEntryClicked(entry: String) {
    searchInput.setText(entry) // the watcher hides the overlay and reveals the clear button
    hideKeyboard()
    search(entry)
}

internal fun MusicActivity.hideKeyboard() {
    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
}
