package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mars.network.model.HealthLevel
import ru.mars.network.repository.ServerHealthPolicy
import java.io.IOException

class ServerHealthPolicyTest {

    // ---- categorizeLatency: default threshold (800 ms) ----

    @Test
    fun zeroLatencyIsHealthy() {
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(0L))
    }

    @Test
    fun fastLatencyIsHealthy() {
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(42L))
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(799L))
    }

    @Test
    fun latencyExactlyAtThresholdIsHealthy() {
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(800L))
    }

    @Test
    fun latencyJustAboveThresholdIsDegraded() {
        assertEquals(HealthLevel.DEGRADED, ServerHealthPolicy.categorizeLatency(801L))
    }

    @Test
    fun latencyUpToThreeTimesThresholdIsDegraded() {
        assertEquals(HealthLevel.DEGRADED, ServerHealthPolicy.categorizeLatency(1500L))
        assertEquals(HealthLevel.DEGRADED, ServerHealthPolicy.categorizeLatency(2400L))
    }

    @Test
    fun latencyAboveThreeTimesThresholdIsOffline() {
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(2401L))
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(60_000L))
    }

    @Test
    fun negativeLatencyIsOffline() {
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(-1L))
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(Long.MIN_VALUE))
    }

    // ---- categorizeLatency: custom threshold ----

    @Test
    fun customThresholdShiftsAllBands() {
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(100L, 100L))
        assertEquals(HealthLevel.DEGRADED, ServerHealthPolicy.categorizeLatency(101L, 100L))
        assertEquals(HealthLevel.DEGRADED, ServerHealthPolicy.categorizeLatency(300L, 100L))
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(301L, 100L))
    }

    @Test
    fun zeroThresholdKeepsOnlyExactZeroHealthy() {
        assertEquals(HealthLevel.HEALTHY, ServerHealthPolicy.categorizeLatency(0L, 0L))
        assertEquals(HealthLevel.OFFLINE, ServerHealthPolicy.categorizeLatency(1L, 0L))
    }

    // ---- evaluateStatus ----

    @Test
    fun successfulFastResponseIsHealthy() {
        assertEquals(
            HealthLevel.HEALTHY,
            ServerHealthPolicy.evaluateStatus(isSuccessful = true, latencyMs = 120L),
        )
    }

    @Test
    fun successfulSlowResponseIsDegraded() {
        assertEquals(
            HealthLevel.DEGRADED,
            ServerHealthPolicy.evaluateStatus(isSuccessful = true, latencyMs = 1200L),
        )
    }

    @Test
    fun unsuccessfulResponseIsOfflineRegardlessOfLatency() {
        assertEquals(
            HealthLevel.OFFLINE,
            ServerHealthPolicy.evaluateStatus(isSuccessful = false, latencyMs = 10L),
        )
    }

    @Test
    fun errorIsOfflineEvenWhenMarkedSuccessful() {
        assertEquals(
            HealthLevel.OFFLINE,
            ServerHealthPolicy.evaluateStatus(isSuccessful = true, latencyMs = 5L, error = IOException("boom")),
        )
    }

    @Test
    fun errorWithFailureIsOffline() {
        assertEquals(
            HealthLevel.OFFLINE,
            ServerHealthPolicy.evaluateStatus(isSuccessful = false, latencyMs = -1L, error = IOException("dns")),
        )
    }

    @Test
    fun nullErrorOnSuccessFallsThroughToLatency() {
        assertEquals(
            HealthLevel.HEALTHY,
            ServerHealthPolicy.evaluateStatus(isSuccessful = true, latencyMs = 10L, error = null),
        )
    }
}
