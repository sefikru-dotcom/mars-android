package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.CompressionJobStatus
import ru.mars.network.repository.CompressionStartResult
import ru.mars.network.repository.CompressionStatusResult
import ru.mars.network.repository.CompressionSummary
import java.util.concurrent.Executor

/**
 * Unit tests for [ContextCompressionController] (no Android framework: gateway/host/executor/
 * ui-dispatcher/sleeper are clean-seam fakes like [InteractiveRequestControllerTest]). Covers
 * the manual start→poll→done pipeline with WebUI poll cadence, fast/synchronous done, server
 * rejections, transient poll failures, job-vanished handling, auto-compression threshold with
 * hysteresis arming, and generation guards (session switch / destroy).
 */
class ContextCompressionControllerTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    /** UI-thread stand-in: runs dispatched blocks inline (single-thread confinement semantics). */
    private val inlineUi = UiDispatcher { it() }

    /** Records the requested poll delays without blocking: proves the WebUI cadence. */
    private class RecordingSleeper : CompressionSleeper {
        val sleeps = mutableListOf<Long>()
        override fun sleep(millis: Long) { sleeps += millis }
    }

    private class FakeHost : ContextCompressionHost {
        val runningStates = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        val completed = mutableListOf<CompressionSummary?>()
        override fun setCompressionRunning(running: Boolean) { runningStates += running }
        override fun showCompressionError(message: String) { errors += message }
        override fun onCompressionCompleted(summary: CompressionSummary?) { completed += summary }
    }

    private class FakeGateway : ContextCompressionGateway {
        var startResult: CompressionStartResult = CompressionStartResult.Running(null)
        val startCalls = mutableListOf<Pair<String, String?>>()
        var statusCalls = 0

        /** Queued poll answers; when exhausted, [defaultStatus] is served. */
        val statusQueue = ArrayDeque<CompressionStatusResult>()
        var defaultStatus: CompressionStatusResult =
            CompressionStatusResult.Found(CompressionJobStatus.RUNNING, null, null)

        override fun startCompression(sessionId: String, focusTopic: String?): CompressionStartResult {
            startCalls += sessionId to focusTopic
            return startResult
        }

        override fun fetchStatus(sessionId: String): CompressionStatusResult {
            statusCalls++
            return statusQueue.removeFirstOrNull() ?: defaultStatus
        }
    }

    private class FakeSettings(var config: AutoCompressionConfig = AutoCompressionConfig()) : AutoCompressionStorage {
        var saves = 0
        override fun load(): AutoCompressionConfig = config
        override fun save(config: AutoCompressionConfig) { saves++; this.config = config }
    }

    private fun running(status: CompressionJobStatus, error: String? = null, summary: CompressionSummary? = null) =
        CompressionStatusResult.Found(status, error, summary)

    private fun usage(percent: Int) = ChatEvent.ContextUsage(percent * 1000, 100_000, percent)

    private class Fixture(
        session: String? = "sess-1",
        var streaming: Boolean = false,
        settings: AutoCompressionConfig = AutoCompressionConfig(),
    ) {
        val host = FakeHost()
        val gateway = FakeGateway()
        val executor = QueueExecutor()
        val sleeper = RecordingSleeper()
        val storage = FakeSettings(settings)
        val controller = ContextCompressionController(
            host = host,
            gateway = gateway,
            sessionProvider = { session },
            streamingProvider = { streaming },
            settings = storage,
            executor = executor,
            uiDispatcher = UiDispatcher { it() }, // inline UI: nested Fixture cannot see the outer instance's inlineUi
            sleeper = sleeper,
        )
    }

    private val summary = CompressionSummary("Compressed: 40 → 6 messages", "~12000 → ~2100 tokens", null)

    // --- manual pipeline ------------------------------------------------------------------------

    @Test
    fun manualStartPollsUntilDoneAndCompletes() {
        val f = Fixture()
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.RUNNING))
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.RUNNING))
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.DONE, summary = summary))

        f.controller.startManualCompression("сеть")
        assertEquals(listOf(true), f.host.runningStates) // running is announced before the poll starts
        f.executor.runAll()

        assertEquals(listOf("sess-1" to "сеть"), f.gateway.startCalls)
        assertEquals(3, f.gateway.statusCalls)
        assertEquals(listOf(true, false), f.host.runningStates)
        assertEquals(listOf<CompressionSummary?>(summary), f.host.completed)
        assertTrue(f.host.errors.isEmpty())
        // WebUI cadence: 700 ms first poll, +300 ms per poll.
        assertEquals(listOf(700L, 1000L, 1300L), f.sleeper.sleeps)
    }

    @Test
    fun pollDelayGrowsAndCapsAtTwoSeconds() {
        val f = Fixture()
        repeat(7) { f.gateway.statusQueue.addLast(running(CompressionJobStatus.RUNNING)) }
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.DONE, summary = summary))

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf(700L, 1000L, 1300L, 1600L, 1900L, 2000L, 2000L, 2000L), f.sleeper.sleeps)
        assertEquals(listOf<CompressionSummary?>(summary), f.host.completed)
    }

    @Test
    fun fastDoneInsideStartSkipsPolling() {
        val f = Fixture()
        f.gateway.startResult = CompressionStartResult.Done(summary)

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(0, f.gateway.statusCalls)
        assertEquals(listOf<CompressionSummary?>(summary), f.host.completed)
        assertEquals(listOf(true, false), f.host.runningStates)
    }

    @Test
    fun streamingRejectionSurfacesServerErrorAndUnlocks() {
        val f = Fixture()
        f.gateway.startResult = CompressionStartResult.Rejected("Session is still streaming; cancel or wait", 409)

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf("Session is still streaming; cancel or wait"), f.host.errors)
        assertEquals(listOf(true, false), f.host.runningStates)
        assertTrue(f.host.completed.isEmpty())
    }

    @Test
    fun startNetworkErrorSurfacesGenericFailure() {
        val f = Fixture()
        f.gateway.startResult = CompressionStartResult.NetworkError("timeout")

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf(ContextCompressionController.NETWORK_FAILURE), f.host.errors)
    }

    @Test
    fun noSessionBlocksStartWithoutGatewayCall() {
        val f = Fixture(session = null)

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf(ContextCompressionController.NO_SESSION), f.host.errors)
        assertTrue(f.gateway.startCalls.isEmpty())
        assertTrue(f.host.runningStates.isEmpty())
    }

    @Test
    fun clientSideStreamingGuardBlocksStartBeforeServer() {
        val f = Fixture(streaming = true)

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf(ContextCompressionController.STREAMING), f.host.errors)
        assertTrue(f.gateway.startCalls.isEmpty())
    }

    @Test
    fun doubleStartIsIgnoredWhileJobRuns() {
        val f = Fixture()
        f.gateway.startResult = CompressionStartResult.Done(summary) // terminal at once: no poll loop

        f.controller.startManualCompression(null)
        f.controller.startManualCompression("second")
        f.executor.runAll()

        assertEquals(1, f.gateway.startCalls.size)
        assertEquals(listOf(true, false), f.host.runningStates) // no second unlock/lock pair
    }

    @Test
    fun pollErrorAndCancelledSurfaceServerMessage() {
        val f = Fixture()
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.ERROR, error = "Compression failed: No provider"))

        f.controller.startManualCompression(null)
        f.executor.runAll()
        assertEquals(listOf("Compression failed: No provider"), f.host.errors)

        val f2 = Fixture()
        f2.gateway.statusQueue.addLast(running(CompressionJobStatus.CANCELLED))
        f2.controller.startManualCompression(null)
        f2.executor.runAll()
        // Cancelled carries no server message: fall back to the repository default.
        assertEquals(listOf(ru.mars.network.repository.ContextCompressionRepository.COMPRESSION_FAILED), f2.host.errors)
    }

    @Test
    fun pollIdleMeansJobVanishedServerSide() {
        val f = Fixture()
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.IDLE))

        f.controller.startManualCompression(null)
        f.executor.runAll()

        assertEquals(listOf(ContextCompressionController.JOB_VANISHED), f.host.errors)
        assertEquals(listOf(true, false), f.host.runningStates)
    }

    @Test
    fun transientPollFailuresAreToleratedThenFatal() {
        val f = Fixture()
        f.gateway.statusQueue.addLast(CompressionStatusResult.Failed("blip"))
        f.gateway.statusQueue.addLast(CompressionStatusResult.Failed("blip"))
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.DONE, summary = summary))

        f.controller.startManualCompression(null)
        f.executor.runAll()

        // Two consecutive transport failures must not abort a long LLM compression...
        assertEquals(listOf<CompressionSummary?>(summary), f.host.completed)
        assertTrue(f.host.errors.isEmpty())

        val f2 = Fixture()
        f2.gateway.defaultStatus = CompressionStatusResult.Failed("offline")
        f2.controller.startManualCompression(null)
        f2.executor.runAll()
        // ...but the third one in a row does.
        assertEquals(listOf(ContextCompressionController.NETWORK_FAILURE), f2.host.errors)
        assertEquals(ContextCompressionController.MAX_POLL_FAILURES, f2.gateway.statusCalls)
    }

    // --- auto compression ------------------------------------------------------------------------

    @Test
    fun autoTriggersAfterTurnWhenThresholdCrossed() {
        val f = Fixture(streaming = true, settings = AutoCompressionConfig(enabled = true, thresholdPercent = 85))
        f.gateway.startResult = CompressionStartResult.Done(summary)

        // Metering arrives mid-stream: recorded, never triggered.
        f.controller.onEvent(usage(90))
        f.executor.runAll()
        assertTrue(f.gateway.startCalls.isEmpty())

        // Turn ends: the fresh meter over the threshold starts one compression.
        f.streaming = false
        f.controller.onEvent(ChatEvent.Done)
        assertTrue(f.controller.isRunning()) // job announced before the executor drains
        f.executor.runAll()

        assertEquals(listOf("sess-1" to null), f.gateway.startCalls)
        assertEquals(listOf<CompressionSummary?>(summary), f.host.completed)
    }

    @Test
    fun autoDisabledNeverTriggers() {
        val f = Fixture(settings = AutoCompressionConfig(enabled = false))

        f.controller.onEvent(usage(97))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()

        assertTrue(f.gateway.startCalls.isEmpty())
    }

    @Test
    fun autoTriggersOncePerArmingCycleWithHysteresis() {
        val f = Fixture(settings = AutoCompressionConfig(enabled = true, thresholdPercent = 85))
        f.gateway.startResult = CompressionStartResult.Done(summary)

        f.controller.onEvent(usage(90))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()
        assertEquals(1, f.gateway.startCalls.size)

        // Still high after the job: a second turn must NOT re-trigger (no compression loop).
        f.controller.onEvent(usage(91))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()
        assertEquals(1, f.gateway.startCalls.size)

        // Fill dropped below the threshold → re-armed; crossing again triggers once more.
        f.controller.onEvent(usage(40))
        f.controller.onEvent(usage(88))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()
        assertEquals(2, f.gateway.startCalls.size)
    }

    @Test
    fun autoNeverTriggersWhileStreaming() {
        val f = Fixture(streaming = true, settings = AutoCompressionConfig(enabled = true, thresholdPercent = 85))

        f.controller.onEvent(usage(99))
        f.controller.onEvent(ChatEvent.Done) // Done but the provider still reports an active stream
        f.executor.runAll()

        assertTrue(f.gateway.startCalls.isEmpty())
    }

    // --- generation guards ------------------------------------------------------------------------

    @Test
    fun sessionChangeAbandonsInFlightJob() {
        val f = Fixture()
        f.gateway.defaultStatus = running(CompressionJobStatus.RUNNING)

        f.controller.startManualCompression(null)
        f.controller.onSessionChanged() // bumps the generation before the executor ever runs
        f.executor.runAll()

        // The abandoned task never reaches the gateway nor resurfaces host callbacks.
        assertTrue(f.gateway.startCalls.isEmpty())
        assertEquals(listOf(true, false), f.host.runningStates) // unlock came from onSessionChanged itself
        assertTrue(f.host.completed.isEmpty())
        assertTrue(f.host.errors.isEmpty())
    }

    @Test
    fun sessionChangeResetsAutoArmingAndMeter() {
        val f = Fixture(settings = AutoCompressionConfig(enabled = true, thresholdPercent = 85))
        f.gateway.startResult = CompressionStartResult.Done(summary)

        f.controller.onEvent(usage(90))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()
        assertEquals(1, f.gateway.startCalls.size)

        // After a session switch the new session's first high reading may trigger again.
        f.controller.onSessionChanged()
        f.controller.onEvent(usage(90))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()
        assertEquals(2, f.gateway.startCalls.size)
    }

    @Test
    fun destroyDropsLateResults() {
        val f = Fixture()
        f.gateway.statusQueue.addLast(running(CompressionJobStatus.DONE, summary = summary))

        f.controller.startManualCompression(null)
        f.controller.destroy()
        f.executor.runAll()

        assertTrue(f.gateway.startCalls.isEmpty())
        assertTrue(f.host.completed.isEmpty())
        assertTrue(f.host.errors.isEmpty())
    }

    @Test
    fun eventsAfterDestroyAreIgnored() {
        val f = Fixture(settings = AutoCompressionConfig(enabled = true, thresholdPercent = 85))
        f.controller.destroy()

        f.controller.onEvent(usage(99))
        f.controller.onEvent(ChatEvent.Done)
        f.executor.runAll()

        assertTrue(f.gateway.startCalls.isEmpty())
    }
}
