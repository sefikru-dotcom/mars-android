package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Шторка «Голос и темп»: список голосов, ступени темпа и кнопка «Проверить».
 *
 * Зачем отдельный View-слой, а не настройки в экране: голос и темп нужны в двух разных
 * местах (серверный чат и локальный диалог), а правила выбора уже собраны в
 * [VoiceProsodyPolicy]. Шторка поэтому ничего не решает: она рисует `draft`, отдаёт
 * готовое значение в [show] и не знает, куда оно поедет — в `SharedPreferences` через
 * [VoiceProsodyStore] или сразу в движок.
 *
 * Почему `draft`, а не «применять на каждое нажатие»: темп — это то, что слушают.
 * Человек шагает по ступеням, слушает «Проверить», возвращается и шагает снова; писать
 * каждое промежуточное значение значило бы, что после «Отмены» настройка осталась
 * последней случайной ступенью. Поэтому [onApply] вызывается ровно один раз — по
 * «Готово».
 *
 * Почему «Проверить» не закрывает шторку: синтез запускается вызывающим тем же путём,
 * что и обычная озвучка, и идёт несколько секунд — закрытая шторка заставила бы человека
 * открывать её заново после каждого прослушивания.
 *
 * Стилистика — [MarsDialog] (та же карточка, тот же заголовок, те же пилюли и кнопки),
 * поэтому шторка выглядит родной без единого нового цвета: выбранный голос подсвечен
 * `MarsTheme.ACCENT_BG`/`ACCENT_BORDER`, как акцентный пункт остальных листов.
 */
internal object VoiceProsodySheet {

    /** Кнопка на границе ступеней гаснет, а не нажимается вхолостую. */
    private const val DISABLED_ALPHA = 0.4f

    /**
     * Показать шторку.
     *
     * @param current сохранённый темп и голос (стартовое состояние `draft`).
     * @param onApply применённое значение — ровно один раз, по «Готово».
     * @param onPreview прослушивание черновика тем же путём, что обычная озвучка.
     *   `null` — на устройстве нечем проверить: кнопка не рисуется вовсе, потому что
     *   мёртвая «Проверить» хуже её отсутствия.
     */
    fun show(
        activity: Activity,
        current: VoiceProsody,
        onApply: (VoiceProsody) -> Unit,
        onPreview: ((VoiceProsody) -> Unit)? = null,
    ) {
        var draft = current
        var dialog: Dialog? = null

        /** Строки голосов в порядке отрисовки: по ним же считается подсветка. */
        val rows = ArrayList<OptionRow>(VoiceProsodyPolicy.options().size + 1)

        var speedValue: TextView? = null
        var speedHint: TextView? = null
        var slower: Button? = null
        var faster: Button? = null

        /**
         * Перерисовать всё по [draft].
         *
         * Один метод на все изменения — чтобы подпись темпа, подсветка голоса и
         * доступность «−»/«+» не разъехались: три отдельных обработчика рано или поздно
         * обновили бы два из трёх состояний.
         */
        fun refresh() {
            rows.forEach { row -> style(activity, row, chosen = row.voiceId == draft.voiceId) }
            speedValue?.text = draft.speedLabel()
            speedHint?.text = VoiceProsodyPolicy.speedHint(draft.speed)
            slower?.let { button ->
                val enabled = VoiceProsodyPolicy.canStepDown(draft.speed)
                button.isEnabled = enabled
                button.alpha = if (enabled) 1f else DISABLED_ALPHA
            }
            faster?.let { button ->
                val enabled = VoiceProsodyPolicy.canStepUp(draft.speed)
                button.isEnabled = enabled
                button.alpha = if (enabled) 1f else DISABLED_ALPHA
            }
        }

        // «−» / значение / «+»: шаг по ступеням политики, не свободный ввод. Значение
        // подписано как в шапке ([VoiceProsody.speedLabel]) — «1.15×», а не «115 %».
        val slowerButton = MarsDialog.secondaryButton(activity, "−") {
            draft = draft.withSpeed(VoiceProsodyPolicy.stepDown(draft.speed))
            refresh()
        }.apply {
            // Символ «−» скринридер прочитает как «минус» без смысла: действие важнее глифа.
            contentDescription = "Медленнее"
        }
        val fasterButton = MarsDialog.secondaryButton(activity, "+") {
            draft = draft.withSpeed(VoiceProsodyPolicy.stepUp(draft.speed))
            refresh()
        }.apply { contentDescription = "Быстрее" }
        slower = slowerButton
        faster = fasterButton

        val value = text(activity, draft.speedLabel(), 17, MarsTheme.TEXT_STRONG, bold = true).apply {
            gravity = Gravity.CENTER
            // Значение меняется на месте: озвучивать его смену обязан TalkBack.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val hint = text(activity, VoiceProsodyPolicy.speedHint(draft.speed), 12, MarsTheme.TEXT_MUTED)
        speedValue = value
        speedHint = hint

        val speedRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slowerButton, LinearLayout.LayoutParams(WRAP, WRAP))
            // Значение забирает всё свободное место: кнопки у краёв, число по центру.
            addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(fasterButton, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, VoiceProsodyPolicy.TITLE, null))
            root.addView(MarsDialog.caption(activity, VoiceProsodyPolicy.VOICE_CAPTION))

            VoiceProsodyPolicy.options().forEachIndexed { index, option ->
                val row = optionRow(activity, option.id, option.label, option.hint) {
                    draft = draft.withVoice(option.id)
                    refresh()
                }
                root.addView(
                    row.root,
                    LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        if (index > 0) topMargin = MarsTheme.dp(activity, ROW_GAP_DP)
                    },
                )
                rows += row
            }

            // Путь назад к «как в вебе»: сброс — такой же выбор, как и голос.
            val reset = optionRow(
                activity,
                voiceId = null,
                label = VoiceProsodyPolicy.DEFAULT_VOICE_LABEL,
                hint = VoiceProsodyPolicy.DEFAULT_VOICE_HINT,
            ) {
                draft = draft.withVoice(null)
                refresh()
            }
            root.addView(
                reset.root,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = MarsTheme.dp(activity, ROW_GAP_DP)
                },
            )
            rows += reset

            root.addView(
                MarsDialog.caption(activity, VoiceProsodyPolicy.SPEED_CAPTION),
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = MarsTheme.dp(activity, SECTION_GAP_DP)
                },
            )
            root.addView(speedRow, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(
                hint,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = MarsTheme.dp(activity, HINT_GAP_DP)
                },
            )

            if (onPreview != null) {
                root.addView(
                    MarsDialog.block(
                        activity,
                        MarsDialog.retryButton(activity, VoiceProsodyPolicy.PREVIEW_LABEL) { onPreview(draft) },
                        bottomDp = 12,
                    ),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        topMargin = MarsTheme.dp(activity, SECTION_GAP_DP)
                    },
                )
            }

            root.addView(
                MarsDialog.buttonRow(
                    activity,
                    MarsDialog.primaryButton(activity, VoiceProsodyPolicy.APPLY_LABEL) {
                        dialog?.dismiss()
                        onApply(draft)
                    },
                    MarsDialog.secondaryButton(activity, VoiceProsodyPolicy.CANCEL_LABEL) {
                        // «Отмена» не зовёт onApply: черновик выбрасывается целиком.
                        dialog?.dismiss()
                    },
                ),
            )
            // Первая отрисовка — по draft: подсветка и подписи ставятся здесь, а не
            // размазаны по созданию вьюх (иначе состояние пришлось бы собирать в трёх местах).
            refresh()
            emptyList()
        }
        // Тот же уровень затемнения, что у листа действий: два листа подряд не мерцают.
        dialog?.window?.setDimAmount(0.8f)
    }

    /** Строка списка голосов: `root` (кликабельный) и два уровня текста внутри. */
    private class OptionRow(
        val root: LinearLayout,
        val title: TextView,
        val hint: TextView,
        /** `null` — строка сброса «как в вебе»; иначе id голоса. */
        val voiceId: String?,
    )

    /**
     * Собрать строку голоса.
     *
     * Заголовок и пояснение — отдельные вьюхи, а не один текст с `\n`: пояснение должно
     * быть мельче и тише подписи, иначе список читается как сплошной абзац.
     */
    private fun optionRow(
        context: Context,
        voiceId: String?,
        label: String,
        hint: String,
        onClick: () -> Unit,
    ): OptionRow {
        val title = text(context, label, 14, MarsTheme.TEXT_STRONG, bold = true)
        val hintView = text(context, hint, 12, MarsTheme.TEXT_MUTED)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            val padH = MarsTheme.dp(context, 14)
            val padV = MarsTheme.dp(context, 12)
            setPadding(padH, padV, padH, padV)
            addView(title)
            addView(
                hintView,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = MarsTheme.dp(context, 3)
                },
            )
            setOnClickListener { onClick() }
        }
        val row = OptionRow(root, title, hintView, voiceId)
        // Базовая раскраска до первого refresh: строка должна существовать оформленной,
        // даже если refresh по какой-то причине не дойдёт.
        style(context, row, chosen = false)
        return row
    }

    /**
     * Раскраска строки: выбранная — акцентом, остальные — тихой карточкой.
     *
     * Фон собирается через [MarsTheme.button], а не [MarsTheme.card]: у строки нужен
     * ripple по нажатию, и он же даёт границу одним вызовом. Цвета — только токены
     * [MarsTheme], своих значений шторка не вводит.
     */
    private fun style(context: Context, row: OptionRow, chosen: Boolean) {
        val background = if (chosen) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE
        val border = if (chosen) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED
        row.root.background = MarsTheme.button(context, background, radiusDp = 12, strokeColor = border)
        row.title.setTextColor(if (chosen) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_STRONG)
        row.hint.setTextColor(if (chosen) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
        row.root.isSelected = chosen
        // Скринридер обязан слышать выбор: подсветка для незрячего не существует.
        row.root.contentDescription = buildString {
            append(row.title.text.toString())
            val detail = row.hint.text.toString()
            if (detail.isNotEmpty()) append(", ").append(detail)
            append(if (chosen) ", выбрано" else ", не выбрано")
        }
    }

    /** Текст в стиле остальных шторок: размер в sp, межстрочный 1.15. */
    private fun text(context: Context, value: String, sp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Промежуток между строками голосов: строки — отдельные карточки, а не абзац. */
    private const val ROW_GAP_DP = 8

    /** Промежуток перед новым разделом («Темп речи»). */
    private const val SECTION_GAP_DP = 14

    /** Промежуток между строкой темпа и её пояснением. */
    private const val HINT_GAP_DP = 6
}