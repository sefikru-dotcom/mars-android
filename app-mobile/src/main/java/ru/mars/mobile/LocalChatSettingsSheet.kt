package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.local.LocalUsage

/**
 * Окно «Контекст и параметры» локального чата: стеклянная шторка [MarsDialog], внутри
 * три полочки — контекст, модель и голос.
 *
 * Зачем отдельный компонент, а не функция экрана. Окно было приватным методом
 * [LocalChatActivity] на полторы сотни строк, и из-за этого его состав нельзя было ни
 * проверить, ни переиспользовать: та же раскладка нужна серверному чату
 * ([ChatActivity.showContextUsageDetails]) и, возможно, экрану голосового режима. Здесь
 * осталась только раскладка: что показать — решает [LocalChatSettingsPolicy] (чистые
 * функции, JVM-тесты), а действия приходят колбэками, поэтому окно не знает ни про
 * презентер, ни про голосовые контроллеры.
 *
 * Почему строки-карточки, а не россыпь мелких чипов под шапкой. Прежняя версия держала
 * семь чипов в одну строку: на 360dp они сплющивались и обрезались по буквам, а
 * настройки озвучки дублировали друг друга (голос, темп, «Фон», полноэкранный разговор).
 * Одна строка на вопрос — с названием, текущим значением и пояснением — читается с
 * одного взгляда и не спорит сама с собой.
 *
 * Что НЕ является настройкой и потому сюда не попадает: включение полноэкранного
 * разговора живёт чипом в шапке (это действие), а флаг `wake_word` — в настройках
 * приложения ([ru.mars.mobile.asr.WakeWordSetting]); строка «слух с погашенным экраном»
 * управляет сервисом и флаг не пишет, иначе у одной настройки стало бы два писателя.
 *
 * Тап по строке закрывает окно ДО действия: второй диалог поверх ещё не снятого окна на
 * части прошивок выходит без затемнения, а пикер, открытый под шторкой, читался бы как
 * «ничего не произошло».
 */
internal object LocalChatSettingsSheet {

    /**
     * Что показать. Значения, а не живые объекты: окно одноразовое (открылось —
     * прочитало — закрылось), и держать ссылку на презентер ему незачем.
     */
    class Content(
        val usage: LocalUsage,
        val windowTokens: Int?,
        val model: String?,
        val supportsReasoning: Boolean,
        val reasoningLabel: String?,
        val turnSettingsCustom: Boolean,
        val prosody: VoiceProsody,
        val offlineVoiceReady: Boolean,
        val wakeArmed: Boolean,
    )

    /**
     * Что делать по тапу. Отдельный класс, а не девять параметров: порядок лямбд в
     * длинном списке легко перепутать, а именованные аргументы делают вызов читаемым и
     * ловят пропущенное действие на компиляции.
     */
    class Callbacks(
        val onNewDialog: () -> Unit,
        val onProviderSettings: () -> Unit,
        val onModel: () -> Unit,
        val onReasoning: () -> Unit,
        val onSampling: () -> Unit,
        val onVoiceMode: () -> Unit,
        val onProsody: () -> Unit,
        val onWakeService: () -> Unit,
        val onMemory: () -> Unit,
    )

    /** Подписи кнопок: те же, что были у окна, — человек не должен искать их заново. */
    const val NEW_DIALOG_LABEL: String = "Новый диалог"
    const val PROVIDER_LABEL: String = "Настройки провайдера / API-ключ"
    const val CLOSE_LABEL: String = "Закрыть"

    /** «Заполнение контекста» без замера — честное «не измерено», а не «0%». */
    const val CONTEXT_UNMEASURED: String = "не измерено"

    /** Высота полосы заполнения — как у полосы в композере ([ContextFillBarView]). */
    private const val BAR_HEIGHT_DP = ContextFillBarView.BAR_HEIGHT_DP

    /** Отступ строк друг от друга: строки — отдельные карточки, а не абзац. */
    private const val ROW_GAP_DP = 8

    fun show(activity: Activity, content: Content, callbacks: Callbacks) {
        if (activity.isFinishing || activity.isDestroyed) return
        var dialog: Dialog? = null

        /**
         * Закрыть окно и выполнить действие: пикер открывается уже без шторки.
         *
         * Не названо `run`: локальная функция с таким именем перекрывает стандартный
         * `run {}` и делает чтение кода двусмысленным.
         */
        fun closeThen(action: () -> Unit) {
            dialog?.dismiss()
            action()
        }

        val view = LocalContextPolicy.view(content.usage.promptTokens, content.windowTokens)
        val percent = view.percent
        val level = view.level ?: LocalContextLevel.NORMAL

        val shown = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, LocalChatSettingsPolicy.TITLE, null))

            root.addView(sectionTitle(activity, LocalChatSettingsPolicy.Section.CONTEXT.title))
            root.addView(contextCard(activity, content, percent, level), matchWrap())
            root.addView(
                MarsDialog.primaryButton(activity, NEW_DIALOG_LABEL) { closeThen(callbacks.onNewDialog) },
                matchWrap(topDp = 12),
            )
            root.addView(
                MarsDialog.secondaryButton(activity, PROVIDER_LABEL) {
                    closeThen(callbacks.onProviderSettings)
                },
                matchWrap(topDp = 8),
            )

            root.addView(sectionTitle(activity, LocalChatSettingsPolicy.Section.MODEL.title))
            LocalChatSettingsPolicy.modelRows(
                model = content.model,
                supportsReasoning = content.supportsReasoning,
                reasoningLabel = content.reasoningLabel,
                turnSettingsCustom = content.turnSettingsCustom,
            ).forEachIndexed { index, row ->
                root.addView(
                    rowCard(activity, row) {
                        when (row.id) {
                            LocalChatSettingsPolicy.RowId.MODEL -> closeThen(callbacks.onModel)
                            LocalChatSettingsPolicy.RowId.REASONING -> closeThen(callbacks.onReasoning)
                            LocalChatSettingsPolicy.RowId.SAMPLING -> closeThen(callbacks.onSampling)
                            else -> Unit
                        }
                    },
                    matchWrap(topDp = if (index == 0) 0 else ROW_GAP_DP),
                )
            }

            root.addView(sectionTitle(activity, LocalChatSettingsPolicy.Section.VOICE.title))
            val voiceRows = LocalChatSettingsPolicy.voiceRows(
                prosody = content.prosody,
                offlineVoiceReady = content.offlineVoiceReady,
                wakeArmed = content.wakeArmed,
            ) + LocalChatSettingsPolicy.memoryRow()
            voiceRows.forEachIndexed { index, row ->
                root.addView(
                    rowCard(activity, row) {
                        when (row.id) {
                            LocalChatSettingsPolicy.RowId.VOICE_MODE -> closeThen(callbacks.onVoiceMode)
                            LocalChatSettingsPolicy.RowId.PROSODY -> closeThen(callbacks.onProsody)
                            LocalChatSettingsPolicy.RowId.WAKE_SERVICE -> closeThen(callbacks.onWakeService)
                            LocalChatSettingsPolicy.RowId.MEMORY -> closeThen(callbacks.onMemory)
                            else -> Unit
                        }
                    },
                    matchWrap(topDp = if (index == 0) 0 else ROW_GAP_DP),
                )
            }

            root.addView(
                MarsDialog.quietButton(activity, CLOSE_LABEL) { dialog?.dismiss() },
                matchWrap(topDp = 12),
            )
            emptyList()
        }
        dialog = shown
        // Тот же уровень затемнения, что у листа действий: два листа подряд не мерцают.
        shown.window?.setDimAmount(0.55f)
    }

    // --- полочки -------------------------------------------------------------------------

    /** Заголовок полочки: маленькие капс-буквы акцентом, как в шторках серверного чата. */
    private fun sectionTitle(context: Context, title: String): TextView = TextView(context).apply {
        text = title
        textSize = 10f
        setTextColor(MarsTheme.ACCENT_TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
        includeFontPadding = false
        setPadding(0, dp(context, 16), 0, dp(context, 8))
    }

    /**
     * Карточка контекста: процент, полоса и точная разбивка.
     *
     * Процент и цвет берутся из [LocalContextPolicy] — той же политики, что рисует пилюлю
     * в композере: одна цифра не может быть зелёной внизу и красной в окне. Текст
     * разбивки — [LocalContextPolicy.detailsText], окно ничего не пересчитывает.
     */
    private fun contextCard(
        activity: Activity,
        content: Content,
        percent: Int?,
        level: LocalContextLevel,
    ): View {
        val tone = when (level) {
            LocalContextLevel.CRITICAL -> MarsTheme.STATUS_ERROR
            LocalContextLevel.WARNING -> MarsTheme.STATUS_WARNING
            LocalContextLevel.NORMAL -> MarsTheme.STATUS_ONLINE
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.glassSurface(activity, radiusDp = 14)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            addView(statRow(activity, "Заполнение контекста", percent?.let { "$it%" } ?: CONTEXT_UNMEASURED))
            addView(
                ContextFillBarView(activity).apply { render(percent, tone) },
                LinearLayout.LayoutParams(-1, dp(activity, BAR_HEIGHT_DP)).apply {
                    topMargin = dp(activity, 8)
                },
            )
            addView(
                TextView(activity).apply {
                    text = LocalContextPolicy.detailsText(content.usage, content.windowTokens)
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_EM)
                    setLineSpacing(dp(activity, 2).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) },
            )
        }
    }

    /** Строка «подпись слева — значение справа» внутри карточки контекста. */
    private fun statRow(context: Context, title: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 12.5f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = 12.5f
                    setTextColor(MarsTheme.TEXT_STRONG)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.END
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(context, 10) },
            )
        }

    /**
     * Строка-карточка параметра: название, значение, пояснение и шеврон.
     *
     * Строка-факт ([LocalChatSettingsPolicy.Row.enabled] == false) не кликается и не
     * показывает шеврон: обещание тапа, за которым ничего нет, читается как поломка.
     * Для скринридера описание собирается из всех трёх строк — иначе он прочитал бы
     * только название и человек не узнал бы текущее значение.
     */
    private fun rowCard(
        context: Context,
        row: LocalChatSettingsPolicy.Row,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = MarsTheme.card(
            context,
            MarsTheme.SURFACE_SUBTLE,
            radiusDp = 12,
            strokeColor = MarsTheme.BORDER_SUBTLE,
        )
        setPadding(dp(context, 14), dp(context, 11), dp(context, 12), dp(context, 11))
        isClickable = row.enabled
        isFocusable = row.enabled
        contentDescription = buildString {
            append(row.title).append(": ").append(row.value)
            row.hint?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
        }
        if (row.enabled) setOnClickListener { onClick() }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = row.title
                    textSize = 12.5f
                    setTextColor(MarsTheme.TEXT)
                    maxLines = 2
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(
                TextView(context).apply {
                    text = row.value
                    textSize = 12f
                    setTextColor(if (row.enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setPadding(0, dp(context, 2), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            row.hint?.takeIf { it.isNotBlank() }?.let { hint ->
                addView(
                    TextView(context).apply {
                        text = hint
                        textSize = 11f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setPadding(0, dp(context, 2), 0, 0)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        if (row.enabled) {
            addView(
                ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_arrow_forward))
                    imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                },
                LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)).apply {
                    marginStart = dp(context, 8)
                },
            )
        }
    }

    // --- мелочи --------------------------------------------------------------------------

    private fun matchWrap(topDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { if (topDp > 0) topMargin = topDp }

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)
}
