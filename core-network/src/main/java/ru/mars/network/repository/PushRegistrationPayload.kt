package ru.mars.network.repository

/**
 * Формирование тела регистрации FCM-токена на сервере Марса — чистая функция,
 * JVM-тесты в PushRegistrationPayloadTest.
 *
 * Контракт волны 4 (ЕДИНОЕ место, где зашиты путь и форма тела; серверная
 * половина должна сойтись именно с ним):
 *
 *     POST /api/push/fcm/register
 *     {"token": "<fcm-token>", "platform": "android", "device_name": "<model>"}
 *
 * Экранирование — общий для модуля строгий RFC 8259 [JsonStringEncoding]
 * (сервер парсит тело Python `json.loads` в strict-режиме: сырые control
 * символы запрещены). Порядок ключей и байты тела фиксированы: тест сверяет
 * строку посимвольно.
 */
internal object PushRegistrationPayload {

    /** Путь регистрации токена относительно `MarsApi.BASE_URL` — один на клиент. */
    const val REGISTER_PATH = "api/push/fcm/register"

    /** Платформа клиента в теле регистрации. */
    const val PLATFORM_ANDROID = "android"

    /** Тело запроса регистрации: токен FCM, платформа и человекочитаемое имя устройства. */
    fun body(token: String, deviceName: String, platform: String = PLATFORM_ANDROID): String =
        """{"token":${JsonStringEncoding.escape(token)},""" +
            """"platform":${JsonStringEncoding.escape(platform)},""" +
            """"device_name":${JsonStringEncoding.escape(deviceName)}}"""
}
