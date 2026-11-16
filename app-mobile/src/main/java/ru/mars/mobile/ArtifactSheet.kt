package ru.mars.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Полноэкранный лист артефакта: длинный ответ открывается отдельной панелью, а не
 * растягивает ленту на несколько экранов.
 *
 * Зачем панель, если текст уже есть в чате: в ленте документ — это набор абзацев, таблиц и
 * блоков кода между репликами, и листать её, чтобы дочитать отчёт, неудобно. Панель
 * показывает ответ одним прокручиваемым полотном, а внизу — три действия: сохранить файл,
 * скопировать, поделиться.
 *
 * Чего здесь НЕТ и почему: лист не решает, артефакт ли это, и не собирает файл. Решение
 * приходит готовым [ArtifactDecision] из [ArtifactPolicy] (правило обязано жить в одном
 * месте, иначе панель однажды предложит XLSX там, где политика сказала DOCX), а сборку и
 * запись файла делает вызывающий через `DocumentBuilder` — у листа нет ни прав на запись,
 * ни доступа к выбору места сохранения.
 *
 * Правка текста тоже не здесь: `MarsDialog.textField` — поле ввода, а панель показывает
 * готовый ответ. Для правки вызывающий открывает свой экран правки; здесь текст только
 * читается (и выделяется — скопировать фрагмент пальцем иногда нужно).
 *
 * Оформление — [MarsDialog]/[MarsTheme], как у остальных шторок проекта: ни одного нового
 * цвета. Моноширинный шрифт только у [ArtifactKind.CODE]: код читается колонками, а прозу
 * моноширинным шрифтом читать тяжело.
 */
internal object ArtifactSheet {

    /** Подпись «скопировать ответ целиком»; колбэк даёт вызывающий (у него есть буфер). */
    const val COPY_LABEL: String = "Копировать"

    /** Подпись «отдать текст в системное меню „Поделиться“». */
    const val SHARE_LABEL: String = "Поделиться"

    /**
     * Потолок высоты окна: панель почти на весь экран, но системные панели и жесты
     * остаются доступными — иначе из листа нельзя выйти назад.
     */
    private const val MAX_HEIGHT_RATIO = 0.88f

    /**
     * Высота самого полотна текста. Считается от высоты экрана, а не по содержимому:
     * ответ на 300 строк иначе растянул бы диалог выше экрана, и кнопки уехали бы за край.
     */
    private const val TEXT_HEIGHT_RATIO = 0.52f

    /**
     * Показать панель.
     *
     * @param decision готовое решение [ArtifactPolicy.decide] — вид, заголовок и формат.
     * @param text текст ответа как есть, с markdown: панель показывает его без пересборки
     *   в rich-text, чтобы не расходиться с лентой в разборе.
     * @param onSave вызывается по кнопке сохранения с форматом из решения; лист к этому
     *   моменту уже закрыт. Сборку файла и системный диалог выбора места делает вызывающий.
     * @param onCopy вызывается по «Копировать»: текст целиком в буфер обмена.
     * @param onShare вызывается по «Поделиться»: текст во внешнее приложение.
     */
    fun show(
        activity: Activity,
        decision: ArtifactDecision,
        text: String,
        onSave: (ArtifactFormat) -> Unit,
        onCopy: () -> Unit,
        onShare: () -> Unit,
    ) {
        // Экран мог уже закрываться: диалог поверх умершего окна — это BadTokenException.
        if (activity.isFinishing || activity.isDestroyed) return
        // Пустой ответ показывать нечего: панель без текста — это заголовок и три кнопки,
        // которые нечего сохранять и нечего копировать.
        if (text.isBlank()) return

        var dialog: Dialog? = null
        dialog = MarsDialog.show(activity) { root ->
            root.addView(
                MarsDialog.header(
                    activity,
                    decision.suggestedTitle.ifBlank { ArtifactPolicy.title(text) },
                    ArtifactPolicy.kindLabel(decision.kind).takeIf { it.isNotBlank() },
                ),
            )
            root.addView(
                caption(activity, decision, text),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 10) },
            )
            root.addView(
                body(activity, decision, text),
                LinearLayout.LayoutParams(-1, -2),
            )
            root.addView(
                MarsDialog.primaryButton(activity, ArtifactPolicy.saveLabel(decision.suggestedFormat)) {
                    dialog?.dismiss()
                    onSave(decision.suggestedFormat)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 14) },
            )
            // «Копировать» шире «Поделиться»: копирование — частое действие в панели
            // (вставить в письмо), а шаринг уводит из приложения.
            val copy = MarsDialog.secondaryButton(activity, COPY_LABEL) {
                dialog?.dismiss()
                onCopy()
            }
            val share = MarsDialog.secondaryButton(activity, SHARE_LABEL) {
                dialog?.dismiss()
                onShare()
            }
            root.addView(
                MarsDialog.buttonRow(activity, copy, share),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) },
            )
            emptyList()
        }
        dialog?.window?.setDimAmount(0.55f)
        clampHeight(activity, dialog)
    }

    /**
     * Подпись под заголовком: сколько строк и сколько блоков.
     *
     * Формат «12 строк · таблиц: 1» — цифра без падежных сюрпризов для блоков (счётная
     * форма «таблиц: N» верна при любом числе) и правильный падеж для строк.
     * Для документа блоков не бывает, поэтому остаётся только число строк.
     */
    private fun caption(context: Context, decision: ArtifactDecision, text: String): TextView {
        val lines = ArtifactPolicy.lineCount(text)
        val blocks = ArtifactPolicy.countBlocks(text, decision.kind)
        val suffix = when {
            decision.kind == ArtifactKind.TABLE && blocks > 0 -> " · таблиц: $blocks"
            decision.kind == ArtifactKind.CODE && blocks > 0 -> " · блоков кода: $blocks"
            else -> ""
        }
        return TextView(context).apply {
            this.text = ArtifactPolicy.linesLabel(lines) + suffix
            setTextColor(MarsTheme.TEXT_MUTED)
            textSize = 11f
            maxLines = 1
        }
    }

    /**
     * Полотно текста: `TextView` в `ScrollView`.
     *
     * Именно `TextView`, а не `MarsDialog.textField`: поле ввода редактируемое, и в панели
     * оно выглядело бы приглашением править ответ (правка живёт в отдельном экране, где
     * есть куда вернуть результат). Текст выделяемый — вытащить из документа одну строку
     * пальцем иногда нужно.
     *
     * Высота — по содержимому, но не выше [textHeight]: таблица из трёх строк не должна
     * открываться в пустой панели на пол-экрана, а ответ на 300 строк не должен выталкивать
     * кнопки за край. Потолок ставится на сам `ScrollView` после первой раскладки
     * ([capBodyHeight]): `maxHeight` у текста здесь не годится — он обрезал бы длинный
     * ответ, потому что дочерний view вернул бы высоту меньше вьюпорта и скроллить стало
     * бы нечего.
     *
     * Код — на `CODE_BG` с моноширинным шрифтом и без переносов «по словам»: листинг,
     * переломанный посередине строки, читается хуже, чем с горизонтальным скроллом.
     */
    private fun body(context: Context, decision: ArtifactDecision, text: String): View {
        val code = decision.kind == ArtifactKind.CODE
        val view = TextView(context).apply {
            this.text = text
            textSize = if (code) 13f else 14f
            setTextColor(MarsTheme.TEXT)
            setLineSpacing(dp(context, if (code) 0 else 2).toFloat(), if (code) 1f else 1.15f)
            setTextIsSelectable(true)
            if (code) {
                typeface = Typeface.MONOSPACE
                setHorizontallyScrolling(true)
            }
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
            background = MarsTheme.card(
                context,
                if (code) MarsTheme.CODE_BG else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = if (code) MarsTheme.CODE_BORDER else MarsTheme.BORDER,
            )
            // TalkBack: заголовок панели озвучивается шапкой, а тут — что именно читается.
            contentDescription = "Текст артефакта, " + ArtifactPolicy.linesLabel(ArtifactPolicy.lineCount(text))
        }
        // Код листается вбок, поэтому он живёт в горизонтальном скролле внутри вертикального:
        // иначе длинные строки обрезались бы по ширине карточки без возможности дочитать.
        val scroller: View = if (code) {
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(view, LinearLayout.LayoutParams(-2, -2))
            }
        } else {
            view
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(scroller, LinearLayout.LayoutParams(-1, -2))
        }
        capBodyHeight(context, scroll)
        return scroll
    }

    /**
     * Потолок высоты полотна: короткий артефакт остаётся коротким, длинный получает
     * внутренний скролл на [textHeight] пикселей.
     *
     * Почему после раскладки, а не сразу: высоту «сколько просит текст» до измерения узнать
     * нельзя, а фиксировать её на пол-экрана всем подряд — это пустая панель вокруг таблицы
     * из трёх строк. Первый кадр может пройти до раскладки (высота 0) — тогда ждём ещё.
     */
    private fun capBodyHeight(context: Context, scroll: ScrollView) {
        val cap = textHeight(context)
        scroll.post(object : Runnable {
            private var attempts = 0

            override fun run() {
                val height = scroll.height
                if (height <= 0 && attempts++ < 3) {
                    scroll.post(this)
                    return
                }
                if (height > cap) {
                    scroll.layoutParams = LinearLayout.LayoutParams(-1, cap)
                    scroll.requestLayout()
                }
            }
        })
    }

    /** Высота полотна от экрана: длинный ответ не должен выталкивать кнопки за край. */
    private fun textHeight(context: Context): Int =
        (context.resources.displayMetrics.heightPixels * TEXT_HEIGHT_RATIO).toInt()

    /**
     * Урезать окно до [MAX_HEIGHT_RATIO] экрана.
     *
     * [MarsDialog] ставит высоту по содержимому; полотно здесь фиксированной высоты, но на
     * маленьком экране (или в альфа-режиме) сумма шапки, полотна и кнопок всё равно может
     * выйти за экран. Высоту до первой раскладки узнать нельзя, поэтому дораскладываем
     * окно несколькими кадрами — как в [LocalTurnSettingsSheet.capHeight].
     */
    private fun clampHeight(activity: Activity, dialog: Dialog?) {
        val cap = (activity.resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        val window = dialog?.window ?: return
        val width = window.attributes?.width ?: 0
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

    private fun dp(context: Context, value: Int): Int = MarsTheme.dp(context, value)
}