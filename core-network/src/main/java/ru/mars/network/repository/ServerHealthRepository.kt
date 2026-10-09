package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.api.MarsApi
import ru.mars.network.model.ServerHealthReport
import java.util.concurrent.TimeUnit

/**
 * Lightweight liveness/latency probe for the Mars backend.
 *
 * Performs a plain GET against `baseUrl + endpointPath` with a bounded
 * per-call timeout, measures the wall-clock round-trip time and delegates
 * the verdict to [ServerHealthPolicy]. Never throws: any transport failure
 * is folded into an OFFLINE [ServerHealthReport] carrying the error message.
 */
class ServerHealthRepository(
    private val client: OkHttpClient,
    private val baseUrl: String = MarsApi.BASE_URL,
) {

    /**
     * Probes [endpointPath] (relative to [baseUrl], e.g. `api/status`).
     *
     * @param timeoutMs hard cap for the whole call (connect + read + write).
     * @return a report with the measured latency, resolved URL and verdict.
     */
    suspend fun checkHealth(
        endpointPath: String = DEFAULT_ENDPOINT_PATH,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ServerHealthReport = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(endpointPath.trimStart('/'))
            .build()
        val request = Request.Builder().url(url).get().build()
        val boundedClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val startMs = System.currentTimeMillis()
        try {
            boundedClient.newCall(request).execute().use { response ->
                // Drain the body so latency covers the full round trip, not just headers.
                response.body?.string()
                val latencyMs = System.currentTimeMillis() - startMs
                ServerHealthReport(
                    status = ServerHealthPolicy.evaluateStatus(response.isSuccessful, latencyMs),
                    latencyMs = latencyMs,
                    checkedUrl = url.toString(),
                    message = "HTTP ${response.code}",
                )
            }
        } catch (error: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            ServerHealthReport(
                status = ServerHealthPolicy.evaluateStatus(
                    isSuccessful = false,
                    latencyMs = latencyMs,
                    error = error,
                ),
                latencyMs = latencyMs,
                checkedUrl = url.toString(),
                message = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT_PATH: String = "api/status"
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
    }
}
