package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun usesTrimmedDeviceModel() {
        assertEquals("Pixel 8 Pro", DeviceIdentity.stableDeviceName("  Pixel 8 Pro "))
    }

    @Test
    fun fallsBackWhenModelIsNull() {
        assertEquals(DeviceIdentity.FALLBACK, DeviceIdentity.stableDeviceName(null))
    }

    @Test
    fun fallsBackWhenModelIsBlank() {
        assertEquals(DeviceIdentity.FALLBACK, DeviceIdentity.stableDeviceName("   "))
    }

    @Test
    fun defaultModelLookupNeverYieldsBlankName() {
        // On the JVM Build.MODEL resolves to the mockable default (null) — the guard must
        // still produce a usable, non-blank name; on a device it yields the real model.
        val name = DeviceIdentity.stableDeviceName()
        assertTrue(name.isNotBlank())
        assertNotEquals("poco_f5", name)
    }
}
