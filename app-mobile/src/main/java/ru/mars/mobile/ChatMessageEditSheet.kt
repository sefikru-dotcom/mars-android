package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

/**
 * Диалог правки своей реплики в СЕРВЕРНОМ чате.
 *
 * Зачем отдельный от [LocalChatEditSheet]: там история живёт на телефоне и удаление строк
 * локальное, здесь — на сервере, поэтому подсказка говорит о серверной обрезке и её
 * необратимости. Общей осталась только форма (тот же [MarsDialog]), и сводить два диалога
 * в один значило бы тащить в общий файл знание про две разные модели хранения.
 *
 * Подтверждения не требует: человек уже нажал «Изменить», а удаляемые сообщения он видит
 * прямо в ленте и в подсказке. Отдельный «вы уверены?» на каждую правку — шум.
 */
internal object ChatMessageEditSheet {

    /** Подсказка поля: что именно уйдёт с сервера. */
    fun hint(keepCount: Int): String =
        if (keepCount <= 0) {
            "История будет очищена до начала диалога"
        } else {
            "Сервер оставит $keepCount ${messagesWord(keepCount)} — " +
                "правимая реплика и всё после неё удалятся"
        }

    /** Русское слово для числа сообщений: 1 сообщение, 2 сообщения, 11 сообщений. */
    internal fun messagesWord(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            // 11..14 — исключение: «11 сообщение» по-русски не бывает.
            mod100 in 11..14 -> "сообщений"
            mod10 == 1 -> "сообщение"
            mod10 in 2..4 -> "сообщения"
            else -> "сообщений"
        }
    }

    /**
     * Показывает диалог. [onApply] получает уже нормализованный (без краевых пробелов)
     * текст и вызывается только при непустом значении: пустую реплику отправлять нечего.
     */
    fun show(activity: Activity, initialText: String, keepCount: Int, onApply: (String) -> Unit) {
        // Экран мог уже закрываться: диалог поверх умирающего окна — BadTokenException.
        if (activity.isFinishing || activity.isDestroyed) return

        var dialog: Dialog? = null
        var field: EditText? = null

        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, LocalChatEditPolicy.TITLE, null))
            root.addView(MarsDialog.paragraph(activity, hint(keepCount)))

            val input = MarsDialog.textField(activity, FIELD_HINT, initialText).apply {
                minLines = FIELD_MIN_LINES
                maxLines = FIELD_MAX_LINES
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                // Enter — перевод строки: в реплике бывают абзацы, и Enter на середине
                // мысли не должен отправлять её на сервер.
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                contentDescription = FIELD_HINT
                setSelection(text.length)
            }
            field = input
            root.addView(input)

            val apply = MarsDialog.primaryButton(activity, APPLY_LABEL) {
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@primaryButton
                dialog?.dismiss()
                onApply(text)
            }
            root.addView(
                MarsDialog.buttonRow(
                    activity,
                    MarsDialog.secondaryButton(activity, CANCEL_LABEL) { dialog?.dismiss() },
                    apply,
                ),
            )
            listOf(apply)
        }
        field?.requestFocus()
        dialog?.window?.setDimAmount(0.55f)
    }

    /** Подпись поля ввода. */
    const val FIELD_HINT: String = "Текст реплики"

    const val APPLY_LABEL: String = "Отправить заново"

    const val CANCEL_LABEL: String = "Отмена"

    private const val FIELD_MIN_LINES = 2
    private const val FIELD_MAX_LINES = 8
}
