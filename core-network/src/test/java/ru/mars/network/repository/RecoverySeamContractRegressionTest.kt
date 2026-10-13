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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Зеркальный контракт между core-network и app-mobile.
 *
 * [ru.mars.mobile.TransportFailureClassifier] решает, запускать ли mid-turn recovery,
 * ЧИСТО ПО ТЕКСТУ `ChatEvent.Error`, и его KDoc требует: список форм «зеркалит
 * core-network/NetworkErrors.kt; расхождение покрыто тестом-эталоном». app-mobile не
 * может вызвать internal `describeNetworkError`, поэтому эталон живёт ЗДЕСЬ: тест
 * прогоняет РЕАЛЬНЫЕ функции ядра по всем веткам исключений и проверяет, что каждая
 * произведённая строка распознавалась бы мобильным классификатором (префиксы
 * продублированы ниже и обязаны правиться вместе с обоими файлами).
 *
 * Дрейф любой ветки (`describeNetworkError` переформулировал фразу, `describeHttpError`
 * сменил формат, retry-after маркер переехал) роняет этот тест ДО того, как мобильный
 * клиент начнёт молча подавлять транспортные обрывы или, хуже, ретраить серверные
 * ошибки хода.
 */
class NetworkErrorsClassifierMirrorTest {

    // Зеркало TransportFailureClassifier.NETWORK_MESSAGE_PREFIXES (app-mobile), lower-case.
    private val mirrorPrefixes = listOf(
        "превышено время ожидания",
        "сервер недоступен: не удалось определить адрес",
        "ошибка защищённого соединения (ssl)",
        "не удалось подключиться к серверу",
        "соединение прервано",
        "сервер неожиданно закрыл соединение",
        "сетевая ошибка:",
        "неизвестная ошибка сети",
    )

    // Зеркало TransportFailureClassifier.HTTP_STATUS_PREFIX и EMPTY_BODY_MESSAGE.
    private val httpStatusPrefix = Regex("""^http\s*\d{3}\b""")
    private val emptyBodyMessage = "пустой ответ сервера"

    /** Ровно та же нормализация, что в мобильном классификаторе (trim + lower-case). */
    private fun isTransportShapedByMirror(message: String): Boolean {
        val normalized = message.trim().lowercase()
        if (httpStatusPrefix.containsMatchIn(normalized)) return true
        if (normalized == emptyBodyMessage) return true
        return mirrorPrefixes.any { normalized.startsWith(it) }
    }

    private fun assertMirrorRecognizes(message: String) {
        assertTrue(
            "форма core-network разошлась с зеркалом мобильного TransportFailureClassifier: «$message»",
            isTransportShapedByMirror(message),
        )
    }

    @Test
    fun everyDescribeNetworkErrorBranchMatchesTheMobileClassifierMirror() {
        // По одной проверке на каждую ветку describeNetworkError (порядок цепочки важен:
        // SocketTimeout до InterruptedIOException, SSLHandshake до SSLException).
        val errors: List<Throwable> = listOf(
            SocketTimeoutException("timeout"),
            SocketTimeoutException(), // null message — фраза ветки без суффикса
            UnknownHostException("api.example.com"),
            UnknownHostException(),
            SSLHandshakeException("handshake failed"),
            SSLException("certificate unknown"),
            ConnectException("connection refused"),
            SocketException("Socket closed"),
            EOFException("unexpected end of stream"),
            InterruptedIOException("interrupted I/O"),
            IOException("stream was reset"),
            RuntimeException(), // fallback: null message → «Неизвестная ошибка сети»
        )
        errors.forEach { error ->
            assertMirrorRecognizes(describeNetworkError(error))
        }
    }

    @Test
    fun wrappedCauseChainIsUnwrappedToATransportPhrase() {
        // OkHttp заворачивает настоящую причину (RouteException/IOException around …):
        // описание обязано достать её из цепочки и остаться в транспортном зеркале.
        assertMirrorRecognizes(describeNetworkError(IOException("route failed", UnknownHostException("host"))))
        assertMirrorRecognizes(describeNetworkError(IOException("wrapped", SocketTimeoutException("read timeout"))))
        assertMirrorRecognizes(describeNetworkError(IOException("outer", IOException("mid", ConnectException("refused")))))
    }

    @Test
    fun rawMessageFallbackPassesThroughAndStaysServerShapedByDesign() {
        // Не-IO throwable c сообщением: describeNetworkError возвращает текст как есть.
        // Мобильный классификатор его НЕ узнаёт — это намеренная консервативность:
        // неизвестная форма считается серверной ошибкой хода и не запускает recovery.
        assertEquals("agent exploded", describeNetworkError(RuntimeException("agent exploded")))
        assertFalse(isTransportShapedByMirror(describeNetworkError(RuntimeException("agent exploded"))))
    }

    @Test
    fun describeHttpErrorShapesStayTransportShapedIncludingRetryAfterMarker() {
        assertMirrorRecognizes(describeHttpError(502, """{"error":"bad gateway"}"""))
        assertMirrorRecognizes(describeHttpError(503, null))
        assertMirrorRecognizes(describeHttpError(401, """{"error":"unauthorized"}"""))
        assertMirrorRecognizes(describeHttpError(404, null))
        assertMirrorRecognizes(describeHttpError(429, """{"error":"slow down"}""", retryAfterSeconds = 30))
    }

    @Test
    fun retryAfterMarkerIsAPureSuffixAndStaysParsableByTheCoordinator() {
        val message = describeHttpError(429, """{"error":"slow down"}""", retryAfterSeconds = 7)
        assertEquals("HTTP 429: slow down", message.removeSuffix(" ${RetryAfterPolicy.marker(7)}"))
        assertEquals(7L, RetryAfterPolicy.parseMarkerSeconds(message))
        // Маркер не ломает мобильное зеркало (префикс «HTTP <code>» не затронут).
        assertMirrorRecognizes(message)
    }
}

/**
 * Добор граничных кейсов [RunJournalCursor], которые основной RunJournalCursorTest не
 * фиксирует: пустой/пробельный high-water (курсор ещё не открыт) и неудававшийся парсинг
 * seq внутри ОДНОГО run — fail-open обязателен, иначе recovery заблокируется на
 * неизвестной форме id.
 */
class RunJournalCursorGapTest {

    @Test
    fun blankHighWaterIsTreatedAsMissingCursor() {
        assertTrue(RunJournalCursor.isAfter("st-1:1", ""))
        assertTrue(RunJournalCursor.isAfter("st-1:1", "   "))
        assertFalse(RunJournalCursor.isAfter("", ""))
        assertFalse(RunJournalCursor.isAfter("   ", null))
    }

    @Test
    fun unparseableSeqInsideSameRunFailsOpenInsteadOfBlockingRecovery() {
        assertTrue(RunJournalCursor.isAfter("st-1:x", "st-1:1"))
        assertTrue(RunJournalCursor.isAfter("st-1:1", "st-1:x"))
        // Равенство строк — всегда дубликат, даже когда seq не парсится.
        assertFalse(RunJournalCursor.isAfter("st-1:x", "st-1:x"))
    }
}
