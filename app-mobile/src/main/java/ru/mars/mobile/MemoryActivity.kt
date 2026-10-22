package ru.mars.mobile

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.MemoryContent
import ru.mars.network.repository.MemoryRepository
import ru.mars.network.repository.MemoryWriteResult

/**
 * Экран «Память Марса»: MEMORY.md / USER.md / SOUL.md / AGENTS.md из `GET /api/memory`.
 * UI-слой тонкий: выбор секции и состояние тела — чистый [MemoryTabPolicy],
 * загрузка — [MemoryRepository], рендер — [MarkdownFormatter] в view палитры [MarsTheme].
 */
class MemoryActivity : ComponentActivity() {
    private lateinit var store: AndroidKeyStoreCredentialStore
    private val repository = MemoryRepository()

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var chipsRow: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var copyBtn: ImageButton
    private lateinit var editBtn: ImageButton

    private var currentTab = MemoryTab.MEMORY
    private var content: MemoryContent? = null
    private var loading = false
    private var failure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        setContentView(buildView())
        load()
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, "Память Марса", ::load)

        // Tab chips: Память / Профиль / SOUL / Контекст + «копировать» одной строкой;
        // шапка, «Назад», спиннер и «Обновить» — общий shell.
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlsRow.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow, ViewGroup.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        copyBtn = MarsTheme.circleIconButton(this, R.drawable.ic_copy, "Копировать секцию", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
            .apply { setOnClickListener { copyCurrentSection() } }
        controlsRow.addView(copyBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        // Правка секции — та же скромная круглая иконка, что copy: минимализм без крупных кнопок.
        editBtn = MarsTheme.circleIconButton(this, R.drawable.ic_edit, "Править секцию", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
            .apply { setOnClickListener { openEditor() } }
        controlsRow.addView(editBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        scaffold.content.addView(controlsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        renderChips()
        renderBody()
        return scaffold.root
    }

    private fun renderChips() {
        chipsRow.removeAllViews()
        MemoryTab.entries.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            val chip = MarsDialog.pill(
                this,
                tab.label,
                bg = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
                fg = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED,
                border = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
            ) {
                if (currentTab != tab) {
                    currentTab = tab
                    renderChips()
                    renderBody()
                }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun renderBody() {
        val state = MemoryTabPolicy.resolve(currentTab, content, loading, failure)
        copyBtn.alpha = if (MemoryTabPolicy.canCopy(currentTab, content)) 1f else 0.4f
        editBtn.alpha = if (MemoryEditPolicy.canEdit(currentTab, state)) 1f else 0.4f
        scaffold.progress.visibility = if (state is MemorySectionState.Loading) View.VISIBLE else View.GONE
        body.removeAllViews()
        when (state) {
            is MemorySectionState.Loading -> body.addView(SectionStateViews.loading(this, "Загрузка ${currentTab.fileName}…"))
            is MemorySectionState.Failure -> body.addView(SectionStateViews.error(this, state.message, ::load))
            is MemorySectionState.Empty -> body.addView(SectionStateViews.empty(this, state.message))
            is MemorySectionState.Body -> renderMarkdown(state.text, body)
        }
    }

    private fun load() {
        if (loading || isFinishing) return
        val credential = store.read()
        if (credential == null) {
            content = null
            failure = MemoryTabPolicy.SIGNED_OUT_FAILURE
            renderBody()
            return
        }
        loading = true
        failure = null
        renderBody()
        lifecycleScope.launch {
            val result = repository.getMemory(MarsApi.BASE_URL, credential)
            loading = false
            if (result == null) {
                content = null
                failure = MemoryTabPolicy.DEFAULT_FAILURE
            } else {
                content = result
                failure = null
            }
            renderBody()
        }
    }

    private fun copyCurrentSection() {
        val text = MemoryTabPolicy.textFor(currentTab, content)
        if (text.isBlank()) {
            Toast.makeText(this, "Нечего копировать", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(currentTab.fileName, text))
        Toast.makeText(this, "Скопировано: ${currentTab.fileName}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Правка секции: диалог с monospace-редактором; сохранение — `POST /api/memory/write`
     * через [MemoryRepository.writeSection] (полная перезапись секции), после успеха
     * память перечитывается с сервера (источник истины — сервер, не черновик).
     */
    private fun openEditor() {
        val state = MemoryTabPolicy.resolve(currentTab, content, loading, failure)
        if (!MemoryEditPolicy.canEdit(currentTab, state)) return
        val section = MemoryEditPolicy.writableSection(currentTab) ?: return
        val credential = store.read()
        if (credential == null) {
            failure = MemoryTabPolicy.SIGNED_OUT_FAILURE
            renderBody()
            return
        }
        val tab = currentTab
        val original = MemoryTabPolicy.textFor(tab, content)
        var dialog: Dialog? = null
        var saving = false
        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, MemoryEditPolicy.dialogTitle(tab), tab.fileName))
            val input = MarsDialog.textField(this, "Текст ${tab.fileName}", original).apply {
                typeface = Typeface.MONOSPACE
                minLines = 8
                maxLines = 18
            }
            root.addView(input)
            val error = MarsDialog.errorStrip(this)
            root.addView(error)
            val save = MarsDialog.primaryButton(this, MemoryEditPolicy.SAVE_LABEL) {
                if (saving) return@primaryButton
                val draft = input.text.toString()
                if (!MemoryEditPolicy.canSave(draft, original)) {
                    MarsDialog.setError(error, MemoryEditPolicy.saveBlockReason(draft, original))
                    return@primaryButton
                }
                saving = true
                MarsDialog.setResponding(listOf(input), true)
                lifecycleScope.launch {
                    val result = repository.writeSection(MarsApi.BASE_URL, credential, section, draft)
                    saving = false
                    MarsDialog.setResponding(listOf(input), false)
                    when (result) {
                        is MemoryWriteResult.Saved -> {
                            dialog?.dismiss()
                            Toast.makeText(
                                this@MemoryActivity,
                                MemoryEditPolicy.savedMessage(tab),
                                Toast.LENGTH_SHORT,
                            ).show()
                            load()
                        }
                        is MemoryWriteResult.Failed -> MarsDialog.setError(error, result.message)
                    }
                }
            }
            root.addView(save)
            root.addView(MarsDialog.quietButton(this, "Отмена") { dialog?.dismiss() })
            listOf(save)
        }
    }

    /** Markdown-рендер секции: заголовки, списки, цитаты, код, таблицы — палитра MarsTheme. */
    private fun renderMarkdown(raw: String, parent: LinearLayout) {
        MarkdownFormatter.parseBlocks(raw).forEach { block ->
            when (block) {
                is ContentBlock.Text -> block.lines.forEach lineLoop@{ line ->
                    val tv = TextView(this).apply {
                        movementMethod = LinkMovementMethod.getInstance()
                        setTextColor(MarsTheme.TEXT)
                        textSize = 15f
                        setPadding(0, dp(2), 0, dp(2))
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }
                    val ssb = SpannableStringBuilder()
                    // См. ChatMarkdownRenderer: цитата кладётся обёрткой (бар + текст),
                    // разделитель — отдельным View, чтобы TextView не мерил пустую строку.
                    var quoteLine = false
                    var skipTv = false
                    when (line) {
                        is RichLine.Header -> {
                            tv.textSize = when (line.level) {
                                1 -> 19f
                                2 -> 17f
                                else -> 16f
                            }
                            tv.setTypeface(null, Typeface.BOLD)
                            tv.setTextColor(MarsTheme.TEXT_STRONG)
                            tv.setPadding(0, dp(10), 0, dp(4))
                            appendInlineSpans(ssb, line.spans)
                        }
                        is RichLine.Bullet -> {
                            // Маркер рисует canvas (BulletSpan) — глиф «•» из строки убран.
                            val bulletStart = ssb.length
                            appendInlineSpans(ssb, line.spans)
                            ssb.setSpan(
                                BulletSpan(dp(10), MarsTheme.TEXT_MUTED),
                                bulletStart,
                                ssb.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        is RichLine.Numbered -> {
                            ssb.append("${line.number} ")
                            appendInlineSpans(ssb, line.spans)
                        }
                        is RichLine.Quote -> {
                            // См. ChatMarkdownRenderer: подложка строки —
                            // BackgroundColorSpan на всём тексте, бар — View
                            // слева. Крашеный пробел давал бар высотой с глиф.
                            val quoteStart = ssb.length
                            appendInlineSpans(ssb, line.spans)
                            if (ssb.length > quoteStart) {
                                ssb.setSpan(
                                    BackgroundColorSpan(MarsTheme.QUOTE_BG),
                                    quoteStart,
                                    ssb.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                            tv.setTypeface(null, Typeface.ITALIC)
                            tv.setTextColor(MarsTheme.TEXT_EM)
                            tv.setPadding(dp(10), dp(3), dp(8), dp(3))
                            quoteLine = true
                        }
                        is RichLine.TaskItem -> {
                            val iconStart = ssb.length
                            ssb.append("  ")
                            val iconRes = if (line.checked) R.drawable.ic_check else R.drawable.ic_checkbox_empty
                            ssb.setSpan(
                                ImageSpan(this@MemoryActivity, iconRes, ImageSpan.ALIGN_BASELINE),
                                iconStart,
                                ssb.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            val start = ssb.length
                            appendInlineSpans(ssb, line.spans)
                            if (line.checked) {
                                ssb.setSpan(StrikethroughSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                ssb.setSpan(ForegroundColorSpan(MarsTheme.TEXT_MUTED), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        is RichLine.Normal -> appendInlineSpans(ssb, line.spans)
                        is RichLine.Divider -> {
                            // Разделитель — View 1dp, а не пустой TextView:
                            // TextView мерил строку и линия «плыла» по высоте.
                            skipTv = true
                            parent.addView(
                                View(this@MemoryActivity).apply { setBackgroundColor(MarsTheme.DIVIDER) },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(1)
                                ).apply {
                                    topMargin = dp(8)
                                    bottomMargin = dp(8)
                                }
                            )
                        }
                    }
                    tv.text = ssb
                    if (skipTv) return@lineLoop
                    if (quoteLine) {
                        val row = LinearLayout(this@MemoryActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(
                                View(this@MemoryActivity).apply { setBackgroundColor(MarsTheme.QUOTE_BAR) },
                                LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
                            )
                            addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        }
                        parent.addView(row)
                    } else {
                        parent.addView(tv)
                    }
                }
                is ContentBlock.Code -> {
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = MarsTheme.card(this@MemoryActivity, MarsTheme.CODE_BG, radiusDp = 10, strokeColor = MarsTheme.CODE_BORDER)
                    }
                    if (block.language.isNotBlank()) {
                        box.addView(TextView(this).apply {
                            text = block.language
                            textSize = 11f
                            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            setTextColor(MarsTheme.ACCENT_TEXT)
                            setPadding(0, 0, 0, dp(4))
                        })
                    }
                    box.addView(HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(TextView(this@MemoryActivity).apply {
                            text = block.code
                            setTextColor(MarsTheme.TEXT)
                            typeface = Typeface.MONOSPACE
                            textSize = 13f
                            setTextIsSelectable(true)
                        })
                    })
                    parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(6)
                    })
                }
                is ContentBlock.Table -> {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = MarsTheme.card(this@MemoryActivity, MarsTheme.SURFACE_CARD, radiusDp = 12)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    val table = TableLayout(this)
                    fun buildRow(cells: List<List<InlineSpan>>, headerRow: Boolean) = TableRow(this).apply {
                        if (headerRow) setBackgroundColor(MarsTheme.SURFACE_SUBTLE)
                        cells.forEach { spans ->
                            addView(TextView(this@MemoryActivity).apply {
                                movementMethod = LinkMovementMethod.getInstance()
                                textSize = 13f
                                setLineSpacing(dp(2).toFloat(), 1f)
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                if (headerRow) {
                                    setTextColor(MarsTheme.TEXT_STRONG)
                                    setTypeface(null, Typeface.BOLD)
                                } else {
                                    setTextColor(MarsTheme.TEXT)
                                }
                                val cellText = SpannableStringBuilder()
                                appendInlineSpans(cellText, spans)
                                text = cellText
                            })
                        }
                    }
                    table.addView(buildRow(block.headers, headerRow = true))
                    block.rows.forEach { row -> table.addView(buildRow(row, headerRow = false)) }
                    card.addView(HorizontalScrollView(this).apply { addView(table) })
                    parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(6)
                    })
                }
            }
        }
    }

    private fun appendInlineSpans(builder: SpannableStringBuilder, spans: List<InlineSpan>) {
        spans.forEach { span ->
            val start = builder.length
            when (span) {
                is InlineSpan.Normal -> builder.append(span.text)
                is InlineSpan.Bold -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Italic -> {
                    builder.append(span.text)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Monospace -> {
                    builder.append(span.text)
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(MarsTheme.SURFACE_SUBTLE), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Link -> {
                    builder.append(span.title)
                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(span.url))) }
                        }
                    }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Strikethrough -> {
                    builder.append(span.text)
                    builder.setSpan(StrikethroughSpan(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineSpan.Image -> {
                    // Memory preview has no lightbox: show the alt label as plain text.
                    builder.append(ImageLightboxPolicy.inlineLabel(span.alt))
                }
            }
        }
    }

    private fun dp(v: Int) = MarsTheme.dp(this, v)
}
