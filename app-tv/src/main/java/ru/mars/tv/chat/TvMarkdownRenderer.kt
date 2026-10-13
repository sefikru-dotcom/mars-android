package ru.mars.tv.chat

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift

/**
 * Тонкий Android-рендер блоков [TvMarkdownBlocks] для пузыря ответа на ТВ:
 * обычный текст — TextView, чек-лист — строки с векторными маркерами (без эмодзи,
 * дизайн-система Dark Obsidian), таблица — [TableLayout] в горизонтально
 * скроллируемой обёртке (широкая таблица не ломает вёрстку 1080p/720p экрана,
 * её можно листать D-pad). Вся СМЫСЛОВАЯ работа (парсинг, решение «богатый ли
 * текст») — в чистом JVM-объекте [TvMarkdownBlocks]; здесь только сборка view.
 */
object TvMarkdownRenderer {

    /** Наполняет контейнер блоками; пустые блоки пропускаются. */
    fun render(context: Context, blocks: List<TvBlock>, container: LinearLayout) {
        blocks.forEach { block ->
            when (block) {
                is TvBlock.Text -> if (block.text.isNotBlank()) {
                    container.addView(textView(context, block.text, bold = false), vparams(context))
                }
                is TvBlock.Table -> container.addView(tableView(context, block), vparams(context))
                is TvBlock.TaskList -> container.addView(taskListView(context, block), vparams(context))
            }
        }
    }

    /** Собирает готовый «богатый» пузырь ответа: фон/паддинги как у обычного bot-пузыря. */
    fun richBubble(context: Context, raw: String, dp: (Int) -> Int): LinearLayout {
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bubble_bot)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isFocusable = true
        }
        TvFocusLift.attach(bubble)
        render(context, TvMarkdownBlocks.parse(raw), bubble)
        return bubble
    }

    private fun vparams(context: Context) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpOf(context, 6) }

    private fun dpOf(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private fun textView(context: Context, text: String, bold: Boolean) = TextView(context).apply {
        this.text = text
        textSize = 21f
        setTextColor(ContextCompat.getColor(context, R.color.mars_text))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    /** Таблица: заголовок жирный, под ним разделительная линия; всё в горизонтальном скролле. */
    private fun tableView(context: Context, block: TvBlock.Table): View {
        val table = TableLayout(context)
        table.addView(headerRow(context, block.headers))
        table.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.mars_border))
        }, TableLayout.LayoutParams(
            TableLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpOf(context, 1)),
        ).apply { topMargin = dpOf(context, 4); bottomMargin = dpOf(context, 4) })
        block.rows.forEach { cells ->
            table.addView(bodyRow(context, cells))
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // D-pad: широкую таблицу можно листать, когда обёртка в фокусе.
            isFocusable = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(table)
        }
    }

    private fun headerRow(context: Context, cells: List<String>) = TableRow(context).apply {
        cells.forEach { addView(cellView(context, it, strong = true), cellParams(context)) }
    }

    private fun bodyRow(context: Context, cells: List<String>) = TableRow(context).apply {
        cells.forEach { addView(cellView(context, it, strong = false), cellParams(context)) }
    }

    private fun cellParams(context: Context) = TableRow.LayoutParams(
        TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dpOf(context, 18); gravity = Gravity.TOP }

    private fun cellView(context: Context, text: String, strong: Boolean) = TextView(context).apply {
        this.text = text
        textSize = 19f
        setTextColor(ContextCompat.getColor(
            context, if (strong) R.color.mars_text_strong else R.color.mars_text,
        ))
        if (strong) setTypeface(null, Typeface.BOLD)
        maxEms = 24
    }

    /** Чек-лист: строка = векторный маркер состояния + текст пункта. */
    private fun taskListView(context: Context, block: TvBlock.TaskList): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            block.items.forEach { task ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(
                            if (task.checked) R.drawable.ic_task_done else R.drawable.ic_task_open,
                        )
                        imageTintList = ContextCompat.getColorStateList(
                            context, if (task.checked) R.color.mars_status_online else R.color.mars_muted,
                        )
                        contentDescription = if (task.checked) "Выполнено" else "Не выполнено"
                    }, LinearLayout.LayoutParams(dpOf(context, 24), dpOf(context, 24)).apply {
                        marginEnd = dpOf(context, 10)
                    })
                    addView(TextView(context).apply {
                        text = task.text
                        textSize = 21f
                        setTextColor(ContextCompat.getColor(
                            context, if (task.checked) R.color.mars_muted else R.color.mars_text,
                        ))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dpOf(context, 4) })
            }
        }
}
