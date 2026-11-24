package ru.mars.mobile

import ru.mars.network.repository.MusicPlaylistImportResult

/**
 * Чистые состояния диалога импорта плейлиста (JVM, без Android):
 * ввод → «Импортирую…» → готово (N треков) либо ошибка с текстом сервера.
 * Offline — обычная [Error]: спиннер обязательно снимается, вечного ожидания нет.
 */
sealed interface MusicPlaylistImportUiState {
    data object Idle : MusicPlaylistImportUiState
    data object Importing : MusicPlaylistImportUiState
    data class Done(val title: String, val count: Int) : MusicPlaylistImportUiState
    data class Error(val message: String) : MusicPlaylistImportUiState

    /** Текст строки состояния; «готово» показывает ровно число импортированных треков. */
    val label: String
        get() = when (this) {
            Idle -> ""
            Importing -> "Импортирую…"
            is Done -> "Импортировано треков: $count"
            is Error -> message
        }
}

/** Преобразование результата репозитория в состояние UI без потери серверного текста. */
object MusicPlaylistImportStateMapper {
    fun from(result: MusicPlaylistImportResult): MusicPlaylistImportUiState = when (result) {
        is MusicPlaylistImportResult.Imported -> MusicPlaylistImportUiState.Done(result.title, result.count)
        is MusicPlaylistImportResult.Failed -> MusicPlaylistImportUiState.Error(result.message)
    }
}
