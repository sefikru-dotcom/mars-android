package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Диалог правки своей реплики в локальном диалоге: поле с текущим текстом и две пилюли —
 * «Отправить заново» / «Отмена».
 *
 * Почему отдельный диалог, а не правка прямо в пузыре: пузырь — не редактор. Он рисуется
 * из состояния презентера и перерисовывается кадрами стрима, поэтому курсор, выделение и
 * клавиатура в нём жили бы до первого кадра и пропадали. Диалог же держит текст, пока
 * человек его набирает, и ничего не меняет в ленте до нажатия.
 *
 * Почему «Отправить заново», а не «Сохранить»: правка реплики — это не переименование
 * сообщения в истории. Старый текст уходит из истории вместе с ответами, которые на него
 * были, и запрос уходит провайдеру заново. Подпись «Сохранить» обещала бы дешёвую
 * косметику, а происходит новый ход — и стоит он токенов.
 *
 * Стилистика — [MarsDialog] и [MarsTheme]: тот же заголовок с рубиновой чертой, то же
 * поле ввода на «код-фоне» и те же пилюли, что в остальных листах. Ни одного нового цвета
 * и ни одного нового компонента здесь нет.
 *
 * Чего здесь НЕТ и почему: подтверждения «точно удалить N сообщений?». Последствия уже
 * написаны под полем до нажатия (см. `hint`), а второй диалог поверх первого только
 * заставлял бы подтверждать то, что человек прочитал секунду назад.
 */
internal object LocalChatEditSheet {

    /** Кнопка отправки: текст уйдёт провайдеру заново, а не просто ляжет в историю. */
    const val APPLY_LABEL = "Отправить заново"

    const val CANCEL_LABEL = "Отмена"

    /** Подсказка внутри пустого поля. */
    const val FIELD_HINT = "Текст сообщения"

    /** Погасшая кнопка: тот же приём, что у [MarsDialog.setResponding]. */
    private const val DISABLED_ALPHA = 0.45f

    /** Поле растёт до восьми строк: дальше оно скроллится внутри себя, а не тащит диалог. */
    private const val FIELD_MIN_LINES = 3
    private const val FIELD_MAX_LINES = 8

    /**
     * Показать диалог правки.
     *
     * @param initialText текущий текст реплики; курсор ставится в конец — человек правит
     *   хвост фразы чаще, чем начало, и попадать пальцем в середину строки ему не приходится.
     * @param hint последствия правки одной строкой (`LocalChatEditPolicy.hint`). Считает
     *   вызывающий: он один знает, сколько строк уйдёт, а лист не должен пересчитывать
     *   ленту — иначе лист и настоящая обрезка разошлись бы. Пустая строка — подсказки нет.
     * @param onApply текст после «Отправить заново». Приходит уже обрезанным по краям и
     *   непустым: кнопка в пустом состоянии не нажимается вовсе
     *   (`LocalChatEditPolicy.canApply`), а повторная проверка внутри обработчика — на
     *   случай, если текст стёрли между нажатием и вызовом.
     */
    fun show(activity: Activity, initialText: String, hint: String, onApply: (String) -> Unit) {
        // Экран мог уже закрываться: диалог поверх умирающего окна — это BadTokenException,
        // то есть падение из-за нажатия, которое человек сделал «на выходе».
        if (activity.isFinishing || activity.isDestroyed) return

        var dialog: Dialog? = null
        var field: EditText? = null

        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, LocalChatEditPolicy.TITLE, null))

            val input = MarsDialog.textField(activity, FIELD_HINT, initialText).apply {
                minLines = FIELD_MIN_LINES
                maxLines = FIELD_MAX_LINES
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                // Enter — перевод строки, а не «отправить»: в реплике бывают абзацы, и
                // случайный Enter на середине мысли не должен отправлять её провайдеру.
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                contentDescription = FIELD_HINT
                // Курсор в конец: `setText` ставит его в начало, и первое же нажатие клавиши
                // дописало бы слово перед репликой, а не после.
                setSelection(text?.length ?: 0)
            }
            field = input
            root.addView(
                input,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 8) },
            )

            if (hint.isNotBlank()) {
                root.addView(
                    hintLabel(activity, hint),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) },
                )
            }

            val cancelButton = MarsDialog.secondaryButton(activity, CANCEL_LABEL) { dialog?.dismiss() }
            val applyButton = MarsDialog.primaryButton(activity, APPLY_LABEL) {
                // Обрезка та же, что в политике: то, что человек видит в поле, и то, что
                // уйдёт в историю, обязано совпадать посимвольно.
                val body = LocalChatEditPolicy.normalize(input.text?.toString().orEmpty())
                if (!LocalChatEditPolicy.canApply(body)) return@primaryButton
                hideKeyboard(activity, input)
                dialog?.dismiss()
                onApply(body)
            }
            // Погасшая кнопка вместо мёртвого нажатия: пустой текст нечего отправлять, а
            // «нажал — и ничего» читается как зависшее приложение.
            setApplyEnabled(applyButton, LocalChatEditPolicy.canApply(initialText))
            // Последствия правки озвучиваются вместе с кнопкой: скринридер читает элемент
            // без контекста поля, и «Отправить заново» без «Будет удалено 4 сообщения»
            // звучало бы как безопасное действие.
            if (hint.isNotBlank()) applyButton.contentDescription = "$APPLY_LABEL. $hint"
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    setApplyEnabled(applyButton, LocalChatEditPolicy.canApply(s?.toString().orEmpty()))
                }
            })

            root.addView(
                MarsDialog.buttonRow(activity, cancelButton, applyButton),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) },
            )
            listOf(input, applyButton)
        }
        dialog?.window?.setDimAmount(0.8f)

        // Клавиатура — сразу: человек пришёл сюда печатать, и лишний тап по полю был бы
        // единственным, что отделяет его от правки. Следующим кадром, потому что до показа
        // окна у поля ещё нет window token, и `showSoftInput` молча ничего не делает.
        val input = field ?: return
        input.post {
            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            runCatching {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** Включить/погасить кнопку отправки; подпись последствий остаётся как была. */
    private fun setApplyEnabled(button: View, enabled: Boolean) {
        if (button.isEnabled == enabled) return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Строка последствий под полем.
     *
     * Приглушённый цвет и мелкий кегль — как у подсказок в [LocalTurnSettingsSheet]: это
     * пояснение, а не предупреждение. Красным его делать нельзя: удаление здесь — штатная
     * часть правки, а не ошибка.
     */
    private fun hintLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(MarsTheme.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.2f)
    }

    private fun hideKeyboard(activity: Activity, anchor: View) {
        runCatching {
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.hideSoftInputFromWindow(anchor.windowToken, 0)
        }
    }

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)
}