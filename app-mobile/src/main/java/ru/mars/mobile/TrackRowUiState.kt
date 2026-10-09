package ru.mars.mobile

/**
 * Визуальное состояние строки трека в списках «Чарт / Избранное / поиск».
 *
 * Чистая логика (без Android): решает, является ли строка активным треком,
 * показывать ли паузу вместо play и должно ли нажатие запускать воспроизведение.
 * Экран ([MusicActivity]) только применяет результат к готовым View.
 */
data class TrackRowUiState(
    /** Строка соответствует текущему треку плеера. */
    val active: Boolean,
    /** Активный трек сейчас играет — кнопка строки показывает паузу. */
    val showPauseIcon: Boolean,
    /** Нажатие начинает воспроизведение списка; false — активная строка переключает play/pause. */
    val tapStartsPlayback: Boolean,
) {
    companion object {
        /**
         * Пустой/нулевой id строки никогда не считается активным: при отсутствующем
         * текущем треке ни одна строка не должна подсвечиваться.
         */
        fun resolve(rowTrackId: String?, currentTrackId: String?, isPlaying: Boolean): TrackRowUiState {
            val active = !rowTrackId.isNullOrBlank() && rowTrackId == currentTrackId
            return TrackRowUiState(
                active = active,
                showPauseIcon = active && isPlaying,
                tapStartsPlayback = !active,
            )
        }
    }
}
