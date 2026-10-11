package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эталонные строки — зерсало core-network/NetworkErrors.kt (`describeNetworkError`,
 * `describeHttpError`) и `ChatRepository.stream` («Пустой ответ сервера»): только они
 * считаются транспортным обрывом. Серверные tool/app-ошибки (журнал хода) — нет.
 */
class TransportFailureClassifierTest {

    private val classifier = TransportFailureClassifier()

    @Test
    fun recognizesDescribeNetworkErrorShapes() {
        // Формы describeNetworkError по типам исключений (SocketTimeout, UnknownHost,
        // SSLHandshake/SSL, Connect, Socket, EOF, InterruptedIO, IOException, fallback).
        listOf(
            "Превышено время ожидания ответа сервера: timeout",
            "Сервер недоступен: не удалось определить адрес api.example.com",
            "Ошибка защищённого соединения (SSL): handshake failed",
            "Ошибка защищённого соединения (SSL): certificate unknown",
            "Не удалось подключиться к серверу: connection refused",
            "Соединение прервано (сеть переключилась или сервер закрыл сокет): EOF",
            "Соединение прервано: interrupted I/O",
            "Сервер неожиданно закрыл соединение: unexpected end of stream",
            "Сетевая ошибка: stream was reset",
            "Неизвестная ошибка сети",
        ).forEach { message ->
            assertTrue("не распознан транспорт: $message", classifier.isTransportShaped(message))
        }
    }

    @Test
    fun recognizesDescribeHttpErrorShapes() {
        listOf(
            "HTTP 502: bad gateway",
            "HTTP 503",
            "HTTP 500: internal server error",
            "HTTP 429: too many requests",
            "HTTP 401: unauthorized",
            "http 404: stream not found",
        ).forEach { message ->
            assertTrue("не распознан транспорт: $message", classifier.isTransportShaped(message))
        }
    }

    @Test
    fun recognizesEmptyBodyAsTransport() {
        assertTrue(classifier.isTransportShaped("Пустой ответ сервера"))
    }

    @Test
    fun serverToolErrorsAreNotTransport() {
        listOf(
            "command not found: git push --force",
            "File not found: /tmp/x",
            "tool call failed: permission denied",
            "HTTP client sent a malformed request", // «HTTP» без кода — не describeHttpError
            "модель отказалась отвечать",
            " ",
        ).forEach { message ->
            assertFalse("серверная ошибка хода классифицирована как транспорт: $message", classifier.isTransportShaped(message))
        }
        assertFalse(classifier.isTransportShaped(null))
    }
}
