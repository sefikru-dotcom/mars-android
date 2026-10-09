package ru.mars.mobile

import ru.mars.network.model.LyricsResult

/** Что показывает диалог «Текст песни»: готовый текст, синхронизированный он, и источник. */
sealed interface LyricsUiState {
    data class Text(val body: String, val synced: Boolean, val source: String?) : LyricsUiState

    /** Текста нет ни в одном поле ответа (или запрос вернул пустоту). */
    data object Missing : LyricsUiState
}

/**
 * Чистое сопоставление ответа `/api/lyrics/{videoId}` в состояние просмотрщика текста.
 *
 * Правила (без Android, тестируется на JVM):
 * - обычный текст важнее синхронизированного: `lyrics` показывается как есть;
 * - если есть только `synced` (LRC), таймкоды `[мм:сс.xx]` срезаются — текст читается
 *   как обычный, но помечается синхронизированным;
 * - служебные строки LRC (`[ar:…]`, `[ti:…]`, `[00:00.00]` без текста) отбрасываются;
 * - пустой результат из всех полей — [LyricsUiState.Missing].
 */
object LyricsViewPolicy {

    /** Таймкод LRC в начале строки: `[12:34]`, `[12:34.5]`, `[12:34.567]`. */
    private val TIME_TAG = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    /** Служебные метаданные LRC: `[ar:Artist]`, `[ti:Title]`, `[offset:+500]` и т.п. */
    private val META_TAG = Regex("""\[[A-Za-z#][^\]]*]""")

    fun resolve(result: LyricsResult?): LyricsUiState {
        result?.lyrics?.let { plain ->
            val body = normalize(plain)
            if (body.isNotEmpty()) return LyricsUiState.Text(body, synced = false, source = result.source)
        }
        result?.synced?.let { synced ->
            val body = normalize(stripSyncTags(synced))
            if (body.isNotEmpty()) return LyricsUiState.Text(body, synced = true, source = result.source)
        }
        return LyricsUiState.Missing
    }

    /** Убирает таймкоды и служебные теги LRC из каждой строки. */
    fun stripSyncTags(synced: String): String =
        synced.lineSequence()
            .map { line ->
                var cleaned = line.replace(TIME_TAG, "")
                cleaned = cleaned.replace(META_TAG, "")
                // Схлопываем пробелы, оставшиеся от вырезанного тега в середине строки.
                cleaned.replace(Regex("""\s{2,}"""), " ").trim()
            }
            .joinToString("\n")

    /** Trim + схлопывание подряд идущих пустых строк до одной (параграфы между куплетами). */
    fun normalize(text: String): String =
        text.lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
