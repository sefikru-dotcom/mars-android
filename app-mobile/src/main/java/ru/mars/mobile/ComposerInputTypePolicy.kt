package ru.mars.mobile

/**
 * Политика поля ввода композера — чистая JVM (без android-импортов), чтобы флаги и геометрия
 * многострочного поля проверялись обычными юнит-тестами, а не на устройстве.
 *
 * Зачем политика вообще: поле собирается вручную в [ChatScreenScaffold], и до неё у него
 * не было `inputType` вовсе — клавиатура не знала ни про капитализацию, ни про многострочность,
 * а `maxLines` молча сбрасывался в MAX_VALUE вызовом `setSingleLine(false)` (ловушка TextView:
 * `applySingleLine(..., changeMaxLines = true)` внутри `setSingleLine(false)` вызывает
 * `setMaxLines(Integer.MAX_VALUE)`). Итог на телефоне: первая буква строчная, а длинный текст
 * растягивал поле до бесконечности и вылезал за капсулу композера на панель чипов.
 *
 * Контракт:
 *
 * 1. [INPUT_TYPE] — `TYPE_CLASS_TEXT` + `TYPE_TEXT_FLAG_CAP_SENTENCES` + `TYPE_TEXT_FLAG_MULTI_LINE`:
 *    заглавная в начале ПРЕДЛОЖЕНИЯ (а не каждого слова и не КАЖДОЙ БУКВЫ), многострочный
 *    key listener с переносом по словам.
 * 2. [IME_OPTIONS] — `IME_ACTION_SEND` (кнопка отправки на клавиатуре остаётся) +
 *    `IME_FLAG_NO_EXTRACT_UI` (IME не открывает полноэкранный «extract» редактор, где текст
 *    уезжает из капсулы в отдельное окно). `IME_FLAG_NO_ENTER_ACTION` НЕ ставится: он запрещает
 *    клавиатуре подменять Enter действием и убивает кнопку отправки у части IME.
 * 3. [maxLines] / [maxHeightPx] — поле растёт до [ComposerLayoutPresenter.MAX_INPUT_LINES] строк,
 *    дальше скроллится внутри себя; `maxHeightPx` даёт пиксельный потолок, который держит капсулу
 *    в рамках даже если платформа проигнорирует `maxLines`.
 * 4. [isSendAction] — из IME-действий отправкой считается только явный SEND; всё остальное
 *    (в т.ч. IME_NULL от Enter) возвращается полю, чтобы перевод строки вставлялся как обычно.
 *
 * Числовые значения флагов — платформенные константы `android.text.InputType` /
 * `android.view.inputmethod.EditorInfo`; они зафиксированы здесь литералами, потому что политика
 * не имеет права тянуть android-классы (иначе она не тестируется на JVM).
 */
object ComposerInputTypePolicy {

    // --- android.text.InputType -----------------------------------------------------------------

    /** Маска класса ввода (`TYPE_MASK_CLASS`). */
    const val TYPE_MASK_CLASS = 0x0000000F

    /** Текстовый класс ввода: обычная клавиатура, подсказки и автозамена доступны. */
    const val TYPE_CLASS_TEXT = 0x00000001

    /** Заглавная в начале предложения — единственная капитализация, которую мы включаем. */
    const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000

    /** Многострочный ввод: Enter — перевод строки, длинный текст переносится по словам. */
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000

    /** Заглавная в каждом слове — НЕ используется (см. тесты: ломает обычный текст). */
    const val TYPE_TEXT_FLAG_CAP_WORDS = 0x00002000

    /** ЗАГЛАВНЫЕ НА КАЖДУЮ БУКВУ — НЕ используется (это Caps Lock, а не капитализация). */
    const val TYPE_TEXT_FLAG_CAP_CHARACTERS = 0x00001000

    /** Отключение подсказок/автозамены — НЕ используется: в чате они нужны. */
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x00080000

    // --- android.view.inputmethod.EditorInfo ----------------------------------------------------

    /** IME-действие «Отправить» (значение `EditorInfo.IME_ACTION_SEND`). */
    const val IME_ACTION_SEND = 0x00000004

    /** Не открывать полноэкранный extract-редактор: текст остаётся внутри капсулы композера. */
    const val IME_FLAG_NO_EXTRACT_UI = 0x10000000

    /** Запрет подмены Enter действием — намеренно НЕ выставляется (см. шапку файла). */
    const val IME_FLAG_NO_ENTER_ACTION = 0x40000000

    /** Готовый `inputType` поля ввода композера. */
    const val INPUT_TYPE: Int =
        TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES or TYPE_TEXT_FLAG_MULTI_LINE

    /** Готовый `imeOptions` поля ввода композера. */
    const val IME_OPTIONS: Int = IME_ACTION_SEND or IME_FLAG_NO_EXTRACT_UI

    /**
     * Предел строк поля: пять строк (единственный источник истины — [ComposerLayoutPresenter],
     * чтобы чипы/презентер и поле не разъехались).
     */
    fun maxLines(): Int = ComposerLayoutPresenter.MAX_INPUT_LINES

    /**
     * Пиксельный потолок высоты поля: [lines] строк по [lineHeightPx] плюс вертикальные отступы
     * поля ([verticalPaddingPx] = paddingTop + paddingBottom). Так капсула композера не растёт
     * выше пяти строк и не наезжает на нижнюю панель чипов.
     *
     * Метрики шрифта до первой раскладки могут быть нулевыми — в этом случае потолок не навязан
     * (`Int.MAX_VALUE`): лучше оставить ограничение за `maxLines`, чем схлопнуть поле в ноль.
     */
    fun maxHeightPx(
        lineHeightPx: Int,
        verticalPaddingPx: Int,
        lines: Int = maxLines(),
    ): Int {
        if (lineHeightPx <= 0 || lines <= 0) return Int.MAX_VALUE
        return lineHeightPx * lines + verticalPaddingPx.coerceAtLeast(0)
    }

    /**
     * IME-действие считается отправкой только при явном [IME_ACTION_SEND].
     * Любое другое действие (IME_NULL/IME_ACTION_NONE/DONE/GO/SEARCH…) поле обрабатывает само —
     * так Enter в многострочном режиме продолжает вставлять перевод строки.
     */
    fun isSendAction(actionId: Int): Boolean = actionId == IME_ACTION_SEND
}
