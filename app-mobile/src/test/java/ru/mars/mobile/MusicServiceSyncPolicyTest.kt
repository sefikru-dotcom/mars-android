package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-тесты [MusicServiceSyncPolicy] плюс контракт проводки в [MusicPlayerBarSection].
 *
 * Ключевой регресс: сервис может остановиться сам (свайп приложения из recent в паузе,
 * пустая очередь) или быть убит системой, а флаг в Activity остаться `true`. Старая
 * логика `track != null && !musicServiceStarted` не поднимала сервис повторно — играть
 * приходилось без уведомления и без foreground-режима. Тест `playingReassertsService`
 * падает на старом поведении и проходит на новом.
 */
class MusicServiceSyncPolicyTest {

    @Test
    fun firstTrackStartsService() {
        val d = MusicServiceSyncPolicy.resolve(
            hasTrack = true, isPlaying = false, previouslyStarted = false
        )
        assertTrue(d.startService)
        assertTrue(d.rememberStarted)
    }

    @Test
    fun playingReassertsService() {
        // Сервис мог умереть, а флаг — остаться true: при воспроизведении он обязан
        // подтверждаться каждый кадр состояния.
        val d = MusicServiceSyncPolicy.resolve(
            hasTrack = true, isPlaying = true, previouslyStarted = true
        )
        assertTrue("старая логика здесь возвращала отказ и теряла FGS", d.startService)
        assertTrue(d.rememberStarted)
    }

    @Test
    fun pausedTrackWithStartedServiceDoesNothing() {
        val d = MusicServiceSyncPolicy.resolve(
            hasTrack = true, isPlaying = false, previouslyStarted = true
        )
        assertFalse(d.startService)
        assertTrue(d.rememberStarted)
    }

    @Test
    fun emptyPlayerResetsTheFlag() {
        val d = MusicServiceSyncPolicy.resolve(
            hasTrack = false, isPlaying = false, previouslyStarted = true
        )
        assertFalse(d.startService)
        assertFalse(d.rememberStarted)
    }

    @Test
    fun deadFlagWithNoTrackStaysReset() {
        val d = MusicServiceSyncPolicy.resolve(
            hasTrack = false, isPlaying = true, previouslyStarted = false
        )
        assertFalse(d.startService)
        assertFalse(d.rememberStarted)
    }

    @Test
    fun promoterIsWiredIntoTheBar() {
        val section = source("src/main/java/ru/mars/mobile/MusicPlayerBarSection.kt")
        assertTrue(
            "синхронизация сервиса обязана идти через политику",
            section.contains("MusicServiceSyncPolicy.resolve("),
        )
        assertTrue(
            "решение политики обязано применяться к флагу экрана",
            section.contains("musicServiceStarted = decision.rememberStarted"),
        )
        assertTrue(
            "решение политики обязано запускать сервис",
            section.contains("if (decision.startService) MarsMusicService.start(this)"),
        )
        assertFalse(
            "старая проверка `track != null && !musicServiceStarted` не восстанавливает убитый сервис",
            section.contains("track != null && !musicServiceStarted"),
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }
}
