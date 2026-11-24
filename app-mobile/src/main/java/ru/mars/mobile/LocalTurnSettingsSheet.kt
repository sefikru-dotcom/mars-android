package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.local.LocalTurnSettings

/**
 * Шторка «Параметры хода» для локального чата: сэмплирование, потолок токенов и
 * стоп-последовательности.
 *
 * Почему шторка, а не инлайн-панель в композере: параметров девять плюс стоп-лист, в
 * строке ввода они занимали бы экран постоянно, а нужны раз в десять ходов. Шторка
 * показывает их поверх ленты и не сдвигает ни композер, ни ленту.
 *
 * Компонент самодостаточен: снаружи нужны только [Activity], текущие настройки, окно
 * модели (для потолка `max_tokens`) и два колбэка. Внутрь проекта он не лезет — вся
 * арифметика в [LocalTurnSettingsPolicy], оформление в [MarsTheme]/[MarsDialog], поэтому
 * шторку можно открыть из любого экрана, а не только из локального чата.
 *
 * ВАЖНО про момент применения: шторка НЕ меняет настройки на лету. Правки живут в полях
 * ввода до нажатия «Сохранить», и только тогда уходят одним [LocalTurnSettings] в
 * [onSave]. Это осознанно: смена `temperature` или `max_tokens` посреди стрима либо
 * оборвала бы уже идущий ответ, либо подменила его параметры на середине — и то и другое
 * читается как «модель сошла с ума». Ответственность вызывающего — не отправлять новые
 * настройки в текущий запрос, а применить их к следующему ходу (см. KDoc `onSave`).
 *
 * «Пустое поле» — не ноль: это «провайдер решает сам», поле не уходит в запрос вовсе.
 * Так человек возвращает параметр провайдеру, не зная его дефолта, — отсюда и подпись
 * «провайдер сам» рядом с каждым полем (тап по ней очищает поле).
 */
internal object LocalTurnSettingsSheet {

    const val TITLE = "Параметры хода"

    /** Одна строка пояснения под заголовком: почему поля можно оставить пустыми. */
    const val SUBTITLE = "Пустое поле — провайдер решает сам"

    const val STOPS_TITLE = "Стоп-последовательности"
    const val ADD_STOP_LABEL = "Добавить"
    const val ADD_STOP_HINT = "Новая последовательность"
    const val RESET_LABEL = "Сбросить к модели"
    const val SAVE_LABEL = "Сохранить"

    /** Потолок высоты шторки: длинный список не должен закрывать ленту целиком. */
    private const val MAX_HEIGHT_RATIO = 0.7f

    /**
     * Показать шторку.
     *
     * @param settings текущее состояние хода; шторка его не меняет, а строит из него поля.
     * @param modelMaxTokens окно/потолок модели из каталога, если провайдер его сообщил:
     *   ограничивает `max_tokens`, чтобы человек не ввёл число, которое площадка обрежет
     *   или отклонит. `null` — каталог молчит, показываем широкий диапазон провайдера.
     * @param onSave вызывается один раз по «Сохранить» после успешной проверки всех полей.
     *   Шторка к этому моменту уже закрыта, вызывающий применяет настройки к СЛЕДУЮЩЕМУ
     *   ходу: менять параметры идущего стрима нельзя — ответ уже начался с другими.
     * @param onReset вызывается по «Сбросить к модели»: все ручные значения надо убрать из
     *   хранения (поля при этом очищены, стоп-лист пуст). Отдельный колбэк, а не
     *   `onSave(reset)`, потому что сброс — это «забудь мои правки», а не «сохрани нули».
     */
    fun show(
        activity: Activity,
        settings: LocalTurnSettings,
        modelMaxTokens: Int?,
        onSave: (LocalTurnSettings) -> Unit,
        onReset: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val rows = LocalTurnSettingsPolicy.rows(settings, modelMaxTokens)
        val fields = ArrayList<ParamField>(rows.size)
        // Рабочая копия стоп-листа: чипы добавляются и снимаются до «Сохранить», а
        // исходные настройки остаются нетронутыми — иначе «отмена» ничего не отменяла бы.
        val stops = ArrayList<String>(settings.stop)

        var dialog: Dialog? = null
        var chipsRow: LinearLayout? = null
        var addInput: EditText? = null
        var addButton: TextView? = null
        var addNote: TextView? = null

        fun renderStops() {
            val row = chipsRow ?: return
            row.removeAllViews()
            stops.forEach { sequence ->
                row.addView(
                    stopChip(activity, sequence) {
                        stops.remove(sequence)
                        setNote(addNote, null)
                        renderStops()
                    },
                    LinearLayout.LayoutParams(-2, dp(activity, 30)).apply {
                        marginEnd = dp(activity, 6)
                    },
                )
            }
            // Предел стоп-листа — не наша прихоть: площадки отклоняют запрос целиком,
            // поэтому «Добавить» гаснет до того, как человек потеряет ход.
            val full = stops.size >= LocalTurnSettingsPolicy.MAX_STOPS
            addButton?.let {
                it.isEnabled = !full
                it.alpha = if (full) 0.45f else 1f
            }
        }

        fun commitStop() {
            val input = addInput ?: return
            val raw = input.text?.toString().orEmpty()
            if (raw.isBlank()) return
            val next = LocalTurnSettingsPolicy.addStop(stops, raw)
            val full = stops.size >= LocalTurnSettingsPolicy.MAX_STOPS
            if (next.size == stops.size) {
                setNote(
                    addNote,
                    if (full) "Больше ${LocalTurnSettingsPolicy.MAX_STOPS} провайдер не примет"
                    else "Такая уже есть",
                )
                return
            }
            stops.clear()
            stops.addAll(next)
            input.setText("")
            setNote(addNote, null)
            renderStops()
        }

        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, TITLE, null))
            root.addView(
                subtitle(activity),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 14) },
            )

            rows.forEachIndexed { index, row ->
                val input = numberField(activity, row, last = index == rows.lastIndex)
                val error = errorLabel(activity)
                val badge = MarsDialog.pill(
                    activity,
                    LocalTurnSettingsPolicy.badge(row) ?: LocalTurnSettingsPolicy.PROVIDER_SELF,
                    bg = MarsTheme.SURFACE_SUBTLE,
                    fg = MarsTheme.TEXT_MUTED,
                    border = MarsTheme.BORDER_MUTED,
                    // Тап по пилюле — быстрый способ вернуть параметр провайдеру без
                    // выделения и стирания числа: стереть «0,70» пальцем на телефоне
                    // дольше, чем один тап, и легко оставить «0,7» вместо пустого поля.
                    onClick = { input.setText("") },
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = (activity.resources.displayMetrics.widthPixels * 0.35f).toInt()
                }

                fun showBadge(value: Double?) {
                    val state = LocalTurnSettingsPolicy.badge(row.copy(value = value))
                        ?: LocalTurnSettingsPolicy.PROVIDER_SELF
                    badge.text = state
                    // Подпись для TalkBack едет вместе с видимым текстом: иначе он
                    // зачитывал бы состояние, которое уже сменилось.
                    badge.contentDescription =
                        "${row.label}: $state. Сбросить — решает провайдер"
                }
                showBadge(row.value)

                val field = ParamField(row, input, badge, error)
                fields += field
                // Пилюля живая: человек видит, что введённое число принято, ещё до
                // «Сохранить». Мусор посреди набора подпись не трогает и ошибку не
                // кричит — иначе поле ругалось бы на каждой нажатой клавише.
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        error.visibility = View.GONE
                        val raw = s?.toString().orEmpty()
                        val parsed = LocalTurnSettingsPolicy.validate(row, raw).getOrNull()
                        if (raw.isBlank() || parsed != null) showBadge(parsed)
                    }
                })
                root.addView(paramBlock(activity, field))
            }

            root.addView(
                divider(activity),
                LinearLayout.LayoutParams(-1, dp(activity, 1)).apply {
                    topMargin = dp(activity, 2)
                    bottomMargin = dp(activity, 14)
                },
            )
            root.addView(MarsDialog.caption(activity, STOPS_TITLE))

            val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            chipsRow = chips
            root.addView(
                HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips, LinearLayout.LayoutParams(-2, -2))
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 8) },
            )

            val input = EditText(activity).apply {
                hint = ADD_STOP_HINT
                setHintTextColor(MarsTheme.TEXT_MUTED)
                setTextColor(MarsTheme.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                background = MarsTheme.card(activity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.BORDER_MUTED)
                val padH = dp(activity, 12)
                setPadding(padH, dp(activity, 10), padH, dp(activity, 10))
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        commitStop()
                        true
                    } else {
                        false
                    }
                }
            }
            addInput = input
            val addPill = MarsDialog.pill(activity, ADD_STOP_LABEL) { commitStop() }
            addButton = addPill
            root.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        addPill,
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
                    )
                },
                LinearLayout.LayoutParams(-1, -2),
            )

            val note = noteLabel(activity)
            addNote = note
            root.addView(
                note,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(activity, 6)
                    bottomMargin = dp(activity, 10)
                },
            )

            val reset = MarsDialog.secondaryButton(activity, RESET_LABEL) {
                // Сброс терминален: форму чистим, шторку закрываем, ручные значения
                // убирает вызывающий — иначе «сброшенное» всплыло бы из хранения при
                // следующем открытии шторки.
                fields.forEach { it.input.setText("") }
                stops.clear()
                renderStops()
                setNote(addNote, null)
                dialog?.dismiss()
                onReset()
            }
            val save = MarsDialog.primaryButton(activity, SAVE_LABEL) {
                var next = settings
                var firstBad: EditText? = null
                fields.forEach { field ->
                    val parsed = LocalTurnSettingsPolicy.validate(
                        field.row,
                        field.input.text?.toString().orEmpty(),
                    )
                    parsed.fold(
                        onSuccess = { value ->
                            field.error.visibility = View.GONE
                            next = LocalTurnSettingsPolicy.apply(field.row, value, next)
                        },
                        onFailure = { failure ->
                            field.error.text = failure.message ?: "Неверное значение"
                            field.error.visibility = View.VISIBLE
                            if (firstBad == null) firstBad = field.input
                        },
                    )
                }
                val bad = firstBad
                if (bad != null) {
                    // Не закрываем: закрытие с ошибкой выглядело бы как «сохранилось»,
                    // а человек терял бы весь ввод из-за одного опечатанного числа.
                    bad.requestFocus()
                    scrollIntoView(bad)
                    return@primaryButton
                }
                // Недописанную последовательность не теряем: человек мог набрать её в
                // поле добавления и сразу нажать «Сохранить», не заметив пилюлю рядом.
                val pending = addInput?.text?.toString().orEmpty()
                if (pending.isNotBlank()) {
                    val withPending = LocalTurnSettingsPolicy.addStop(stops, pending)
                    if (withPending.size != stops.size) {
                        stops.clear()
                        stops.addAll(withPending)
                    }
                }
                val applied = LocalTurnSettingsPolicy.withStop(next, stops.toList())
                hideKeyboard(activity, root)
                dialog?.dismiss()
                onSave(applied)
            }
            root.addView(
                MarsDialog.buttonRow(activity, reset, save),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 6) },
            )
            emptyList()
        }

        renderStops()
        capHeight(activity, dialog)
    }

    // --- строки параметров -------------------------------------------------------------------

    /** Один параметр: подпись, поле с пилюлей состояния, короткая подсказка, место под ошибку. */
    private fun paramBlock(activity: Activity, field: ParamField): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(activity).apply {
                    text = field.row.label
                    setTextColor(MarsTheme.TEXT_EM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 6) },
            )
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(field.input, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        field.badge,
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
                    )
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(
                TextView(activity).apply {
                    text = field.row.hint
                    setTextColor(MarsTheme.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 4) },
            )
            addView(
                field.error,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 4) },
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 14) }
        }

    private fun numberField(
        activity: Activity,
        row: LocalTurnSettingsPolicy.ParamRow,
        last: Boolean,
    ): EditText = EditText(activity).apply {
        setText(LocalTurnSettingsPolicy.valueText(row))
        setTextColor(MarsTheme.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val integer = LocalTurnSettingsPolicy.isInteger(row)
        // number / numberDecimal: клавиатура обязана быть цифровой, а не буквенной.
        inputType = if (integer) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        } else {
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        // А фильтр ставим свой, поверх: тот, что Android выводит из `numberDecimal`,
        // знает только разделитель текущей локали, и предзаполненное «0,7» на
        // английской раскладке превращалось бы в «07» на первом же нажатии. Здесь
        // пропускаются обе запятые — ровно то, что принимает [LocalTurnSettingsPolicy.validate].
        // Целым параметрам дробь не нужна: 2,5 в top_k провайдер молча усечёт.
        keyListener = DigitsKeyListener.getInstance(
            if (integer) "0123456789-" else "0123456789,.-",
        )
        maxLines = 1
        isSingleLine = true
        contentDescription = row.label
        // Синтетического свойства нет: у TextView публичен только сеттер.
        setSelectAllOnFocus(true)
        imeOptions = if (last) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        background = MarsTheme.card(activity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.BORDER_MUTED)
        val padH = dp(activity, 12)
        setPadding(padH, dp(activity, 10), padH, dp(activity, 10))
    }

    /** Чип стоп-последовательности: текст моноширинно, крестик — vector `ic_close`. */
    private fun stopChip(context: Context, sequence: String, onRemove: () -> Unit): TextView =
        TextView(context).apply {
            text = sequence
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(MarsTheme.TEXT_EM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Длинная последовательность не должна съедать всю строку: половина экрана.
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.5f).toInt()
            setPadding(dp(context, 10), 0, dp(context, 8), 0)
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(context, MarsTheme.SURFACE_SUBTLE, strokeColor = MarsTheme.BORDER_MUTED)
            val close = ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()?.apply {
                setTint(MarsTheme.TEXT_MUTED)
                val size = dp(context, 12)
                setBounds(0, 0, size, size)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, close, null)
            compoundDrawablePadding = dp(context, 6)
            contentDescription = "Убрать стоп-последовательность $sequence"
            isClickable = true
            isFocusable = true
            setOnClickListener { onRemove() }
        }

    private fun subtitle(context: Context): TextView = TextView(context).apply {
        text = SUBTITLE
        setTextColor(MarsTheme.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun errorLabel(context: Context): TextView = TextView(context).apply {
        setTextColor(MarsTheme.ACCENT_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        visibility = View.GONE
        // Ошибку под полем озвучивает TalkBack при появлении: иначе «Сохранить» просто
        // не закрывает шторку, и непонятно, что не так.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** Замечание под полем добавления стоп-последовательности: дубль или предел списка. */
    private fun noteLabel(context: Context): TextView = TextView(context).apply {
        setTextColor(MarsTheme.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(MarsTheme.DIVIDER)
    }

    private fun setNote(note: TextView?, message: String?) {
        if (note == null) return
        if (message.isNullOrBlank()) {
            note.text = ""
            note.visibility = View.GONE
        } else {
            note.text = message
            note.visibility = View.VISIBLE
        }
    }

    // --- окно ---------------------------------------------------------------------------------

    /**
     * Высота шторки не больше [MAX_HEIGHT_RATIO] экрана.
     *
     * [MarsDialog] уже кладёт содержимое в `ScrollView` с высотой по контенту; длинный
     * список параметров на маленьком экране упирался бы в системные панели, а кнопки
     * уезжали бы за край. Меряем фактическую высоту после раскладки и урезаем окно —
     * так короткая шторка не растягивается на 70% экрана пустотой.
     */
    private fun capHeight(activity: Activity, dialog: Dialog?) {
        val cap = (activity.resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        val window = dialog?.window ?: return
        val width = window.attributes?.width ?: 0
        // Высота до первой раскладки равна нулю, а окно с ней ещё не сравнимо: пробуем
        // несколько кадров, иначе на медленном старте шторка остаётся без потолка.
        window.decorView.post(object : Runnable {
            private var attempts = 0

            override fun run() {
                val current = dialog.window ?: return
                val height = current.decorView.height
                if (height <= 0 && attempts++ < 3) {
                    current.decorView.post(this)
                    return
                }
                if (height > cap) current.setLayout(width, cap)
            }
        })
    }

    /**
     * Долистать до поля с ошибкой.
     *
     * `requestFocus` сам по себе не скроллит: `ScrollView` внутри диалога не реализует
     * `requestChildRectangleOnScreen`, поэтому позицию считаем вручную — от окна к
     * содержимому скролла.
     */
    private fun scrollIntoView(anchor: View) {
        var node: View? = anchor
        var scroll: ScrollView? = null
        while (node != null) {
            if (node is ScrollView) {
                scroll = node
                break
            }
            node = node.parent as? View
        }
        val container = scroll ?: return
        val anchorAt = IntArray(2)
        val containerAt = IntArray(2)
        anchor.getLocationInWindow(anchorAt)
        container.getLocationInWindow(containerAt)
        val y = container.scrollY + (anchorAt[1] - containerAt[1])
        container.post {
            container.smoothScrollTo(0, (y - dp(container.context, 24)).coerceAtLeast(0))
        }
    }

    private fun hideKeyboard(activity: Activity, anchor: View) {
        runCatching {
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.hideSoftInputFromWindow(anchor.windowToken, 0)
        }
    }

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)

    /** Поле параметра вместе с его подписью состояния и местом под ошибку. */
    private class ParamField(
        val row: LocalTurnSettingsPolicy.ParamRow,
        val input: EditText,
        val badge: TextView,
        val error: TextView,
    )
}
