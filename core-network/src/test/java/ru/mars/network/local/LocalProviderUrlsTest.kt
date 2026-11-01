package ru.mars.network.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Нормализация адреса прямого провайдера и построение путей.
 *
 * Почему это тест, а не мелочь: человек вводит адрес руками («plusvibeapi.ru/v1/»,
 * «http://host/v1», пустое поле), и любая ошибка здесь выглядит как «неверный ключ» или
 * 404 на ровном месте. Пути `chat/completions` и `models` обязаны строиться из ОДНОГО
 * нормализованного адреса, иначе проверка ключа ходит по одному URL, а чат — по другому.
 */
class LocalProviderUrlsTest {

    @Test
    fun blankInputStaysBlankInsteadOfBecomingHttpsScheme() {
        assertEquals("", LocalProviderUrls.normalizeBaseUrl(""))
        assertEquals("", LocalProviderUrls.normalizeBaseUrl("   "))
    }

    @Test
    fun bareHostGetsHttpsAndTrailingSlashIsTrimmed() {
        assertEquals("https://plusvibeapi.ru/v1", LocalProviderUrls.normalizeBaseUrl("plusvibeapi.ru/v1/"))
    }

    @Test
    fun explicitHttpSchemeIsPreserved() {
        // Локальный/тестовый провайдер без TLS должен остаться http — иначе тесты и
        // self-hosted шлюзы ломаются на «https к http-порту».
        assertEquals("http://host/v1", LocalProviderUrls.normalizeBaseUrl("http://host/v1"))
        assertEquals("http://127.0.0.1:8080/v1", LocalProviderUrls.normalizeBaseUrl("http://127.0.0.1:8080/v1/"))
    }

    @Test
    fun httpsSchemeIsPreservedAndNormalizationIsIdempotent() {
        val once = LocalProviderUrls.normalizeBaseUrl("https://plusvibeapi.ru/v1/")
        assertEquals("https://plusvibeapi.ru/v1", once)
        assertEquals(once, LocalProviderUrls.normalizeBaseUrl(once))
    }

    @Test
    fun allTrailingSlashesAreTrimmed() {
        assertEquals("https://host/v1", LocalProviderUrls.normalizeBaseUrl("https://host/v1///"))
        assertEquals("https://host", LocalProviderUrls.normalizeBaseUrl("https://host/"))
        assertEquals("https://host", LocalProviderUrls.normalizeBaseUrl("host//"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://host/v1", LocalProviderUrls.normalizeBaseUrl("  https://host/v1/  "))
    }

    @Test
    fun chatCompletionsUrlIsBuiltFromNormalizedBase() {
        assertEquals(
            "https://plusvibeapi.ru/v1/chat/completions",
            LocalProviderUrls.chatCompletionsUrl("plusvibeapi.ru/v1/"),
        )
        // Уже нормализованный адрес даёт тот же путь — без двойного слэша.
        assertEquals(
            "https://plusvibeapi.ru/v1/chat/completions",
            LocalProviderUrls.chatCompletionsUrl("https://plusvibeapi.ru/v1"),
        )
    }

    @Test
    fun modelsUrlIsBuiltFromNormalizedBase() {
        assertEquals(
            "https://plusvibeapi.ru/v1/models",
            LocalProviderUrls.modelsUrl("plusvibeapi.ru/v1/"),
        )
        assertEquals(
            "https://plusvibeapi.ru/v1/models",
            LocalProviderUrls.modelsUrl("https://plusvibeapi.ru/v1"),
        )
    }

    @Test
    fun bothPathsShareTheSameNormalizedBase() {
        // Инвариант из контракта: единая точка сборки адреса. Проверяем на «грязном» вводе,
        // чтобы расхождение «в чате один слэш, в проверке ключа два» падало здесь, а не в проде.
        val raw = "  plusvibeapi.ru/v1//  "
        val normalized = LocalProviderUrls.normalizeBaseUrl(raw)
        assertEquals("$normalized/chat/completions", LocalProviderUrls.chatCompletionsUrl(raw))
        assertEquals("$normalized/models", LocalProviderUrls.modelsUrl(raw))
    }
}
