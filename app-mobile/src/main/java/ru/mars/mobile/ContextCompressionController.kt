package ru.mars.mobile

import android.content.SharedPreferences
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.CompressionStartResult
import ru.mars.network.repository.CompressionStatusResult
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Transport port for session-context compression. The controller owns the lifecycle decisions
 * (guards, polling, auto-trigger); this port owns "how the bytes travel" — exactly the split of
 * [InteractiveRequestsGateway]. All methods are blocking and run on the controller's executor.
 */
interface ContextCompressionGateway {
    /** `POST /api/session/compress/start` — admits the background compression job. */
    fun startCompression(sessionId: String, focusTopic: String?): CompressionStartResult

    /** `GET /api/session/compress/status?session_id=…` — one poll of the job state. */
    fun fetchStatus(sessionId: String): CompressionStatusResult
}

/**
 * Production gateway: binds [ContextCompressionRepository] to the base URL and the paired-device
 * credential. A missing credential is reported as a rejection/failure — never a crash and never
 * an unauthenticated fallback (same contract as [RepositoryInteractiveGateway]).
 */
class RepositoryContextCompressionGateway(
    private val repository: ContextCompressionRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : ContextCompressionGateway {
    override fun startCompression(sessionId: String, focusTopic: String?): CompressionStartResult {
        val credential = credentialProvider()
            ?: return CompressionStartResult.Rejected(AUTH_REQUIRED, 0)
        return repository.startCompression(baseUrl, credential, sessionId, focusTopic)
    }

    override fun fetchStatus(sessionId: String): CompressionStatusResult {
        val credential = credentialProvider() ?: return CompressionStatusResult.Failed(AUTH_REQUIRED)
        return repository.fetchStatus(baseUrl, credential, sessionId)
    }

    companion object {
        const val AUTH_REQUIRED = "Устройство не авторизовано — выполните сопряжение заново."
    }
}

/** UI seam of [ContextCompressionController]; every method is invoked on the UI dispatcher. */
interface ContextCompressionHost {
    /** Job started/finished: drive the status line and lock the context pill re-entry. */
    fun setCompressionRunning(running: Boolean)

    /** Terminal failure of a manual or automatic job (non-blocking status message). */
    fun showCompressionError(message: String)

    /**
     * Job finished successfully: the server REPLACED the session history with the compressed
     * transcript, so the host must reload the session (`GET /api/session`) and re-render.
     */
    fun onCompressionCompleted(summary: CompressionSummary?)
}

/**
 * Auto-compression preference. [thresholdPercent] mirrors the WebUI ring warning band: the
 * server auto-compresses mid-turn on its own threshold, this client-side trigger covers the
 * idle-between-turns case when the meter already crossed the warning level.
 */
data class AutoCompressionConfig(
    val enabled: Boolean = false,
    val thresholdPercent: Int = DEFAULT_THRESHOLD,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 85
    }
}

/** Persistence port for [AutoCompressionConfig]; the app implements it with SharedPreferences. */
interface AutoCompressionStorage {
    fun load(): AutoCompressionConfig
    fun save(config: AutoCompressionConfig)
}

/** SharedPreferences-backed [AutoCompressionStorage]. */
class PrefsAutoCompressionStorage(private val prefs: SharedPreferences) : AutoCompressionStorage {
    override fun load(): AutoCompressionConfig {
        val threshold = prefs.getInt(KEY_THRESHOLD, AutoCompressionConfig.DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        return AutoCompressionConfig(prefs.getBoolean(KEY_ENABLED, false), threshold)
    }

    override fun save(config: AutoCompressionConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_THRESHOLD, config.thresholdPercent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "auto_compression_enabled"
        const val KEY_THRESHOLD = "auto_compression_threshold"
        const val MIN_THRESHOLD = 50
        const val MAX_THRESHOLD = 99
    }
}

/** Sleep seam for the poll loop so unit tests never block on real delays. */
fun interface CompressionSleeper {
    fun sleep(millis: Long)
}

/**
 * Manual + automatic session-context compression, mirroring the WebUI flow
 * (static/commands.js runManualCompression / resumeManualCompressionForSession):
 *
 * 1. `POST /api/session/compress/start` — the server refuses while a stream is active (409) and
 *    may answer `done` immediately for small histories; otherwise it admits a background job.
 * 2. Poll `GET /api/session/compress/status` with the WebUI cadence (700 ms, +300 ms per poll,
 *    capped at 2 s) until `done` / `error` / `cancelled`; an `idle` in the middle of a job means
 *    it vanished server-side (TTL/eviction) and is reported, not silently dropped.
 * 3. On `done` the host reloads the transcript — the compressed history replaces the old one.
 *
 * Auto mode: when the `metering` meter crosses [AutoCompressionConfig.thresholdPercent] while no
 * stream is active and the user enabled it, one compression is triggered per arming cycle — the
 * trigger re-arms only after the fill drops back below the threshold (hysteresis), so a single
 * high reading can never start a compression loop.
 *
 * Threading contract (same as [InteractiveRequestController]): all public methods are called on
 * the UI thread; gateway calls run on [executor]; every state mutation and host callback is
 * funnelled through [uiDispatcher]. A generation counter neutralises results of jobs that were
 * abandoned by a session switch or [destroy].
 */
class ContextCompressionController(
    private val host: ContextCompressionHost,
    private val gateway: ContextCompressionGateway,
    private val sessionProvider: () -> String?,
    private val streamingProvider: () -> Boolean,
    private val settings: AutoCompressionStorage,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: UiDispatcher = MainThreadDispatcher(),
    private val sleeper: CompressionSleeper = CompressionSleeper { Thread.sleep(it) },
) {

    /**
     * Bumped by [onSessionChanged]/[destroy]; background work captures its generation and is
     * dropped when the guard no longer matches (a compression result of a dead session must
     * never resurface UI). Volatile for the executor-side pre-flight; the authoritative check
     * runs on the dispatcher thread.
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var destroyed = false

    // Fields below are confined to the UI dispatcher thread.
    private var running = false
    private var lastUsage: ChatEvent.ContextUsage? = null
    private var autoArmed = true

    /** True while a compression job is in flight (UI-thread read for pill/status locking). */
    fun isRunning(): Boolean = running

    /**
     * Starts a manual compression. [focusTopic] is the optional "keep the focus on …" hint
     * (`focus_topic` in the server body); blank/null means plain compression.
     */
    fun startManualCompression(focusTopic: String? = null) {
        if (destroyed || running) return
        val sid = sessionProvider()
        if (sid.isNullOrBlank()) {
            host.showCompressionError(NO_SESSION)
            return
        }
        if (streamingProvider()) {
            host.showCompressionError(STREAMING)
            return
        }
        running = true
        host.setCompressionRunning(true)
        val gen = generation
        val topic = focusTopic?.trim()?.takeIf { it.isNotEmpty() }
        executor.execute {
            if (destroyed || gen != generation) return@execute
            when (val result = gateway.startCompression(sid, topic)) {
                is CompressionStartResult.Running -> pollUntilTerminal(sid, gen)
                is CompressionStartResult.Done -> dispatch(gen) { complete(result.summary) }
                is CompressionStartResult.Rejected -> dispatch(gen) { fail(result.message) }
                is CompressionStartResult.NetworkError -> dispatch(gen) { fail(NETWORK_FAILURE) }
            }
        }
    }

    /**
     * Stream-event feed from the collect loop. `ContextUsage` updates the meter and evaluates
     * the auto-trigger when idle; `Done`/`Error` end the turn and re-evaluate (the meter is
     * freshest right after a turn, and the server refuses compression mid-stream anyway).
     */
    fun onEvent(event: ChatEvent) {
        if (destroyed) return
        when (event) {
            is ChatEvent.ContextUsage -> {
                lastUsage = event
                if (streamingProvider()) {
                    // Mid-stream: never trigger, but re-arm once the fill drops below the mark.
                    if (event.percent < settings.load().thresholdPercent) autoArmed = true
                } else {
                    evaluateAutoCompression()
                }
            }
            ChatEvent.Done, is ChatEvent.Error -> evaluateAutoCompression()
            else -> Unit
        }
    }

    /** Session switched/created/deleted: abandon in-flight observation and re-arm auto mode. */
    fun onSessionChanged() {
        generation++
        val wasRunning = running
        running = false
        lastUsage = null
        autoArmed = true
        if (wasRunning && !destroyed) host.setCompressionRunning(false)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        generation++
    }

    // --- auto trigger ------------------------------------------------------------------------

    private fun evaluateAutoCompression() {
        if (running || streamingProvider()) return
        val usage = lastUsage ?: return
        val config = settings.load()
        if (usage.percent < config.thresholdPercent) {
            autoArmed = true
            return
        }
        if (!config.enabled || !autoArmed) return
        autoArmed = false
        startManualCompression(null)
    }

    // --- poll loop (executor thread) -----------------------------------------------------------

    private fun pollUntilTerminal(sid: String, gen: Int) {
        var delay = INITIAL_POLL_DELAY_MS
        var consecutiveFailures = 0
        while (!destroyed && gen == generation) {
            sleeper.sleep(delay)
            if (destroyed || gen != generation) return
            when (val lookup = gateway.fetchStatus(sid)) {
                is CompressionStatusResult.Failed -> {
                    // A transient network blip must not abort a long LLM compression; WebUI keeps
                    // polling on slow jobs — we tolerate up to MAX_POLL_FAILURES in a row.
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_POLL_FAILURES) {
                        dispatch(gen) { fail(NETWORK_FAILURE) }
                        return
                    }
                }
                is CompressionStatusResult.Found -> {
                    consecutiveFailures = 0
                    when (lookup.status) {
                        ru.mars.network.repository.CompressionJobStatus.RUNNING -> Unit
                        ru.mars.network.repository.CompressionJobStatus.DONE -> {
                            dispatch(gen) { complete(lookup.summary) }
                            return
                        }
                        ru.mars.network.repository.CompressionJobStatus.ERROR,
                        ru.mars.network.repository.CompressionJobStatus.CANCELLED,
                        -> {
                            dispatch(gen) {
                                fail(lookup.error ?: ContextCompressionRepository.COMPRESSION_FAILED)
                            }
                            return
                        }
                        // `idle` while we hold a running job: it vanished server-side (TTL/restart).
                        ru.mars.network.repository.CompressionJobStatus.IDLE,
                        ru.mars.network.repository.CompressionJobStatus.UNKNOWN,
                        -> {
                            dispatch(gen) { fail(JOB_VANISHED) }
                            return
                        }
                    }
                }
            }
            delay = (delay + POLL_DELAY_STEP_MS).coerceAtMost(MAX_POLL_DELAY_MS)
        }
    }

    // --- terminal handling (UI thread via dispatcher) -------------------------------------------

    private fun dispatch(gen: Int, block: () -> Unit) {
        uiDispatcher.dispatch {
            if (destroyed || gen != generation) return@dispatch
            block()
        }
    }

    private fun complete(summary: CompressionSummary?) {
        running = false
        host.setCompressionRunning(false)
        host.onCompressionCompleted(summary)
    }

    private fun fail(message: String) {
        running = false
        host.setCompressionRunning(false)
        host.showCompressionError(message)
    }

    companion object {
        const val NO_SESSION = "Нет активной сессии — сжатие не запущено."
        const val STREAMING = "Сессия отвечает — сжатие доступно после завершения хода."
        const val NETWORK_FAILURE = "Сеть недоступна — сжатие не выполнено."
        const val JOB_VANISHED = "Задание сжатия потеряно на сервере — попробуйте ещё раз."

        // WebUI cadence (static/commands.js runManualCompression): 700 ms first poll,
        // +300 ms per poll, capped at 2 s.
        const val INITIAL_POLL_DELAY_MS = 700L
        const val POLL_DELAY_STEP_MS = 300L
        const val MAX_POLL_DELAY_MS = 2000L
        const val MAX_POLL_FAILURES = 3

        // Dedicated daemon executor: the poll loop holds its thread for the whole job, so it
        // must NOT share the interactive-requests single thread (approval/clarify responds
        // would queue behind a multi-minute compression).
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "context-compression").apply { isDaemon = true }
        }
    }
}
