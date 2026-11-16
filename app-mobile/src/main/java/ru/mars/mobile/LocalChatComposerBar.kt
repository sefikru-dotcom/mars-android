package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.local.AttachmentKind
import ru.mars.network.local.LocalAttachment
import ru.mars.network.local.LocalUsage

/**
 * Композер локального чата — паритет с серверным ([ChatScreenScaffold]) и WebUI:
 *
 * 1. лента из трёх быстрых баблов («Ок», «Продолжай», «Новый») над капсулой;
 * 2. ряд чипов вложений (только когда прикреплять есть что);
 * 3. единая двухуровневая стеклянная капсула [MarsTheme.composerCapsule]:
 *    - верх — чистое многострочное поле [input] (без вложенной прямоугольной подложки);
 *    - низ — кнопка-пилюля «Контекст и настройки» слева и три круглые кнопки 36dp справа
 *      (скрепка, голос, звезда отправки) с единым шагом 6dp.
 *
 * Данные контекста берутся из [LocalChatUiState.context], а не из серверных
 * metering-кадров. Пороги, цвет, текст и признак «показывать ли» решает презентер
 * ([LocalContextPolicy]); этот слой только передаёт готовое решение полосе, подписям и
 * компактной пилюле — второй правды о процентах здесь нет.
 *
 * Строка `contextRow` (токены + шкала + процент) остаётся в дереве ради проводки
 * Activity и остаётся скрытой: её показания теперь дублируются компактной пилюлей
 * «Контекст» в нижней панели капсулы, а растянутая шкала над полем ввода убрана.
 *
 * Вложения: композер показывает выбранное (чипы над капсулой) и просит выбрать файл
 * ([onAttach]), но НЕ читает его — выбор, чтение и лимиты живут в Activity, потому что
 * там есть `registerForActivityResult` и `contentResolver`. Композер остаётся тупым
 * рендером: он получает готовый список [LocalAttachment] и отдаёт индекс удалённого.
 */
internal class LocalChatComposerBar(
    private val activity: Activity,
    private val onPrimaryAction: () -> Unit,
    /**
     * Скрепка: композер не знает, как открывать системный пикер, — он только просит.
     * Дефолт-заглушка, чтобы не ломать вызовы без вложений (тесты, превью).
     */
    private val onAttach: () -> Unit = {},
    /**
     * Единое окно контекста и настроек локального чата. null — композер показывает
     * собственный компактный диалог деталей ([showContextDetails]): экран, который ещё
     * не научили открывать полное окно, продолжает работать как раньше.
     */
    private val onOpenContextHub: (() -> Unit)? = null,
    /**
     * Быстрые баблы: `"ok"`, `"continue"`, `"new"`. null — баблы не отправляют ничего
     * (композер всё равно остаётся валидным для тестов и превью).
     */
    private val onQuickAction: ((String) -> Unit)? = null,
) {

    private val context: Context = activity

    /** Вертикальный столбец: баблы, вложения, (скрытая) строка контекста, капсула ввода. */
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val contextRow: LinearLayout

    /** Полоса заполнения: рисует процент, цвет — по порогам [LocalContextPolicy]. */
    private val bar: ContextFillBarView

    /** Подпись токенов «3.1k / 1M» — слева от полосы, без процента. */
    private val tokensLabel: TextView

    /** Процент — справа от полосы, тем же цветом, что и заливка. */
    private val percentLabel: TextView

    /** Поле ввода: экран читает его при отправке и пишет в него «Повторить». */
    val input: EditText

    /** Лента быстрых баблов над капсулой: «Ок», «Продолжай», «Новый». */
    private val quickChips: LinearLayout

    /** Подпись кнопки-пилюли контекста: модель и/или процент («Qwen 2.5 · 12%»). */
    private val contextSummaryLabel: TextView

    /** Компактный индикатор уровня контекста внутри пилюли (цвет — из порогов политики). */
    private val contextToneDot: View

    private val attachButton: ImageButton

    private val sendButton: ImageButton

    /** Ряд кнопок действий: скрепка, (голос), звезда отправки. */
    private val actionsRow: LinearLayout

    /** Ряд чипов вложений над капсулой. Скрыт, пока прикреплять нечего. */
    private val chipsRow: HorizontalScrollView

    /** Контейнер чипов: перестраивается целиком при каждом изменении списка. */
    private val chips: LinearLayout

    /** Процент последнего замера: подстраховка подписи диалога, когда окно неизвестно. */
    private var percent: Int? = null

    /** Размер окна модели из решения презентера; null — неизвестен. */
    private var windowTokens: Int? = null

    /** Токены запроса последнего шага — то, из чего посчитан процент пилюли. */
    private var usedTokens: Int = 0

    /** Расход диалога: разбивка prompt/completion для диалога деталей. */
    private var usage: LocalUsage = LocalUsage()

    /** Подпись модели для пилюли контекста: приходит из Activity ([setModelSummary]). */
    private var modelSummary: String = ""

    /** Цвет порога последнего замера: им красится компактный индикатор пилюли. */
    private var contextTone: Int = MarsTheme.TEXT_MUTED

    /** Последнее состояние кнопки: null — иконка ещё не выставлена. */
    private var lastStreaming: Boolean? = null
    /** Последнее известное состояние «Останавливаюсь…»: render зовётся на каждой дельте. */
    private var lastStopping: Boolean? = null

    init {
        // Порядок в строке: токены → полоса → процент. Подпись слева отвечает «сколько
        // занято», полоса — «сколько осталось», процент справа — «близко ли к пределу».
        // Строка скрыта с экрана (её показания дублирует пилюля контекста в капсуле), но
        // живёт в дереве: на неё смотрят update*-методы Activity.
        tokensLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // min-width 0: длинная подпись («12.4k / 1M») сжимается сама, но не тянет
            // строку шире экрана и не выдавливает полосу с процентом за край.
            minimumWidth = 0
            // Предел ширины подписи: она статична, и отдавать ей больше половины строки
            // незачем — иначе на POCO F5 полоса схлопнется в точку.
            maxWidth = dp(MAX_LABEL_WIDTH_DP)
        }

        percentLabel = TextView(context).apply {
            textSize = 11.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            minimumWidth = 0
            setPadding(dp(6), 0, 0, 0)
        }

        bar = ContextFillBarView(context)

        contextRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            // Тап-цель не меньше 32dp: сама полоса 4dp, палец на телефоне крупнее.
            minimumHeight = dp(32)
            setPadding(0, 0, 0, dp(4))
            // Тап по всей строке, а не только по полосе: она слишком тонкая для пальца.
            setOnClickListener { openContext() }
        }
        contextRow.addView(tokensLabel, LinearLayout.LayoutParams(-2, -2))
        // Полоса — единственный взвешенный элемент: 0 ширины + weight 1 = «весь остаток».
        contextRow.addView(
            bar,
            LinearLayout.LayoutParams(0, dp(ContextFillBarView.BAR_HEIGHT_DP), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        contextRow.addView(percentLabel, LinearLayout.LayoutParams(-2, -2))
        // Строка контекста скрыта по умолчанию: до первого замера показывать нечего.
        contextRow.visibility = View.GONE
        root.addView(contextRow, LinearLayout.LayoutParams(-1, -2))

        // Быстрые баблы — над капсулой, как в серверном чате и WebUI. Скрыты по умолчанию:
        // пустой диалог не должен предлагать «Продолжай» и «Ок». Показом управляет экран
        // ([setQuickChipsVisible]: есть сообщения и не идёт стрим).
        quickChips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                quickChip("Ок", R.drawable.ic_check, QUICK_ACTION_OK),
                LinearLayout.LayoutParams(-2, dp(QUICK_CHIP_HEIGHT_DP)).apply { marginEnd = dp(6) },
            )
            addView(
                quickChip("Продолжай", R.drawable.ic_play, QUICK_ACTION_CONTINUE),
                LinearLayout.LayoutParams(-2, dp(QUICK_CHIP_HEIGHT_DP)).apply { marginEnd = dp(6) },
            )
            addView(
                quickChip("Новый", R.drawable.ic_add, QUICK_ACTION_NEW),
                LinearLayout.LayoutParams(-2, dp(QUICK_CHIP_HEIGHT_DP)),
            )
            visibility = View.GONE
        }
        root.addView(
            quickChips,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )

        // Чипы вложений: между баблами и капсулой — ближе к полю ввода, потому что это
        // про отправляемое сообщение, а не про окно модели.
        // Горизонтальный скролл, а не перенос: пять имён файлов в столбик съели бы ленту.
        chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsRow = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // Ряд не должен диктовать ширину экрану: иначе длинное имя файла распирает
            // композер и выдавливает капсулу за край.
            isFillViewport = false
            addView(chips, LinearLayout.LayoutParams(-2, -2))
            visibility = View.GONE
        }
        root.addView(
            chipsRow,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )

        // Верхний уровень капсулы: чистое многострочное поле на всю ширину.
        input = EditText(context).apply {
            hint = "Сообщение"
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 15f
            // Подложки нет: поле лежит прямо в стеклянной капсуле, а симметричные отступы
            // 10/8 держат символы и курсор дальше внутреннего контура скругления капсулы.
            background = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // Текст прижат к верхнему левому углу: капсула растёт вниз, а не «центрирует»
            // первую строку по мере набора.
            gravity = Gravity.TOP or Gravity.START
            // Порядок важен: inputType включает многострочность, minLines/maxLines задают
            // предел роста, а minHeight ставится ПОСЛЕДНИМ — setMinLines сам переписывает
            // minHeight на высоту одной строки, и поле схлопнулось бы в полоску.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = MAX_INPUT_LINES
            // Высота одной строки гарантирована даже до первой раскладки.
            minHeight = dp(40)
            // Font padding включён: иначе высокие глифы («Й», «Щ», «д», «у») и курсор
            // подрезались сверху и снизу.
            includeFontPadding = true
            // Ровный межстрочный ритм: 1.05 множителя + 2dp добавки на строку.
            setLineSpacing(dp(2).toFloat(), 1.05f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onPrimaryAction()
                    true
                } else {
                    false
                }
            }
        }

        // Единая двухуровневая капсула: поле сверху, панель действий снизу.
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.composerCapsule(context)
            // Отступы капсулы 14/10/14/8: скругление — полный овал, поэтому и поле, и
            // нижняя панель стоят внутри ровной зоны, не наезжая на борта.
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        capsule.addView(input, LinearLayout.LayoutParams(-1, -2))

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 6dp — тот же ритм, что и шаг между кнопками: панель не «прилипает» к полю.
            setPadding(0, dp(6), 0, 0)
        }

        // Слева — кнопка-пилюля «Контекст и настройки»: иконка, подпись модели/процента и
        // компактный индикатор порога. Тап открывает единое окно контекста и настроек.
        contextSummaryLabel = TextView(context).apply {
            text = CONTEXT_FALLBACK_LABEL
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // min-width 0: длинное имя модели сжимается, но не выдавливает кнопки за край.
            minimumWidth = 0
            maxWidth = dp(SUMMARY_MAX_WIDTH_DP)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        contextToneDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(contextTone)
            }
        }
        val contextIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_settings))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val contextCapsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(
                context,
                MarsTheme.GLASS_CHIP,
                strokeColor = MarsTheme.BORDER_SUBTLE,
            )
            setPadding(dp(8), dp(4), dp(10), dp(4))
            minimumHeight = dp(28)
            isClickable = true
            isFocusable = true
            contentDescription = "Контекст и настройки"
            setOnClickListener { openContext() }
        }
        contextCapsule.addView(contextIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
        contextCapsule.addView(
            contextSummaryLabel,
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
        )
        contextCapsule.addView(
            contextToneDot,
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(6) },
        )
        bottomBar.addView(contextCapsule, LinearLayout.LayoutParams(-2, -2))

        // Воздух между пилюлей контекста и группой действий.
        bottomBar.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Скрепка — первая в ряду действий: прикрепление относится к набираемому сообщению.
        // 36dp и тёмный ореол — тот же размер и тон, что у голоса и звезды.
        attachButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_attach,
            "Прикрепить файл или картинку",
            bg = MarsTheme.COMPOSER_ACTION_BG,
            iconTint = MarsTheme.TEXT_EM,
            sizeDp = BUTTON_SIZE_DP,
            strokeColor = MarsTheme.COMPOSER_ACTION_BORDER,
        ).apply {
            setOnClickListener { onAttach() }
        }
        actionsRow.addView(
            attachButton,
            LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        // Звезда отправки — в тёмном ореоле, а не в залитом красном круге: паритет с
        // серверным композером ([MarsTheme.sendHaloBackground]).
        sendButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_send,
            "Отправить",
            bg = MarsTheme.SEND_HALO_BG,
            iconTint = MarsTheme.SEND_STAR_TINT,
            sizeDp = BUTTON_SIZE_DP,
            strokeColor = MarsTheme.SEND_HALO_BORDER,
        ).apply {
            setOnClickListener { onPrimaryAction() }
        }
        actionsRow.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)).apply {
                marginStart = dp(BUTTON_STEP_DP)
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        bottomBar.addView(actionsRow, LinearLayout.LayoutParams(-2, -2))
        capsule.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(capsule, LinearLayout.LayoutParams(-1, -2))

        renderSummary()
    }

    /**
     * Поставить кнопку голоса в ряд действий — ПЕРЕД звездой отправки.
     *
     * Раньше она висела отдельной строкой под композером и отнимала место у ленты.
     * Теперь это третья круглая кнопка 36dp того же ряда: скрепка → голос → звезда.
     */
    fun attachVoice(voiceRoot: android.view.View) {
        val sendIndex = actionsRow.indexOfChild(sendButton)
        val index = if (sendIndex < 0) actionsRow.childCount else sendIndex
        actionsRow.addView(
            voiceRoot,
            index,
            LinearLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)).apply {
                marginStart = dp(BUTTON_STEP_DP)
                gravity = Gravity.CENTER_VERTICAL
            },
        )
    }

    /**
     * Показать/скрыть ленту быстрых баблов. Экран зовёт это при каждом кадре состояния:
     * баблы уместны, когда в диалоге уже есть сообщения и ответ не печатается.
     */
    fun setQuickChipsVisible(visible: Boolean) {
        quickChips.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Подпись модели в кнопке контекста. Процент добавляется сам, когда есть замер:
     * «Qwen 2.5 · 12%»; без замера — просто название модели; пустая строка возвращает
     * нейтральное «Контекст · Параметры».
     */
    fun setModelSummary(modelLabel: String) {
        val next = modelLabel.trim()
        if (next == modelSummary) return
        modelSummary = next
        renderSummary()
    }

    /**
     * Показать состояние: пилюля контекста и иконка кнопки. Ввод не трогаем — его текст
     * принадлежит человеку, а не кадру стрима.
     */
    fun render(state: LocalChatUiState) {
        usage = state.usage
        renderContext(state)
        val streaming = state.streaming
        // Фаза «Останавливаюсь…»: стрим уже не принимает дельты, но цикл ещё не вышел.
        // Кнопка обязана быть мёртвой — иначе второй тап уйдёт в пустоту и человек
        // решит, что стоп не сработал.
        val stopping = state.stopping
        if (lastStopping != stopping) {
            lastStopping = stopping
            sendButton.isEnabled = !stopping
            sendButton.alpha = if (stopping) 0.4f else 1f
        }
        // Иконка меняется только на переходах «стрим/покой», а render зовётся на каждой
        // дельте: без этого гварда каждый кадр заново тянул бы drawable и tint.
        if (lastStreaming != streaming) {
            lastStreaming = streaming
            sendButton.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (streaming) R.drawable.ic_stop else R.drawable.ic_send,
                )
            )
            sendButton.imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_STRONG)
            // Ореол перекрашивается вместе с иконкой и только на переходе фазы: в ответе —
            // плотный рубин с акцентным кольцом, в покое — глубокий тёмный ореол звезды.
            sendButton.background = MarsTheme.sendHaloBackground(context, stopping = streaming)
            // Подпись — по действию клика, а не по иконке: «Стоп» во время ответа.
            sendButton.contentDescription = if (streaming) "Остановить ответ" else "Отправить"
        }
        // Баблы уместны, когда в диалоге уже есть о чём говорить и ответ не печатается:
        // состояние знает это само, экран может переопределить вызовом
        // [setQuickChipsVisible], но по умолчанию композер честен без проводки.
        setQuickChipsVisible(state.items.isNotEmpty() && !streaming)
        // Подпись модели — из состояния: «Qwen 2.5 · 12%» вместо немого «Контекст».
        // Экран вправе уточнить её своим [setModelSummary] после render.
        state.model?.takeIf { it.isNotBlank() }?.let { setModelSummary(it) }
    }

    /**
     * Показать выбранные вложения над капсулой. [onRemove] получает индекс чипа —
     * сам композер список не хранит: им владеет экран, он же его и отправляет.
     *
     * Пустой список — ряд скрыт целиком, а не пустая строка с отступами: место в ленте
     * дорого, и «рамка ни для чего» читалась бы как незагрузившееся вложение.
     */
    fun setAttachments(items: List<LocalAttachment>, onRemove: (Int) -> Unit) {
        chips.removeAllViews()
        if (items.isEmpty()) {
            chipsRow.visibility = View.GONE
            return
        }
        items.forEachIndexed { index, item ->
            chips.addView(
                attachmentChip(item, index, onRemove),
                LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) },
            )
        }
        chipsRow.visibility = View.VISIBLE
    }

    /**
     * Быстрый бабл: иконка + подпись в стеклянной пилюле. Иконка — vector, а не эмодзи:
     * глиф зависит от шрифта и на части прошивок выглядит иначе.
     */
    private fun quickChip(label: String, iconRes: Int, action: String): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            background = MarsTheme.pill(
                context,
                MarsTheme.GLASS_CHIP,
                strokeColor = MarsTheme.BORDER_SUBTLE,
            )
            val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(MarsTheme.TEXT_EM)
                val size = dp(13)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(5)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onQuickAction?.invoke(action) }
        }
    }

    /**
     * Чип вложения: имя файла + крестик. Крестик — vector `ic_close`, а не текстовый «×»:
     * глиф зависит от шрифта и на части прошивок выглядит как дефис.
     */
    private fun attachmentChip(
        item: LocalAttachment,
        index: Int,
        onRemove: (Int) -> Unit,
    ): TextView {
        val label = chipLabel(item)
        return TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_STRONG)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Длинное имя файла не должно съедать всю ширину: 60% экрана — предел чипа.
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.6f).toInt()
            setPadding(dp(10), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(
                context,
                MarsTheme.SURFACE,
                strokeColor = MarsTheme.ACCENT,
            )
            val close = ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()?.apply {
                setTint(MarsTheme.TEXT_STRONG)
                val size = dp(12)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, close, null)
            compoundDrawablePadding = dp(6)
            // Тап по чипу убирает вложение: отдельная зона 12dp под палец не годится,
            // а тап-цель в 30dp по высоте — норма.
            contentDescription = "Убрать вложение $label"
            isClickable = true
            isFocusable = true
            setOnClickListener { onRemove(index) }
        }
    }

    /** Подпись чипа: имя файла, а без имени — честный вид вложения. */
    private fun chipLabel(item: LocalAttachment): String {
        val name = item.fileName?.takeIf { it.isNotBlank() }
        if (name != null) return name
        return when (item.kind) {
            AttachmentKind.IMAGE -> "Картинка"
            AttachmentKind.TEXT_FILE -> "Текстовый файл"
            AttachmentKind.DOCUMENT -> "Документ"
        }
    }

    /**
     * Полоса, токены и процент по решению презентера.
     *
     * Процент и уровень считает [LocalContextPolicy] — здесь только отрисовка:
     * [ContextFillBarView] красит заливку по порогу, подписи показывают «сколько / из
     * скольких» и процент. Второй правды о порогах на экране нет.
     */
    private fun renderContext(state: LocalChatUiState) {
        val view = state.context
        if (view == null || !view.visible) {
            // Окно неизвестно (или замера нет) — строки нет вовсе: фейковый «0%» читался бы
            // как «контекст пуст», хотя на самом деле он просто не измерен.
            contextRow.visibility = View.GONE
            percent = null
            windowTokens = null
            usedTokens = 0
            contextTone = MarsTheme.TEXT_MUTED
            renderSummary()
            return
        }
        val window = view.windowTokens ?: 0
        val shown = view.percent ?: 0
        percent = shown
        windowTokens = window
        usedTokens = view.usedTokens
        // Цвет — один на полосу и на процент: жёлтая полоса с зелёной цифрой врала бы
        // о том, близко ли к пределу.
        val tone = ContextFillBarView.toneOf(view.level)
        bar.render(shown, tone)
        // Подпись «3.1k / 1M» и процент разделены: в одну строку «3.1k / 1M · 6%» на
        // POCO F5 усекалось бы до «3.1k / 1…», и процент — самое важное — пропадал.
        tokensLabel.text = LocalContextPolicy.formatTokens(view.usedTokens) +
            " / " + LocalContextPolicy.formatTokens(window)
        percentLabel.text = "$shown%"
        percentLabel.setTextColor(tone)
        tokensLabel.contentDescription = view.contentDescription
        contextRow.contentDescription = view.contentDescription ?: "Окно контекста"
        // Строка контекста остаётся СКРЫТОЙ: её показания дублирует компактная пилюля в
        // нижней панели капсулы, а растянутая шкала над полем ввода убрана (паритет с
        // серверным чатом). Поля и рендер сохранены — на них смотрит проводка Activity.
        contextRow.visibility = View.GONE
        contextTone = tone
        renderSummary()
    }

    /**
     * Подпись и индикатор пилюли контекста: модель + процент, когда замер есть.
     * Текст собирается здесь, а не в Activity, чтобы «Контекст · Параметры» оставалось
     * честным состоянием до первого кадра.
     */
    private fun renderSummary() {
        val pct = percent
        val model = shortModel(modelSummary)
        val text = when {
            model.isNotEmpty() && pct != null -> "$model · $pct%"
            model.isNotEmpty() -> model
            pct != null -> "Контекст · $pct%"
            else -> CONTEXT_FALLBACK_LABEL
        }
        if (contextSummaryLabel.text?.toString() != text) contextSummaryLabel.text = text
        val dot = contextToneDot.background as? GradientDrawable
        dot?.setColor(contextTone)
        contextSummaryLabel.contentDescription = if (pct != null) {
            "Контекст и настройки, заполнено $pct%"
        } else {
            "Контекст и настройки"
        }
    }

    /**
     * Короткая подпись модели: длинный id провайдера («deepseek-v4.1-flash-1017») срезается
     * многоточием, иначе процент — самое важное в пилюле — уезжал за край эллипсиса.
     */
    private fun shortModel(label: String): String =
        if (label.length <= MODEL_LABEL_MAX_CHARS) label else label.take(MODEL_LABEL_MAX_CHARS - 1) + "…"

    /** Единый вход в окно контекста: полное окно экрана, если оно передано, иначе детали. */
    private fun openContext() {
        val open = onOpenContextHub
        if (open != null) open() else showContextDetails()
    }

    /**
     * Диалог деталей окна: заполнение, токены шага и порог. Текст порога и процента
     * готовит презентер ([LocalContextPolicy]) — экран их не пересчитывает, иначе порог
     * показывался бы по другой формуле, чем та, по которой реально сработает сжатие.
     */
    private fun showContextDetails() {
        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(context, "Окно контекста", null))
            root.addView(MarsDialog.paragraph(context, detailsBody()))
            root.addView(
                MarsDialog.buttonRow(
                    context,
                    MarsDialog.secondaryButton(context, "Закрыть") { dialog?.dismiss() },
                ),
            )
            emptyList()
        }
    }

    /** Тело диалога: текст политики ([LocalContextPolicy.detailsText]) без пересчёта в экране. */
    private fun detailsBody(): String {
        // Строка «Запрос (последний шаг)» должна показывать замер последнего шага, а не
        // сумму по всем: `usage.promptTokens` кумулятивен, и процент в диалоге разошёлся
        // бы с процентом в пилюле. Поэтому подставляем токены, из которых он посчитан.
        val step = if (usedTokens > 0) usage.copy(promptTokens = usedTokens) else usage
        val text = LocalContextPolicy.detailsText(step, windowTokens)
        if (text.isNotBlank()) return text
        return percent?.let { "Заполнено: $it%" } ?: LocalContextPolicy.UNKNOWN_DETAILS
    }

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)

    private companion object {
        /**
         * Предел ширины подписи токенов. Строке композера на 360dp-экране нужен воздух:
         * без предела «12.4k / 1M» съедала бы половину, а полосе оставалась точка.
         */
        const val MAX_LABEL_WIDTH_DP = 96

        /** Предел ширины подписи модели в пилюле контекста: не выдавливает кнопки. */
        const val SUMMARY_MAX_WIDTH_DP = 150

        /** Сколько символов модели оставить в пилюле, чтобы процент не уезжал за край. */
        const val MODEL_LABEL_MAX_CHARS = 18

        /**
         * Единый размер всех трёх кнопок ряда (скрепка, голос, звезда отправки) — 36dp,
         * как в серверном композере: один ряд, один ритм, одна тап-цель.
         */
        const val BUTTON_SIZE_DP = 36

        /** Единый шаг между кнопками ряда: 6dp — ровный воздух без «слипания» кругов. */
        const val BUTTON_STEP_DP = 6

        /** Высота быстрых баблов: 28dp — компактно, но палец попадает уверенно. */
        const val QUICK_CHIP_HEIGHT_DP = 28

        /** Потолок роста поля ввода: пять строк, как в серверном композере. */
        const val MAX_INPUT_LINES = 5

        /** Нейтральная подпись пилюли, пока нет ни модели, ни замера. */
        const val CONTEXT_FALLBACK_LABEL = "Контекст · Параметры"

        /** Идентификаторы быстрых действий: их разбирает экран ([onQuickAction]). */
        const val QUICK_ACTION_OK = "ok"
        const val QUICK_ACTION_CONTINUE = "continue"
        const val QUICK_ACTION_NEW = "new"
    }
}
