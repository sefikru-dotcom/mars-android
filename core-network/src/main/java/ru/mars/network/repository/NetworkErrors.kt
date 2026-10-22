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
 * Newer contract failures carry the reason under `code` instead of `error` — notably
 * `409 {"code": "unsupported_voice_mode_backend"}` (SERVER-CONTRACT.md §1). Both keys are
 * read, `error` first for backward compatibility, so a `code`-only refusal never degrades
 * into a bare "HTTP 409" that the UI could not explain or classify.
 *
 * Result format: `"HTTP <code>: <server reason>"` when the body parses and carries a
 * non-blank `error`/`code`, else the plain `"HTTP <code>"`. Both shapes stay compatible
 * with the app-level substring policies (AuthRejectionPolicy "http 401" / "unauthorized",
 * StreamReattachPolicy `\b404\b` / "not found").
 *
 * [retryAfterSeconds] — the parsed `Retry-After` response header (RFC 9110 §10.2.3,
 * typically on 429/503 from a throttling proxy or a restarting backend), see
 * [RetryAfterPolicy]. When present, the ANCHORED marker `"(retry-after <N>s)"` is
 * appended so [MidTurnRecoveryCoordinator] can honor the server-directed wait instead
 * of burning its reconnect ladder against a server that asked to wait. It is a pure
 * suffix: every app-level policy keys on the message prefix or on substrings that the
 * marker never touches.
 */
internal fun describeHttpError(code: Int, body: String?, retryAfterSeconds: Long? = null): String {
    val parsed = StreamEventMapper.parseObject(body.orEmpty())
    // `error` — старый контракт; `code` — нынешние отказы контракта (409
    // unsupported_voice_mode_backend). Порядок важен: старый ключ не должен проигрывать,
    // но ПУСТОЙ `error` не имеет права скрывать причину из `code` — иначе отказ контракта
    // деградировал бы до голого "HTTP 409", который UI не может ни объяснить, ни
    // классифицировать (app-level VoiceModeRejectionPolicy ищет и код, и маркер причины).
    val serverMessage = listOfNotNull(parsed?.get("error"), parsed?.get("code"))
        .map { it.toString().trim() }
        .firstOrNull { it.isNotBlank() }
    val base = if (serverMessage != null) "HTTP $code: $serverMessage" else "HTTP $code"
    return retryAfterSeconds?.let { "$base ${RetryAfterPolicy.marker(it)}" } ?: base
}

/**
 * Машинный код отказа из тела HTTP-ответа (поле `code`), без человеческого текста.
 *
 * Зачем отдельно от [describeHttpError]. Строка ошибки — для человека: она берёт ПЕРВОЕ
 * непустое поле тела, а реальный отказ контракта приходит ДВУМЯ полями сразу:
 *
 * ```
 * HTTP 409 {"error": "voice_mode is not supported by the runner backend.",
 *           "code": "unsupported_voice_mode_backend"}
 * ```
 *
 * поэтому в тексте оказывается формулировка сервера, а код в неё не попадает. Приложение,
 * которое классифицировало отказ по подстроке кода, переставало его узнавать: ход уходил в
 * офлайн-очередь и повторялся с `voice_mode`, который сервер отвергает детерминированно.
 * Классификация должна опираться на это поле, а не на формулировку: текст меняется между
 * бэкендами и локалями, код — часть контракта.
 *
 * @return код из `code` либо `null`, если тело не JSON, поля нет или оно пустое.
 */
internal fun httpErrorCode(body: String?): String? {
    val parsed = StreamEventMapper.parseObject(body.orEmpty()) ?: return null
    return parsed["code"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
}
