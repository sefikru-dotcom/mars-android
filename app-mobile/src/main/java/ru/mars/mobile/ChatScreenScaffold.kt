package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Сборка каркаса экрана чата: шапка (меню, логотип, новый диалог), баннер сети,
 * мини-плеер, статусная строка, панель поиска по диалогу, чипы вложений, транскрипт
 * с FAB «к последнему», быстрые подсказки, smart-чипы, todo-виджет, live-run плашка
 * и композер (цитата, поле ввода, чипы сессии, кнопки действий).
 *
 * Вынесено из ChatActivity.buildChatContent() дословно (чистый рефакторинг): тот же
 * порядок вставки, те же LayoutParams, те же тексты и обработчики, поэтому вид и
 * поведение экрана не меняются. Каркас НЕ держит Activity: всё, что раньше читалось
 * или дёргалось напрямую (состояние чипов, шторка разделов, поиск, отправка, озвучка),
 * приходит через узкий [Host]. Готовые view уезжают наружу в [Scaffold]: на них
 * по-прежнему смотрят update*-методы Activity и рендер транскрипта.
 *
 * Контракты, которые нельзя терять при правках:
 * - поля-идентификаторы (`messages`, `networkBanner`, `statusView`, чипы, FAB) остаются
 *   доступными из Activity — она раскладывает их из [Scaffold] по своим полям;
 * - `networkBanner`, `statusView`, `searchCounter` — polite live-регионы;
 * - статусная строка, счётчик поиска и подпись кнопки отправки обновляются в Activity.
 */
object ChatScreenScaffold {

    /**
     * Узкий хост каркаса: подписи чипов, действия пользователя и переходы вместо самого
     * Activity. Держится намеренно маленьким — новый доступ к Activity должен быть осознанным.
     */
    interface Host {
        /** Тап по кнопке разделов: шторка открывается/закрывается (решает Activity). */
        fun onMenuClick()

        /** Новый диалог: сброс текущей сессии. */
        fun onNewChat()

        /** Тап по мини-плееру: переход в экран музыки. */
        fun openMusic()

        /** Панель поиска: шаг по совпадениям вперёд/назад. */
        fun navigateTranscriptSearch(forward: Boolean)

        /** Изменился запрос поиска: пересчёт совпадений с начала. */
        fun refreshTranscriptSearch(resetPosition: Boolean, scrollToMatch: Boolean)

        /** Закрытие панели поиска и снятие подсветки. */
        fun closeTranscriptSearch()

        /** Прокрутка транскрипта: питает auto-scroll и видимость FAB. */
        fun onTranscriptScrolled()

        /** Тап по FAB «к последнему сообщению». */
        fun onJumpToLatest()

        /** Быстрая подсказка: подставить текст и отправить. */
        fun quickSend(text: String)

        /** Завершить сессию (быстрая подсказка «Финиш»). */
        fun finishSession()

        /** Заголовок todo-виджета: свернуть/развернуть список задач. */
        fun toggleTodoWidget()

        /** Ввод в композере: серверный черновик (программная вставка не пишется). */
        fun onComposerTextChanged(text: String)

        /** Отправка из композера (кнопка/IME). */
        fun sendMessage()

        /** Снять активную цитату в композере. */
        fun clearQuote()

        /** Подпись чипа модели: локальный пикер, пока сервер не прислал модель хода. */
        fun modelChipLabel(): String

        /** Подпись чипа режима активности. */
        fun activityModeChipLabel(): String

        /** Подпись чипа рабочего пространства. */
        fun workspaceChipLabel(): String

        /** Подпись и описание чипа набора инструментов (решает [SessionToolsetsPresenter]). */
        fun toolsetsChipLabel(): String

        fun toolsetsChipDescription(): String

        fun chooseModel()

        fun chooseReasoningEffort()

        fun chooseActivityMode()

        fun chooseWorkspace()

        fun showContextUsageDetails()

        fun showSessionUsageDetails()

        fun showQuotaDetails()

        fun showToolsetsDialog()

        /** Открыть панель поиска по диалогу. */
        fun openTranscriptSearch()

        /** Пикер вложения. */
        fun chooseAttachment()

        /** Голосовой ввод. */
        fun startVoiceInput()

        /**
         * Переключить hands-free диалог (микрофон не закрывается между репликами);
         * возвращает новое состояние. Привязка — долгое нажатие на микрофон.
         */
        fun toggleHandsFree(): Boolean

        /** Озвучить последний ответ. */
        fun speakLastMessage()

        /** Переключить автоозвучивание ответов; возвращает новое состояние. */
        fun toggleAutoSpeak(): Boolean

        /** Идёт ли ход (кнопка отправки превращается в «Остановить»). */
        fun isTyping(): Boolean

        /** Прервать текущий стрим. */
        fun cancelStream()
    }

    /** Мини-плеер: полоса, заголовок трека и кнопка play/pause. */
    class MiniPlayerViews(val box: LinearLayout, val title: TextView, val playBtn: ImageButton)

    /** Панель поиска по диалогу: плашка, поле и счётчик «N из M». */
    class SearchViews(val plate: LinearLayout, val input: EditText, val counter: TextView)

    /** Транскрипт: скролл, контейнер сообщений и FAB «к последнему». */
    class TranscriptViews(val scroll: ScrollView, val messages: LinearLayout, val fab: ImageButton)

    /** Todo-виджет сессии: плашка, заголовок-аккордеон, шеврон и контейнер строк. */
    class TodoViews(
        val plate: LinearLayout,
        val header: LinearLayout,
        val title: TextView,
        val chevron: ImageView,
        val container: LinearLayout,
    )

    /** Live-run плашка: полоса, метка и деталь хода. */
    class LiveRunViews(val plate: LinearLayout, val label: TextView, val detail: TextView)

    /** Композер: цитата, поле ввода, чипы сессии и кнопки действий. */
    class ComposerViews(
        val quoteView: LinearLayout,
        val quoteTitle: TextView,
        val quoteExcerpt: TextView,
        val input: EditText,
        val modelBtn: Button,
        val reasoningBtn: Button,
        val activityModeBtn: Button,
        val workspaceBtn: Button,
        val contextIndicator: TextView,
        val usageIndicator: TextView,
        val toolsetsChip: Button,
        val quotaChip: TextView,
        val voiceBtn: ImageButton,
        val volumeBtn: ImageButton,
        val sendButton: ImageButton,
    )

    /** Результат сборки: корень каркаса и все view, которые Activity держит полями. */
    class Scaffold(
        val root: View,
        val networkBanner: TextView,
        val miniPlayer: MiniPlayerViews,
        val statusView: TextView,
        val search: SearchViews,
        val attachmentChips: LinearLayout,
        val transcript: TranscriptViews,
        val quickChipsScroll: HorizontalScrollView,
        val smartChipsRow: HorizontalScrollView,
        val todo: TodoViews,
        val liveRun: LiveRunViews,
        val composer: ComposerViews,
    )

    /**
     * Сборка каркаса. Тело перенесено из buildChatContent() дословно; отличия только в
     * источнике зависимостей: `this` Activity стал [context], а действия и подписи —
     * вызовами [Host].
     */
    fun build(context: Context, host: Host): Scaffold {
        fun dp(value: Int) = MarsTheme.dp(context, value)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), systemBars.top + dp(6), dp(12), systemBars.bottom + dp(6))
            insets
        }

        // Шапка
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        val menuBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_menu,
            "Разделы",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            // Шторку разделов открывает Activity: каркас не знает про DrawerLayout.
            setOnClickListener { host.onMenuClick() }
        }
        header.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val logoDot = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.mars_planet))
        }
        titleBox.addView(logoDot, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        titleBox.addView(TextView(context).apply {
            text = "МАРС"
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        })
        val headerStatusDot = MarsTheme.glowingStatusDot(context, MarsTheme.STATUS_ONLINE)
        titleBox.addView(headerStatusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8) })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val newChatBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_edit,
            "Новый диалог",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40
        ).apply {
            setOnClickListener { host.onNewChat() }
        }
        header.addView(newChatBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })

        root.addView(header)

        // Баннер сетевого статуса: видимость/текст/тон решает чистая NetworkBannerPolicy,
        // хост лишь мапит тон на токены MarsTheme (полоса под хедером, над мини-плеером).
        val networkBanner = TextView(context).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
            // Доступность: смену сетевого статуса TalkBack озвучивает сам (polite).
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(networkBanner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Полоса мини-плеера
        val miniPlayerBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = MarsTheme.card(context, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
            visibility = View.GONE
            setOnClickListener { host.openMusic() }
        }
        val miniIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_music))
            imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        }
        miniPlayerBox.addView(miniIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })

        val miniPlayerTitle = TextView(context).apply {
            textSize = 13f
            setTextColor(MarsTheme.TEXT_STRONG)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniPlayerBox.addView(miniPlayerTitle, LinearLayout.LayoutParams(0, -2, 1f))

        val miniPrevBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_skip_prev,
            "Предыдущий",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.previous() }
        }
        miniPlayerBox.addView(miniPrevBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val miniPlayerPlayBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_play,
            "Воспроизведение",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = Color.WHITE,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.togglePlay() }
        }
        miniPlayerBox.addView(miniPlayerPlayBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        val miniNextBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_skip_next,
            "Следующий",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34
        ).apply {
            setOnClickListener { MarsMusicPlayer.next() }
        }
        miniPlayerBox.addView(miniNextBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })

        root.addView(miniPlayerBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Статусная строка
        val statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(4), dp(4), 0, dp(4))
            // Доступность: статусная строка озвучивается при смене текста (polite).
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(statusView)

        // Панель поиска по диалогу: поле, счётчик «N из M», кольцевая навигация и закрытие.
        // Решение о совпадениях/подписи — чистая MessageSearchPolicy (JVM-тесты);
        // состояние и подсветка — в TranscriptSearchHighlightController.
        val searchPlate = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = MarsTheme.card(context, MarsTheme.SURFACE_CARD, radiusDp = 12, strokeColor = MarsTheme.BORDER)
        }
        val searchPlateIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_search))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }
        searchPlate.addView(searchPlateIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        val searchInput = EditText(context).apply {
            hint = "Искать в диалоге…"
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 14f
            isSingleLine = true
            background = null
            contentDescription = "Поиск по диалогу"
            imeOptions = 3 // IME_ACTION_SEARCH: кнопка «Найти» листает совпадения вперёд.
            setOnEditorActionListener { _, _, _ -> host.navigateTranscriptSearch(forward = true); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    host.refreshTranscriptSearch(resetPosition = true, scrollToMatch = true)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchPlate.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        val searchCounter = TextView(context).apply {
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            setPadding(dp(6), 0, dp(6), 0)
            // Доступность: счётчик «N из M» озвучивается при навигации по совпадениям.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        searchPlate.addView(searchCounter, LinearLayout.LayoutParams(-2, -2))
        val searchPrevBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_chevron_up,
            "Предыдущее совпадение",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { host.navigateTranscriptSearch(forward = false) } }
        searchPlate.addView(searchPrevBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val searchNextBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_chevron_down,
            "Следующее совпадение",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { host.navigateTranscriptSearch(forward = true) } }
        searchPlate.addView(searchNextBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val searchCloseBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_close,
            "Закрыть поиск",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply { setOnClickListener { host.closeTranscriptSearch() } }
        searchPlate.addView(searchCloseBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(searchPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Чипы вложений
        val attachmentChips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(attachmentChips)

        // Скролл транскрипта
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
        }
        // Feed live scroll positions into the policies so scrolling back down re-pins the
        // viewport and hides the scroll-to-bottom FAB.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> host.onTranscriptScrolled() }
        val messages = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        // Transcript frame: overlays the scroll-to-bottom FAB on the transcript's bottom-right,
        // right above the chips rows and the composer, so it never covers the input bar.
        val transcriptFrame = FrameLayout(context)
        transcriptFrame.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        val scrollToBottomFab = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_chevron_down,
            "К последнему сообщению",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT,
            sizeDp = 40,
            strokeColor = MarsTheme.BORDER
        ).apply {
            visibility = View.GONE
            setOnClickListener { host.onJumpToLatest() }
        }
        transcriptFrame.addView(
            scrollToBottomFab,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(12)
                bottomMargin = dp(10)
            }
        )
        root.addView(transcriptFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // Быстрые подсказки / чипы
        val quickChipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, dp(6))
        }
        val quickBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val quickPrompts = listOf(
            "Погода в Ставрополе" to { host.quickSend("Какая сейчас погода и прогноз на день в Ставрополе?") },
            "Статус хоста" to { host.quickSend("Марс, проверь аптайм, диск и состояние сервисов") },
            "Новый чат" to { host.onNewChat() },
            "Финиш" to { host.finishSession() }
        )
        quickPrompts.forEach { (title, action) ->
            quickBox.addView(Button(context).apply {
                text = title
                textSize = 12f
                setTextColor(MarsTheme.TEXT)
                background = MarsTheme.pill(context, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) })
        }
        quickChipsScroll.addView(quickBox)
        root.addView(quickChipsScroll)

        // Smart quick chips: context-aware one-tap replies right above the composer.
        val smartChipsRow = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, 0, 0, dp(2))
        }
        root.addView(smartChipsRow)

        // Виджет задач сессии (аккордеон)
        val todoWidgetPlate = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = MarsTheme.card(context, MarsTheme.SURFACE_CARD, radiusDp = 10, strokeColor = MarsTheme.ACCENT_BORDER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val todoWidgetHeader = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            // Свёртку держит Activity: флаг и перерисовка виджета живут там.
            setOnClickListener { host.toggleTodoWidget() }
        }
        val todoHeaderRow = todoWidgetHeader
        val todoIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_plan))
            imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        }
        todoHeaderRow.addView(todoIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })

        val todoWidgetTitle = TextView(context).apply {
            textSize = 12.5f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
        }
        todoHeaderRow.addView(todoWidgetTitle, LinearLayout.LayoutParams(0, -2, 1f))

        val todoWidgetChevron = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_chevron_down))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
        }
        todoHeaderRow.addView(todoWidgetChevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        todoWidgetPlate.addView(todoHeaderRow)

        val todoWidgetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        todoWidgetPlate.addView(todoWidgetContainer)
        root.addView(todoWidgetPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Live Run Status Plate: active runner pulse right above the composer
        val liveRunPlate = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = MarsTheme.card(context, MarsTheme.SURFACE_0, radiusDp = 10, strokeColor = MarsTheme.ACCENT_BORDER)
        }
        val liveRunDot = MarsTheme.glowingStatusDot(context, MarsTheme.STATUS_RUNNING)
        liveRunPlate.addView(liveRunDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        val liveRunLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(MarsTheme.ACCENT_TEXT)
            setTypeface(null, Typeface.BOLD)
        }
        liveRunPlate.addView(liveRunLabel)
        val liveRunDetail = TextView(context).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(8), 0, 0, 0)
        }
        liveRunPlate.addView(liveRunDetail)
        root.addView(liveRunPlate, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Двухуровневая стеклянная капсула композера (паритет с WebUI)
        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = MarsTheme.composerCapsule(context)
        }

        // Верхний уровень: плашка цитаты/ответа (если активна)
        val composerQuoteView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = MarsTheme.card(context, MarsTheme.SURFACE_1, radiusDp = 8, strokeColor = MarsTheme.ACCENT_BORDER)
        }
        val quoteAccentBar = View(context).apply {
            setBackgroundColor(MarsTheme.ACCENT)
        }
        composerQuoteView.addView(quoteAccentBar, LinearLayout.LayoutParams(dp(3), dp(24)).apply { marginEnd = dp(6) })
        val quoteTextBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val composerQuoteTitle = TextView(context).apply {
            textSize = 11f
            setTextColor(MarsTheme.ACCENT_TEXT)
            setTypeface(null, Typeface.BOLD)
        }
        quoteTextBox.addView(composerQuoteTitle)
        val composerQuoteExcerpt = TextView(context).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        quoteTextBox.addView(composerQuoteExcerpt)
        composerQuoteView.addView(quoteTextBox, LinearLayout.LayoutParams(0, -2, 1f))

        val quoteCancelBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_close,
            "Отменить цитату",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 24,
            strokeColor = 0
        ).apply {
            setOnClickListener { host.clearQuote() }
        }
        composerQuoteView.addView(quoteCancelBtn, LinearLayout.LayoutParams(dp(24), dp(24)))
        composer.addView(composerQuoteView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

        // Поле ввода: многострочное, чистое, отзывчивое
        val input = EditText(context).apply {
            hint = ComposerLayoutPresenter.HINT
            setTextColor(MarsTheme.TEXT_STRONG)
            setHintTextColor(MarsTheme.TEXT_MUTED)
            textSize = 15f
            maxLines = ComposerLayoutPresenter.MAX_INPUT_LINES
            isSingleLine = false
            background = null
            setPadding(dp(6), dp(4), dp(6), dp(4))
            imeOptions = 4
            setOnEditorActionListener { _, action, _ ->
                if (action == 4) { host.sendMessage(); true } else false
            }
            // A15: серверный черновик composer. Запись дебаунсится политикой
            // [ServerDraftSyncPolicy] (~2 с покоя между запросами); программная вставка
            // (восстановление черновика, steer-leftover) записью НЕ считается.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    host.onComposerTextChanged(s?.toString().orEmpty())
                }
            })
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))

        // Нижний уровень: чипы сессии слева + кнопки действий справа
        val composerBottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        // Слева: компактные контролы модели, уровня размышлений и всегда видимого контекста.
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val modelBtn = Button(context).apply {
            text = host.modelChipLabel()
            textSize = 10.5f
            contentDescription = "Модель"
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { host.chooseModel() }
        }
        chipsRow.addView(modelBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        val reasoningBtn = Button(context).apply {
            text = "· Авто"
            textSize = 10.5f
            contentDescription = "Уровень размышлений"
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { host.chooseReasoningEffort() }
        }
        chipsRow.addView(reasoningBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        val activityModeBtn = Button(context).apply {
            text = host.activityModeChipLabel()
            textSize = 10.5f
            contentDescription = "Режим активности"
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setOnClickListener { host.chooseActivityMode() }
        }
        chipsRow.addView(activityModeBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        val workspaceBtn = Button(context).apply {
            text = host.workspaceChipLabel()
            textSize = 10.5f
            contentDescription = "Рабочее пространство"
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            isSingleLine = true
            maxEms = 11
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setOnClickListener { host.chooseWorkspace() }
        }
        chipsRow.addView(workspaceBtn, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(4) })

        val contextIndicator = TextView(context).apply {
            textSize = 9.5f
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            visibility = View.GONE
            setOnClickListener { host.showContextUsageDetails() }
        }
        chipsRow.addView(contextIndicator, LinearLayout.LayoutParams(-2, -2))

        // A14: расход/стоимость сессии. Отдельная пилюля от контекстного заполнения:
        // первый показывает кумулятивные токены и деньги (`GET api/session/usage`),
        // второй — процент окна из metering-кадров. NaN-подобный «$0.00» не показываем.
        val usageIndicator = TextView(context).apply {
            textSize = 9.5f
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            setTextColor(MarsTheme.TEXT_MUTED)
            visibility = View.GONE
            setOnClickListener { host.showSessionUsageDetails() }
        }
        chipsRow.addView(usageIndicator, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })

        // A9: чип набора инструментов сессии. Подпись и видимость решает [SessionToolsetsPresenter],
        // tap открывает множественный выбор из справочника тулсетов.
        val toolsetsChip = Button(context).apply {
            text = host.toolsetsChipLabel()
            textSize = 10.5f
            contentDescription = host.toolsetsChipDescription()
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = 0
            minHeight = 0
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { host.showToolsetsDialog() }
        }
        chipsRow.addView(toolsetsChip, LinearLayout.LayoutParams(-2, dp(24)).apply { marginStart = dp(4) })

        // A10: чип квоты провайдера. Провайдер без квоты показывает это словами, а не пустотой:
        // состояние берётся из полей ответа (supported/status), а не из HTTP-кода.
        val quotaChip = TextView(context).apply {
            textSize = 9.5f
            background = MarsTheme.pill(context, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            setTextColor(MarsTheme.TEXT_MUTED)
            visibility = View.GONE
            setOnClickListener { host.showQuotaDetails() }
        }
        chipsRow.addView(quotaChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })

        chipsScroll.addView(chipsRow)
        composerBottomBar.addView(chipsScroll, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })

        // Справа: кнопки действий (поиск, вложение, голос, озвучка, отправка/стоп)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Вход в поиск по диалогу: панель раскрывается над транскриптом (под статус-строкой).
        val searchEntryBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_search,
            "Поиск по диалогу",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { host.openTranscriptSearch() }
        }
        actionsRow.addView(searchEntryBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val attachBtn = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_attach,
            "Прикрепить файл",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { host.chooseAttachment() }
        }
        actionsRow.addView(attachBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        val voiceButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_mic,
            "Голосовой ввод, удержание — голосовой диалог",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { host.startVoiceInput() }
            // Долгое нажатие — hands-free диалог: тап остаётся одиночной репликой, а удержание
            // включает режим, в котором микрофон не закрывается между вопросами.
            setOnLongClickListener {
                val enabled = host.toggleHandsFree()
                Toast.makeText(
                    context,
                    if (enabled) "Голосовой диалог включён" else "Голосовой диалог выключен",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
        actionsRow.addView(voiceButton, LinearLayout.LayoutParams(dp(34), dp(34)))

        val volumeButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_volume,
            "Озвучить ответ, удержание — автоозвучивание",
            bg = Color.TRANSPARENT,
            iconTint = MarsTheme.TEXT_MUTED,
            sizeDp = 34,
            strokeColor = 0
        ).apply {
            setOnClickListener { host.speakLastMessage() }
            // Явное действие пользователя: долгое нажатие переключает режим «читать ответы
            // вслух» (persisted). Автоматически режим никогда не включается.
            setOnLongClickListener {
                val enabled = host.toggleAutoSpeak()
                Toast.makeText(
                    context,
                    if (enabled) "Озвучивание ответов включено"
                    else "Озвучивание ответов выключено",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
        actionsRow.addView(volumeButton, LinearLayout.LayoutParams(dp(34), dp(34)))

        val sendButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_send,
            "Отправить",
            bg = MarsTheme.ACCENT,
            iconTint = Color.WHITE,
            sizeDp = 36,
            strokeColor = 0
        ).apply {
            setOnClickListener {
                if (host.isTyping()) {
                    host.cancelStream()
                } else {
                    host.sendMessage()
                }
            }
        }
        actionsRow.addView(sendButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) })

        composerBottomBar.addView(actionsRow, LinearLayout.LayoutParams(-2, -2))
        composer.addView(composerBottomBar, LinearLayout.LayoutParams(-1, -2))

        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        return Scaffold(
            root = root,
            networkBanner = networkBanner,
            miniPlayer = MiniPlayerViews(miniPlayerBox, miniPlayerTitle, miniPlayerPlayBtn),
            statusView = statusView,
            search = SearchViews(searchPlate, searchInput, searchCounter),
            attachmentChips = attachmentChips,
            transcript = TranscriptViews(scroll, messages, scrollToBottomFab),
            quickChipsScroll = quickChipsScroll,
            smartChipsRow = smartChipsRow,
            todo = TodoViews(todoWidgetPlate, todoWidgetHeader, todoWidgetTitle, todoWidgetChevron, todoWidgetContainer),
            liveRun = LiveRunViews(liveRunPlate, liveRunLabel, liveRunDetail),
            composer = ComposerViews(
                quoteView = composerQuoteView,
                quoteTitle = composerQuoteTitle,
                quoteExcerpt = composerQuoteExcerpt,
                input = input,
                modelBtn = modelBtn,
                reasoningBtn = reasoningBtn,
                activityModeBtn = activityModeBtn,
                workspaceBtn = workspaceBtn,
                contextIndicator = contextIndicator,
                usageIndicator = usageIndicator,
                toolsetsChip = toolsetsChip,
                quotaChip = quotaChip,
                voiceBtn = voiceButton,
                volumeBtn = volumeButton,
                sendButton = sendButton,
            ),
        )
    }
}
