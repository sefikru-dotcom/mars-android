package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StreamQualitySettingsTest {
    private class FakeStorage(initial: StreamQuality = StreamQuality.DEFAULT) : StreamQualityStorage {
        var stored = initial
        var saveCount = 0

        override fun load(): StreamQuality = stored

        override fun save(quality: StreamQuality) {
            stored = quality
            saveCount++
        }
    }

    @Test
    fun restoresAndPersistsExplicitProfile() {
        val storage = FakeStorage(StreamQuality.STANDARD)
        val settings = StreamQualitySettings(storage)

        assertSame(StreamQuality.STANDARD, settings.value)
        assertSame(StreamQuality.ORIGINAL, settings.set(StreamQuality.ORIGINAL))
        assertSame(StreamQuality.ORIGINAL, settings.value)
        assertSame(StreamQuality.ORIGINAL, storage.stored)
        assertEquals(1, storage.saveCount)
    }

    @Test
    fun defaultRemainsTheSafeFallback() {
        val storage = FakeStorage()
        val settings = StreamQualitySettings(storage)

        assertSame(StreamQuality.DEFAULT, settings.value)
        settings.set(StreamQuality.ECONOMY)
        assertSame(StreamQuality.ECONOMY, settings.value)
        settings.set(StreamQuality.DEFAULT)
        assertSame(StreamQuality.DEFAULT, settings.value)
        assertSame(StreamQuality.DEFAULT, storage.stored)
    }

    @Test
    fun selectableProfilesExcludeInternalDefault() {
        assertEquals(
            listOf(StreamQuality.ORIGINAL, StreamQuality.STANDARD, StreamQuality.ECONOMY),
            StreamQuality.selectable,
        )
    }
}
