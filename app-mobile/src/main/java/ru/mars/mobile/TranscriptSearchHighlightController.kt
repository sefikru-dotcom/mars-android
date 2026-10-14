package ru.mars.mobile

import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ru.mars.network.repository.MessageSearchPolicy
import ru.mars.network.repository.SearchableMessage
import ru.mars.network.repository.TextMatch

/**
 * Screen-scoped controller поиска по текущему диалогу (слой UI-контроллеров экрана,
 * между Activity-обвязкой и чистыми политиками).
 *
 * Владеет ТОЛЬКО состоянием поиска и визуальной подсветкой совпадения:
 * - состояние: совпадения, текущая позиция, якорь, подсвеченная карточка и спан;
 * - подсветка: рубиновая рамка карточки (foreground), янтарный спан на самом
 *   совпадении внутри пузыря, скролл к строке совпадения с фоллбэком на карточку.
 *
 * НЕ владеет view-обвязкой панели (`searchPlate`/`searchInput`/`searchCounter`
 * остаются в Activity) и не знает про `CardEntry`: доступ к карточкам идёт через
 * узкий контракт [Host]. Решения о совпадениях/подписи/навигации принимает чистая
 * [MessageSearchPolicy], позиция совпадения в отрисованных строках — [MatchLocationPolicy].
 */
class TranscriptSearchHighlightController(private val host: Host) {

    /**
     * Явный контракт хоста (Activity): ровно те данные и эффекты, которые контроллеру
     * нужны, без передачи `CardEntry`, снимков карточек и самого Activity.
     */
    interface Host {
        /** Тексты сообщений в порядке отображения (снимок на момент пересчёта). */
        val messageTexts: List<String>

        /** Текущий запрос из поля ввода. */
        val query: String

        /** Подпись счётчика «N из M» — панель остаётся в Activity. */
        fun setCounter(label: String)

        /** Карточка сообщения по индексу (tag-адресация транскрипта: tag = индекс). */
        fun cardViewAt(index: Int): View?

        /** Секция markdown внутри пузыря по индексу (null, пока текста нет). */
        fun markdownHostAt(index: Int): View?

        /** Корень прокрутки: граница суммирования смещений по цепочке родителей. */
        val scrollContainer: View

        /** Скролл транскрипта: цель smoothScrollTo для совпадения/фоллбэка «к карточке». */
        fun scrollTo(y: Int)

        fun dp(value: Int): Int
    }

    private var matches: List<TextMatch> = emptyList()
    private var position: Int = -1

    /** Якорь текущего совпадения: переживает пересчёт совпадений во время стрима. */
    private var anchor: TextMatch? = null

    private var highlightedCard: View? = null

    /** Подсвеченный спан совпадения: TextView пузыря + сам спан (снятие при шаге/закрытии). */
    private var highlightedSpan: Pair<TextView, BackgroundColorSpan>? = null

    /** Текущая позиция совпадения (-1, если совпадений нет) — для тестов и отладки. */
    val currentPosition: Int get() = position

    /** Текущее число совпадений. */
    val matchCount: Int get() = matches.size

    /**
     * Пересчитывает совпадения по текущему транскрипту и обновляет счётчик.
     *
     * Совпадения адресует чистая [MessageSearchPolicy] (якорь messageId +
     * ordinalInMessage): каждое вхождение — отдельный шаг, несколько вхождений
     * в одном пузыре не «съедают» друг друга. Пузырь транскрипта = одно сообщение
     * (tag карточки = индекс), поэтому messageId синтетический: «m<индекс>» —
     * стабильный в пределах текущего транскрипта.
     *
     * [resetPosition] — сброс на первое совпадение (новый запрос); без него позиция
     * ВОССТАНАВЛИВАЕТСЯ ПО ЯКОРЮ (перерисовка во время стрима: текст растёт, индексы
     * совпадений смещаются, а текущее совпадение не теряется).
     * [scrollToMatch] — скроллить к совпадению (только явная навигация/новый запрос).
     */
    fun refresh(resetPosition: Boolean, scrollToMatch: Boolean) {
        val query = host.query
        matches = MessageSearchPolicy.findMatches(
            host.messageTexts.mapIndexed { index, text ->
                SearchableMessage(messageId = messageId(index), text = text)
            },
            query,
        )
        position = resolvePosition(matches, anchor, position, resetPosition)
        anchor = MessageSearchPolicy.anchorAt(matches, position)
        host.setCounter(MessageSearchPolicy.counterLabel(query, position, matches.size))
        applyHighlight(scrollTo = scrollToMatch && position >= 0)
    }

    /** Кольцевой шаг «следующее/предыдущее совпадение» + обновление счётчика и подсветки. */
    fun navigate(forward: Boolean) {
        val next = MessageSearchPolicy.navigate(matches.size, position, forward)
        if (next < 0) return
        position = next
        anchor = MessageSearchPolicy.anchorAt(matches, position)
        host.setCounter(MessageSearchPolicy.counterLabel(host.query, position, matches.size))
        applyHighlight(scrollTo = true)
    }

    /** Сброс состояния поиска и снятие подсветки (закрытие панели / уход с диалога). */
    fun clear() {
        matches = emptyList()
        position = -1
        anchor = null
        clearHighlight()
    }

    /**
     * Подсветка и скролл к ТОЧНОЙ позиции совпадения: рубиновая рамка вокруг карточки +
     * янтарный спан на самом совпадении внутри пузыря; скролл — к строке совпадения,
     * а не к верхушке карточки/дня-разделителя. Позицию совпадения в ОТОБРАЖЁННЫХ
     * строках пузыря находит чистая [MatchLocationPolicy] (markdown-рендер меняет текст:
     * маркеры списков, заголовки). Если вхождение в отрисованных строках не найдено
     * (например, текст уехал в свёрнутый блок) — откат к прежнему поведению:
     * рамка + скролл к карточке.
     *
     * Старый foreground/span снимается ПЕРЕД применением нового: иначе подсветки
     * накапливаются на переиспользуемых Spannable-текстах пузырей.
     */
    private fun applyHighlight(scrollTo: Boolean) {
        clearHighlight()
        val match = MessageSearchPolicy.anchorAt(matches, position) ?: return
        val messageIndex = indexOfMessageId(match.messageId) ?: return
        val card = host.cardViewAt(messageIndex) ?: return
        // foreground, а не background: градиент пузыря/карточки остаётся нетронутым.
        card.foreground = GradientDrawable().apply {
            cornerRadius = host.dp(16).toFloat()
            setStroke(host.dp(2), MarsTheme.ACCENT)
        }
        highlightedCard = card

        // Точная цель: TextView отрисованной строки + спан совпадения в нём.
        val bubble = host.markdownHostAt(messageIndex) ?: card
        val textViews = collectTextViews(bubble)
        val location = MatchLocationPolicy.locate(
            textViews.map { it.text?.toString().orEmpty() },
            host.query,
            match.ordinalInMessage,
        )
        var scrollTargetY: Int? = null
        if (location != null) {
            val target = textViews.getOrNull(location.lineIndex)
            if (target != null) {
                val spannable = target.text as? Spannable
                if (spannable != null && location.end <= spannable.length) {
                    val span = BackgroundColorSpan(HIGHLIGHT_COLOR)
                    spannable.setSpan(span, location.start, location.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    highlightedSpan = target to span
                }
                scrollTargetY = offsetInScroll(target) + lineTopOffset(target, location.start)
            }
        }
        if (scrollTo) {
            // К строке совпадения (с отступом-воздухом сверху), фоллбэк — верх карточки.
            val y = (scrollTargetY ?: offsetInScroll(card)) - host.dp(72)
            host.scrollTo(y.coerceAtLeast(0))
        }
    }

    private fun clearHighlight() {
        highlightedCard?.foreground = null
        highlightedCard = null
        highlightedSpan?.let { (view, span) ->
            (view.text as? Spannable)?.removeSpan(span)
        }
        highlightedSpan = null
    }

    /** Все TextView поддерева в порядке отрисовки (обход в глубину). */
    private fun collectTextViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    /**
     * Вертикальное смещение view внутри контента скролла: сумма top по цепочке
     * родителей. `card.top` сам по себе — координата ВНУТРИ обёртки с день-разделителем;
     * без цепочки скролл уезжал к разделителю/верху контейнера (баг волны C).
     */
    private fun offsetInScroll(view: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== host.scrollContainer) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** Верх строки Layout, содержащей символ [offset] (0, если Layout ещё не измерен). */
    private fun lineTopOffset(view: TextView, offset: Int): Int {
        val layout = view.layout ?: return 0
        val textLength = layout.text?.length ?: 0
        if (offset !in 0..textLength) return 0
        return layout.getLineTop(layout.getLineForOffset(offset))
    }

    companion object {
        /** Префикс синтетического messageId пузыря («m<индекс>») для [MessageSearchPolicy]. */
        const val ID_PREFIX = "m"

        /** Синтетический id сообщения-пузыря по индексу карточки транскрипта. */
        fun messageId(index: Int): String = ID_PREFIX + index

        /** Индекс карточки из синтетического messageId; null — id не наш. */
        fun indexOfMessageId(messageId: String): Int? =
            messageId.removePrefix(ID_PREFIX).toIntOrNull()?.takeIf { it >= 0 }

        /**
         * Позиция текущего совпадения после пересчёта: новый запрос — первое совпадение;
         * иначе — восстановление ПО ЯКОРЮ, с откатом на прежнюю позицию внутри нового
         * списка (совпадение исчезло из стрима/текст уехал). Пустой список — -1.
         */
        fun resolvePosition(
            matches: List<TextMatch>,
            anchor: TextMatch?,
            currentPosition: Int,
            resetPosition: Boolean,
        ): Int = when {
            matches.isEmpty() -> -1
            resetPosition -> 0
            else -> MessageSearchPolicy.positionOf(matches, anchor)
                .takeIf { it >= 0 }
                ?: currentPosition.coerceIn(0, matches.size - 1)
        }

        /**
         * Янтарная полупрозрачная подсветка самого совпадения (не перекрывает текст).
         * Цвет берётся из палитры (STATUS_WARNING) и приглушается альфой: хардкод-hex вне
         * файла палитры запрещён гейтом дизайн-системы (scripts/theme-parity-audit.py).
         */
        val HIGHLIGHT_COLOR: Int =
            MarsGradients.withAlpha(MarsGradients.STATUS_WARNING, MarsGradients.SEARCH_HIGHLIGHT_ALPHA)
    }
}
