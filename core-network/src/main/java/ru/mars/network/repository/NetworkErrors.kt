package ru.mars.network.repository

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Maps transport-level failures onto short, user-comprehensible Russian messages for
 * `ChatEvent.Error`. Raw exception texts ("timeout", "Socket closed", null) are useless in
 * the UI and — worse — a null message used to degrade into a generic "Network error".
 *
 * Typical real-world causes on a phone (POCO F5, Wi-Fi <-> LTE handover):
 * - [SocketTimeoutException] — the radio stalled mid-stream or the agent is silent past
 *   the read timeout;
 * - [UnknownHostException] — DNS failed because connectivity just vanished;
 * - [SSLHandshakeException]/[SSLException] — half-established sockets after a handover,
 *   captive portals, proxies;
 * - [SocketException]/[EOFException] — the OS killed the socket (connection reset /
 *   unexpected end of stream) when the active network changed under an in-flight SSE stream.
 * HTTP/2 resets (okhttp StreamResetException) are IOExceptions and fall into the same bucket.
 *
 * OkHttp frequently wraps the real cause (RouteException/IOException around
 * UnknownHostException or SSL failures), so the whole cause chain is inspected, most
 * specific type first. Order matters within the chain checks too: SocketTimeoutException
 * before InterruptedIOException, SSLHandshakeException before SSLException.
 */
internal fun describeNetworkError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(10).toList()
    chain.firstOrNull { it is SocketTimeoutException }?.let {
        return "Превышено время ожидания ответа сервера" +
            (it.message?.takeIf(String::isNotBlank)?.let { m -> " ($m)" } ?: "") +
            ". Проверьте соединение и повторите попытку."
    }
    chain.firstOrNull { it is UnknownHostException }?.let {
        val host = it.message?.takeIf(String::isNotBlank)
        return "Сервер недоступен: не удалось определить адрес" +
            (host?.let { h -> " ($h)" } ?: "") + ". Проверьте интернет-соединение."
    }
    chain.firstOrNull { it is SSLHandshakeException }?.let {
        return "Ошибка защищённого соединения (SSL): " + (it.message ?: "handshake failed") +
            ". Сертификат недействителен или сеть перехватывает трафик."
    }
    chain.firstOrNull { it is SSLException }?.let {
        return "Ошибка защищённого соединения (SSL): " + (it.message ?: "SSL failure")
    }
    chain.firstOrNull { it is ConnectException }?.let {
        return "Не удалось подключиться к серверу: " + (it.message ?: "connection refused")
    }
    chain.firstOrNull { it is SocketException }?.let {
        return "Соединение прервано (сеть переключилась или сервер закрыл сокет): " +
            (it.message ?: "socket error")
    }
    chain.firstOrNull { it is EOFException }?.let {
        return "Сервер неожиданно закрыл соединение: " + (it.message ?: "unexpected end of stream")
    }
    chain.firstOrNull { it is InterruptedIOException }?.let {
        return "Соединение прервано: " + (it.message ?: "interrupted I/O")
    }
    chain.firstOrNull { it is IOException }?.let {
        return "Сетевая ошибка: " + (it.message ?: "не удалось выполнить запрос")
    }
    return error.message ?: "Неизвестная ошибка сети"
}

/**
 * HTTP-level failure of a chat request (non-2xx `POST /api/chat/start`, the SSE `GET`
 * or the legacy `/chat`): keeps the status code AND surfaces the server's JSON reason.
 *
 * The WebUI backend answers failures with `{"error": "<reason>"}` (routes.py: 400
 * "message is required", 401 "unauthorized", 404 "stream not found", 502 gateway …).
 * A bare "HTTP 400" bubble gives the user nothing to act on, while the reason was
 * already on the wire. Same pattern the rest of the module uses (uploadAttachment,
 * SessionRepository.createSession).
 *
 * Result format: `"HTTP <code>: <server error>"` when the body parses and carries a
 * non-blank `error`, else the plain `"HTTP <code>"`. Both shapes stay compatible with
 * the app-level substring policies (AuthRejectionPolicy "http 401" / "unauthorized",
 * StreamReattachPolicy `\b404\b` / "not found").
 */
internal fun describeHttpError(code: Int, body: String?): String {
    val serverMessage = StreamEventMapper.parseObject(body.orEmpty())
        ?.get("error")?.toString()?.trim()?.takeIf { it.isNotBlank() }
    return if (serverMessage != null) "HTTP $code: $serverMessage" else "HTTP $code"
}
