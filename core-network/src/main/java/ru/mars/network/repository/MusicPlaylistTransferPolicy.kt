package ru.mars.network.repository

/**
 * Чистая JVM-валидация ввода «импорт плейлиста YouTube Music».
 *
 * Сервер (`handle_import_playlist` → `extract_playlist_id`) ищет в entries первое
 * совпадение `[?&]list=([a-zA-Z0-9_-]+)`; если его нет — отправляет всю строку
 * как ID (и при неудаче дополнительно пробует префикс `VL`). Политика повторяет
 * это правило на клиенте, чтобы не отправлять на сервер заведомый мусор и чтобы
 * диалог мог сразу показать понятную ошибку.
 *
 * Принимаются:
 * - `https://music.youtube.com/playlist?list=PL123abc` и `?list=` в середине;
 * - `https://youtu.be/PL123abc?si=…`, `youtube.com/watch?v=…&list=…`;
 * - ID с VL-префиксом (`VLPL123abc`) и без него (`PL123abc`);
 * - голый ID с допустимыми символами `[A-Za-z0-9_-]`.
 *
 * Отвергаются: пустота, пробелы внутри, произвольные слова, ссылки без `list=`
 * (кроме коротких youtu.be/ID), строки с протоколом, но без хоста.
 */
object MusicPlaylistImportPolicy {

    /** Результат проверки: accept — Normalized, reject — Invalid. */
    sealed interface Validation {
        data class Accepted(val urlOrId: String) : Validation
        data object Invalid : Validation
    }

    private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{5,}")
    private val LIST_PARAM = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val SHORT_LINK = Regex("(?i)^(?:https?://)?(?:www\\.)?youtu\\.be/([A-Za-z0-9_-]+)(?:[?#/].*)?$")

    /**
     * Нормализует пользовательский ввод к строке, которую можно отправить серверу.
     * Пробелы по краям срезаются; во внутренних пробелах ввод отвергается.
     */
    fun validate(raw: String?): Validation {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Validation.Invalid
        if (value.any { it.isWhitespace() }) return Validation.Invalid

        LIST_PARAM.find(value)?.let { match ->
            val id = match.groupValues[1]
            return if (id.length >= 5) Validation.Accepted(id) else Validation.Invalid
        }

        SHORT_LINK.matchEntire(value)?.let { match ->
            val id = match.groupValues[1]
            return if (PLAYLIST_ID.matches(id)) Validation.Accepted(id) else Validation.Invalid
        }

        // Голый ID (включая VL-префикс). Замыкающая пунктуация ссылки — мусор.
        if (PLAYLIST_ID.matches(value)) return Validation.Accepted(value)
        return Validation.Invalid
    }
}
