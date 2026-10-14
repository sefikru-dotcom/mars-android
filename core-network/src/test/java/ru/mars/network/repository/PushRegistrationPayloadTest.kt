package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PushRegistrationPayload] — чистая функция формирования тела регистрации
 * FCM-токена на сервере Марса. Контракт (согласован с серверной задачей wave4):
 * `POST /api/push/fcm/register` c телом
 * `{"token": ..., "platform": "android", "device_name": ...}`.
 *
 * Тело собирается строками с общим для модуля строгим RFC 8259 экранированием
 * ([JsonStringEncoding]): сервер парсит его Python `json.loads` в strict-режиме,
 * поэтому управляющие символы и кавычки в токене/имени устройства обязаны быть
 * экранированы, а порядок полей — фиксирован (детерминированные тесты).
 */
class PushRegistrationPayloadTest {

    @Test
    fun `register path is the single place holding the URL`() {
        assertEquals("api/push/fcm/register", PushRegistrationPayload.REGISTER_PATH)
    }

    @Test
    fun `body is built with fixed field order and android platform by default`() {
        assertEquals(
            """{"token":"fcm-tok","platform":"android","device_name":"Pixel 8"}""",
            PushRegistrationPayload.body(token = "fcm-tok", deviceName = "Pixel 8"),
        )
    }

    @Test
    fun `platform can be overridden explicitly`() {
        assertEquals(
            """{"token":"t","platform":"android-tv","device_name":"Mi TV"}""",
            PushRegistrationPayload.body(token = "t", deviceName = "Mi TV", platform = "android-tv"),
        )
    }

    @Test
    fun `quotes, backslashes and control characters are strictly escaped`() {
        assertEquals(
            """{"token":"a\"b\\c","platform":"android","device_name":"line1\nline2\ttab\u0001ctl"}""",
            PushRegistrationPayload.body(
                token = "a\"b\\c",
                deviceName = "line1\nline2\ttab\u0001ctl",
            ),
        )
    }

    @Test
    fun `non-ascii passes through verbatim as UTF-8`() {
        assertEquals(
            """{"token":"токен","platform":"android","device_name":"Ми ТВ"}""",
            PushRegistrationPayload.body(token = "токен", deviceName = "Ми ТВ"),
        )
    }
}
