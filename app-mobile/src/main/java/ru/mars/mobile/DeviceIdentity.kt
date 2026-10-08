package ru.mars.mobile

import android.os.Build

/**
 * Stable device name for pairing/login requests, derived from the hardware model
 * instead of a hardcoded per-device string.
 */
object DeviceIdentity {
    /** Used when [Build.MODEL] is missing or blank (emulators, stripped ROMs, JVM tests). */
    const val FALLBACK = "mars_android"

    fun stableDeviceName(model: String? = Build.MODEL): String {
        val normalized = model?.trim().orEmpty()
        return if (normalized.isEmpty()) FALLBACK else normalized
    }
}
