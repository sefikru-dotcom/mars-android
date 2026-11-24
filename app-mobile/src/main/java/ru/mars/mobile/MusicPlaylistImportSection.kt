package ru.mars.mobile

import android.app.Dialog
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.repository.MusicPlaylistImportPolicy
import ru.mars.network.repository.MusicPlaylistImportResult

/**
 * B2: диалог импорта плейлиста YouTube Music.
 *
 * Вызываемая точка входа для координатора: `promptImportPlaylist()` из экрана
 * плейлистов (кнопка в меню/шапке). Подсказка поля имеет значение по умолчанию.
 *
 * Состояния: ввод → «Импортирую…» → готово (N треков) / ошибка сервера дословно.
 * Любой исход снимает спиннер и разблокирует кнопки — offline не оставляет вечного ожидания.
 */
internal fun MusicActivity.promptImportPlaylist(
    inputHint: String = getString(R.string.music_import_url_hint),
) {
    var dialogRef: Dialog? = null
    val field = MarsDialog.textField(this, hint = inputHint)
    val status = TextView(this).apply {
        textSize = 13f
        setTextColor(MarsTheme.TEXT_MUTED)
        visibility = View.GONE
    }
    val errorStrip = MarsDialog.errorStrip(this)
    val progress = MarsDialog.progressRow(this)

    fun render(state: MusicPlaylistImportUiState) {
        MarsDialog.setError(errorStrip, null)
        when (state) {
            MusicPlaylistImportUiState.Idle -> {
                status.visibility = View.GONE
            }
            MusicPlaylistImportUiState.Importing,
            is MusicPlaylistImportUiState.Done,
            -> {
                status.visibility = View.VISIBLE
                status.text = state.label
            }
            is MusicPlaylistImportUiState.Error -> {
                // Текст сервера показываем в errorStrip как есть, без дубликата в статусе.
                status.visibility = View.GONE
                MarsDialog.setError(errorStrip, state.message)
            }
        }
    }

    fun submit() {
        val raw = field.text?.toString().orEmpty()
        val validated = MusicPlaylistImportPolicy.validate(raw)
        if (validated !is MusicPlaylistImportPolicy.Validation.Accepted) {
            render(MusicPlaylistImportUiState.Error(getString(R.string.music_import_bad_url)))
            return
        }
        val payload = validated.urlOrId
        val controls = listOf(field)
        MarsDialog.setResponding(controls, true)
        MarsDialog.setProgress(progress, true)
        render(MusicPlaylistImportUiState.Importing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.importPlaylist(payload) } }
            MarsDialog.setProgress(progress, false)
            MarsDialog.setResponding(controls, false)
            val state = result.fold(
                onSuccess = { MusicPlaylistImportStateMapper.from(it) },
                onFailure = { MusicPlaylistImportUiState.Error(it.message ?: getString(R.string.music_import_offline)) },
            )
            render(state)
            if (state is MusicPlaylistImportUiState.Done) {
                showStatus(getString(R.string.music_import_done_title, state.title, state.count))
                loadPlaylists()
            }
        }
    }

    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, getString(R.string.music_import_title), null))
        root.addView(MarsDialog.paragraph(this, getString(R.string.music_import_hint)))
        root.addView(field)
        root.addView(progress)
        root.addView(status)
        root.addView(errorStrip)
        root.addView(
            MarsDialog.buttonRow(
                this,
                MarsDialog.secondaryButton(this, getString(R.string.music_import_cancel)) { dialogRef?.dismiss() },
                MarsDialog.primaryButton(this, getString(R.string.music_import_submit)) { submit() },
            ),
        )
        listOf(field)
    }
}
