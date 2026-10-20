package ru.mars.mobile

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

/**
 * Виджет задач хода над композером (todo-карточка).
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; модель карточки считает
 * [TodoPresenter], здесь только вид.
 */

internal fun ChatActivity.updateTodoWidget() {
    if (!isTodoWidgetPlateReady()) return
    val card = TodoPresenter.present(uiState.todoState)
    if (!card.visible) {
        todoWidgetPlate.visibility = View.GONE
        return
    }

    todoWidgetPlate.visibility = View.VISIBLE
    todoWidgetTitle.text = card.title
    applyExpandableHeaderA11yFromSection(
        todoWidgetHeader,
        ChatA11yPolicy.expandableHeaderDescription(todoWidgetExpanded, card.title),
        ChatA11yPolicy.stateDescription(todoWidgetExpanded)
    )
    todoWidgetChevron.setImageDrawable(
        ContextCompat.getDrawable(this, if (todoWidgetExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
    )

    todoWidgetContainer.removeAllViews()
    if (todoWidgetExpanded) {
        card.items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val dot = MarsTheme.glowingStatusDot(
                this,
                when {
                    item.isCompleted -> MarsTheme.STATUS_ONLINE
                    item.isInProgress -> MarsTheme.STATUS_RUNNING
                    else -> MarsTheme.TEXT_MUTED
                }
            )
            row.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })

            val itemText = TextView(this).apply {
                textSize = 12f
                if (item.isCompleted) {
                    val ssb = SpannableStringBuilder(item.content)
                    ssb.setSpan(StrikethroughSpan(), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = ssb
                    setTextColor(MarsTheme.TEXT_MUTED)
                } else {
                    text = item.content
                    setTextColor(if (item.isInProgress) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
                    if (item.isInProgress) setTypeface(null, Typeface.BOLD)
                }
            }
            row.addView(itemText, LinearLayout.LayoutParams(0, -2, 1f))
            todoWidgetContainer.addView(row)
        }

        val moreBtn = Button(this).apply {
            text = "Все задачи подробнее →"
            textSize = 11.5f
            setTextColor(MarsTheme.ACCENT_TEXT)
            background = null
            setPadding(0, dp(4), 0, 0)
            gravity = Gravity.START
            setOnClickListener {
                startActivity(Intent(this@updateTodoWidget, TodosActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", currentSessionId)
                })
            }
        }
        todoWidgetContainer.addView(moreBtn)
    } else if (card.inProgressContent != null) {
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        val pulse = MarsTheme.glowingStatusDot(this, MarsTheme.STATUS_RUNNING)
        previewRow.addView(pulse, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })

        val activeLabel = TextView(this).apply {
            text = card.inProgressContent
            textSize = 12f
            setTextColor(MarsTheme.TEXT_STRONG)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        previewRow.addView(activeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        todoWidgetContainer.addView(previewRow)
    }
}
