package ru.mars.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFxPolicyTest {

    private class FakeStorage(initial: AudioFxState = AudioFxState()) : AudioFxStorage {
        var saved: AudioFxState = initial
        var saveCount = 0
        override fun load(): AudioFxState = saved
        override fun save(state: AudioFxState) {
            saved = state
            saveCount++
        }
    }

    // --- Набор пресетов (паритет с WebUI EQ_PRESETS) ---

    @Test fun allSixPresetsCarryRussianTitles() {
        assertEquals(
            listOf(
                AudioFxPreset.FLAT to "Обычный",
                AudioFxPreset.BASS_BOOST to "Усиление басов",
                AudioFxPreset.ROCK to "Рок",
                AudioFxPreset.POP to "Поп",
                AudioFxPreset.VOCAL to "Вокал",
                AudioFxPreset.ELECTRONIC to "Электроника",
            ),
            AudioFxPreset.values().map { it to it.title }
        )
    }

    @Test fun everyPresetCurveHasFiveControlPoints() {
        AudioFxPreset.values().forEach {
            assertEquals(it.name, AudioFxPolicy.CURVE_POINTS, it.curveDb.size)
        }
    }

    // --- Валидация уровня Bass Boost ---

    @Test fun strengthClampsIntoHardwareScale() {
        assertEquals(0, AudioFxPolicy.sanitizeStrength(-15))
        assertEquals(0, AudioFxPolicy.sanitizeStrength(AudioFxPolicy.MIN_BASS_BOOST))
        assertEquals(750, AudioFxPolicy.sanitizeStrength(750))
        assertEquals(1000, AudioFxPolicy.sanitizeStrength(AudioFxPolicy.MAX_BASS_BOOST))
        assertEquals(1000, AudioFxPolicy.sanitizeStrength(4321))
    }

    // --- Восстановление пресета по имени ---

    @Test fun presetByNameFallsBackToFlat() {
        assertSame(AudioFxPreset.ROCK, AudioFxPolicy.presetByName("ROCK"))
        assertSame(AudioFxPreset.FLAT, AudioFxPolicy.presetByName(null))
        assertSame(AudioFxPreset.FLAT, AudioFxPolicy.presetByName(""))
        assertSame(AudioFxPreset.FLAT, AudioFxPolicy.presetByName("club"))
    }

    // --- Расчёт полос эквалайзера ---

    @Test fun flatPresetIsSilentOnAnyBandCount() {
        assertArrayEquals(
            shortArrayOf(0, 0, 0, 0, 0),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.FLAT, 5, -1500, 1500)
        )
        assertArrayEquals(
            shortArrayOf(0, 0, 0),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.FLAT, 3, -1500, 1500)
        )
    }

    @Test fun fiveBandsMapCurveExactlyInMillibels() {
        assertArrayEquals(
            shortArrayOf(900, 500, -100, 0, 200),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.BASS_BOOST, 5, -1500, 1500)
        )
        assertArrayEquals(
            shortArrayOf(-200, 100, 600, 300, 300),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.VOCAL, 5, -1500, 1500)
        )
    }

    @Test fun fewerBandsInterpolateControlPoints() {
        // 3 полосы: позиции 0, 2 и 4 кривой — первая, средняя и последняя контрольные точки.
        assertArrayEquals(
            shortArrayOf(900, -100, 200),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.BASS_BOOST, 3, -1500, 1500)
        )
        // 1 полоса: центр кривой.
        assertArrayEquals(
            shortArrayOf(-100),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.BASS_BOOST, 1, -1500, 1500)
        )
    }

    @Test fun levelsClampToDeviceRange() {
        assertArrayEquals(
            shortArrayOf(300, 300, 0, 0, 200),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.BASS_BOOST, 5, 0, 300)
        )
        // Перевёрнутый диапазон нормализуется, а не падает.
        assertArrayEquals(
            shortArrayOf(300, 300, 0, 0, 200),
            AudioFxPolicy.bandLevelsMb(AudioFxPreset.BASS_BOOST, 5, 300, 0)
        )
    }

    @Test fun zeroOrNegativeBandCountIsEmpty() {
        assertEquals(0, AudioFxPolicy.bandLevelsMb(AudioFxPreset.ROCK, 0, -1500, 1500).size)
        assertEquals(0, AudioFxPolicy.bandLevelsMb(AudioFxPreset.ROCK, -3, -1500, 1500).size)
    }

    // --- Хранилище и настройки ---

    @Test fun defaultsToFlatDisabledZeroBoost() {
        val settings = AudioFxSettings(FakeStorage())
        assertEquals(AudioFxState(), settings.state)
    }

    @Test fun setPresetSwitchesAndPersists() {
        val storage = FakeStorage()
        val settings = AudioFxSettings(storage)
        settings.setPreset(AudioFxPreset.ELECTRONIC)
        assertEquals(AudioFxPreset.ELECTRONIC, settings.state.preset)
        assertEquals(AudioFxPreset.ELECTRONIC, storage.saved.preset)
        assertEquals(1, storage.saveCount)
    }

    @Test fun togglesEnabledBackAndForth() {
        val storage = FakeStorage()
        val settings = AudioFxSettings(storage)
        assertTrue(settings.setEnabled(true).enabled)
        assertFalse(settings.setEnabled(false).enabled)
        assertFalse(storage.saved.enabled)
        assertEquals(2, storage.saveCount)
    }

    @Test fun setBassBoostClampsAndPersists() {
        val storage = FakeStorage()
        val settings = AudioFxSettings(storage)
        assertEquals(650, settings.setBassBoost(650).bassBoost)
        assertEquals(1000, settings.setBassBoost(5000).bassBoost)
        assertEquals(0, settings.setBassBoost(-8).bassBoost)
        assertEquals(0, storage.saved.bassBoost)
    }

    @Test fun restoredStateIsSanitized() {
        val dirty = AudioFxState(preset = AudioFxPreset.ROCK, enabled = true, bassBoost = 4242)
        val settings = AudioFxSettings(FakeStorage(dirty))
        assertEquals(AudioFxState(AudioFxPreset.ROCK, true, 1000), settings.state)
    }

    @Test fun mutationsKeepOtherFields() {
        val settings = AudioFxSettings(FakeStorage(AudioFxState(AudioFxPreset.POP, true, 300)))
        settings.setPreset(AudioFxPreset.VOCAL)
        assertEquals(AudioFxState(AudioFxPreset.VOCAL, true, 300), settings.state)
        settings.setBassBoost(800)
        assertEquals(AudioFxState(AudioFxPreset.VOCAL, true, 800), settings.state)
    }
}
