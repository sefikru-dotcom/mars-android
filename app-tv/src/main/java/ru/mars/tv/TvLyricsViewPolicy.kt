package ru.mars.tv

import ru.mars.network.model.LyricsResult

/** Что показывает панель «Текст песни»: готовый текст, синхронизирован он, и источник. */
sealed interface TvLyricsUiState {
    data class Text(val body: String, val synced: Boolean, val source: String?) : TvLyricsUiState

    /** Текста нет ни в одном поле ответа (или сервер вернул заглушку «Текст не найден.»). */
    data object Missing : TvLyricsUiState
}

/**
 * Чистое сопоставление ответа `GET /api/lyrics/{videoId}` в состояние просмотрщика
 * текста (порт mobile [ru.mars.mobile.LyricsViewPolicy] + правило серверной заглушки).
 *
 * Живой контракт (снят 13.10): ответ — ПЛОСКИЙ объект
 * `{"lyrics": "…", "synced": null, "source": "youtube"}`; «пустого» ответа не бывает —
 * отсутствие текста сервер отдаёт НЕПУСТОЙ заглушкой в теле (`"Текст не найден."`),
 * поэтому [isNotFoundStub] распознаёт её и превращает в [TvLyricsUiState.Missing].
 *
 * Правила (без Android, тестируется на JVM — [TvLyricsViewPolicyTest]):
 * - обычный текст важнее синхронизированного: `lyrics` показывается как есть;
 * - если есть только `synced` (LRC), таймкоды `[мм:сс.xx]` срезаются — текст читается
 *   как обычный, но помечается синхронизированным;
 * - служебные строки LRC (`[ar:…]`, `[ti:…]`, `[00:00.00]` без текста) отбрасываются;
 * - заглушка «Текст не найден.» (в любом регистре, с точкой/пробелами) — Missing;
 * - пустой результат из всех полей — [TvLyricsUiState.Missing].
 */
object TvLyricsViewPolicy {

    /** Тело панели, пока запрос в пути. */
    const val LOADING_TEXT: String = "Загружаю текст…"

    /** Осмысленная заглушка: сервер не вернул текст (пусто или заглушка в теле). */
    const val MISSING_TEXT: String = "Текст не найден"

    /** Таймкод LRC в начале строки: `[12:34]`, `[12:34.5]`, `[12:34.567]`. */
    private val TIME_TAG = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    /** Служебные метаданные LRC: `[ar:Artist]`, `[ti:Title]`, `[offset:+500]` и т.п. */
    private val META_TAG = Regex("""\[[A-Za-z#][^\]]*]""")

    /** Серверная заглушка отсутствия текста: «Текст не найден», «Текст не найден.» и т.п. */
    private val NOT_FOUND_STUB = Regex("""^текст\s+не\s+найден[.\s!?]*$""", RegexOption.IGNORE_CASE)

    fun resolve(result: LyricsResult?): TvLyricsUiState {
        result?.lyrics?.let { plain ->
            val body = normalize(plain)
            if (body.isNotEmpty() && !isNotFoundStub(body)) {
                return TvLyricsUiState.Text(body, synced = false, source = result.source)
            }
        }
        result?.synced?.let { synced ->
            val body = normalize(stripSyncTags(synced))
            if (body.isNotEmpty() && !isNotFoundStub(body)) {
                return TvLyricsUiState.Text(body, synced = true, source = result.source)
            }
        }
        return TvLyricsUiState.Missing
    }

    /** True, когда тело — серверная заглушка «Текст не найден.» (а не настоящая песня). */
    fun isNotFoundStub(body: String): Boolean = NOT_FOUND_STUB.matches(body.trim())

    /**
     * Строка метаданных под заголовком: «синхронизированный текст» и/или
     * «источник: youtube», через разделитель; пусто — строку прячем.
     */
    fun metaLine(synced: Boolean, source: String?): String = buildList {
        if (synced) add("синхронизированный текст")
        source?.takeIf { it.isNotBlank() }?.let { add("источник: $it") }
    }.joinToString(" · ")

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
