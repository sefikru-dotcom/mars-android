package ru.mars.mobile

import org.json.JSONArray
import org.json.JSONObject
import ru.mars.network.model.MusicTrack

/**
 * Pure charts-loading logic for [MusicActivity], extracted for JVM testability.
 *
 * Key rule: an HTTP 200 body of `{success:false,error:...}` is a FAILURE (error
 * screen + retry), never an empty track list — otherwise a server-side rejection
 * silently degrades into «Треки не найдены» and the user cannot tell "no data"
 * from "request rejected".
 */
object MusicChartLoader {

    /** Server answered HTTP 200 but the envelope says `success=false`. */
    class ServerRejectedException(val serverError: String) : IllegalStateException(serverError)

    sealed interface Outcome {
        /** Legitimate track list; an empty list here really means "no tracks". */
        data class Tracks(val items: List<MusicTrack>) : Outcome

        /** Both endpoints failed; the UI must show its error/retry state. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Resolves the charts tab outcome: the primary `/api/charts` attempt (via
     * `MusicRepository.getCharts()`, which already surfaces `success:false` as an
     * exception) wins; only when it fails is the `/api/home` fallback consulted.
     * A failure of both is [Outcome.Failed] — never an empty list.
     */
    fun resolve(
        charts: Result<List<MusicTrack>>,
        homeFallback: () -> Result<List<MusicTrack>>,
    ): Outcome = charts.fold(
        onSuccess = { Outcome.Tracks(it) },
        onFailure = { chartsError ->
            homeFallback().fold(
                onSuccess = { Outcome.Tracks(it) },
                onFailure = { homeError ->
                    Outcome.Failed(
                        homeError.message ?: chartsError.message ?: "charts unavailable",
                    )
                },
            )
        },
    )

    /**
     * Typed parse of a raw `/api/home` payload. `success:false` throws
     * [ServerRejectedException]; only a genuinely empty `tracks`/`results` array
     * yields an empty list.
     *
     * Поле `album` разбирается здесь же ([albumRef]): главная и чарты шли через этот
     * ручной парсер, а не через `MusicRepository`, и без него трек терял свой альбом —
     * сервер поле отдаёт, а строка на экране показывала только исполнителя.
     */
    fun parseHomePayload(payload: String): List<MusicTrack> {
        val root = JSONObject(payload)
        if (!root.optBoolean("success", true)) {
            throw ServerRejectedException(
                root.optString("error").ifBlank { "Music request failed" },
            )
        }
        val array = root.optJSONArray("tracks")
            ?: root.optJSONArray("results")
            ?: root.optJSONObject("data")?.optJSONArray("tracks")
            ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val x = array.optJSONObject(i) ?: return@mapNotNull null
            val album = albumRef(x)
            MusicTrack(
                x.optString("id", x.optString("track_id")),
                x.optString("title", "Без названия"),
                x.optString("artist", "Неизвестный артист"),
                x.optString("duration", "--:--"),
                x.optString("thumbnail").takeIf { it.isNotBlank() },
                albumTitle = album?.first,
                albumBrowseId = album?.second,
            )
        }.filter { it.id.isNotBlank() }
    }

    /**
     * Разбор `album` трека: `(имя, browseId)` или null, если альбома нет.
     *
     * Две формы — не догадка, а факт контракта: ytmusicapi отдаёт то объект
     * `{name, id}`, то голую строку названия, и сервер нормализует обе в
     * `{name, browseId}` (см. `AlbumRefDto` в core-network). Голую строку всё равно
     * понимаем — старые сборки сервера отдают её как есть, и терять из-за этого
     * название альбома на главной незачем. Пустой `browseId` — законный случай
     * (альбом не открывается, но имя показывается).
     */
    private fun albumRef(track: JSONObject): Pair<String?, String?>? = when (val raw = track.opt("album")) {
        is JSONObject -> {
            // `optString` на JSON-`null` отдаёт строку "null" (Android org.json), а не
            // пустоту, — такой «browseId» ушёл бы в сеть и вернул 500. Поэтому чистим явно.
            val name = raw.optString("name").cleanField()
            val browseId = (raw.optString("browseId").cleanField() ?: raw.optString("id").cleanField())
            if (name == null) null else name to browseId
        }
        is String -> raw.cleanField()?.let { it to null }
        else -> null
    }

    /** Поле JSON как есть, но без пустых строк и строкового `"null"` от `optString`. */
    private fun String.cleanField(): String? =
        trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
