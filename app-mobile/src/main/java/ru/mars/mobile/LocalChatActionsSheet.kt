package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Лист действий над сообщением локального диалога: Копировать, Повторить, Выбрать текст,
 * Озвучить, Поделиться.
 *
 * Открывается долгим нажатием на сообщение (пузырь пользователя, карточка ассистента).
 * Состав листа — только реально выполнимые пункты: пустое сообщение нечего копировать и
 * шарить, «Повторить» существует лишь для последнего ответа ассистента (повторять середину
 * диалога нечего — ход уже ушёл), «Озвучить» скрыт, когда на устройстве нет движка TTS.
 * Мёртвых кнопок здесь нет по построению.
 *
 * Стилистика — [MarsDialog] (тот же лист пилюль, что у серверного чата), поэтому лист
 * выглядит родным без единого нового цвета.
 */
internal object LocalChatActionsSheet {

    /** Подпись пункта «Повторить» (последний ответ ассистента). */
    const val RETRY_LABEL = "Повторить"

    const val COPY_LABEL = "Копировать"
    const val SELECT_LABEL = "Выбрать текст"
    const val SPEAK_LABEL = "Озвучить"

    /** Пересобрать ответ в файл DOCX/XLSX/TXT и отдать в «Поделиться». */
    const val SAVE_FILE_LABEL = "Сохранить как файл"
    const val SHARE_LABEL = "Поделиться"

    /**
     * Показать лист действий. [canRetry] — сообщение является последним ответом
     * ассистента; [canSpeak] — на устройстве доступна озвучка.
     */
    fun show(
        activity: Activity,
        text: String,
        canRetry: Boolean,
        canSpeak: Boolean,
        onCopy: () -> Unit,
        onRetry: () -> Unit,
        onSelectText: () -> Unit,
        onSpeak: () -> Unit,
        onShare: () -> Unit,
        onSaveFile: (() -> Unit)? = null,
    ) {
        val hasText = text.isNotBlank()
        val actions = ArrayList<Pair<String, () -> Unit>>(5)
        if (hasText) actions += COPY_LABEL to onCopy
        if (canRetry) actions += RETRY_LABEL to onRetry
        if (hasText) actions += SELECT_LABEL to onSelectText
        if (hasText && canSpeak) actions += SPEAK_LABEL to onSpeak
        if (hasText) actions += SHARE_LABEL to onShare
        if (hasText && onSaveFile != null) actions += SAVE_FILE_LABEL to onSaveFile
        // Нечего предложить — не показываем пустой лист.
        if (actions.isEmpty()) return

        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, "Действия с сообщением", null))
            root.addView(
                MarsDialog.pillStack(
                    activity,
                    actions.map { (label, action) ->
                        label to {
                            dialog?.dismiss()
                            action()
                        }
                    },
                )
            )
            emptyList()
        }
        dialog?.window?.setDimAmount(0.8f)
    }

    /**
     * «Выбрать текст»: сырой текст сообщения в отдельном листе, доступный для выделения.
     *
     * Почему отдельный диалог, а не выделение прямо в ленте: ответ ассистента — набор
     * markdown-вьюх (абзацы, блоки кода, таблицы), выделение по ним ходит рвано, а долгое
     * нажатие на карточке занято листом действий. Здесь текст один и копируется целиком.
     */
    fun showSelectableText(activity: Activity, text: String) {
        if (text.isBlank()) return
        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, "Текст сообщения", null))
            root.addView(selectableText(activity, text))
            root.addView(
                MarsDialog.buttonRow(
                    activity,
                    MarsDialog.secondaryButton(activity, "Закрыть") { dialog?.dismiss() },
                ),
            )
            emptyList()
        }
    }

    /**
     * Прокручиваемый блок с выделяемым текстом: длинный ответ не растягивает лист на
     * несколько экранов и не ломает разметку (max-height, изолированный скролл).
     */
    private fun selectableText(context: Context, text: String): View {
        val metrics = context.resources.displayMetrics
        val maxHeight = (metrics.heightPixels * 0.45f).toInt()
        val body = TextView(context).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(MarsTheme.TEXT)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            background = MarsTheme.card(context, MarsTheme.CODE_BG, radiusDp = 12, strokeColor = MarsTheme.BORDER_MUTED)
        }
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                scroll,
                LinearLayout.LayoutParams(-1, maxHeight).apply { bottomMargin = dp(context, 12) },
            )
        }
    }

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)
}
