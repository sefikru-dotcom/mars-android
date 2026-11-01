package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.local.AttachmentKind
import ru.mars.network.local.LocalAttachment
import ru.mars.network.local.LocalUsage

/**
 * Композер локального чата: строка контекста (полоса заполнения + подпись) и капсула
 * с полем ввода и единственной кнопкой «отправить/стоп».
 *
 * Строка контекста — паритет с серверным чатом (там кольцо [ContextRingView]), но здесь
 * полоса: в композере она занимает остаток ширины и читается как шкала, а не как ещё
 * одна кнопка. Данные берутся из [LocalChatUiState.context], а не из серверных
 * metering-кадров. Пороги, цвет, текст и признак «показывать ли» решает презентер
 * ([LocalContextPolicy]); этот слой только передаёт готовое решение полосе и подписям —
 * второй правды о процентах здесь нет.
 * Окно неизвестно — строка целиком скрыта: выдуманный процент хуже пустого места.
 *
 * Капсула ввода отделена от Activity, потому что она теперь несёт два независимых
 * состояния (пилюля контекста и иконка «отправить/стоп») и перерисовывается на каждом
 * кадре стрима.
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
) {

    private val context: Context = activity

    /** Вертикальный столбец: строка контекста сверху, капсула ввода снизу. */
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

    private val sendButton: ImageButton

    /**
     * Поставить кнопку голоса внутрь капсулы — слева от поля ввода.
     *
     * Раньше она висела отдельной строкой под композером и отнимала место у ленты.
     * Индекс 0 — перед скрепкой: микрофон первым, как в серверном чате.
     */
    fun attachVoice(voiceRoot: android.view.View) {
        val capsule = root.getChildAt(root.childCount - 1) as? android.widget.LinearLayout ?: return
        capsule.addView(
            voiceRoot,
            0,
            android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(4) },
        )
    }

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

    /** Последнее состояние кнопки: null — иконка ещё не выставлена. */
    private var lastStreaming: Boolean? = null
    /** Последнее известное состояние «Останавливаю…»: render зовётся на каждой дельте. */
    private var lastStopping: Boolean? = null

    init {
        // Порядок в строке: токены → полоса → процент. Подпись слева отвечает «сколько
        // занято», полоса — «сколько осталось», процент справа — «близко ли к пределу».
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
            setOnClickListener { showContextDetails() }
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

        // Чипы вложений: между строкой контекста и капсулой — ближе к полю ввода, чем
        // контекст, потому что это про отправляемое сообщение, а не про окно модели.
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

        input = EditText(context).apply {
            hint = "Сообщение"
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 15f
            background = null
            setPadding(dp(10), dp(9), dp(10), dp(9))
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.composerCapsule(context)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        // Скрепка — слева от поля ввода, внутри капсулы: прикрепление относится к
        // набираемому сообщению, а отдельная строка под композером отнимала бы ленту.
        // 40dp — тот же размер, что у кнопки отправки: капсула остаётся ровной по высоте.
        val attachButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_attach,
            "Прикрепить файл или картинку",
            bg = MarsTheme.SURFACE_SUBTLE,
            iconTint = MarsTheme.TEXT_EM,
            sizeDp = 40,
            strokeColor = MarsTheme.BORDER,
        ).apply {
            setOnClickListener { onAttach() }
        }
        capsule.addView(
            attachButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(4) },
        )

        capsule.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        sendButton = MarsTheme.circleIconButton(
            context,
            R.drawable.ic_send,
            "Отправить",
            bg = MarsTheme.ACCENT,
            iconTint = MarsTheme.TEXT_STRONG,
            sizeDp = 40,
            strokeColor = 0,
        ).apply {
            setOnClickListener { onPrimaryAction() }
        }
        capsule.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) },
        )
        root.addView(capsule, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * Показать состояние: пилюля контекста и иконка кнопки. Ввод не трогаем — его текст
     * принадлежит человеку, а не кадру стрима.
     */
    fun render(state: LocalChatUiState) {
        usage = state.usage
        renderContext(state)
        val streaming = state.streaming
        // Фаза «Останавливаю…»: стрим уже не принимает дельты, но цикл ещё не вышел.
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
            // Подпись — по действию клика, а не по иконке: «Стоп» во время ответа.
            sendButton.contentDescription = if (streaming) "Остановить ответ" else "Отправить"
        }
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
        contextRow.visibility = View.VISIBLE
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
    }
}
