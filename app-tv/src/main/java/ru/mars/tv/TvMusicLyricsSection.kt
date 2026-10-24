package ru.mars.tv

import android.app.Dialog
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.model.MusicTrack

/**
 * Секция «Диалог текста песни» ТВ-экрана «Музыка».
 *
 * Вынесено из [TvMusicActivity] (декомпозиция хоста, W3) как extension-функции на Activity.
 * Диалог `dialog_tv_lyrics`, состояния [TvLyricsPanelFlow] (Loading -> Text/Missing/Error) и
 * переключатель «Перевод»/«Оригинал» ([TvLyricsTranslationFlow]): подписи, тело и заметку
 * выбирает [TvLyricsTranslationPolicy], Android здесь только рисует.
 *
 * Фокус диалога не глохнет: при скрытии «Перевода» он уходит на стабильное «Закрыть» ДО
 * скрытия ([TvMusicFocusPolicy.handoffBeforeHide]), а UP с обеих кнопок ведёт в скролл.
 */

/**
 * Показывает тематический диалог текста песни с телом-загрузкой; содержимое заполняет
 * [renderLyricsPanel] по состояниям [lyricsFlow]. Back/«Закрыть» — dismiss, который
 * закрывает и запрос ([TvLyricsPanelFlow.close] в OnDismissListener) и сбрасывает
 * переключатель перевода: следующее открытие начинает с оригинала.
 */
internal fun TvMusicActivity.showLyricsDialog(track: MusicTrack) {
    val dialog = Dialog(this)
    dialog.setContentView(R.layout.dialog_tv_lyrics)
    dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(track)
    dialog.findViewById<TextView>(R.id.lyricsBodyText).text = TvLyricsViewPolicy.LOADING_TEXT
    val closeButton = dialog.findViewById<Button>(R.id.lyricsCloseButton)
    closeButton.setOnClickListener { dialog.dismiss() }
    // «Перевод»/«Оригинал»: нажатие уходит в [TvLyricsTranslationFlow] (двойное он
    // глушит сам), подпись/видимость/тело рисует [renderLyricsPanel]. Кнопка остаётся
    // фокусируемой и во время запроса — на TV disabled-элемент роняет D-pad-фокус.
    val translateButton = dialog.findViewById<Button>(R.id.lyricsTranslateButton)
    translateButton.setOnClickListener {
        val panel = lyricsFlow.state
        if (panel is TvLyricsPanelState.Text) lyricsTranslationFlow.toggle(panel.body)
    }
    TvFocusLift.attach(closeButton, reducedMotion)
    TvFocusLift.attach(translateButton, reducedMotion)
    dialog.setOnDismissListener {
        lyricsFlow.close()
        lyricsTranslationFlow.reset()
    }
    dialog.window?.apply {
        setLayout(dp(680), dp(620))
        setBackgroundDrawableResource(android.R.color.transparent)
    }
    dialog.show()
    // D-pad-окно: диалог стартует с фокусом на «Закрыть» — навигация не глохнет.
    closeButton.requestFocus()
    lyricsDialog = dialog
}

/**
 * Рендер состояния панели ([lyricsFlow]) в открытый диалог (только UI-поток).
 * [focusText] — переносить ли фокус на скролл: только при приходе нового текста;
 * перерисовки перевода ([lyricsTranslationFlow]) фокус не двигают. Тело — наложение
 * перевода ([TvLyricsTranslationPolicy.bodyFor]), кнопка «Перевод»/«Оригинал» живёт
 * только при настоящем тексте ([TvLyricsTranslationPolicy.buttonVisible]).
 */
internal fun TvMusicActivity.renderLyricsPanel(state: TvLyricsPanelState, focusText: Boolean) {
    val dialog = lyricsDialog?.takeIf { it.isShowing } ?: return
    val body = dialog.findViewById<TextView>(R.id.lyricsBodyText)
    val meta = dialog.findViewById<TextView>(R.id.lyricsMetaText)
    val translation = lyricsTranslationFlow.state
    when (state) {
        TvLyricsPanelState.Idle -> Unit

        is TvLyricsPanelState.Loading -> {
            body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted_on_glass))
            body.text = TvLyricsViewPolicy.LOADING_TEXT
            meta.visibility = View.GONE
            renderTranslateButton(dialog, visible = false)
        }

        is TvLyricsPanelState.Text -> {
            dialog.findViewById<TextView>(R.id.lyricsTrackText).text = trackLabel(state.track)
            body.setTextColor(ContextCompat.getColor(this, R.color.mars_text_em))
            body.text = TvLyricsTranslationPolicy.bodyFor(state.body, translation)
            // Под заголовком: мета панели и заметка перевода (понятная строка неудачи).
            val metaLine = TvLyricsTranslationPolicy.metaLine(
                panelMeta = TvLyricsViewPolicy.metaLine(state.synced, state.source),
                note = translationNote(translation),
            )
            meta.text = metaLine
            meta.visibility = if (metaLine.isEmpty()) View.GONE else View.VISIBLE
            renderTranslateButton(dialog, visible = TvLyricsTranslationPolicy.buttonVisible(state))
            if (focusText) {
                // Текст пришёл: фокус на скролл, куплеты листаются стрелками D-pad.
                dialog.findViewById<ScrollView>(R.id.lyricsScroll).requestFocus()
            }
        }

        is TvLyricsPanelState.Missing -> {
            body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted_on_glass))
            body.text = TvLyricsViewPolicy.MISSING_TEXT
            meta.visibility = View.GONE
            renderTranslateButton(dialog, visible = false)
        }

        is TvLyricsPanelState.Error -> {
            body.setTextColor(ContextCompat.getColor(this, R.color.mars_muted_on_glass))
            body.text = state.message
            meta.visibility = View.GONE
            renderTranslateButton(dialog, visible = false)
        }
    }
}

/**
 * Видимость и подпись кнопки «Перевод»/«Оригинал». Перед скрытием фокус (если кнопка
 * его держала) передаётся стабильному «Закрыть» ([TvMusicFocusPolicy.handoffBeforeHide] —
 * D-pad-окно не должно оставаться без фокуса); цепочка LEFT/RIGHT переподключается по
 * видимости — без прыжков в GONE-соседа (образец: [TvMusicFocusPolicy.horizontalChain]).
 */
internal fun TvMusicActivity.renderTranslateButton(dialog: Dialog, visible: Boolean) {
    val translateButton = dialog.findViewById<Button>(R.id.lyricsTranslateButton)
    val closeButton = dialog.findViewById<Button>(R.id.lyricsCloseButton)
    if (TvMusicFocusPolicy.handoffBeforeHide(
            viewVisible = translateButton.visibility == View.VISIBLE,
            viewHasFocus = translateButton.hasFocus(),
            staysVisible = visible,
        )
    ) {
        closeButton.requestFocus()
    }
    translateButton.text = translationLabel(TvLyricsTranslationPolicy.buttonLabel(lyricsTranslationFlow.state))
    translateButton.visibility = if (visible) View.VISIBLE else View.GONE
    if (visible) {
        translateButton.nextFocusLeftId = translateButton.id
        translateButton.nextFocusRightId = closeButton.id
        closeButton.nextFocusLeftId = translateButton.id
    } else {
        closeButton.nextFocusLeftId = closeButton.id
    }
    // UP с обеих кнопок — в скролл текста; DOWN из скролла — штатный поиск (листается).
    translateButton.nextFocusUpId = R.id.lyricsScroll
    closeButton.nextFocusUpId = R.id.lyricsScroll
}

/** Подпись кнопки из ресурсов: policy выбирает смысл, текст живёт в strings_tv_lyrics.xml. */
internal fun TvMusicActivity.translationLabel(label: TvLyricsTranslationPolicy.Label): String = getString(
    when (label) {
        TvLyricsTranslationPolicy.Label.TRANSLATE -> R.string.tv_lyrics_action_translate
        TvLyricsTranslationPolicy.Label.ORIGINAL -> R.string.tv_lyrics_action_original
        TvLyricsTranslationPolicy.Label.TRANSLATING -> R.string.tv_lyrics_label_translating
    },
)

/** Заметка перевода для строки метаданных; пустая — когда перевод не неудачен. */
internal fun TvMusicActivity.translationNote(state: TvLyricsTranslationState): String {
    val reason = TvLyricsTranslationPolicy.failureOf(state) ?: return ""
    return getString(
        when (reason) {
            TvLyricsTranslationFailure.EMPTY_ANSWER -> R.string.tv_lyrics_translation_empty
            TvLyricsTranslationFailure.NETWORK -> R.string.tv_lyrics_translation_failed
        },
    )
}
