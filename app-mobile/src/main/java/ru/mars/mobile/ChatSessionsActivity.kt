package ru.mars.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.mobile.offline.OfflineStores
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.export.SessionMarkdownExporter
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.SessionExportOutcome
import ru.mars.network.repository.SessionImportOutcome
import ru.mars.network.repository.SessionMutationOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionShareOutcome
import java.io.File

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

    /**
     * Индикатор офлайна списка: видимый, но не блокирующий — список из кэша читается
     * и открывается как обычно (требование «usable без сети»). Текст и тон считает
     * [NetworkBannerPolicy] + [OfflineIndicatorPolicy], хост только мапит цвет.
     */
    private lateinit var offlineBanner: TextView

    /** Состояние сети: тот же детектор, что в чате (снимок при старте + подписка). */
    private val networkPolicy = NetworkStatePolicy()
    private val bannerPolicy = NetworkBannerPolicy()
    private lateinit var networkObserver: NetworkConnectivityObserver

    /**
     * Момент сохранения показанного снимка кэша (null — список пришёл с сервера).
     * Нужен индикатору, чтобы писать «Нет связи · Кэш (5 мин назад)», а не просто «нет связи».
     */
    private var offlineSnapshotAt: Long? = null

    /** Плоский список диалогов (сеть, при сбое — снимок офлайн-кэша). */
    private var sessions: List<SessionItem> = emptyList()
    private var filterQuery: String = ""

    /**
     * Серверные совпадения (`GET /api/sessions/search`) — ДОПОЛНЕНИЕ к локальному
     * фильтру, а не его замена: локальный даёт мгновенный отзыв по загруженному
     * списку, серверный находит диалоги вне него. Живут отдельной секцией под
     * локальным деревом ([SessionSearchPolicy]).
     */
    private var remoteMatches: List<SessionItem> = emptyList()

    /** Джоб отложенного серверного поиска: новый ввод отменяет предыдущий. */
    private var searchJob: Job? = null

    /**
     * Джоб отложенного рендера списка: ввод в фильтре не должен ждать полной сборки
     * карточек на каждый символ, а отменённое обновление не должно попадать на экран.
     */
    private var renderJob: Job? = null

    /** Джоб отложенного (Later-ветка throttle) обновления: новый триггер отменяет предыдущий. */
    private var refreshJob: Job? = null

    /**
     * Отпечаток отрисованного состояния: если входное состояние не изменилось, список
     * не снимается и не строится заново (см. [SessionListRenderPolicy]).
     */
    private val renderPolicy = SessionListRenderPolicy()

    /**
     * Схлопывание триггеров обновления списка: восстановление сети и подтверждённые
     * мутации могут приходить серией, а каждый триггер — это `GET /api/sessions`.
     * Правила и догоняющий refresh — [SessionListRefreshThrottle]; цикл — в чистом
     * [SessionListRefreshScheduler], поэтому шторм проверяется JVM-тестом.
     */
    private val refreshScheduler by lazy {
        SessionListRefreshScheduler(
            nowMs = { SystemClock.elapsedRealtime() },
            startLoad = { onFinished -> loadSessions(onFinished) },
            scheduleLater = { delayMs, run ->
                refreshJob?.cancel()
                refreshJob = lifecycleScope.launch {
                    delay(delayMs)
                    run()
                }
            },
        )
    }

    /** Id раскрытых родительских карточек: дочерние сессии живут внутри родителя. */
    private val expandedParents: MutableSet<String> = mutableSetOf()

    /**
     * Системный пикер JSON-файла для импорта диалога (O5).
     *
     * `OpenDocument` (а не `GetContent`) даёт стабильный `content://` uri, который
     * живёт до конца задачи; текст читается через `contentResolver` — файл не
     * копируется в кэш приложения. MIME-список широкий намеренно: экспорт может
     * прийти как `application/json`, `text/plain` или вовсе без типа (файл из
     * мессенджера), а форму документа всё равно проверяет
     * [ru.mars.network.export.SessionImportDocument].
     */
    private val importDocument = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSessionFrom(uri)
    }

    // Офлайн-хранилища общие с ChatActivity ([OfflineStores]): один SQLite-носитель на
    // процесс. Кэш списка переживает обрыв связи, а удаление диалога стирает его копию
    // и буферизованные сообщения — поведение прежней шторки без регресса.
    private val offlineCache by lazy { OfflineStores.sessionCache(this) }
    private val drafts by lazy {
        SessionDraftController(
            PrefsSessionDraftStore(getSharedPreferences(ChatActivity.DRAFTS_PREFS, MODE_PRIVATE))
        )
    }
    private val pendingQueue by lazy {
        OfflineMessageQueuePolicy(OfflineStores.pendingMessageStore(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
        // Детектор сети стартует ДО первого запроса списка: снимок при старте даёт
        // корректный индикатор с первого кадра, а не после первого системного колбэка.
        networkObserver = NetworkConnectivityObserver(this, networkPolicy) { updateOfflineBanner() }
        networkObserver.start()
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
        // Список освежается при каждом возврате на корень раздела CHAT. Через
        // throttle: возврат с чата сразу после мутации не должен давать второй
        // запрос поверх только что завершённого — он схлопывается/откладывается.
        refreshScheduler.trigger()
    }

    override fun onDestroy() {
        // Отложенные запуски не должны пережить экран: иначе рендер/запрос прилетит
        // в уже снятое view-дерево (lifecycleScope отменит корутину, но джобы,
        // ждущие delay, снимаем явно — так снимается и ожидание, и его результат).
        renderJob?.cancel()
        searchJob?.cancel()
        refreshJob?.cancel()
        // Колбэк сети снимаем: Activity-подписчик не должен держаться ConnectivityManager'ом.
        if (::networkObserver.isInitialized) runCatching { networkObserver.stop() }
        super.onDestroy()
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

    /**
     * Загрузка списка с сервера (при сбое — снимок офлайн-кэша) и рендер.
     *
     * [onFinished] вызывается на ЛЮБОМ исходе — это часть контракта
     * [SessionListRefreshScheduler]: без него throttle навсегда остался бы «в полёте»
     * и список перестал бы обновляться.
     */
    private fun loadSessions(onFinished: () -> Unit = {}) {
        val c = store.read()
        if (c == null) {
            onFinished()
            return
        }
        lifecycleScope.launch {
            try {
                val fresh = SessionRepository().getSessions(ChatActivity.BASE_URL, c)
                // Пустой ответ (сбой сети или диалогов действительно нет) сверяется с
                // офлайн-кэшем: на сбое уровень показывает последний сохранённый снимок.
                sessions = withContext(Dispatchers.IO) { offlineCache.reconcile(fresh) }
                // Пустой сетевой ответ = показан снимок: запоминаем его возраст для
                // индикатора. Свежий список возраст сбрасывает — копия больше не нужна.
                offlineSnapshotAt = if (fresh.isEmpty() && sessions.isNotEmpty()) {
                    withContext(Dispatchers.IO) { offlineCache.loadSessions()?.savedAtEpochMillis }
                } else {
                    null
                }
                // Обновлённый список может уже содержать серверные совпадения —
                // секция серверного поиска пересчитывается от нового списка.
                if (remoteMatches.isNotEmpty()) remoteMatches = emptyList()
                renderSessionList()
                updateOfflineBanner()
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Индикатор офлайна: решение (видимость/текст/тон) — чистые [NetworkBannerPolicy] и
     * [OfflineIndicatorPolicy], хост мапит тон на цвета [MarsTheme] и всё.
     *
     * Возраст копии дописывается только в офлайне и только если он известен: «Нет связи»
     * без даты читается как «данные свежие», а это неправда уже через час без сети.
     */
    private fun updateOfflineBanner() {
        if (!::offlineBanner.isInitialized) return
        val pendingCount = runCatching { pendingQueue.size }.getOrDefault(0)
        val banner = bannerPolicy.bannerFor(networkPolicy.state, pendingCount)
        offlineBanner.visibility = if (banner.visible) View.VISIBLE else View.GONE
        if (!banner.visible) return
        val text = if (networkPolicy.state == NetworkState.OFFLINE) {
            OfflineIndicatorPolicy.listHint(banner.text, offlineSnapshotAt, System.currentTimeMillis())
        } else {
            banner.text
        }
        val tone = when (banner.tone) {
            NetworkBannerUi.Tone.OK -> MarsTheme.STATUS_ONLINE
            NetworkBannerUi.Tone.WARNING -> MarsTheme.STATUS_WARNING
            NetworkBannerUi.Tone.ERROR -> MarsTheme.STATUS_ERROR
            NetworkBannerUi.Tone.INFO -> MarsTheme.STATUS_INFO
        }
        offlineBanner.text = text
        offlineBanner.setTextColor(tone)
        offlineBanner.background = MarsTheme.card(this, MarsTheme.SURFACE_CARD, radiusDp = 10, strokeColor = tone)
    }

    /**
     * Отложенный рендер списка (ввод в фильтре).
     *
     * Полная сборка карточек на каждый символ — самая дорогая часть экрана (снятие
     * всех view + новая карточка на строку), поэтому она снимается с пути ввода:
     * символы только пишут [filterQuery], а рендер идёт после паузы
     * [RENDER_DEBOUNCE_MS]. Новый ввод отменяет предыдущий джоб — отменённое
     * обновление на экран не попадает (как у серверного поиска).
     */
    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(RENDER_DEBOUNCE_MS)
            renderSessionList()
        }
    }

    // --- Глобальный поиск (A2) --------------------------------------------------------------------

    /**
     * Отложенный серверный поиск по вводу в строке фильтра.
     *
     * Локальный фильтр ([SessionTreePolicy]) применяется в том же `onTextChanged`,
     * но рендер списка отложен ([scheduleRender]) — ввод не ждёт сборки карточек.
     * Серверный запрос уходит с задержкой [SEARCH_DEBOUNCE_MS], чтобы не дёргать
     * `GET /api/sessions/search` на каждую букву; новый ввод отменяет предыдущий
     * Джоб, поэтому ответ устаревшего запроса на экран не попадает.
     */
    private fun scheduleServerSearch(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (!SessionSearchPolicy.shouldQueryServer(q)) {
            // Запрос короче порога: серверная секция гаснет, локальный фильтр живёт.
            if (remoteMatches.isNotEmpty()) {
                remoteMatches = emptyList()
                scheduleRender()
            }
            return
        }
        val c = store.read() ?: return
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val found = SessionRepository().searchSessionsGlobal(ChatActivity.BASE_URL, c, q)
            // Пока запрос летел, пользователь мог набрать другое — тогда ответ
            // относится к прошлому запросу и на экран не попадает.
            if (query.trim() != q) return@launch
            remoteMatches = found
            renderSessionList()
        }
    }

    /**
     * Список диалогов: дерево [SessionTreePolicy] (поиск → группировка детей
     * внутри родителей → раскрытые строки), семантика карточек — паритет с
     * прежней смешанной шторкой ChatActivity.
     *
     * Рендер идемпотентен по входному состоянию: если фильтр, раскрытие, строки и
     * серверные совпадения те же, что и у уже построенного списка, метод выходит
     * сразу ([SessionListRenderPolicy]) — раньше здесь на каждый вызов снимались
     * ВСЕ карточки и собирались заново.
     */
    private fun renderSessionList() {
        if (!::sessionList.isInitialized) return
        val state = SessionListRenderState(
            filterQuery = filterQuery,
            expandedParents = expandedParents,
            sessions = sessions,
            remoteMatches = remoteMatches,
        )
        if (renderPolicy.decide(state) == SessionListRenderDecision.Reuse) return
        sessionList.removeAllViews()
        val tree = SessionTreePolicy.tree(
            sessions = sessions,
            query = filterQuery,
            expandedParents = expandedParents,
        )
        val filtered = SessionTreePolicy.visibleRows(tree)
        // Локальное дерево непусто — значит есть что показать; пусто — но серверная
        // секция может ещё содержать совпадения (см. ниже), поэтому «Ничего не
        // найдено» показывается только когда пусто И там.
        // Видимые id — одним проходом: `map{}.toSet()` давал промежуточный список.
        val visibleIds = HashSet<String>(filtered.size * 2)
        for (i in filtered.indices) visibleIds.add(filtered[i].session.id)
        val serverOnly = SessionSearchPolicy.serverOnly(remoteMatches, visibleIds)
        if (filtered.isEmpty() && serverOnly.isEmpty()) {
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
        // dp() и текущее время — ОДИН раз на рендер, а не на каждую строку:
        // density-пересчёт и часы в цикле строк это чистая потеря на длинном списке.
        val dp4 = dp(4)
        val dp2 = dp(2)
        val dp6 = dp(6)
        val dp8 = dp(8)
        val dp32 = dp(32)
        val dp40 = dp(40)
        val nowMillis = System.currentTimeMillis()
        val nowEpochSeconds = nowMillis / 1000.0
        filtered.forEach { node ->
            val session = node.session
            val isCurrent = node.isCurrent
            // Раскрытие нужно и для иконки, и для подписи кнопки — считается один раз.
            val expanded = node.hasChildren && session.id in expandedParents
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isCurrent) {
                    MarsTheme.card(this@ChatSessionsActivity, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.ACCENT)
                } else {
                    MarsTheme.card(this@ChatSessionsActivity, MarsTheme.SURFACE, radiusDp = 12, strokeColor = MarsTheme.BORDER_SUBTLE)
                }
                setPadding(dp(12 + node.depth * 16), dp8, dp8, dp8)
                isClickable = true
                isFocusable = true
            }
            // Карточка и название всегда открывают сам диалог. Раскрытие воркеров
            // выполняется отдельной кнопкой, чтобы родительский чат нельзя было
            // случайно сделать недоступным.
            val openRow: () -> Unit = { openSession(session.id) }
            card.setOnClickListener { openRow() }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (node.hasChildren) {
                val toggleChildren: () -> Unit = {
                    if (!expandedParents.remove(session.id)) expandedParents.add(session.id)
                    renderSessionList()
                }
                row.addView(MarsTheme.circleIconButton(
                    this,
                    if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    if (expanded) "Свернуть воркеры" else "Развернуть воркеры",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 32,
                    strokeColor = 0,
                ).apply {
                    setOnClickListener { toggleChildren() }
                }, LinearLayout.LayoutParams(dp32, dp32).apply { marginEnd = dp4 })
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
                row.addView(renameBtn, LinearLayout.LayoutParams(dp32, dp32))

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
                row.addView(deleteBtn, LinearLayout.LayoutParams(dp32, dp32))
            }

            // Контекстное меню карточки: pin / archive / export для управляемых строк
            // и «Перенести» для дочерних (субагент/форк). Состав считает чистая
            // SessionManagementPolicy, поэтому у read_only-субагента кнопка остаётся —
            // но в меню у него только перенос.
            if (SessionManagementPolicy.menu(session).isNotEmpty()) {
                val moreBtn = MarsTheme.circleIconButton(
                    this,
                    R.drawable.ic_more_vert,
                    "Действия с диалогом",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 32,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { showSessionMenu(session) }
                }
                row.addView(moreBtn, LinearLayout.LayoutParams(dp32, dp32))
            }
            card.addView(row)

            // Мета активности: когда диалог обновлялся и сколько в нём сообщений
            // («5 мин назад · 12 сообщений») — чистая SessionMetaPolicy + MessageTimePolicy.
            val metaLine = SessionMetaPolicy.metaLine(
                updatedAtMillis = MessageTimePolicy.parseServerTimestamp(session.updatedAt ?: session.createdAt),
                messageCount = session.messageCount,
                nowMillis = nowMillis,
            )
            if (metaLine != null) {
                card.addView(TextView(this).apply {
                    text = metaLine
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp4, 0, dp40, dp2)
                })
            }

            if (!session.preview.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = session.preview
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp4, 0, dp40, dp2)
                })
            }

            // Строка субагента: ТЕМА его работы из `worker_status.goal` (паритет с
            // WebUI), статус и длительность — вместо безликой роли «субагент ·
            // только чтение». Состав подписи и тон считает чистая
            // SubagentWorkLabelPolicy; хост только красит по палитре.
            if (session.isSubagent) {
                val work = SubagentWorkLabelPolicy.label(
                    status = session.workerStatus,
                    nowEpochSeconds = nowEpochSeconds,
                )
                card.addView(TextView(this).apply {
                    text = work.text
                    textSize = 11f
                    setTextColor(subagentWorkColor(work.tone))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp4, 0, dp40, 0)
                })
            }

            // Pin/archive state from the server row (A3): the flags are server-owned,
            // so the card states them instead of inferring anything locally.
            if (session.pinned || session.archived) {
                card.addView(TextView(this).apply {
                    text = buildString {
                        if (session.pinned) append("Закреплён")
                        if (session.pinned && session.archived) append(" · ")
                        if (session.archived) append(SessionManagementPolicy.archivedNote(session))
                    }
                    textSize = 11f
                    setTextColor(if (session.pinned) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp4, 0, dp40, 0)
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
                    setPadding(dp4, 0, dp40, 0)
                })
            }
            sessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp6 })
        }

        // Секция серверного поиска (A2): совпадения вне локального списка —
        // другие профили, глубокая история, попадание по содержимому сообщений.
        // Пустая — не рисуется вовсе, поэтому обычный список ничем не затронут.
        if (serverOnly.isNotEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = SessionSearchPolicy.sectionLabel(serverOnly.size)
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
            serverOnly.forEach { session -> sessionList.addView(remoteCard(session)) }
        }
    }

    /**
     * Карточка серверного совпадения — та же семантика клика, что у локальной
     * строки ([openSession]), но без узла дерева: серверные результаты не
     * раскладываются по родителям (их lineage вне локального списка).
     */
    private fun remoteCard(session: SessionItem): View {
        val note = SessionSearchPolicy.matchNote(session, filterQuery)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(
                this@ChatSessionsActivity,
                MarsTheme.SURFACE,
                radiusDp = 12,
                strokeColor = MarsTheme.BORDER_SUBTLE
            )
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { openSession(session.id) }
        }
        card.addView(TextView(this).apply {
            text = session.title ?: "Новый диалог"
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (note != null) {
            card.addView(TextView(this).apply {
                text = note
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                setPadding(dp(4), 0, dp(40), 0)
            })
        }
        val meta = SessionMetaPolicy.metaLine(
            updatedAtMillis = MessageTimePolicy.parseServerTimestamp(session.updatedAt ?: session.createdAt),
            messageCount = session.messageCount,
            nowMillis = System.currentTimeMillis(),
        )
        if (meta != null) {
            card.addView(TextView(this).apply {
                text = meta
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                isSingleLine = true
                setPadding(dp(4), 0, dp(40), dp(2))
            })
        }
        return card
    }

    /**
     * Контекстное меню карточки диалога (A3): pin / archive / export / share.
     *
     * Состав меню — чистая [SessionManagementPolicy.menu]; подписи берутся оттуда
     * же, чтобы состояние строки читалось однозначно («Закрепить» против
     * «Открепить»). «Поделиться» — публичная ссылка сервера
     * (`POST /api/share/create`): ручка разрешена device-токену, поэтому пункт
     * ходит в сервер, а не изображает недоступность ([shareSession]). Локальный
     * Markdown-экспорт ([exportSession]) остаётся отдельным пунктом для файла.
     */
    private fun showSessionMenu(session: SessionItem) {
        val kinds = SessionManagementPolicy.menu(session)
        if (kinds.isEmpty()) return
        val labels = kinds.map { kind ->
            when (kind) {
                SessionActionKind.PIN -> SessionManagementPolicy.pinLabel(session)
                SessionActionKind.ARCHIVE -> SessionManagementPolicy.archiveLabel(session)
                SessionActionKind.EXPORT -> "Экспорт в Markdown"
                SessionActionKind.SHARE -> SessionManagementPolicy.shareLabel()
                SessionActionKind.MOVE -> SessionManagementPolicy.moveLabel()
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(session.title?.takeIf { it.isNotBlank() } ?: "Диалог")
            .setItems(labels.toTypedArray()) { _, index ->
                when (kinds[index]) {
                    SessionActionKind.PIN -> togglePinned(session)
                    SessionActionKind.ARCHIVE -> toggleArchived(session)
                    SessionActionKind.EXPORT -> exportSession(session)
                    SessionActionKind.SHARE -> shareSession(session)
                    SessionActionKind.MOVE -> pickMoveTarget(session)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Выбор нового родителя для дочернего диалога.
     *
     * Список целей — из УЖЕ загруженного [sessions] (отдельного запроса не нужно):
     * чистый [SessionMovePolicy.targets] отбрасывает сам диалог, его потомков,
     * текущего родителя, субагентов и архивные строки. В пунктах — заголовок и дата,
     * сырой id в UI не показывается.
     */
    private fun pickMoveTarget(session: SessionItem) {
        val targets = SessionMovePolicy.targets(sessions, session)
        if (targets.isEmpty()) {
            android.widget.Toast.makeText(this, SessionMovePolicy.NO_TARGETS, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = targets.map { SessionMovePolicy.targetLabel(it) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(SessionMovePolicy.DIALOG_TITLE)
            .setItems(labels) { _, index -> moveSessionTo(session, targets[index]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Перенос: `POST /api/session/move`. Строка обновляется по ОТВЕТУ сервера, как
     * и pin/archive: подтверждённая строка из [SessionMutationOutcome.Success] кладётся
     * в список, а затем список перечитывается — новая ветка дерева строится сервером,
     * локально её не дорисовать. Отказ сервера — тост с его текстом, состояние списка
     * не трогаем (никакого оптимистичного переноса).
     */
    private fun moveSessionTo(session: SessionItem, target: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val outcome = SessionRepository().moveSession(ChatActivity.BASE_URL, c, session.id, target.id)
            when (outcome) {
                is SessionMutationOutcome.Success -> {
                    outcome.session?.let { confirmed ->
                        sessions = sessions.map { if (it.id == confirmed.id) confirmed else it }
                        renderSessionList()
                    }
                    // Дерево и порядок строк — по серверу: перенос меняет родителя,
                    // поэтому список перечитывается, а не правится на месте.
                    // Через throttle: серия переносов схлопывается в один запрос.
                    refreshScheduler.trigger()
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionMovePolicy.successMessage(target),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is SessionMutationOutcome.Failure ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionMovePolicy.failureMessage(outcome.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    /**
     * Pin/Unpin: `POST /api/session/pin`. Строка обновляется по ОТВЕТУ сервера
     * ([SessionMutationOutcome.Success] несёт подтверждённую строку), а не
     * оптимистично: у сервера есть квота закреплённых, и показать закрепление,
     * которое сервер не принял, значило бы соврать в UI.
     */
    private fun togglePinned(session: SessionItem) {
        mutateSession(session, pinned = SessionManagementPolicy.nextPinned(session)) { confirmed ->
            refreshScheduler.trigger()
            android.widget.Toast.makeText(
                this,
                if (confirmed.pinned) "Диалог закреплён" else "Диалог откреплён",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Archive/Unarchive: `POST /api/session/archive`. Архивная строка уходит из
     * активного списка сервера только после подтверждения — поэтому список
     * перезагружается, а не фильтруется локально.
     */
    private fun toggleArchived(session: SessionItem) {
        mutateSession(session, archived = SessionManagementPolicy.nextArchived(session)) { confirmed ->
            refreshScheduler.trigger()
            android.widget.Toast.makeText(
                this,
                if (confirmed.archived) "Диалог в архиве" else "Диалог возвращён из архива",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Общий путь pin/archive: одна мутация на сервере → перерисовка списка. */
    private fun mutateSession(
        session: SessionItem,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        onSuccess: (SessionItem) -> Unit,
    ) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val repo = SessionRepository()
            val outcome = when {
                pinned != null -> repo.setSessionPinned(ChatActivity.BASE_URL, c, session.id, pinned)
                archived != null -> repo.setSessionArchived(ChatActivity.BASE_URL, c, session.id, archived)
                else -> null
            } ?: return@launch
            when (outcome) {
                is SessionMutationOutcome.Success -> {
                    // Сервер не всегда возвращает строку в ответе: тогда состояние
                    // берём из локального списка с применённым флагом.
                    val confirmed = outcome.session
                        ?: session.copy(pinned = pinned ?: session.pinned, archived = archived ?: session.archived)
                    sessions = sessions.map { if (it.id == confirmed.id) confirmed else it }
                    withContext(Dispatchers.IO) { offlineCache.saveSessions(sessions) }
                    // Подтверждённая строка уже в списке — карточка перерисовывается
                    // сразу, не ожидая перезагрузки (она идёт через throttle и может
                    // быть отложена). Повторный рендер после загрузки — Reuse.
                    renderSessionList()
                    onSuccess(confirmed)
                }
                is SessionMutationOutcome.Failure ->
                    android.widget.Toast.makeText(this@ChatSessionsActivity, outcome.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Экспорт диалога в Markdown (A3).
     *
     * Источник — `GET /api/session/export?session_id=` (полный JSON сессии), который
     * разбирается в тот же [ru.mars.network.model.SessionDetail], что и детальный
     * эндпоинт; Markdown пишет уже готовый
     * [ru.mars.network.export.SessionMarkdownExporter] — своей разметки клиент не
     * изобретает. Файл кладётся в `cacheDir/session-exports` и уходит наружу через
     * FileProvider (`content://`), после чего открывается системный share-sheet:
     * так экспорт доступен офлайн и не требует серверного share-токена.
     */
    private fun exportSession(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val outcome = SessionRepository().exportSession(ChatActivity.BASE_URL, c, session.id)
            when (outcome) {
                is SessionExportOutcome.Failure ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionExportSharePolicy.failureMessage(outcome.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                is SessionExportOutcome.Success -> {
                    val markdown = SessionMarkdownExporter().export(outcome.detail)
                    val fileName = SessionManagementPolicy.exportFileName(session)
                    // Запись файла — на IO-диспетчере: транскрипт может быть большим.
                    val uri = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(cacheDir, SessionExportSharePolicy.EXPORT_DIR).apply { mkdirs() }
                            val file = File(dir, fileName).apply { writeText(markdown) }
                            androidx.core.content.FileProvider.getUriForFile(
                                this@ChatSessionsActivity,
                                SessionExportSharePolicy.authority(packageName),
                                file
                            )
                        }.getOrNull()
                    }
                    if (uri == null) {
                        android.widget.Toast.makeText(
                            this@ChatSessionsActivity,
                            SessionExportSharePolicy.failureMessage("не удалось сохранить файл"),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = SessionExportSharePolicy.MIME_TYPE
                        putExtra(Intent.EXTRA_SUBJECT, SessionExportSharePolicy.subject(session))
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(share, SessionExportSharePolicy.CHOOSER_TITLE)
                    runCatching {
                        startActivity(chooser)
                        android.widget.Toast.makeText(
                            this@ChatSessionsActivity,
                            SessionExportSharePolicy.successMessage(),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure {
                        android.widget.Toast.makeText(
                            this@ChatSessionsActivity,
                            "Некому открыть файл для отправки",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Публичная ссылка диалога (O4): `POST /api/share/create`.
     *
     * Ручка разрешена device-токену (`api/auth.py`, `NATIVE_BEARER_EXACT_PATHS`),
     * поэтому клиент получает ссылку у сервера, а не собирает её локально.
     * Адрес в ответе относительный (`/share/<token>`) — абсолютный собирает
     * [SessionSharePolicy.absoluteUrl] от [ChatActivity.BASE_URL].
     *
     * После успеха открывается диалог ссылки: «Скопировать» кладёт адрес в буфер
     * обмена, «Отозвать» дёргает `POST /api/share/revoke` — то есть отзыв живёт
     * ровно там, где пользователь видит саму ссылку.
     */
    private fun shareSession(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            when (val outcome = SessionRepository().createShare(ChatActivity.BASE_URL, c, session.id)) {
                is SessionShareOutcome.Failure ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionSharePolicy.failureMessage(outcome.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()

                is SessionShareOutcome.Success -> showShareLink(session, outcome.share)
            }
        }
    }

    /** Диалог готовой ссылки: текст, «Скопировать», «Отозвать ссылку». */
    private fun showShareLink(session: SessionItem, link: ru.mars.network.repository.SessionShareLink) {
        val url = SessionSharePolicy.clipboardText(ChatActivity.BASE_URL, link)
        android.app.AlertDialog.Builder(this)
            .setTitle("Публичная ссылка")
            .setMessage("$url\n\n${SessionSharePolicy.dialogMessage(link)}")
            .setPositiveButton("Скопировать") { _, _ -> copyToClipboard(url) }
            .setNeutralButton(SessionManagementPolicy.revokeLabel()) { _, _ -> revokeShare(session) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Копирование адреса в буфер обмена. На Android 13+ система сама показывает
     * подтверждение копирования, поэтому свой тост остаётся только там, где его нет
     * (до Android 13) — иначе пользователь видит два сообщения подряд.
     */
    private fun copyToClipboard(text: String) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("Публичная ссылка", text))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(this, SessionSharePolicy.copiedMessage(), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Отзыв ссылки: `POST /api/share/revoke`; отказ показывается текстом сервера. */
    private fun revokeShare(session: SessionItem) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            when (val outcome = SessionRepository().revokeShare(ChatActivity.BASE_URL, c, session.id)) {
                is SessionMutationOutcome.Success ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionSharePolicy.revokeSuccessMessage(),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                is SessionMutationOutcome.Failure ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        SessionSharePolicy.revokeFailureMessage(outcome.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    /**
     * Импорт диалога из JSON-файла (O5): `POST /api/session/import`.
     *
     * Файл выбирается системным пикером (`ACTION_OPEN_DOCUMENT`), читается через
     * `contentResolver` — копировать его в кэш не нужно. Документ проверяет
     * [ru.mars.network.export.SessionImportDocument] до запроса, а сервер создаёт
     * диалог с НОВЫМ id: в список кладётся строка из ответа, затем список
     * перечитывается (импорт мог лечь в другую секцию — архив/закреплённые).
     */
    private fun importSessionFrom(uri: android.net.Uri) {
        val c = store.read() ?: return
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (raw == null) {
                android.widget.Toast.makeText(
                    this@ChatSessionsActivity,
                    "Не удалось прочитать файл",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            when (val outcome = SessionRepository().importSession(ChatActivity.BASE_URL, c, raw)) {
                is SessionImportOutcome.Failure ->
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        "Импорт не удался: ${outcome.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()

                is SessionImportOutcome.Success -> {
                    sessions = listOf(outcome.session) + sessions.filterNot { it.id == outcome.session.id }
                    withContext(Dispatchers.IO) { offlineCache.saveSessions(sessions) }
                    renderSessionList()
                    android.widget.Toast.makeText(
                        this@ChatSessionsActivity,
                        "Диалог импортирован",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Полный список — от сервера: импорт мог попасть в другую секцию.
                    refreshScheduler.trigger()
                }
            }
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
            setTextColor(MarsTheme.TEXT_STRONG)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Переименовать диалог")
            .setView(nameInput)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = nameInput.text.toString().trim()
                if (title.isNotBlank()) {
                    lifecycleScope.launch {
                        if (SessionRepository().renameSession(ChatActivity.BASE_URL, c, session.id, title)) {
                            // Подтверждённое имя кладётся в строку сразу: перезагрузка
                            // идёт через throttle и может быть отложена, а заголовок
                            // в карточке не должен ждать сети.
                            sessions = sessions.map { if (it.id == session.id) it.copy(title = title) else it }
                            renderSessionList()
                            refreshScheduler.trigger()
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
        // Импорт диалога из JSON (O5): системный пикер файла, дальше — /api/session/import.
        val importBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_import,
            "Импорт диалога",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener {
                importDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        }
        header.addView(importBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
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

        // Индикатор офлайна: видимая, но не блокирующая полоса над списком. Список из
        // кэша читается и открывается как обычно — без сети экран остаётся рабочим.
        offlineBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
            // Доступность: смену сетевого статуса TalkBack озвучивает сам (polite).
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(offlineBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

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
                    // Ввод только пишет состояние: рендер отложен на паузу набора,
                    // поэтому карточки не пересобираются на каждый символ.
                    scheduleRender()
                    // Серверный поиск идёт следом, отложенно (A2), и не блокирует ввод.
                    scheduleServerSearch(filterQuery)
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
        // Новое дерево view — отпечаток предыдущего рендера недействителен.
        renderPolicy.reset()
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

    /**
     * Цвет строки субагента по тону политики: палитра только из MarsTheme,
     * хардкод-цветов здесь нет (дизайн-гейт).
     */
    private fun subagentWorkColor(tone: SubagentWorkTone): Int = when (tone) {
        SubagentWorkTone.RUNNING -> MarsTheme.ACCENT
        SubagentWorkTone.DONE -> MarsTheme.STATUS_ONLINE
        SubagentWorkTone.ERROR -> MarsTheme.STATUS_ERROR
        SubagentWorkTone.STOPPED, SubagentWorkTone.UNKNOWN -> MarsTheme.TEXT_MUTED
    }

    private fun dp(value: Int) = MarsTheme.dp(this, value)

    private companion object {
        /**
         * Задержка серверного поиска. Ввод обрабатывается немедленно локальным
         * фильтром, а `GET /api/sessions/search` уходит только после паузы — иначе
         * набор слова порождал бы запрос на каждую букву.
         */
        const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * Задержка рендера списка по вводу в фильтре. Тот же ритм, что у серверного
         * поиска: пауза набора даёт ОДНУ пересборку карточек вместо одной на символ
         * (каждый рендер — снятие всех view и inflate строк на весь список).
         */
        const val RENDER_DEBOUNCE_MS = 300L
    }
}
