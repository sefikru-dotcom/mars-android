package ru.mars.mobile

/**
 * Выбор расширения временного файла для аудио серверного синтеза.
 *
 * Зачем: сервер `api/tts` отвечает то MP3, то WAV (движок и параметры меняются), а плеер
 * получает локальный файл. ExoPlayer определяет контейнер по содержимому, но расширение
 * всё равно должно совпадать с реальным форматом: по нему работают логи, кеш и внешние
 * приложения, которым файл может быть отдан. Ошибка здесь стоит тишины вместо ответа,
 * поэтому правило вынесено в чистую функцию и покрыто тестом.
 *
 * Content-Type разбирается терпимо: параметры (`; charset=…`), регистр и суффиксы вида
 * `audio/x-wav`/`audio/wave` не должны ломать выбор. Неизвестный тип даёт нейтральное
 * `.audio` — файл всё равно играется по содержимому.
 */
object ServerTtsAudioFormat {

    /** Расширение файла (с точкой) для [contentType]. */
    fun extensionFor(contentType: String?): String {
        val normalized = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (normalized.isEmpty()) return FALLBACK
        return when {
            normalized.contains("wav") || normalized.contains("wave") -> ".wav"
            normalized.contains("mpeg") || normalized.contains("mp3") -> ".mp3"
            normalized.contains("ogg") || normalized.contains("opus") -> ".ogg"
            normalized.contains("aac") || normalized.contains("mp4") || normalized.contains("m4a") -> ".m4a"
            normalized.contains("flac") -> ".flac"
            else -> FALLBACK
        }
    }

    /** Нейтральное расширение: формат неизвестен, играем по содержимому. */
    const val FALLBACK: String = ".audio"
}
