package ru.mars.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож микрофона: переходы состояний без Android.
 *
 * Почему тестом, а не «посмотрим на телефоне»: обе ошибки здесь тихие и дорогие. Слишком
 * терпеливый сторож оставляет человека говорить в мёртвый микрофон (фаза «Слушаю», кнопка
 * живая, реакции нет). Слишком строгий — перезапускает ЖИВОЙ захват и теряет начало фразы,
 * а второй провал объявляет там, где всё работало.
 *
 * Часы подменяются числами: три секунды проверяются мгновенно и без флаки.
 */
class MicCaptureWatchdogTest {

    private fun watchdog() = MicCaptureWatchdog(timeoutMs = 3_000L)

    @Test
    fun silentCaptureIsRestartedOnceAfterThreeSeconds() {
        val dog = watchdog()
        dog.onCaptureStarted(nowMs = 0L)

        assertEquals("до срока ничего не делаем", MicCaptureWatchdog.Decision.WAIT, dog.tick(2_999L))
        assertEquals("три секунды тишины — перезапуск", MicCaptureWatchdog.Decision.RESTART, dog.tick(3_000L))
        assertEquals("израсходован ровно один перезапуск", 1, dog.restarts)
        assertEquals(MicCaptureWatchdog.State.RESTARTED, dog.state)
    }

    @Test
    fun secondSilenceAfterRestartGivesUp() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        // Пауза 150 мс между stop() и init(): перезапуск объявляется чуть позже срока.
        dog.onRestartStarted(3_150L)

        assertEquals("новый захват получил свои три секунды", MicCaptureWatchdog.Decision.WAIT, dog.tick(6_149L))
        assertEquals(
            "повторная тишина — сдаёмся, а не перезапускаем вечно",
            MicCaptureWatchdog.Decision.GIVE_UP,
            dog.tick(6_150L),
        )
        assertEquals(MicCaptureWatchdog.State.FAILED, dog.state)
    }

    @Test
    fun giveUpIsNotRepeatedByEveryTick() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        dog.onRestartStarted(3_150L)
        dog.tick(6_150L)

        assertEquals("решение выдаётся один раз", MicCaptureWatchdog.Decision.WAIT, dog.tick(9_000L))
        assertEquals(MicCaptureWatchdog.State.FAILED, dog.state)
    }

    @Test
    fun audioChunkMakesWatchdogSilent() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.onAudioChunk()

        assertTrue("звук пошёл — микрофон здоров", dog.healthy)
        assertEquals("и сторож больше ничего не решает", MicCaptureWatchdog.Decision.WAIT, dog.tick(60_000L))
        assertEquals("перезапусков не было", 0, dog.restarts)
    }

    @Test
    fun audioAfterRestartStopsTheWatchdog() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        dog.onRestartStarted(3_150L)
        dog.onAudioChunk()

        assertTrue(dog.healthy)
        assertEquals(MicCaptureWatchdog.Decision.WAIT, dog.tick(10_000L))
        assertEquals("перезапуск не превращается в сдачу", MicCaptureWatchdog.State.HEALTHY, dog.state)
    }

    @Test
    fun lateChunkDoesNotUndoGiveUp() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        dog.onRestartStarted(3_150L)
        dog.tick(6_150L)
        // Захват уже закрыт, буфер пришёл «вдогонку» — режим всё равно остановлен.
        dog.onAudioChunk()

        assertFalse(dog.healthy)
        assertEquals(MicCaptureWatchdog.State.FAILED, dog.state)
    }

    @Test
    fun newUtteranceGetsAFreshChance() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        dog.onRestartStarted(3_150L)
        dog.tick(6_150L)

        // Новый тап человека: правило «один перезапуск» считается заново, иначе после одного
        // сбоя микрофон отказывал бы до перезапуска приложения.
        dog.onCaptureStarted(20_000L)

        assertEquals(0, dog.restarts)
        assertEquals(MicCaptureWatchdog.State.WAITING, dog.state)
        assertEquals(MicCaptureWatchdog.Decision.RESTART, dog.tick(23_000L))
    }

    @Test
    fun resetReturnsToIdle() {
        val dog = watchdog()
        dog.onCaptureStarted(0L)
        dog.tick(3_000L)
        dog.reset()

        assertEquals(MicCaptureWatchdog.State.IDLE, dog.state)
        assertEquals(0, dog.restarts)
        assertEquals("без нового старта решение не выдаётся", MicCaptureWatchdog.Decision.WAIT, dog.tick(90_000L))
    }
}
