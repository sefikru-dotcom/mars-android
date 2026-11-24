package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Лист выбора языков перевода: с какого языка и на какой, плюс кнопка «Поменять».
 *
 * Задача листа — только выбор направления. Он не открывает микрофон, не создаёт синтез и
 * ничего не переводит: получает текущее направление, отдаёт выбранное через [onApply] и
 * закрывается. Подключение к голосовому каналу делается снаружи (координатором): второй
 * захват микрофона в проекте сломал бы `LocalVoiceController`, а второй синтез — очередь
 * озвучки.
 *
 * Две колонки, а не один список пар: направление выбирается по частям. Человек меняет
 * либо исходный язык, либо целевой, и общий список «Русский → Английский, Английский →
 * Русский, …» заставлял бы искать нужную пару среди перестановок и не давал бы выразить
 * «с немецкого на турецкий». Кнопка «Поменять» — отдельный жест, потому что это самое
 * частое действие в режиме: человек говорит не на том языке, на котором собирался.
 *
 * Стилистика — [MarsDialog] (тот же лист пилюль, что у остальных листов): новых цветов и
 * размеров здесь нет, пилюля выбранного языка берёт акцентную пару [MarsTheme.ACCENT_BG] /
 * [MarsTheme.ACCENT_TEXT], как последний пункт `pillStack`.
 *
 * Недоступный выбор — тот же язык с двух сторон — не просто гасится: пилюля выключается
 * (`isEnabled = false`), поэтому нажатие не срабатывает и ripple не вспыхивает. Мёртвой
 * кнопки, которая выглядит живой и молчит, здесь нет по построению, а вырожденное
 * направление «с русского на русский» невозможно получить из интерфейса вовсе.
 */
internal object TranslateSheet {

    /** Поменять языки местами — главный жест режима. */
    const val SWAP_LABEL = "Поменять"

    /** Подтвердить выбранное направление и вернуться в режим. */
    const val APPLY_LABEL = "Готово"

    const val SOURCE_TITLE = "С какого языка"
    const val TARGET_TITLE = "На какой язык"

    /**
     * Сколько пилюль в ряду.
     *
     * Два, а не четыре: подписи языков — целые слова («Английский», «Немецкий»), и в один
     * ряд они влезают только на планшете, а на телефоне обрезались бы или уезжали в
     * горизонтальный скролл, которого человек не ждёт в диалоге выбора. Два ряда по две
     * пилюли одинаковой ширины читаются как сетка и попадаются пальцем без прицела.
     */
    private const val PER_ROW = 2

    /** Насколько гасить недоступную пилюлю: видно, что она есть, но не активна. */
    private const val DISABLED_ALPHA = 0.35f

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * Показать лист. [current] — направление, с которым режим работает сейчас;
     * [onApply] вызывается только с допустимым направлением (разные языки) и только после
     * нажатия «Готово» — закрытие листа свайпом/назад ничего не меняет.
     *
     * Вырожденное [current] (одинаковые языки — например, из сохранённого состояния)
     * заменяется на [TranslatePolicy.defaultDirection]: лист не имеет права открыться в
     * состоянии, из которого нельзя выйти подтверждением.
     */
    fun show(
        activity: Activity,
        current: TranslateDirection,
        onApply: (TranslateDirection) -> Unit,
    ) {
        var from = current.from
        var to = current.to
        if (current.isSame) {
            val fallback = TranslatePolicy.defaultDirection()
            from = fallback.from
            to = fallback.to
        }

        val sourcePills = LinkedHashMap<String, TextView>(TranslatePolicy.LANGUAGES.size)
        val targetPills = LinkedHashMap<String, TextView>(TranslatePolicy.LANGUAGES.size)

        // Перерисовка выбора: подсветка, доступность и подсказка меняются на месте, без
        // пересоздания диалога. Пересборка листа на каждый тап мигала бы затемнением и
        // сбрасывала прокрутку — на коротком листе это выглядит как «экран дёрнулся».
        var refresh: () -> Unit = {}

        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, TranslatePolicy.TITLE, null))

            val hint = TextView(activity).apply {
                setTextColor(MarsTheme.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.15f)
            }
            root.addView(
                hint,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = MarsTheme.dp(activity, 14)
                },
            )

            root.addView(MarsDialog.caption(activity, SOURCE_TITLE))
            root.addView(
                languageGrid(activity, sourcePills) { language ->
                    from = language
                    refresh()
                },
            )
            root.addView(MarsDialog.caption(activity, TARGET_TITLE))
            root.addView(
                languageGrid(activity, targetPills) { language ->
                    to = language
                    refresh()
                },
            )

            val swapButton = MarsDialog.secondaryButton(activity, SWAP_LABEL) {
                val previousFrom = from
                from = to
                to = previousFrom
                refresh()
            }
            val applyButton = MarsDialog.primaryButton(activity, APPLY_LABEL) {
                val chosen = TranslateDirection(from = from, to = to)
                // Проверка обязательна, даже если интерфейс не даёт собрать такое
                // направление: колбэк уходит наружу, и наружу не должно уйти «переведи
                // русский на русский».
                if (!chosen.isSame) {
                    dialog?.dismiss()
                    onApply(chosen)
                }
            }
            root.addView(MarsDialog.buttonRow(activity, swapButton, applyButton))

            refresh = {
                val direction = TranslateDirection(from = from, to = to)
                TranslatePolicy.LANGUAGES.forEach { language ->
                    paint(
                        activity,
                        sourcePills[language.code],
                        selected = language.code == from.code,
                        // Исходный язык не может совпасть с целевым: выбирая его здесь,
                        // человек фактически просит поменять направление местами, и это
                        // делается кнопкой «Поменять», а не вырожденным состоянием.
                        available = language.code != to.code,
                    )
                    paint(
                        activity,
                        targetPills[language.code],
                        selected = language.code == to.code,
                        available = language.code != from.code,
                    )
                }
                hint.text = TranslatePolicy.pendingHint(direction)
                applyButton.isEnabled = !direction.isSame
                applyButton.alpha = if (direction.isSame) DISABLED_ALPHA else 1f
            }
            refresh()

            emptyList()
        }
        dialog?.window?.setDimAmount(0.55f)
    }

    /**
     * Сетка пилюль одного языка-списка: [PER_ROW] в ряд, одинаковой ширины.
     *
     * Пилюли складываются в [pills] по коду, чтобы [paint] мог перекрасить их при смене
     * выбора, не пересобирая лист. Карта `LinkedHashMap` — чтобы порядок обхода совпадал
     * с [TranslatePolicy.LANGUAGES], когда он понадобится (например, для автотеста).
     */
    private fun languageGrid(
        context: Context,
        pills: MutableMap<String, TextView>,
        onPick: (TranslateLanguage) -> Unit,
    ): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        TranslatePolicy.LANGUAGES.chunked(PER_ROW).forEachIndexed { rowIndex, rowLanguages ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowLanguages.forEachIndexed { index, language ->
                val pill = MarsDialog.pill(
                    context,
                    language.label,
                    onClick = { onPick(language) },
                )
                // Самоназвание — только для чтения с экрана: в подписи оно занимает
                // место, а TalkBack проговаривает «Deutsch» там, где «Немецкий» ничего
                // не говорит человеку, который ищет свой язык.
                if (language.nativeLabel != language.label) {
                    pill.contentDescription = "${language.label}, ${language.nativeLabel}"
                }
                pills[language.code] = pill
                row.addView(
                    pill,
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        if (index > 0) marginStart = MarsTheme.dp(context, 8)
                    },
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    if (rowIndex > 0) topMargin = MarsTheme.dp(context, 8)
                },
            )
        }
        return MarsDialog.block(context, column, bottomDp = 12)
    }

    /**
     * Состояние одной пилюли: выбран / доступен.
     *
     * Выключение через `isEnabled` (а не через пустой обработчик) — единственный способ
     * обойтись без мёртвого нажатия: выключенная вьюха не доставляет клик и не показывает
     * ripple, поэтому человек не получает отклика на действие, которого не будет. `alpha`
     * добавляет видимую причину: пилюля есть в списке, но выбрать её сейчас нельзя.
     */
    private fun paint(
        context: Context,
        pill: TextView?,
        selected: Boolean,
        available: Boolean,
    ) {
        if (pill == null) return
        val background = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE
        val stroke = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED
        val textColor = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM
        // Фон перекрашивается через тот же [MarsTheme.pill], что и при создании: скругление
        // и толщина обводки остаются прежними, меняется только пара цветов.
        pill.background = MarsTheme.pill(context, background, stroke)
        pill.setTextColor(textColor)
        pill.isEnabled = available
        pill.isClickable = available
        pill.alpha = if (available) 1f else DISABLED_ALPHA
    }
}