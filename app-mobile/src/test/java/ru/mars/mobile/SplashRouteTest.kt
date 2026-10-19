package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Маршрут заставки — единственное решение, которое экран принимает о навигации.
 *
 * Тест держит контракт «токен есть -> чат, токена нет -> логин» отдельно от
 * Android: если кто-то захочет «на всякий случай» показать логин при битом
 * keystore или наоборот открыть чат без токена, падает здесь, а не в проде на
 * холодном старте.
 */
class SplashRouteTest {

    @Test
    fun `stored credential goes straight to chat`() {
        assertEquals(
            "С сохранённым токеном заставка обязана вести в чат, минуя логин",
            SplashRoute.Destination.CHAT,
            SplashRoute.destinationFor(hasStoredCredential = true)
        )
    }

    @Test
    fun `missing credential goes to login`() {
        assertEquals(
            "Без токена заставка обязана вести на экран авторизации",
            SplashRoute.Destination.LOGIN,
            SplashRoute.destinationFor(hasStoredCredential = false)
        )
    }

    @Test
    fun `route is a pure function of credential presence`() {
        // Повторный вызов не зависит от состояния: маршрут не кешируется и не
        // «залипает» после logout внутри того же процесса.
        assertEquals(SplashRoute.Destination.CHAT, SplashRoute.destinationFor(true))
        assertEquals(SplashRoute.Destination.LOGIN, SplashRoute.destinationFor(false))
        assertEquals(SplashRoute.Destination.CHAT, SplashRoute.destinationFor(true))
    }
}
