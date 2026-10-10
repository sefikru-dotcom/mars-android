package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM-тесты [AdaptiveSseReconnectPolicy]: экспоненциальный рост задержек с потолком,
 * джиттер в пределах полосы, исчерпание бюджета [maxRetries] и — главное отличие от
 * [StreamRecoveryBackoffPolicy] — сброс лестницы и бюджета на первом успешном
 * токене/событии стрима ([AdaptiveSseReconnectPolicy.onStreamEvent]).
 */
class AdaptiveSseReconnectPolicyTest {

    /** Детерминированная политика: без джиттера, 100 мс -> 200 -> 400 … с потолком 10 с. */
    private fun flatPolicy(maxRetries: Int = 8) = AdaptiveSseReconnectPolicy(
        initialDelayMs = 100L,
        maxDelayMs = 10_000L,
        jitterFraction = 0.0,
        maxRetries = maxRetries,
        random = Random(1),
    )

    // ---------- экспоненциальная лестница ----------

    @Test
    fun delayGrowsExponentiallyAcrossConsecutiveFailures() {
        val policy = flatPolicy()
        val delays = (1..5).map { policy.onStreamFailure()?.delayMs }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1600L), delays)
    }

    @Test
    fun delayIsCappedAtCeilingAndNeverOverflows() {
        val policy = flatPolicy(maxRetries = 500)
        repeat(40) { assertNotNull(policy.onStreamFailure()) }
        // 100 * 2^40 далеко за потолком; 500 подряд — проверка на переполнение Long.
        repeat(460) {
            val plan = policy.onStreamFailure()
            assertNotNull(plan)
            assertEquals(10_000L, plan!!.delayMs)
        }
        assertEquals(10_000L, policy.nextDelayMs())
    }

    @Test
    fun attemptNumbersGrowWithinTheStreak() {
        val policy = flatPolicy()
        assertEquals(1, policy.onStreamFailure()?.attempt)
        assertEquals(2, policy.onStreamFailure()?.attempt)
        assertEquals(3, policy.onStreamFailure()?.attempt)
        assertEquals(3, policy.consecutiveFailures())
    }

    // ---------- джиттер ----------

    @Test
    fun jitterStaysWithinItsBand() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitterFraction = 0.25,
            maxRetries = 1_000,
            random = Random(42),
        )
        // Первая ступень (база 1000): все задержки в [750, 1250].
        repeat(200) {
            policy.reset()
            val plan = policy.onStreamFailure()
            assertNotNull(plan)
            assertTrue("delay ${plan!!.delayMs} out of band", plan.delayMs in 750L..1_250L)
        }
    }

    @Test
    fun jitterActuallyVariesDelays() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = 1_000L,
            jitterFraction = 0.25,
            maxRetries = 1_000,
            random = Random(7),
        )
        val seen = (1..50).map {
            policy.reset()
            policy.onStreamFailure()!!.delayMs
        }.toSet()
        assertTrue("expected varied jittered delays, got $seen", seen.size > 10)
        assertTrue(seen.all { it in 750L..1_250L })
    }

    @Test
    fun jitteredDelayNeverExceedsCeiling() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = 9_000L,
            maxDelayMs = 10_000L,
            jitterFraction = 1.0,
            maxRetries = 1_000,
            random = Random(3),
        )
        repeat(100) {
            val plan = policy.onStreamFailure()
            assertNotNull(plan)
            assertTrue(plan!!.delayMs in 0L..10_000L)
        }
    }

    @Test
    fun seededRandomIsDeterministic() {
        fun sequence(seed: Int) = AdaptiveSseReconnectPolicy(
            initialDelayMs = 100L,
            jitterFraction = 0.5,
            maxRetries = 20,
            random = Random(seed),
        ).let { policy -> (1..10).map { policy.onStreamFailure()!!.delayMs } }

        assertEquals(sequence(11), sequence(11))
        assertNotEquals(sequence(11), sequence(12))
    }

    // ---------- бюджет повторов ----------

    @Test
    fun budgetExhaustsAfterMaxRetries() {
        val policy = flatPolicy(maxRetries = 3)
        assertNotNull(policy.onStreamFailure())
        assertNotNull(policy.onStreamFailure())
        assertNotNull(policy.onStreamFailure())
        assertNull(policy.onStreamFailure())
        assertNull(policy.onStreamFailure())
        assertFalse(policy.canRetry())
        assertEquals(3, policy.consecutiveFailures())
    }

    @Test
    fun zeroRetriesMeansNoReconnectAtAll() {
        val policy = flatPolicy(maxRetries = 0)
        assertNull(policy.onStreamFailure())
        assertFalse(policy.canRetry())
        assertEquals(0, policy.consecutiveFailures())
    }

    @Test
    fun freshPolicyIsHealthyAndCanRetry() {
        val policy = flatPolicy()
        assertEquals(0, policy.consecutiveFailures())
        assertTrue(policy.canRetry())
        // nextDelayMs без зафиксированных сбоев — первая ступень лестницы.
        assertEquals(100L, policy.nextDelayMs())
    }

    // ---------- сброс на первом успешном событии ----------

    @Test
    fun streamEventResetsStreakBudgetAndLadder() {
        val policy = flatPolicy(maxRetries = 3)
        assertEquals(100L, policy.onStreamFailure()?.delayMs)
        assertEquals(200L, policy.onStreamFailure()?.delayMs)

        assertTrue(policy.onStreamEvent())
        assertEquals(0, policy.consecutiveFailures())
        assertTrue(policy.canRetry())

        // Лестница началась заново: снова 100 -> 200 -> 400, бюджет восстановлен.
        assertEquals(100L, policy.onStreamFailure()?.delayMs)
        assertEquals(200L, policy.onStreamFailure()?.delayMs)
        assertEquals(400L, policy.onStreamFailure()?.delayMs)
        assertNull(policy.onStreamFailure())
    }

    @Test
    fun streamEventOnHealthyPolicyIsIdempotentNoOp() {
        val policy = flatPolicy()
        assertFalse(policy.onStreamEvent())
        assertFalse(policy.onStreamEvent())
        assertEquals(0, policy.consecutiveFailures())
        assertEquals(100L, policy.nextDelayMs())
    }

    @Test
    fun policySurvivesManyHealthyReconnectCycles() {
        val policy = flatPolicy(maxRetries = 3)
        // Имитация дней работы: сбой -> пара ретраев -> успех -> сброс, 1000 циклов.
        repeat(1_000) {
            assertNotNull(policy.onStreamFailure())
            assertNotNull(policy.onStreamFailure())
            assertTrue(policy.onStreamEvent())
        }
        assertTrue(policy.canRetry())
        assertEquals(100L, policy.nextDelayMs())
    }

    @Test
    fun manualResetBehavesLikeStreamEvent() {
        val policy = flatPolicy(maxRetries = 2)
        policy.onStreamFailure()
        policy.onStreamFailure()
        assertNull(policy.onStreamFailure())
        policy.reset()
        assertEquals(0, policy.consecutiveFailures())
        assertEquals(100L, policy.onStreamFailure()?.delayMs)
    }

    // ---------- нормализация входных параметров ----------

    @Test
    fun constructorArgumentsAreNormalized() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = -50L,
            maxDelayMs = -10L,
            factor = 0.5,
            maxRetries = -3,
            jitterFraction = 5.0,
        )
        assertEquals(0L, policy.initialDelayMs)
        assertEquals(0L, policy.maxDelayMs)
        assertEquals(1.0, policy.factor, 0.0)
        assertEquals(0, policy.maxRetries)
        assertEquals(1.0, policy.jitterFraction, 0.0)
    }

    @Test
    fun maxDelayBelowInitialIsRaisedToInitial() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = 1_000L,
            maxDelayMs = 10L,
            jitterFraction = 0.0,
            maxRetries = 5,
        )
        assertEquals(1_000L, policy.maxDelayMs)
        assertEquals(1_000L, policy.onStreamFailure()?.delayMs)
        assertEquals(1_000L, policy.onStreamFailure()?.delayMs)
    }

    @Test
    fun zeroCeilingCollapsesEveryDelayToZero() {
        val policy = AdaptiveSseReconnectPolicy(
            initialDelayMs = 0L,
            maxDelayMs = 0L,
            maxRetries = 4,
            jitterFraction = 0.5,
            random = Random(9),
        )
        repeat(4) { assertEquals(0L, policy.onStreamFailure()?.delayMs) }
    }

    @Test
    fun defaultsAreSane() {
        val policy = AdaptiveSseReconnectPolicy()
        assertEquals(500L, policy.initialDelayMs)
        assertEquals(30_000L, policy.maxDelayMs)
        assertEquals(2.0, policy.factor, 0.0)
        assertEquals(8, policy.maxRetries)
        assertEquals(0.2, policy.jitterFraction, 1e-9)
    }

    @Test
    fun nextDelayMsFollowsTheStreakWithoutMutatingIt() {
        val policy = flatPolicy()
        policy.onStreamFailure()
        // Один сбой: следующая задержка — вторая ступень; состояние не меняется.
        assertEquals(200L, policy.nextDelayMs())
        assertEquals(200L, policy.nextDelayMs())
        assertEquals(1, policy.consecutiveFailures())
    }
}
