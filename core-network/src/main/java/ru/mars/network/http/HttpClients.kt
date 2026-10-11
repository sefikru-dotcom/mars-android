package ru.mars.network.http

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients for the whole module: ONE connection pool and ONE dispatcher
 * for every repository, two timeout profiles.
 *
 * Why two profiles:
 * - [stream] — SSE chat streams (`POST /api/chat/start` + `GET /api/chat/stream`, and
 *   the legacy `POST /chat` whose response IS the stream). The read timeout must cover
 *   the longest legitimate silence of a healthy stream: the WebUI backend writes an
 *   application-level `: heartbeat` comment every 5 s while the agent works (verified
 *   read-only in `/opt/hermes-webui/api/routes.py`, `_SSE_HEARTBEAT_INTERVAL_SECONDS`),
 *   so long tool calls do NOT create multi-minute byte gaps — but server-side stalls
 *   (GIL contention, journal replay of a huge run) can stretch the gap well past a
 *   default 10 s. [STREAM_READ_TIMEOUT_MS] keeps a healthy stream alive with a wide
 *   margin while a silently dead socket (NAT rebinding, radio handover) is detected
 *   in ~1.5 min instead of hanging forever. There is deliberately NO `callTimeout`:
 *   an SSE call legitimately lasts as long as the whole turn (hours).
 * - [rest] — plain request/response calls (sessions, models, uploads, cancel/steer,
 *   health). Every phase is bounded ([REST_CONNECT_TIMEOUT_MS] / [REST_READ_TIMEOUT_MS]
 *   / [REST_WRITE_TIMEOUT_MS]) so a hung backend can never pin a UI coroutine for
 *   minutes. No `callTimeout` either — a slow-but-steady 20 MB upload must not be
 *   killed by a total-time budget; the per-phase write timeout still catches a truly
 *   stalled socket.
 *
 * Heartbeat honesty: OkHttp's `pingInterval` is intentionally NOT set. It only sends
 * HTTP/2 PING / WebSocket frames; the Mars backend serves SSE over HTTP/1.1 with
 * `Connection: close`, so pings would never reach the wire, and even on h2 a ping is
 * NOT an SSE heartbeat — it says nothing about the event stream making progress. Liveness
 * of an SSE stream is the server's `: heartbeat` comment plus [STREAM_READ_TIMEOUT_MS]
 * on the client; nothing here replaces the application-level heartbeat.
 *
 * Both clients are built from one [OkHttpClient] via `newBuilder()`, so they share
 * [connectionPool] and [dispatcher] (sockets and threads are reused across profiles).
 * The dispatcher only throttles ASYNC (`enqueue`) calls; the repositories issue
 * synchronous `execute()` calls, which are tracked but never queued behind
 * `maxRequests` — a long-lived SSE call therefore cannot starve short REST calls.
 *
 * Tests keep injecting their own clients through the repository constructors
 * (`ChatRepository(client, restClient)` etc.); nothing here is mandatory to use.
 */
object HttpClients {

    /** Connect timeout for both profiles, ms. */
    const val CONNECT_TIMEOUT_MS: Long = 10_000L

    /** Per-phase read timeout for plain REST calls, ms (bounded by design). */
    const val REST_READ_TIMEOUT_MS: Long = 20_000L

    /** Per-phase write timeout for both profiles, ms (uploads post in short bursts). */
    const val WRITE_TIMEOUT_MS: Long = 20_000L

    /**
     * Read timeout for SSE streams, ms: must exceed the server's 5 s heartbeat comment
     * interval by a wide margin (stalls, journal replay, radio latency) yet stay low
     * enough to detect a dead socket. See the class KDoc — do NOT lower this below the
     * longest tool-call silence you expect the server to bridge WITHOUT heartbeats.
     */
    const val STREAM_READ_TIMEOUT_MS: Long = 90_000L

    /** Sockets kept for reuse across ALL repositories (both profiles). */
    val connectionPool: ConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    /**
     * Single dispatcher for both profiles. `maxRequestsPerHost` is raised from the
     * default 5 because every call goes to one Mars host; it only affects async calls.
     */
    val dispatcher: Dispatcher = Dispatcher().apply { maxRequestsPerHost = 16 }

    private val shared: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .build()

    /** Bounded request/response profile: sessions, models, uploads, control, health. */
    val rest: OkHttpClient = shared.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REST_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Long-lived SSE profile: no call timeout, generous read timeout. */
    val stream: OkHttpClient = shared.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Preconfigured builder (shared pool/dispatcher) for callers needing tweaks. */
    fun restBuilder(): OkHttpClient.Builder = rest.newBuilder()

    /** Preconfigured builder (shared pool/dispatcher) for callers needing tweaks. */
    fun streamBuilder(): OkHttpClient.Builder = stream.newBuilder()
}
