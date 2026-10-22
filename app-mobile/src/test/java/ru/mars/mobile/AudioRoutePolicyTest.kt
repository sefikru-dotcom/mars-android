package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маршрут вывода → риск акустической обратной связи.
 *
 * Зачем это тестируется отдельно: политика перебивания на наушниках и на динамике разная, и ошибка
 * в одну сторону стоит дорого — «эха нет» на динамике означает, что цикл начнёт перебивать сам себя.
 * Числа `AudioDeviceInfo.TYPE_*` продублированы в [AudioRoutePolicy], поэтому тест фиксирует
 * соответствие «тип устройства → класс выхода».
 */
class AudioRoutePolicyTest {

    @Test
    fun `все виды наушников считаются наушниками`() {
        val headphoneTypes = listOf(
            AudioRoutePolicy.TYPE_WIRED_HEADSET,
            AudioRoutePolicy.TYPE_WIRED_HEADPHONES,
            AudioRoutePolicy.TYPE_BLUETOOTH_SCO,
            AudioRoutePolicy.TYPE_BLUETOOTH_A2DP,
            AudioRoutePolicy.TYPE_USB_HEADSET,
            AudioRoutePolicy.TYPE_HEARING_AID,
            AudioRoutePolicy.TYPE_BLE_HEADSET,
        )
        headphoneTypes.forEach { type ->
            assertEquals("тип $type", OutputKind.HEADPHONES, AudioRoutePolicy.kindFor(type))
            assertEquals("тип $type", AudioRoute.HEADPHONES, AudioRoutePolicy.routeForDeviceTypes(listOf(type)))
        }
    }

    @Test
    fun `встроенный динамик и док считаются динамиком`() {
        listOf(AudioRoutePolicy.TYPE_BUILTIN_SPEAKER, AudioRoutePolicy.TYPE_DOCK).forEach { type ->
            assertEquals("тип $type", OutputKind.SPEAKER, AudioRoutePolicy.kindFor(type))
            assertEquals("тип $type", AudioRoute.SPEAKER, AudioRoutePolicy.routeForDeviceTypes(listOf(type)))
        }
    }

    @Test
    fun `hdmi и line-out не выдаются за наушники`() {
        listOf(
            AudioRoutePolicy.TYPE_HDMI,
            AudioRoutePolicy.TYPE_LINE_ANALOG,
            AudioRoutePolicy.TYPE_LINE_DIGITAL,
            AudioRoutePolicy.TYPE_USB_DEVICE,
            0,
            999,
        ).forEach { type ->
            assertEquals("тип $type", OutputKind.OTHER, AudioRoutePolicy.kindFor(type))
            assertEquals("тип $type", AudioRoute.UNKNOWN, AudioRoutePolicy.routeForDeviceTypes(listOf(type)))
        }
    }

    @Test
    fun `пустой список устройств — неизвестный маршрут`() {
        assertEquals(AudioRoute.UNKNOWN, AudioRoutePolicy.routeFor(emptyList()))
        assertEquals(AudioRoute.UNKNOWN, AudioRoutePolicy.routeForDeviceTypes(emptyList()))
    }

    @Test
    fun `наушники приоритетнее динамика`() {
        val route = AudioRoutePolicy.routeForDeviceTypes(
            listOf(AudioRoutePolicy.TYPE_BUILTIN_SPEAKER, AudioRoutePolicy.TYPE_BLUETOOTH_A2DP),
        )
        assertEquals("микрофон слышит наушники, а не динамик", AudioRoute.HEADPHONES, route)
    }

    @Test
    fun `только неизвестные устройства не превращаются в наушники`() {
        val route = AudioRoutePolicy.routeForDeviceTypes(
            listOf(AudioRoutePolicy.TYPE_HDMI, AudioRoutePolicy.TYPE_LINE_DIGITAL),
        )
        assertEquals(AudioRoute.UNKNOWN, route)
    }

    @Test
    fun `риск эха есть везде, кроме наушников`() {
        assertFalse(AudioRoute.HEADPHONES.isEchoRisk)
        assertTrue(AudioRoute.SPEAKER.isEchoRisk)
        assertTrue("неизвестный маршрут считаем эхо-опасным", AudioRoute.UNKNOWN.isEchoRisk)
    }
}
