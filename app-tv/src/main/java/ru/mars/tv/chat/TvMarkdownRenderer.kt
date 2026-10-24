package ru.mars.tv.chat

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
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

    /**
     * Собирает готовый «богатый» пузырь ответа: фон/паддинги как у обычного bot-пузыря.
     *
     * Паддинги берутся из токенов ([R.dimen.tv_chat_bubble_pad_h]/[_pad_v]) — те же,
     * что у плоского пузыря, поэтому богатый и обычный ответ не «прыгают» по высоте.
     * Параметр `dp` оставлен ради существующих вызовов (секции передают свой масштаб).
     */
    fun richBubble(
        context: Context,
        raw: String,
        @Suppress("UNUSED_PARAMETER") dp: (Int) -> Int,
    ): LinearLayout {
        val padH = context.resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_pad_h)
        val padV = context.resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_pad_v)
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bubble_bot)
            setPadding(padH, padV, padH, padV)
            isFocusable = true
        }
        TvFocusLift.attach(bubble)
        render(context, TvMarkdownBlocks.parse(raw), bubble)
        return bubble
    }

    private fun vparams(context: Context) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = context.resources.getDimensionPixelSize(R.dimen.tv_gap_s) }

    /** Токен размера в пикселях — единственный источник отступов рендера. */
    private fun dimen(context: Context, id: Int) = context.resources.getDimensionPixelSize(id)

    private fun textView(context: Context, text: String, bold: Boolean) = TextView(context).apply {
        this.text = text
        // Кегль — из шкалы. Единица PX задаётся явно: `textSize =` трактует число как SP,
        // а getDimension возвращает уже масштабированные пиксели.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.tv_text_body))
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
            TableLayout.LayoutParams.MATCH_PARENT, dimen(context, R.dimen.tv_card_stroke),
        ).apply {
            val gap = dimen(context, R.dimen.tv_gap_xs)
            topMargin = gap
            bottomMargin = gap
        })
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
    ).apply { marginEnd = dimen(context, R.dimen.tv_gap_l); gravity = Gravity.TOP }

    private fun cellView(context: Context, text: String, strong: Boolean) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.tv_text_label))
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
                    }, LinearLayout.LayoutParams(
                        dimen(context, R.dimen.tv_icon_m), dimen(context, R.dimen.tv_icon_m),
                    ).apply {
                        marginEnd = dimen(context, R.dimen.tv_gap_s)
                    })
                    addView(TextView(context).apply {
                        text = task.text
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            context.resources.getDimension(R.dimen.tv_text_body),
                        )
                        setTextColor(ContextCompat.getColor(
                            context, if (task.checked) R.color.mars_muted else R.color.mars_text,
                        ))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(context, R.dimen.tv_gap_xs) })
            }
        }
}
