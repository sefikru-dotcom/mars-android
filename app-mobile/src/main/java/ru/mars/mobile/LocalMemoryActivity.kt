package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import ru.mars.mobile.SectionScaffold
import ru.mars.mobile.MarsDialog
import ru.mars.mobile.MarsTheme
import ru.mars.network.local.AddResult
import ru.mars.network.local.MemoryStore
import ru.mars.network.local.OwnerProfile
import ru.mars.network.local.OwnerProfileStore
import java.io.File

/**
 * Память Марса: что модель о тебе знает и что она запомнит дальше.
 *
 * Зачем экран. Память без возможности посмотреть и удалить — это чужой блокнот о тебе
 * в кармане: модель дописывает факты сама (инструмент `remember`), и человек должен
 * видеть этот список целиком, а не догадываться по ответам.
 *
 * Две части, как на серверном Марсе:
 * - **профиль** — кто ты: имя, город, о себе. Уходит в каждый запрос;
 * - **факты** — то, что модель записала по ходу общения, через `§`.
 *
 * Без них модель начинает каждый разговор с чистого листа и переспрашивает то,
 * что уже знает.
 */
class LocalMemoryActivity : ComponentActivity() {

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, LocalMemoryActivity::class.java)

        /** Лимит памяти — как `memory_char_limit` на сервере Марса. */
        private const val MEMORY_LIMIT = 8_000
    }

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var memory: MemoryStore
    private lateinit var profiles: OwnerProfileStore
    private lateinit var factsColumn: LinearLayout
    private lateinit var usage: TextView
    private lateinit var nameField: EditText
    private lateinit var cityField: EditText
    private lateinit var aboutField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, "memory")
        dir.mkdirs()
        memory = MemoryStore(File(dir, "memory.md"))
        // Стартовая память владельца сажается здесь, а не в Application: экран памяти —
        // единственное место, где хранилище точно создано и открыто, и посадка видна
        // сразу, без «открой чат, потом вернись».
        OwnerMemorySeed.applyOnce(this, memory)
        profiles = OwnerProfileStore(File(dir, "profile.json"))
        scaffold = SectionScaffold.create(this, "Память", ::reload)
        scaffold.content.addView(buildView(), LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(scaffold.root)
        reload()
    }

    private fun buildView(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(
            MarsDialog.paragraph(
                this,
                "Профиль и факты уходят в начало каждого запроса. Чем точнее профиль, " +
                    "тем меньше модель переспрашивает.",
            ),
            weakParams(),
        )

        column.addView(sectionTitle("О тебе"))
        nameField = field("Имя", 1)
        cityField = field("Город", 1)
        aboutField = field("О себе: чем занимаешься, техника, привычки", 3)
        column.addView(nameField, weakParams())
        column.addView(cityField, weakParams())
        column.addView(aboutField, weakParams())

        column.addView(
            actionButton("Сохранить профиль", MarsTheme.ACCENT, MarsTheme.TEXT_STRONG) {
                profiles.save(
                    OwnerProfile(
                        name = nameField.text.toString().trim(),
                        city = cityField.text.toString().trim(),
                        about = aboutField.text.toString().trim(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                toast("Профиль сохранён")
                reload()
            },
            weakParams(),
        )

        column.addView(sectionTitle("Что Марс запомнил"))
        usage = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
        }
        column.addView(usage, weakParams())

        factsColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(factsColumn, weakParams())

        column.addView(
            actionButton("Добавить факт вручную", MarsTheme.SURFACE_SUBTLE, MarsTheme.TEXT) {
                addFactDialog()
            },
            weakParams(),
        )
        column.addView(
            actionButton("Очистить память", MarsTheme.SURFACE_SUBTLE, MarsTheme.TEXT) {
                clearDialog()
            },
            weakParams(),
        )
        return scroll
    }

    private fun reload() {
        val profile = profiles.load()
        nameField.setText(profile.name)
        cityField.setText(profile.city)
        aboutField.setText(profile.about)

        val entries = memory.entries()
        val used = memory.systemPromptBlock().length
        usage.text = "записей: ${entries.size} · занято $used из $MEMORY_LIMIT знаков"

        factsColumn.removeAllViews()
        if (entries.isEmpty()) {
            factsColumn.addView(
                MarsDialog.paragraph(this, "Пока пусто. Скажи в чате что-то о себе — Марс запишет сам."),
                weakParams(),
            )
            return
        }
        entries.forEachIndexed { index, entry ->
            factsColumn.addView(factRow(index, entry), weakParams())
        }
    }

    /** Строка факта: текст слева, удаление справа. */
    private fun factRow(index: Int, entry: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.card(this@LocalMemoryActivity, MarsTheme.SURFACE_CARD)
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        val label = TextView(this).apply {
            text = entry
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
            minimumWidth = 0
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val remove = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            contentDescription = "Удалить факт: $entry"
            setOnClickListener {
                memory.remove(index)
                reload()
            }
        }
        row.addView(remove)
        return row
    }

    private fun addFactDialog() {
        val input = EditText(this).apply {
            hint = "Факт одной фразой"
            textSize = 15f
            setTextColor(MarsTheme.TEXT)
            setHintTextColor(MarsTheme.TEXT_MUTED)
        }
        MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Новый факт", null))
            root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            listOf(
                MarsDialog.pill(
                    context = this,
                    label = "Добавить",
                    bg = MarsTheme.ACCENT,
                    fg = MarsTheme.TEXT_STRONG,
                    onClick = {
                        val text = input.text.toString().trim()
                        if (text.isEmpty()) {
                            toast("Пустой факт не запомнить")
                        } else {
                            when (val result = memory.add(text)) {
                                AddResult.Ok -> toast("Запомнил")
                                is AddResult.Rejected -> toast(result.reason)
                            }
                            reload()
                        }
                    },
                ),
            )
        }
    }

    private fun clearDialog() {
        MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Очистить память", null))
            root.addView(MarsDialog.paragraph(this, "Все запомненные факты будут удалены. Профиль останется."))
            listOf(
                MarsDialog.pill(
                    context = this,
                    label = "Очистить",
                    bg = MarsTheme.ACCENT,
                    fg = MarsTheme.TEXT_STRONG,
                    onClick = {
                        memory.clear()
                        reload()
                    },
                ),
            )
        }
    }

    // --- мелкие помощники --------------------------------------------------------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(MarsTheme.ACCENT_TEXT)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun field(hint: String, lines: Int): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(MarsTheme.TEXT)
        setHintTextColor(MarsTheme.TEXT_MUTED)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        maxLines = lines
        minLines = lines
        background = MarsTheme.card(this@LocalMemoryActivity, MarsTheme.SURFACE_CARD)
    }

    private fun actionButton(label: String, bg: Int, fg: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = MarsTheme.pill(this@LocalMemoryActivity, bg, MarsTheme.BORDER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun weakParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(6)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
