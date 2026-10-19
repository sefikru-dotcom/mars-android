package ru.mars.tv

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * D-pad-разблокировка текстовых полей на ТВ — перенос РАБОТАЮЩЕГО паттерна
 * [ru.mars.tv.TvMusicActivity] (поиск музыки), а не новая механика.
 *
 * Проблема ровно та же, что была у поиска: Android `EditText` САМ потребляет стрелки
 * (UP/DOWN — переход по строкам, LEFT/RIGHT — курсор), поэтому `nextFocusDown` поля
 * не срабатывает никогда и фокус залипает в поле — до кнопок пультом не добраться.
 * Второй элемент паттерна — `setShowSoftInputOnFocus(false)`: клавиатура не всплывает
 * на фокус, а открывается только явным нажатием (DPAD_CENTER/клик), поэтому стрелки
 * остаются навигацией, пока пользователь действительно не начал печатать.
 *
 * Правило музыки сохранено дословно: **при открытой клавиатуре не вмешиваемся** —
 * стрелки принадлежат IME.
 */
object TvDpadTextInput {

    /**
     * Клавиатура по явному нажатию вместо авто-показа на фокус (музыкальный поиск,
     * строка 285: `setShowSoftInputOnFocus(false)` + `setOnClickListener { showIme() }`).
     */
    fun openImeOnClick(editText: EditText, show: (EditText) -> Unit = ::showIme) {
        editText.setShowSoftInputOnFocus(false)
        editText.setOnClickListener { show(editText) }
    }

    fun showIme(editText: EditText) {
        val ime = editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        ime.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme(editText: EditText) {
        val ime = editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        ime.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    /**
     * Перехват стрелок: каждая из [up]/[down]/[left]/[right] возвращает `true`, если
     * фокус удалось увести (тогда клавиша считается обработанной). `false` — событие
     * уходит дальше в поле (курсор), ровно как в музыкальном поиске.
     *
     * @param imeVisible при открытой клавиатуре стрелки не перехватываются вовсе.
     */
    fun escapeArrows(
        editText: EditText,
        imeVisible: () -> Boolean = { false },
        up: () -> Boolean = { false },
        down: () -> Boolean = { false },
        left: () -> Boolean = { false },
        right: () -> Boolean = { false },
    ) {
        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else if (imeVisible()) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> up()
                KeyEvent.KEYCODE_DPAD_DOWN -> down()
                KeyEvent.KEYCODE_DPAD_LEFT -> left()
                KeyEvent.KEYCODE_DPAD_RIGHT -> right()
                else -> false
            }
        }
    }

    /** Перевод фокуса на [view] с ответом «получилось» — короткая форма для целей. */
    fun focus(view: View?): Boolean = view?.requestFocus() ?: false

    /**
     * Видна ли экранная клавиатура для [view]: разница между высотой root-View и
     * видимой области окна ([View.getWindowVisibleDisplayFrame]) — тот же расчёт, что
     * в `globalLayoutListener` музыки, но без хранения флага: годится и для полей
     * внутри карточек, у которых нет доступа к Activity.
     */
    fun isImeVisible(view: View): Boolean {
        val rect = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(rect)
        val height = view.rootView?.height ?: 0
        if (height <= 0) return false
        return height - rect.bottom > height * 0.15f
    }
}
