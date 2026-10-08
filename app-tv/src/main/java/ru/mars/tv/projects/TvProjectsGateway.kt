package ru.mars.tv.projects

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Результат transport-слоя: тело ответа либо человекочитаемая причина отказа. */
sealed interface TvProjectsFetchResult {
    data class Success(val body: String) : TvProjectsFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvProjectsFetchResult
}

/**
 * Data-seam между [TvProjectsController] и dashboard API (паттерн TvInteractiveGateway).
 * На устройстве — [HttpTvProjectsGateway]; unit-тесты подставляют fake без HTTP.
 * Аутентификация не требуется: метрики публичны (GET, parity с мобильным ProjectsActivity).
 */
interface TvProjectsGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchMetrics(): TvProjectsFetchResult
}

/**
 * Продакшн-реализация: plain HTTPS GET c таймаутами мобильного ProjectsActivity
 * (connect 8 c, read 10 c). Любой отказ отображается в явную короткую причину;
 * тексты исключений наружу не выпускаются (могут содержать URL/фрагменты ответа).
 */
class HttpTvProjectsGateway(private val url: String) : TvProjectsGateway {

    override fun fetchMetrics(): TvProjectsFetchResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) {
                TvProjectsFetchResult.Failure("HTTP $code")
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                TvProjectsFetchResult.Success(body)
            }
        } catch (e: IOException) {
            TvProjectsFetchResult.Failure("нет соединения")
        } catch (e: RuntimeException) {
            TvProjectsFetchResult.Failure("ошибка запроса")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}
