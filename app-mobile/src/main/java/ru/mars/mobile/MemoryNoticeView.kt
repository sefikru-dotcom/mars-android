package ru.mars.mobile

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Тонкая полоска «Запомнил: …» под ответом в ленте локального чата.
 *
 * Зачем отдельный View, а не строка внутри карточки ответа: подсказка живёт минуту и
 * относится не к тексту ответа, а к побочному действию модели (`remember`). В карточке
 * она удлиняла бы пузырь и оставалась бы в истории после истечения срока, а в ленте —
 * висит поверх потока и исчезает, не оставляя следа в переписке.
 *
 * Что здесь есть и чего нет:
 * - **есть** одна строка с точкой [MarsTheme.STATUS_INFO] и текстом `ellipsize = END`:
 *   факт бывает длинным, а полоска не должна занимать пол-экрана;
 * - **нет анимации** — ни появления, ни исчезновения, ни пульсации точки (`statusDot`, а
 *   не `glowingStatusDot`): подсказка информационная, а не живое состояние, и мигающая
 *   строка в ленте отвлекала бы от чтения ответа;
 * - **нет таймера**. Срок жизни — правило [MemoryNoticePolicy.isExpired], его проверяет
 *   хост (экран решает, когда убрать подсказку). View не держит часы и не планирует
 *   `postDelayed`: два источника времени в одном месте расходятся при возврате из фона,
 *   и подсказка либо пропадала бы мгновенно, либо висела бы после `onPause`.
 *
 * Размеры намеренно мелкие (12sp, точка 6dp, отступы 10/6dp): полоска — сноска к ответу,
 * а не второй ответ. Цвет текста [MarsTheme.TEXT_MUTED], а не [MarsTheme.TEXT]: сноска
 * не должна спорить с основным текстом за внимание.
 */
internal class MemoryNoticeView(activity: Activity) {

    private val context: Activity = activity

    /** Точка статуса: статичный кружок, тот же приём, что у статусов в шапках карточек. */
    private val dot: View = MarsTheme.statusDot(context, MarsTheme.STATUS_INFO)

    /**
     * Текст подсказки. `maxLines = 1` + `ellipsize = END` и вес 1f в родителе:
     * при длинном факте обрезается текст, а точка и отступы остаются на месте
     * (без веса TextView выдавил бы точку за край узкой полоски).
     */
    private val label = TextView(context).apply {
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        isSingleLine = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // min-width: 0 — иначе длинная строка задаёт минимальную ширину и полоска
        // вылезает за край ленты вместо обрезки.
        minimumWidth = 0
    }

    /**
     * Корень полоски: горизонтальная строка с пилюлей-подложкой.
     *
     * Подложка — [MarsTheme.SURFACE_SUBTLE] с тонкой рамкой: та же пара, что у служебных
     * пилюль в чате, поэтому сноска читается как часть ленты, а не как чужой баннер.
     * `isClickable = false`: у подсказки нет действия, и «мёртвое» нажатие с ripple
     * обещало бы поведение, которого нет.
     */
    val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = MarsTheme.pill(context, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_SUBTLE)
        setPadding(dp(10), dp(6), dp(12), dp(6))
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        addView(
            dot,
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(7) },
        )
        addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /**
     * Показать подсказку. Идемпотентно: повторный вызов с новой подсказкой заменяет текст.
     *
     * Пустой факт не показывается вовсе (см. [hide]): полоска без текста — это
     * серая плашка с точкой, хуже, чем ничего.
     *
     * [label.contentDescription] получает **полную** строку без обрезки: на экране текст
     * обрезан многоточием, а TalkBack обязан прочитать факт целиком.
     */
    fun show(notice: MemoryNotice) {
        if (!notice.isVisible) {
            hide()
            return
        }
        label.text = MemoryNoticePolicy.render(notice)
        label.contentDescription = MemoryNoticePolicy.fullText(notice)
        root.visibility = View.VISIBLE
    }

    /** Убрать подсказку из ленты. Текст сбрасывается, чтобы не держать строку в памяти. */
    fun hide() {
        label.text = ""
        label.contentDescription = null
        root.visibility = View.GONE
    }

    /** Висит ли подсказка сейчас: по этому флагу хост решает, нужен ли отложенный вызов. */
    fun isShown(): Boolean = root.visibility == View.VISIBLE

    private fun dp(value: Int): Int = MarsTheme.dp(context, value)
}
