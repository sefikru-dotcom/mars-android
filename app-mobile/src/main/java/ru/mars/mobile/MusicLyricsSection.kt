package ru.mars.mobile

import android.app.Dialog
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Текст песни»: диалог с текстом и ленивым переводом.
 *
 * Вынесено из [MusicActivity] (шаг 9 раскладки) как internal-расширения.
 * Диалог самодостаточен: `dialogRef` живёт локально в функции, состояние перевода —
 * в локальных переменных; ответ→вид по-прежнему решают [LyricsViewPolicy] и
 * [LyricsTranslationPolicy].
 */

/**
 * «Текст песни»: opens the themed lyrics dialog immediately (with a loading body) and
 * fills it when `/api/lyrics` answers. The answer→view mapping is pure
 * ([LyricsViewPolicy]): plain lyrics win, synced LRC loses its timestamps.
 */
internal fun MusicActivity.onLyricsClicked() {
    val track = MarsMusicPlayer.currentTrack ?: run { showStatus("Сначала включите трек"); return }
    if (lyricsJob?.isActive == true) return

    val metaView = TextView(this).apply {
        textSize = 11f
        setTextColor(MarsTheme.TEXT_MUTED)
        visibility = View.GONE
        setPadding(0, 0, 0, dp(10))
    }
    val bodyView = TextView(this).apply {
        text = "Загружаем текст…"
        textSize = 14f
        setTextColor(MarsTheme.TEXT_MUTED)
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
    }
    /** Original text as returned by /api/lyrics; null while it has not arrived yet. */
    var original: String? = null
    /** Russian translation, fetched on demand; null until the user asks for it. */
    var translation: String? = null
    var showingTranslation = false
    /** Toggle button, added only once there is a text worth translating. */
    val translateButton = MarsDialog.quietButton(this, LyricsTranslationPolicy.ACTION_TRANSLATE) {}
    translateButton.visibility = View.GONE

    fun applyBody() {
        val text = original
        if (text == null) return
        bodyView.text = LyricsTranslationPolicy.bodyFor(text, translation, showingTranslation)
        translateButton.text = LyricsTranslationPolicy.actionLabel(showingTranslation)
    }

    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Текст песни", null))
        root.addView(TextView(this).apply {
            text = "${track.title} — ${track.artist}"
            textSize = 13f
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(metaView)
        root.addView(bodyView)
        translateButton.setOnClickListener {
            if (showingTranslation) {
                showingTranslation = false
                applyBody()
                return@setOnClickListener
            }
            val text = original
            if (!LyricsTranslationPolicy.canTranslate(text)) {
                showStatus("Перевод недоступен — сначала нужен текст песни")
                return@setOnClickListener
            }
            if (translation != null) {
                showingTranslation = true
                applyBody()
                return@setOnClickListener
            }
            translation = null
            translateButton.isEnabled = false
            translateButton.alpha = 0.4f
            translateButton.text = "Переводим…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.translateLyrics(text.orEmpty()) }
                }
                translateButton.isEnabled = true
                translateButton.alpha = 1f
                result.onSuccess { translated ->
                    translation = translated
                    showingTranslation = translated.isNotBlank()
                    if (translated.isBlank()) {
                        showStatus(LyricsTranslationPolicy.emptyTranslationText())
                    }
                    applyBody()
                }.onFailure {
                    translateButton.text = LyricsTranslationPolicy.ACTION_TRANSLATE
                    showStatus(LyricsTranslationPolicy.failureText())
                }
            }
        }
        root.addView(translateButton)
        root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
        emptyList()
    }

    lyricsJob = lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { repository.getLyrics(track.id, title = track.title, artist = track.artist) }
        }
        result.onSuccess { lyrics ->
            when (val state = LyricsViewPolicy.resolve(lyrics)) {
                is LyricsUiState.Text -> {
                    original = state.body
                    bodyView.setTextColor(MarsTheme.TEXT_EM)
                    applyBody()
                    translateButton.visibility =
                        if (LyricsTranslationPolicy.canTranslate(state.body)) View.VISIBLE else View.GONE
                    val labels = buildList {
                        if (state.synced) add("синхронизированный текст")
                        state.source?.takeIf { it.isNotBlank() }?.let { add("источник: $it") }
                    }
                    if (labels.isNotEmpty()) {
                        metaView.text = labels.joinToString(" · ")
                        metaView.visibility = View.VISIBLE
                    }
                }
                LyricsUiState.Missing -> bodyView.text = "Текст не найден — сервер вернул пустой ответ"
            }
        }.onFailure {
            bodyView.text = "Текст недоступен — проверьте сеть"
        }
    }
}
