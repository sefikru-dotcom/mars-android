package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile

/**
 * Выбор модели и уровня размышлений для локального чата.
 *
 * Каталог — `GET {base}/models` через [LocalChatClient.catalog]: он единственный отдаёт
 * размер окна контекста и признак поддержки `reasoning_effort`. Имена моделей без
 * метаданных не годятся: без окна пилюля контекста не может честно посчитать процент,
 * а переключатель reasoning на модели без поддержки — мёртвая кнопка.
 *
 * Выбор ничего не пишет в профиль сам: он отдаёт [LocalModelInfo] вызывающему, а тот
  * меняет активный профиль через презентер — так у экрана остаётся один путь записи.
  *
  * Список разбит на СЕКЦИИ по семейству модели ([LocalModelPickerPolicy.sections]): у
  * одного профиля PlusVibe 84 строки подряд, и плоская простыня не даёт понять, где
  * кончились Claude и начались Qwen. Заголовок секции — «Claude», «GPT», «Gemini»…;
  * при нескольких провайдерах в каталоге — «PlusVibe · Claude».
  *
 * Все размеры — в dp и с ограничением по ширине: каталог провайдера содержит длинные
 * идентификаторы (`anthropic/claude-sonnet-4.5-20250929`), которые обязаны усекаться,
 * а не растягивать лист шире экрана.
 */
internal object LocalChatModelPicker {

    /** Предел строк в списке: каталог PlusVibe — 85 моделей, больше не рисуем разом. */
    private const val MAX_ROWS = 120

    /** Один уровень `reasoning_effort`; `null` — «Авто» (поле не отправляется). */
    private data class Level(val effort: String?, val label: String, val hint: String)

    /**
     * Лестница уровней. Набор зафиксирован контрактом (§2): провайдер принимает
     * `low`/`medium`/`high`, а «Авто» означает «поле не отправлять».
     *
     * Подпись чипа — короткая (само значение провайдера), русское пояснение живёт в листе
     * выбора: чип в шапке не резиновый, а «medium · средний» на POCO F5 уже усекается.
     */
    private val LEVELS = listOf(
        Level(null, "Авто", "уровень выбирает провайдер"),
        Level("low", "low", "низкий"),
        Level("medium", "medium", "средний"),
        Level("high", "high", "высокий"),
    )

    /** Подпись уровня для чипа в шапке: неизвестное значение читается как «Авто». */
    fun label(effort: String?): String {
        val normalized = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return LEVELS.firstOrNull { it.effort == normalized }?.label ?: LEVELS.first().label
    }

    /**
     * Лист выбора модели: поиск, окно контекста и метка reasoning по каждой строке.
     *
     * [scope] — область экрана: каталог тянется по сети, и запрос обязан умереть вместе
     * с Activity, а не жить до конца процесса.
     */
    fun show(
        activity: Activity,
        scope: CoroutineScope,
        client: LocalChatClient,
        profile: LocalProviderProfile,
        apiKey: String,
        current: String?,
        onPick: (LocalModelInfo) -> Unit,
    ) {
        var dialog: Dialog? = null
        var catalog: List<LocalModelInfo> = emptyList()

        val status = TextView(activity).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            text = "Загружаю каталог моделей…"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        val search = EditText(activity).apply {
            hint = "Поиск модели"
            setHintTextColor(MarsTheme.TEXT_MUTED)
            setTextColor(MarsTheme.TEXT)
            textSize = 14f
            setSingleLine(true)
            background = MarsTheme.card(
                activity,
                MarsTheme.CODE_BG,
                radiusDp = 10,
                strokeColor = MarsTheme.BORDER_MUTED,
            )
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            val icon = ContextCompat.getDrawable(activity, R.drawable.ic_search)?.mutate()
            icon?.setTint(MarsTheme.TEXT_MUTED)
            val iconSize = dp(activity, 16)
            icon?.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(activity, 8)
            // Поиск до загрузки каталога бессмысленен: поля нет, пока нечего искать.
            visibility = View.GONE
        }

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // Раскрытые группы. `null` — ещё не решали: по умолчанию раскрыта только та,
        // в которой лежит текущая модель, остальные свёрнуты в одну строку заголовка.
        // Хранится ключами секций (заголовок), а не индексами: после поиска индексы
        // другие, и свёрнутость уехала бы на чужую группу.
        var expanded: MutableSet<String>? = null

        fun rebuild(query: String, keepScroll: Boolean = false) {
            val scroll = scrollParent(list)
            val scrollY = if (keepScroll) scroll?.scrollY ?: 0 else 0
            list.removeAllViews()
            if (catalog.isEmpty()) return
            // Эмбеддинги и реранкеры (/models отдаёт их пачкой) не умеют chat/completions:
            // в пикере чата они только удлиняют список в полтора раза.
            val entries = LocalModelPickerPolicy.entries(
                LocalModelPickerPolicy.chatModels(catalog),
                LocalModelPickerPolicy.providerOf(profile.baseUrl),
            )
            val filtered = LocalModelPickerPolicy.filter(entries, query)
            if (filtered.isEmpty()) {
                status.text = "Ничего не найдено"
                status.visibility = View.VISIBLE
                return
            }
            // Группы по СЕМЕЙСТВУ: профиль в локальном чате один, и деление по провайдеру
            // дало бы одну секцию на 80 строк — ровно тот плоский список, на который
            // жалуется владелец. Заводская модель профиля идёт первой в своей секции.
            val sections = LocalModelPickerPolicy.sections(filtered, preferredId = current)
            val open = expanded ?: sections
                .filter { section -> section.entries.any { it.id == current } }
                .map { it.key }
                .ifEmpty { listOfNotNull(sections.firstOrNull()?.key) }
                .toMutableSet()
                .also { expanded = it }
            // Поиск — явное намерение увидеть модели: результаты не прячем в свёрнутые
            // заголовки, иначе человек ищет «kimi» и получает одну строку «KIMI · 2».
            val searching = query.isNotBlank()
            status.text = "Моделей: ${filtered.size} · групп: ${sections.size}"
            status.visibility = View.VISIBLE
            var drawn = 0
            for ((sectionIndex, section) in sections.withIndex()) {
                if (drawn >= MAX_ROWS) break
                val isOpen = searching || section.key in open
                list.addView(
                    groupHeader(
                        activity = activity,
                        section = section,
                        expanded = isOpen,
                        // При поиске заголовок не кликабелен: складывать группу, которая
                        // обязана быть раскрытой, — кнопка, которая молча ничего не делает.
                        onToggle = if (searching) null else {
                            {
                                if (isOpen) open.remove(section.key) else open.add(section.key)
                                rebuild(search.text?.toString().orEmpty(), keepScroll = true)
                            }
                        },
                    ),
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(activity, if (sectionIndex > 0) 14 else 2)
                        bottomMargin = dp(activity, 2)
                    },
                )
                drawn++
                if (!isOpen) continue
                // Площадка одна на группу — она уже в заголовке; иначе пишем её в строке,
                // иначе «Claude» выглядел бы однородным, хотя это Anthropic и PlusVibe.
                val showVendor = section.vendor == null
                for (entry in section.entries) {
                    if (drawn >= MAX_ROWS) break
                    list.addView(
                        row(
                            activity = activity,
                            entry = entry,
                            selected = entry.id == current,
                            showVendor = showVendor,
                            onClick = {
                                dialog?.dismiss()
                                onPick(entry.info)
                            },
                        ),
                        LinearLayout.LayoutParams(-1, -2).apply {
                            // Воздух между карточками: столбик без отступов читается
                            // как таблица, а не как список выбора.
                            topMargin = dp(activity, 6)
                        },
                    )
                    drawn++
                }
            }
            if (keepScroll && scrollY > 0) {
                val target = scroll
                if (target != null) target.post { target.scrollTo(0, scrollY) }
            }
        }

        dialog = MarsDialog.show(activity) { root ->
            root.addView(
                MarsDialog.header(
                    activity,
                    "Модель",
                    // В подзаголовке — человеческое имя, а не id: «Claude Haiku 5.5»
                    // владелец узнаёт, а `claude-haiku-5.5` — нет.
                    current?.takeIf { it.isNotBlank() }?.let { LocalModelHints.title(it) },
                )
            )
            root.addView(
                search,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 10) },
            )
            root.addView(
                status,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 8) },
            )
            root.addView(list, LinearLayout.LayoutParams(-1, -2))
            emptyList()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rebuild(s?.toString().orEmpty())
        })

        scope.launch {
            val result = withContext(Dispatchers.IO) { client.catalog(profile, apiKey) }
            if (dialog?.isShowing != true) return@launch
            result.fold(
                onSuccess = { models ->
                    catalog = models
                    if (models.isEmpty()) {
                        status.text = "Провайдер не вернул ни одной модели"
                        return@fold
                    }
                    search.visibility = View.VISIBLE
                    rebuild(search.text?.toString().orEmpty())
                },
                onFailure = { error ->
                    status.text = "Каталог недоступен: " + (error.message ?: "нет ответа провайдера")
                },
            )
        }
    }

    /**
     * Лист уровней размышлений: Авто / low / medium / high, текущий помечен.
     * Показывается только у моделей с поддержкой — решение принимает экран по
     * [LocalChatUiState.supportsReasoning], здесь лишь сам выбор.
     */
    fun showReasoning(activity: Activity, current: String?, onPick: (String?) -> Unit) {
        val normalized = current?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(MarsDialog.header(activity, "Уровень размышлений", null))
            root.addView(
                MarsDialog.paragraph(
                    activity,
                    "«Авто» — уровень выбирает провайдер. Остальные пункты уходят в запрос " +
                        "полем reasoning_effort и влияют только на моделях, которые его принимают.",
                )
            )
            val stack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            LEVELS.forEachIndexed { index, level ->
                val selected = level.effort == normalized
                val pill = MarsDialog.pill(
                    activity,
                    if (selected) "${level.label} · выбрано" else "${level.label} — ${level.hint}",
                    bg = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                    fg = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
                    border = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
                    onClick = {
                        dialog?.dismiss()
                        onPick(level.effort)
                    },
                )
                stack.addView(
                    pill,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        if (index > 0) topMargin = dp(activity, 8)
                    },
                )
            }
            root.addView(stack)
            emptyList()
        }
    }

    /**
     * Заголовок группы: имя семейства капсом, число моделей и площадка.
     *
     * Тап по заголовку складывает/раскрывает группу ([onToggle] == null — заголовок
     * не кликабелен, так бывает при поиске). Стрелка — векторная (`ic_chevron_*`), а не
     * символ шрифта: на HyperOS глифы вроде «▾» приходят из CJK-фолбэка и съезжают.
     *
     * Обычная строка-разделитель, а не липкая шапка: список живёт в диалоге, и липкость
     * потребовала бы собственного RecyclerView — лишний вес ради одной подписи.
     */
    private fun groupHeader(
        activity: Activity,
        section: LocalPickerSection,
        expanded: Boolean,
        onToggle: (() -> Unit)?,
    ): View {
        val title = section.title
        val count = section.count
        // Площадка одна на группу — пишем её здесь; площадок несколько — их число, а сами
        // площадки уходят в строки моделей (иначе «Claude» врал бы об однородности).
        val vendor = section.vendor ?: "площадки: ${section.vendors.size}"
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 2), dp(activity, 8), dp(activity, 2), dp(activity, 8))
        }
        // Локальная копия: смарт-каст параметра внутри лямбды-обработчика ненадёжен,
        // а именно он решает, кликабельна ли группа.
        val toggle = onToggle
        if (toggle != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { toggle() }
        }
        row.addView(
            TextView(activity).apply {
                text = title.uppercase(java.util.Locale.ROOT) + " · " + count
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minimumWidth = 0
                // Голосом заголовок читается как «Группа Claude, моделей: 5».
                isAccessibilityHeading = true
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        row.addView(
            TextView(activity).apply {
                text = vendor
                textSize = 10f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minimumWidth = 0
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
        )
        if (onToggle != null) {
            row.addView(
                ImageView(activity).apply {
                    setImageDrawable(
                        ContextCompat.getDrawable(
                            activity,
                            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                        ),
                    )
                    imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(activity, 14), dp(activity, 14)).apply {
                    marginStart = dp(activity, 6)
                },
            )
        }
        row.contentDescription = buildString {
            append("Группа ")
            append(title)
            append(", моделей: ")
            append(count)
            append(", площадка ")
            append(vendor)
            if (onToggle != null) append(if (expanded) ", свёрнута" else ", раскрыта")
        }
        return row
    }

    /**
     * Строка каталога: название, площадка (когда в группе их несколько), окно контекста,
     * метки vision и reasoning, отметка выбора.
     *
     * Назначение второй строкой — только у выбранной модели: у остальных оно занимало
     * половину строки в списке на 80 моделей, и владелец справедливо просил «свернуть по
     * полочкам». Назначение выбранной остаётся на виду — это та модель, что сейчас работает.
     */
    private fun row(
        activity: Activity,
        entry: LocalPickerEntry,
        selected: Boolean,
        showVendor: Boolean,
        onClick: () -> Unit,
    ): View {
        val info = entry.info
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(
                activity,
                if (selected) MarsTheme.SURFACE_CARD else MarsTheme.SURFACE,
                radiusDp = 12,
                strokeColor = if (selected) MarsTheme.ACCENT else MarsTheme.BORDER,
                // Выбранная — рамка вдвое толще: цвет на солнце на POCO F5 почти не
                // читается, а толщина видна всегда.
                strokeWidthDp = if (selected) 2 else 1,
            )
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            isClickable = true
            isFocusable = true
        }

        val name = TextView(activity).apply {
            text = entry.title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) MarsTheme.TEXT_STRONG else MarsTheme.TEXT_EM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Название длиннее экрана: усекается, а не растягивает лист.
            minimumWidth = 0
        }
        // Название и вторая строка собираются в колонку ДО добавления в row:
        // один View не может иметь двух родителей, и повторный addView падает
        // с «The specified child already has a parent».
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = 0
            addView(name, LinearLayout.LayoutParams(-1, -2))
            // Вторая строка — только у выбранной модели: назначение, а если его нет — сырой
            // id. У остальных строк её нет вовсе: список из 80 моделей с подписью у каждой
            // читается как простыня, а назначение нужно тому, кто выбирает, — его и так
            // видно в чипе выбранной модели.
            if (selected) {
                val second = if (entry.purpose.isNotBlank()) {
                    TextView(activity).apply {
                        text = entry.purpose
                        textSize = 10.5f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                } else {
                    TextView(activity).apply {
                        text = info.id
                        textSize = 10.5f
                        typeface = Typeface.MONOSPACE
                        setTextColor(MarsTheme.TEXT_MUTED)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
                addView(second, LinearLayout.LayoutParams(-1, -2))
            }
        }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))

        // Площадка в строке — только когда в группе их несколько: при одной она уже стоит
        // в заголовке группы, и повторять её 12 раз значит вернуть ту длину, от которой
        // владелец и просил избавиться.
        if (showVendor) {
            row.addView(
                pillLabel(activity, entry.vendor, MarsTheme.TEXT_MUTED),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
            )
        }

        val window = info.contextWindow
        if (window != null && window > 0) {
            row.addView(
                TextView(activity).apply {
                    text = formatTokenCount(window)
                    textSize = 10.5f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
            )
        }

        // Метка зрения: «vision» — принимает картинки, «без фото» — провайдер сказал
        // «только текст». Неизвестное не помечаем: пугалка без доказательства запретила
        // бы картинку там, где провайдер её на самом деле принимает.
        when (entry.vision) {
            true -> row.addView(
                pillLabel(activity, "vision", MarsTheme.STATUS_ONLINE),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
            )

            false -> row.addView(
                pillLabel(activity, "без фото", MarsTheme.TEXT_MUTED),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) },
            )

            null -> Unit
        }

        if (info.supportsReasoningEffort) {
            row.addView(
                pillLabel(activity, "reasoning", MarsTheme.ACCENT_TEXT).apply {
                    background = MarsTheme.pill(
                        activity,
                        MarsTheme.ACCENT_BG,
                        strokeColor = MarsTheme.ACCENT_BORDER,
                    )
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 6) },
            )
        }

        // Выбранная — ещё и галочка справа: рамку на беглом взгляде легко пропустить,
        // а значок стоит на одном и том же месте у всех строк.
        if (selected) {
            row.addView(
                ImageView(activity).apply {
                    setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_check))
                    imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(activity, 16), dp(activity, 16)).apply {
                    marginStart = dp(activity, 6)
                },
            )
        }

        row.contentDescription = buildString {
            append("Модель ")
            append(entry.title)
            append(", id ")
            append(info.id)
            if (showVendor) {
                append(", площадка ")
                append(entry.vendor)
            }
            if (entry.purpose.isNotBlank()) {
                append(", ")
                append(entry.purpose)
            }
            if (window != null && window > 0) {
                append(", окно ")
                append(formatTokenCount(window))
            }
            when (entry.vision) {
                true -> append(", принимает картинки")
                false -> append(", не принимает картинки")
                null -> Unit
            }
            if (info.supportsReasoningEffort) append(", поддерживает reasoning")
            if (selected) append(", выбрана")
        }
        row.setOnClickListener { onClick() }
        return row
    }

    /** Маленькая метка строки: цвет текста и задаёт смысл (зрение / без зрения). */
    private fun pillLabel(activity: Activity, text: String, fg: Int): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 9.5f
            setTextColor(fg)
            maxLines = 1
            background = MarsTheme.pill(
                activity,
                MarsTheme.SURFACE_SUBTLE,
                strokeColor = MarsTheme.BORDER_MUTED,
            )
            setPadding(dp(activity, 7), dp(activity, 3), dp(activity, 7), dp(activity, 3))
        }

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)

    /**
     * Ближайший [ScrollView] над списком — диалог строит его сам (`MarsDialog.show`).
     *
     * Нужен, чтобы сохранить прокрутку при складывании группы: перерисовка идёт через
     * `removeAllViews()`, а без восстановления `scrollY` список прыгал бы в начало и
     * владелец терял бы место, до которого долистал.
     */
    private fun scrollParent(view: View): ScrollView? {
        var current: View? = view
        while (current != null) {
            if (current is ScrollView) return current
            // Через `as?` к следующему View: у `ViewParent.parent` и `View.parent`
            // одинаковый тип, и прямой вызов на смарт-касте даёт «overload ambiguity».
            current = current.parent as? View
        }
        return null
    }
}
