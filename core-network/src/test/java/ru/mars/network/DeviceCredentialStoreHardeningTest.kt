package ru.mars.network

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the auth-hardening invariant of [ru.mars.network.credential.AndroidKeyStoreCredentialStore]:
 * no embedded/preseeded credential may exist in the binary. An empty storage must mean
 * "signed out" (read() returns null); credentials only ever come from pairing or login.
 *
 * Behavioural instantiation is impossible on the JVM (real AndroidKeyStore + Cipher), so the
 * invariant is asserted structurally over the compiled class constants.
 */
class DeviceCredentialStoreHardeningTest {
    private val storeClass = Class.forName("ru.mars.network.credential.AndroidKeyStoreCredentialStore")
    private val classesToScan = listOf(storeClass) + storeClass.declaredClasses.toList()

    @Test
    fun storeDeclaresNoPreseededCredentialConstants() {
        val suspicious = classesToScan
            .flatMap { it.declaredFields.toList() }
            .map { it.name }
            .filter { name ->
                val upper = name.uppercase()
                upper.contains("PRESEEDED") || upper.contains("EMBEDDED") ||
                    upper.contains("FALLBACK_TOKEN") || upper.contains("DEFAULT_TOKEN")
            }
        assertTrue("Embedded credential constants found: $suspicious", suspicious.isEmpty())
    }

    @Test
    fun storeContainsNoHardcodedTokenOrDeviceIdValues() {
        val stringConstants = classesToScan.flatMap { clazz ->
            clazz.declaredFields.mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(null) as? String
                }.getOrNull()
            }
        }
        assertTrue(
            "Hardcoded secret-like constant found in credential store",
            stringConstants.none { it.startsWith("hdev_") || it.startsWith("dev_") },
        )
    }
}
