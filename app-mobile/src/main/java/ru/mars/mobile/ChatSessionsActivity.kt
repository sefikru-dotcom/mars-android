package ru.mars.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.SessionRepository

/**
 * Уровень списка диалогов — корень раздела [AppDestination.CHAT] в живом
 * двухуровневом навигаторе (волна 2). Левый бар здесь (как и в [ChatActivity])
 * содержит ТОЛЬКО приложенческие разделы ([AppSectionsBar]); список диалогов —
 * отдельный экран, а не часть смешанной шторки.
 *
 * Навигационная модель подключена к реальным переходам:
 * - клик по диалогу проходит через [AppNavPolicy.openSession] и открывает
 *   [ChatActivity] с extra [ChatActivity.EXTRA_OPEN_SESSION_ID]
 *   ([AppNavIntents.openSessionIntent]); диалог стартует НАД этим экраном,
 *   поэтому back из чата (CLEAR_TOP|SINGLE_TOP) возвращается сюда — стек
 *   остаётся двухуровневым;
 * - клик «Чат» в баре идемпотентен на корне раздела ([AppNavPolicy.selectSection]);
 * - back решает чистая [NavBackPolicy]: диалоги → разделы (шторка) → системный
 *   выход ([AppNavPolicy.back] на корне — null, выход — решение UI-слоя);
 * - экран обслуживает deep-link `mars://…` ([AppDeepLink]): план переходов
 *   строит [AppNavPolicy.transitionsTo] (≤ [AppNavPolicy.MAX_TRANSITIONS]),
 *   применяет [AppNavIntents.deepLinkIntents].
 *
 * Продуктовые решения сохранены: дочерние сессии раскрываются ВНУТРИ карточки
 * родителя ([SessionTreePolicy]) и не образуют уровень; logout — только
 * «Настройки» ([AppNavPolicy.LOGOUT_HOST]); поиск/переименование/удаление
 * диалогов — семантика прежней шторки ChatActivity.
 */
class ChatSessionsActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var drawer: DrawerLayout
    private lateinit var backCallback: OnBackPressedCallback
    private lateinit var sessionList: LinearLayout
    private lateinit var filterInput: EditText

    /** Плоский список диалогов (сеть, при сбое — снимок офлайн-кэша). */
    private var sessions: List<SessionItem> = emptyList()
    private var filterQuery: String = ""

    /** Id раскрытых родительских карточек: дочерние сессии живут внутри родителя. */
    private val expandedParents: MutableSet<String> = mutableSetOf()

    // Приватные хранилища общие с ChatActivity (ключи — ChatActivity companion):
    // кэш списка переживает обрыв связи, а удаление диалога стирает его черновик
    // и буферизованные сообщения — поведение прежней шторки без регресса.
    private val offlineCache by lazy {
        SessionOfflineCache(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(ChatActivity.OFFLINE_CACHE_PREFS, MODE_PRIVATE)
            )
        )
    }
    private val drafts by lazy {
        SessionDraftController(
            PrefsSessionDraftStore(getSharedPreferences(ChatActivity.DRAFTS_PREFS, MODE_PRIVATE))
        )
    }
    private val pendingQueue by lazy {
        OfflineMessageQueuePolicy(
            PrefsPendingMessageStore(getSharedPreferences(ChatActivity.PENDING_QUEUE_PREFS, MODE_PRIVATE))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (store.read() == null) {
            // Без креденшелов уровню диалогов нечего показывать — на авторизацию
            // (паритет с ChatActivity.onResume).
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // Список освежается при каждом возврате на корень раздела CHAT.
        loadSessions()
    }

    // --- Навигация (живая модель) ---------------------------------------------------------------

    /**
     * Клик по разделу левого бара: [AppNavPolicy.selectSection] из корня CHAT.
     * Повторный выбор «Чата» на корневом уровне идемпотентен — остаёмся на месте;
     * остальные разделы — свои Activity через [AppNavIntents.sectionIntent]
     * (единственная таблица «раздел → Activity»).
     */
    private fun onSectionSelected(destination: AppDestination) {
        drawer.closeDrawer(GravityCompat.START)
        val current = AppNavState.INITIAL // этот экран — корень раздела CHAT
        val next = AppNavPolicy.selectSection(current, destination)
        if (next == current) return
        startActivity(AppNavIntents.sectionIntent(this, destination))
    }

    /** Клик по leaf-диалогу: [AppNavPolicy.openSession] → уровень диалога ([ChatActivity]). */
    private fun openSession(sessionId: String) {
        val next = AppNavPolicy.openSession(AppNavState.INITIAL, sessionId)
        check(next.chatLevel == ChatLevel.Session(sessionId)) {
            "openSession из корня CHAT обязан дать уровень диалога"
        }
        // БЕЗ finish(): этот экран остаётся уровнем под диалогом — back из чата
        // (CLEAR_TOP|SINGLE_TOP) вернётся в этот же экземпляр.
        startActivity(AppNavIntents.openSessionIntent(this, sessionId))
    }

    /** «Новый диалог»: уровень диалога, ещё не созданный на сервере. */
    private fun startNewChat() {
        startActivity(AppNavIntents.chatIntent(this))
    }

    /** Back: [NavBackPolicy] — диалоги → разделы (шторка) → системный выход. */
    private fun handleBack() {
        when (
            NavBackPolicy.resolve(
                level = NavBackPolicy.Level.SESSION_LIST,
                searchOpen = false,
                drawerOpen = drawer.isDrawerOpen(GravityCompat.START),
            )
        ) {
            NavBackPolicy.Action.OPEN_DRAWER -> drawer.openDrawer(GravityCompat.START)
            NavBackPolicy.Action.SYSTEM_EXIT -> {
                backCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
            else -> Unit
        }
    }

    /**
     * Deep-link вход навигационной модели: [AppDeepLink.parse] → целевое
     * состояние → [AppNavPolicy.transitionsTo] от [AppNavState.INITIAL]
     * (≤ 2 переходов) → реальные Intent ([AppNavIntents]). Нераспознанная
     * ссылка игнорируется — экран остаётся на списке диалогов.
     */
    private fun handleDeepLink(incoming: Intent?) {
        val raw = incoming?.data?.toString() ?: return
        val link = AppDeepLink.parse(raw) ?: return
        AppNavIntents.deepLinkIntents(this, link).forEach { startActivity(it) }
    }

    // --- Данные и список --------------------------------------------------------------------------

    private fun loadSessions() {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val fresh = SessionRepository().getSessions(ChatActivity.BASE_URL, c)
            // Пустой ответ (сбой сети или диалогов действительно нет) сверяется с
            // офлайн-кэшем: на сбое уровень показывает последний сохранённый снимок.
            sessions = withContext(Dispatchers.IO) { offlineCache.reconcile(fresh) }
            renderSessionList()
        }
    }

    /**
     * Список диалогов: дерево [SessionTreePolicy] (поиск → группировка детей
     * внутри родителей → раскрытые строки), семантика карточек — паритет с
     * прежней смешанной шторкой ChatActivity.
     */
    private fun renderSessionList() {
        if (!::sessionList.isInitialized) return
        sessionList.removeAllViews()
        val tree = SessionTreePolicy.tree(
            sessions = sessions,
            query = filterQuery,
            expandedParents = expandedParents,
        )
        val filtered = SessionTreePolicy.visibleRows(tree)
        if (filtered.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = if (filterQuery.isNotBlank()) {
                    "Ничего не найдено"
                } else {
                    "Диалогов пока нет — начните новый"
                }
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(0, dp(14), 0, dp(14))
                gravity = Gravity.CENTER
            })
            return
        }
        filtered.forEach { node ->
            val session = node.session
            val isCurrent = node.isCurrent
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isCurrent) {
                    MarsTheme.card(this@ChatSessionsActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.ACCENT)
                } else {
                    MarsTheme.card(this@ChatSessionsActivity, MarsTheme.SURFACE, radiusDp = 12, strokeColor = MarsTheme.BORDER_SUBTLE)
                }
                setPadding(dp(12 + node.depth * 16), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
            }
            // Карточка и название всегда открывают сам диалог. Раскрытие воркеров
            // выполняется отдельной кнопкой, чтобы родительский чат нельзя было
            // случайно сделать недоступным.
            val openRow: () -> Unit = { openSession(session.id) }
            val toggleChildren: () -> Unit = {
                if (node.hasChildren) {
                    if (!expandedParents.remove(session.id)) expandedParents.add(session.id)
                    renderSessionList()
                }
            }
            card.setOnClickListener { openRow() }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (node.hasChildren) {
                row.addView(MarsTheme.circleIconButton(
                    this,
                    if (expandedParents.contains(session.id)) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    if (expandedParents.contains(session.id)) "Свернуть воркеры" else "Развернуть воркеры",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 32,
                    strokeColor = 0,
                ).apply {
                    setOnClickListener { toggleChildren() }
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(4) })
            }
            row.addView(android.widget.Button(this).apply {
                text = session.title ?: "Новый диалог"
                textSize = 14f
                setTextColor(MarsTheme.TEXT_STRONG)
                background = null
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { openRow() }
            }, LinearLayout.LayoutParams(0, -2, 1f))

            // Rename/delete only for manageable rows; the server `read_only`
            // flag is the single authority (pure policy in SessionRowActions).
            if (SessionRowActions.canManage(session)) {
                val renameBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_edit,
                    "Переименовать",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 32,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { renameSession(session) }
                }
                row.addView(renameBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

                val deleteBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_trash,
                    "Удалить",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.STATUS_ERROR,
                    sizeDp = 32,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { deleteSession(session) }
                }
                row.addView(deleteBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            card.addView(row)

            // Мета активности: когда диалог обновлялся и сколько в нём сообщений
            // («5 мин назад · 12 сообщений») — чистая SessionMetaPolicy + MessageTimePolicy.
            val metaLine = SessionMetaPolicy.metaLine(
                updatedAtMillis = MessageTimePolicy.parseServerTimestamp(session.updatedAt ?: session.createdAt),
                messageCount = session.messageCount,
                nowMillis = System.currentTimeMillis(),
            )
            if (metaLine != null) {
                card.addView(TextView(this).apply {
                    text = metaLine
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), dp(2))
                })
            }

            if (!session.preview.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = session.preview
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), dp(2))
                })
            }

            // Subagent marker for revealed delegated children (read-only on the
            // server; hidden under the collapsed parent by default).
            if (session.isSubagent) {
                card.addView(TextView(this).apply {
                    text = buildString {
                        append(session.sourceLabel?.takeIf { it.isNotBlank() } ?: "субагент")
                        if (session.readOnly) append(" · только чтение")
                    }
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), 0)
                })
            }

            // Active-turn/attention badge: which sessions are live or waiting for the user.
            val turn = session.activeTurn
            val turnAttention = turn.attention
            // Clean textual badges — status is carried by the themed color, not symbol glyphs.
            val badge: Pair<String, Int>? = when {
                turnAttention != null -> attentionLabel(turnAttention) to MarsTheme.STATUS_WARNING
                turn.isStreaming -> "Активный ход" to MarsTheme.STATUS_ONLINE
                turn.hasPendingUserMessage -> "Сообщение в очереди" to MarsTheme.STATUS_INFO
                else -> null
            }
            if (badge != null) {
                card.addView(TextView(this).apply {
                    text = badge.first
                    textSize = 11f
                    setTextColor(badge.second)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(40), 0)
                })
            }
            sessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun deleteSession(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            if (SessionRepository().deleteSession(ChatActivity.BASE_URL, c, session.id)) {
                // Удалённого диалога больше нет: его черновик и буферизованные
                // сообщения стираются вместе с ним (паритет с прежней шторкой).
                drafts.onSessionRemoved(session.id)
                pendingQueue.removeMessagesFor(session.id)
                sessions = sessions.filterNot { it.id == session.id }
                withContext(Dispatchers.IO) {
                    offlineCache.removeMessages(session.id)
                    offlineCache.saveSessions(sessions)
                }
                renderSessionList()
            }
        }
    }

    private fun renameSession(session: SessionItem) {
        val c = store.read() ?: return
        val nameInput = EditText(this).apply {
            setText(session.title.orEmpty())
            setSingleLine(true)
            setTextColor(Color.WHITE)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Переименовать диалог")
            .setView(nameInput)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = nameInput.text.toString().trim()
                if (title.isNotBlank()) {
                    lifecycleScope.launch {
                        if (SessionRepository().renameSession(ChatActivity.BASE_URL, c, session.id, title)) {
                            loadSessions()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Human banner for the session's server-side attention (WebUI parity). */
    private fun attentionLabel(attention: SessionAttention): String {
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

    // --- View -------------------------------------------------------------------------------------

    private fun buildView(): View {
        drawer = DrawerLayout(this).apply { setBackgroundColor(MarsTheme.BG) }
        drawer.addView(buildListContent(), DrawerLayout.LayoutParams(-1, -1))
        drawer.addView(buildSectionsDrawer(), DrawerLayout.LayoutParams(dp(310), -1, GravityCompat.START))
        return drawer
    }

    /** Контент уровня: хедер (бар/заголовок/новый диалог), поиск, список. */
    private fun buildListContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), systemBars.top + dp(6), dp(12), systemBars.bottom + dp(6))
            insets
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val menuBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_menu,
            "Разделы",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    drawer.openDrawer(GravityCompat.START)
                }
            }
        }
        header.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this).apply {
            text = "Диалоги"
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val newChatBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_edit,
            "Новый диалог",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { startNewChat() }
        }
        header.addView(newChatBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        // Поиск по диалогам (title/preview — семантика фильтра прежней шторки).
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@ChatSessionsActivity, MarsTheme.SURFACE, radiusDp = 12)
            setPadding(dp(10), 0, dp(10), 0)
        }
        searchBox.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@ChatSessionsActivity, R.drawable.ic_search))
            imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        filterInput = EditText(this).apply {
            hint = "Поиск диалогов…"
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 14f
            background = null
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterQuery = s?.toString().orEmpty()
                    renderSessionList()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchBox.addView(filterInput, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply {
            addView(sessionList, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderSessionList()
        return root
    }

    /** Шторка левого бара: ТОЛЬКО приложенческие разделы (AppSectionsBar). */
    private fun buildSectionsDrawer(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(MarsTheme.SIDEBAR)
            isClickable = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(8), dp(16), systemBars.bottom + dp(8))
            insets
        }
        val headerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        headerRow.addView(TextView(this).apply {
            text = "Разделы"
            textSize = 20f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_close,
            "Закрыть панель",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 36
        ).apply {
            setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        panel.addView(headerRow)
        panel.addView(
            AppSectionsBar(
                context = this,
                currentSection = { AppDestination.CHAT },
                onSectionClick = ::onSectionSelected,
            ).build(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )
        return panel
    }

    private fun dp(value: Int) = MarsTheme.dp(this, value)
}
