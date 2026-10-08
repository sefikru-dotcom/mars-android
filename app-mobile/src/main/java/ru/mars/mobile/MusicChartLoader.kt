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
            MusicTrack(
                x.optString("id", x.optString("track_id")),
                x.optString("title", "Без названия"),
                x.optString("artist", "Неизвестный артист"),
                x.optString("duration", "--:--"),
                x.optString("thumbnail").takeIf { it.isNotBlank() },
            )
        }.filter { it.id.isNotBlank() }
    }
}
