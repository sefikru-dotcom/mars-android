package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.local.LocalSession
import java.util.Locale

/**
 * Раздел «Локально» — список диалогов прямого провайдера.
 *
 * Экран минималистичен по форме и полон по смыслу: шапка раздела ([SectionScaffold]),
 * строка состояния провайдера, поиск по истории, две компактные кнопки (новый чат,
 * настройки провайдера) и кликабельные строки диалогов. Крупных кнопок и своего тулбара
 * нет — как в остальных разделах левого бара.
 *
 * Диалоги живут только на телефоне ([LocalSessionStore], `filesDir/local-chat/`): сервер
 * Марса для этого раздела не нужен вовсе.
 *
 * Поиск идёт по названию и тексту сообщений — через `LocalSessionStore.search`
 * (регистронезависимо, пустой запрос = вся история). Фильтрация выполняется на IO-потоке
 * и с паузой на набор, поэтому ввод не дёргает файлы на каждый символ.
 *
 * Долгое нажатие на строку открывает лист действий: переименовать или удалить
 * (удаление — с подтверждением [LocalChatConfirmDialog]).
 *
 * [newIntent] — публичный вход раздела: его вызывает [AppNavIntents.sectionIntent]
 * для `AppDestination.LOCAL`.
 */
class LocalSessionsActivity : ComponentActivity() {

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, LocalSessionsActivity::class.java)

        /** Пауза набора: список не пересобирается на каждый символ. */
        private const val SEARCH_DEBOUNCE_MS = 220L

        private const val STATE_QUERY = "local_sessions_query"
    }

    private lateinit var store: LocalSessionStore
    private lateinit var providerStore: LocalProviderStore

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var body: LinearLayout
    private lateinit var providerLine: TextView

    /** Стеклянная плитка провайдера: точка статуса + две строки текста. */
    private lateinit var providerTile: LinearLayout

    /** Хозяин цветной точки: перерисовывается при каждой перезагрузке статуса. */
    private lateinit var providerDotHost: LinearLayout

    /** Заголовок плитки: «Провайдер готов» / «Нужен API-ключ» / «Провайдер не настроен». */
    private lateinit var providerTitle: TextView

    private lateinit var searchInput: EditText

    /** Текущий запрос поиска; пусто — показываем всю историю. */
    private var query: String = ""

    /** Текущая загрузка списка: новый ввод отменяет предыдущую. */
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalSessionStore.forContext(this)
        providerStore = LocalProviderPrefsStore(this)
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        setContentView(buildView())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    override fun onResume() {
        super.onResume()
        // Возврат из диалога/настроек: список и статус провайдера обязаны быть свежими.
        reload()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, "Локально", ::reload)

        scaffold.content.addView(
            actionsRow(),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        scaffold.content.addView(
            providerStatusTile(),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        scaffold.content.addView(
            searchBox(),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        scaffold.content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                addView(body, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        return scaffold.root
    }

    /**
     * Быстрые действия экрана: «Новый чат», «Разговор» и «Провайдер».
     *
     * Ряд прокручивается по горизонтали, а не сжимается: три подписи с иконками на
     * 360dp-экране не влезают, и раньше правый край уезжал за границу. Прокрутка
     * честнее обрезки — видно, что кнопок три.
     *
     * «Разговор» открывает ПОСЛЕДНИЙ диалог сразу в полноэкранном голосовом режиме
     * ([VoiceModeActivity.chatIntentFor] → `LocalChatActivity` + `open_voice_mode`).
     * Раньше здесь открывался сам `VoiceModeActivity` — витрина без движков: орб рисовался,
     * а микрофон не включался, и «голос работает только изнутри чата» было правдой.
     */
    private fun actionsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            MarsDialog.quietButton(this, "Новый чат", R.drawable.ic_add) { openChat(null) },
            LinearLayout.LayoutParams(-2, -2),
        )
        row.addView(
            MarsDialog.quietButton(this, "Разговор", R.drawable.ic_mic) { openVoiceChat() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
        )
        row.addView(
            MarsDialog.quietButton(this, "Провайдер", R.drawable.ic_settings) {
                openProviderSettings()
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
        )
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row, LinearLayout.LayoutParams(-2, -2))
        }
    }

    /**
     * Плитка активного провайдера: стеклянная карточка с цветной точкой статуса.
     *
     * Зелёная точка — провайдер выбран и ключ сохранён, жёлтая — ключа нет (или
     * провайдер не настроен вовсе). Раньше это была серая строка мелким шрифтом:
     * состояние приходилось вычитывать из текста, а «ключ не сохранён» ничем не
     * отличалось от обычной подписи. Тап по всей плитке — настройки провайдера.
     */
    private fun providerStatusTile(): View {
        providerDotHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        providerTitle = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.BOLD,
            )
            setTextColor(MarsTheme.TEXT_STRONG)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        providerLine = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(providerTitle, LinearLayout.LayoutParams(-1, -2))
            addView(providerLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        providerTile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.glassSurface(this@LocalSessionsActivity, radiusDp = 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { openProviderSettings() }
            addView(
                providerDotHost,
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) },
            )
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                ImageView(this@LocalSessionsActivity).apply {
                    setImageDrawable(
                        ContextCompat.getDrawable(this@LocalSessionsActivity, R.drawable.ic_arrow_forward),
                    )
                    imageTintList =
                        android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) },
            )
        }
        return providerTile
    }

    /**
     * Поле поиска по истории. Фильтрация отложена на [SEARCH_DEBOUNCE_MS]: каталог
     * читается с диска, и пересборка на каждый символ дёргала бы файлы зря.
     */
    private fun searchBox(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@LocalSessionsActivity, MarsTheme.SURFACE, radiusDp = 12)
            setPadding(dp(10), 0, dp(10), 0)
        }
        box.addView(
            ImageView(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@LocalSessionsActivity, R.drawable.ic_search))
                imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            },
            LinearLayout.LayoutParams(dp(18), dp(18)),
        )
        searchInput = EditText(this).apply {
            hint = "Поиск по истории…"
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT_STRONG)
            textSize = 14f
            background = null
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    scheduleReload()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, _, _ ->
                reload()
                true
            }
        }
        box.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        return box
    }

    // --- загрузка ------------------------------------------------------------------------

    /** Немедленная перезагрузка списка (кнопка «Обновить», возврат на экран, сохранение). */
    private fun reload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch { loadNow() }
    }

    /** Перезагрузка после паузы набора в поиске. */
    private fun scheduleReload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadNow()
        }
    }

    private suspend fun loadNow() {
        scaffold.progress.visibility = View.VISIBLE
        renderProviderLine()
        val activeQuery = query.trim()
        // Каталог читается с диска: 100 файлов на main-потоке — это подвисание
        // прокрутки при возврате с экрана диалога.
        val rows = withContext(Dispatchers.IO) {
            runCatching { loadRows(activeQuery) }.getOrDefault(emptyList())
        }
        if (isFinishing || isDestroyed) return
        scaffold.progress.visibility = View.GONE
        renderRows(rows, activeQuery)
    }

    private fun loadRows(activeQuery: String): List<SessionRow> {
        // Мета читается один раз на весь список ([LocalSessionStore.metas]): декодировать
        // сообщения ради строки «модель · расход» незачем.
        val metas = runCatching { store.metas() }.getOrDefault(emptyList())
        val byId = metas.associateBy { it.session.id }
        val sessions = if (activeQuery.isBlank()) {
            metas.map { it.session }
        } else {
            runCatching { store.search(activeQuery) }.getOrDefault(emptyList())
        }
        return sessions.map { session -> SessionRow(session, detailsOf(session, byId[session.id])) }
    }

    // --- рендер --------------------------------------------------------------------------

    private fun renderRows(rows: List<SessionRow>, activeQuery: String) {
        body.removeAllViews()
        if (rows.isEmpty()) {
            body.addView(
                if (activeQuery.isBlank()) {
                    emptyState(
                        "Диалогов пока нет. Новый чат живёт только на телефоне: провайдер " +
                            "отвечает напрямую, сервер Марса не нужен.",
                        withActions = true,
                    )
                } else {
                    emptyState(
                        "По запросу «$activeQuery» ничего не нашлось. Поиск идёт по названию " +
                            "диалога и тексту сообщений.",
                        withActions = false,
                    )
                }
            )
            return
        }
        rows.forEach { row ->
            body.addView(
                sessionCard(row),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
        }
    }

    /**
     * Пустое состояние: подсказка, что здесь живёт, и два выхода — новый чат и
     * настройка провайдера. Без кнопок оно превращается в тупик на первом запуске.
     */
    private fun emptyState(message: String, withActions: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, dp(8))
        }
        box.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
        })
        if (withActions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(
                MarsDialog.quietButton(this, "Новый чат", R.drawable.ic_add) { openChat(null) },
                LinearLayout.LayoutParams(-2, -2),
            )
            row.addView(
                MarsDialog.quietButton(this, "Провайдер", R.drawable.ic_settings) { openProviderSettings() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
            )
            box.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        return box
    }

    /**
     * Статус провайдера на плитке: цветная точка + два текста.
     *
     * Точка — единственный индикатор «готов / нужен ключ»: зелёная, когда провайдер
     * выбран и ключ сохранён, жёлтая во всех остальных случаях. Текст под заголовком
     * называет причину, чтобы жёлтая точка не была загадкой.
     */
    private fun renderProviderLine() {
        // Плитка собирается в buildView; если статус запросили раньше — молчим, а не падаем.
        if (!::providerDotHost.isInitialized) return
        val state = runCatching { providerStore.state() }.getOrNull()
        val active = state?.active
        val hasKey = active != null && !providerStore.apiKey(active.id).isNullOrBlank()
        val ready = active != null && hasKey
        providerDotHost.removeAllViews()
        providerDotHost.addView(
            MarsTheme.glowingStatusDot(
                this,
                if (ready) MarsTheme.STATUS_ONLINE else MarsTheme.STATUS_WARNING,
            ),
            LinearLayout.LayoutParams(dp(10), dp(10)),
        )
        providerTitle.text = when {
            active == null -> "Провайдер не настроен"
            !hasKey -> "Нужен API-ключ"
            else -> "Провайдер готов"
        }
        providerTitle.setTextColor(if (ready) MarsTheme.TEXT_STRONG else MarsTheme.STATUS_WARNING)
        providerLine.text = when {
            active == null -> "Нажмите, чтобы выбрать провайдера и вставить ключ"
            !hasKey -> "${active.title} · ключ не сохранён — нажмите, чтобы добавить"
            else -> "${active.title} · ${active.model}"
        }
        providerLine.setTextColor(MarsTheme.TEXT_MUTED)
        providerTile.contentDescription =
            "${providerTitle.text}. ${providerLine.text}. Открыть настройки провайдера"
    }

    /**
     * Строка диалога: название, дата, модель и расход токенов (если провайдер их
     * сообщил). Тап открывает, долгое нажатие — лист действий (переименовать/удалить).
     * Фон и ripple — фабрики палитры, поэтому состояние нажатия видно на тёмной теме.
     */
    private fun sessionCard(row: SessionRow): View {
        val session = row.session
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = MarsTheme.button(this@LocalSessionsActivity, MarsTheme.SURFACE_CARD, 12, MarsTheme.BORDER)
            isClickable = true
            isFocusable = true
            contentDescription = "${session.title}. ${row.details}. " +
                "Долгое нажатие — переименовать или удалить диалог"
            setOnClickListener { openChat(session.id) }
            setOnLongClickListener {
                showActions(session)
                true
            }
        }

        card.addView(TextView(this).apply {
            text = session.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MarsTheme.TEXT_STRONG)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })

        if (row.details.isNotEmpty()) {
            card.addView(
                TextView(this).apply {
                    text = row.details
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
        return card
    }

    /** Лист действий над диалогом: переименовать / удалить. */
    private fun showActions(session: LocalSession) {
        var dialog: android.app.Dialog? = null
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, session.title, null))
            root.addView(
                MarsDialog.pillStack(
                    this,
                    listOf(
                        "Переименовать" to {
                            dialog?.dismiss()
                            renameDialog(session)
                        },
                        "Удалить" to {
                            dialog?.dismiss()
                            confirmDelete(session)
                        },
                    ),
                )
            )
            root.addView(
                MarsDialog.quietButton(this, "Отмена") { dialog?.dismiss() },
                LinearLayout.LayoutParams(-2, -2),
            )
            emptyList()
        }
    }

    /**
     * Переименование. Заголовок хранится в мете диалога, поэтому переживает
     * перезапуск приложения (проверяется критерием приёмки §5.6).
     */
    private fun renameDialog(session: LocalSession) {
        var dialog: android.app.Dialog? = null
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Переименовать", null))
            root.addView(MarsDialog.paragraph(this, "Название видно в списке диалогов и в шапке чата."))
            val field = MarsDialog.textField(this, "Название диалога", session.title).apply {
                minLines = 1
                maxLines = 1
                isSingleLine = true
            }
            root.addView(field, LinearLayout.LayoutParams(-1, -2))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }
            row.addView(
                MarsDialog.secondaryButton(this, "Отмена") { dialog?.dismiss() },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            row.addView(
                MarsDialog.primaryButton(this, "Сохранить") {
                    val title = field.text.toString().trim()
                    if (title.isEmpty()) {
                        field.error = "Название не может быть пустым"
                    } else {
                        dialog?.dismiss()
                        renameSession(session, title)
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) },
            )
            root.addView(row)
            listOf(field)
        }
    }

    private fun renameSession(session: LocalSession, title: String) {
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                // rename(sessionId, title) — вариант W3 для списка, у которого на руках
                // только id. `null` — диалога уже нет (файл удалён извне).
                val result = runCatching { store.rename(session.id, title) }
                result.exceptionOrNull()
                    ?: if (result.getOrNull() == null) IllegalStateException("диалог не найден") else null
            }
            if (failure != null) {
                note("Не переименовалось: ${failure.message ?: "ошибка"}", error = true)
            } else {
                note("Переименовано: $title")
            }
            reload()
        }
    }

    private fun confirmDelete(session: LocalSession) {
        lifecycleScope.launch {
            val allowed = LocalChatConfirmDialog.ask(
                activity = this@LocalSessionsActivity,
                title = "Удалить диалог?",
                body = "«${session.title}» будет удалён с телефона. Отменить это нельзя.",
                confirmLabel = "Удалить",
                denyLabel = "Отмена",
                code = null,
                codeLabel = null,
            )
            if (allowed) {
                runCatching { store.delete(session.id) }
                note("Диалог удалён")
                reload()
            }
        }
    }

    // --- мета строки ---------------------------------------------------------------------

    /**
     * Подпись строки: дата · модель · расход.
     *
     * Данные берутся из меты хранилища ([LocalSessionStore.metas] → [StoredSession]):
     * `model` и `totalTokens` пишет W3 при сохранении диалога. Старый файл волны 1 этих
     * полей не имеет — тогда в подписи остаётся только дата, а не нули и не «неизвестно».
     */
    private fun detailsOf(session: LocalSession, stored: StoredSession?): String {
        val parts = ArrayList<String>(3)
        timeLabel(session.updatedAt).takeIf { it.isNotEmpty() }?.let(parts::add)
        val model = stored?.model
        if (!model.isNullOrBlank()) parts.add(model)
        val tokens = stored?.totalTokens ?: 0L
        if (tokens > 0L) parts.add(compactTokens(tokens) + " токенов")
        return parts.joinToString(" · ")
    }

    /** 12 345 → «12.3k»; 1 200 000 → «1.2M». */
    private fun compactTokens(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    // --- навигация и мелочи ---------------------------------------------------------------

    private fun note(text: String, error: Boolean = false) {
        scaffold.statusLine.text = text
        scaffold.statusLine.setTextColor(if (error) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
        scaffold.statusLine.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun openChat(sessionId: String?) {
        startActivity(LocalChatActivity.newIntent(this, sessionId))
    }

    /**
     * «Разговор»: чат последнего диалога (или новый) сразу в полноэкранном голосовом режиме.
     *
     * Почему последний диалог, а не пустой экран: человек, стоящий в списке истории, хочет
     * продолжить разговор, а не начинать с нуля. Пустая история — новый диалог, и это тот же
     * путь: флаг `open_voice_mode` открывает орб и включает микрофон в обоих случаях.
     *
     * Каталог читается на IO: `sessions()` разбирает метаданные файлов, и делать это на главном
     * потоке ради одного id — подвисание на пустом месте. Экран мог закрыться, пока читали
     * диск, поэтому после `withContext` проверяется `isFinishing`.
     */
    private fun openVoiceChat() {
        lifecycleScope.launch {
            val sessionId = withContext(Dispatchers.IO) {
                runCatching { store.sessions().firstOrNull()?.id }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            startActivity(VoiceModeActivity.chatIntentFor(this@LocalSessionsActivity, sessionId))
        }
    }

    private fun openProviderSettings() {
        startActivity(LocalProviderSettingsActivity.newIntent(this))
    }

    private fun timeLabel(value: Long): String {
        if (value <= 0L) return ""
        return DateUtils.formatDateTime(
            this,
            value,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)
}

/**
 * Строка списка: диалог и его подпись (дата · модель · расход), собранная на IO.
 * Локальный тип экрана — снаружи не нужен.
 */
private data class SessionRow(val session: LocalSession, val details: String)
