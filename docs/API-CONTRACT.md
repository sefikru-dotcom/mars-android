# API contract: device pairing v3

## Create a pairing

`POST /api/v1/auth/device-pairings`

The server returns `pair_id`, `code`, `poll_secret`, `expires_in`, and `status`.
`poll_secret` is confidential and is never logged or sent in a URL.

## Poll pairing status

`GET /api/v1/auth/device-pairings/status?pair_id={pair_id}`

The response is metadata only: `pair_id` and `status` (plus server metadata such as `expires_at` when supplied). It never contains a device token or native credential. Polling is not approval and must not be used as credential acquisition.

## Exchange the approved pairing

`POST /api/v1/auth/device-pairings/{pair_id}/exchange`

JSON body:

```json
{"poll_secret":"..."}
```

The response contains exactly the native credential fields consumed by the client:
`access_token`, `token_type`, `expires_in`, `device_id`, and `scopes`.
The access token is accepted once in memory, then stored only in encrypted Android Keystore-backed storage. It must not appear in URLs, logs, or `toString()` output.

## Revoke

`POST /api/v1/auth/devices/{device_id}/revoke` with `Authorization: Bearer <access_token>`.

The client accepts a successful HTTP response without reading a credential from the response body.

Approval remains browser-only and requires the browser session plus CSRF protection. A native client must not emulate browser approval.

# Turn control: mid-turn steer and context compression

All endpoints below use the same native credential (`Authorization: Bearer <access_token>`) as chat.

## Mid-turn steer

`POST /api/chat/steer` with `{"session_id": "...", "text": "..."}`.

A 2xx response is NOT success by itself: the server answers 200 with `{"accepted": false, "fallback": "no_cached_agent"}` when the correction could not be injected into the running turn. The client treats `accepted: false` as a failed steer and keeps the text in the composer.

When a steer was accepted but the turn ended before the agent consumed it, the stream emits SSE `pending_steer_leftover` `{session_id, text}`. The client maps it to `ChatEvent.SteerLeftover`, records a transcript trace and returns the text to the composer input.

## Manual context compression

`POST /api/session/compress/start` with `{"session_id": "...", "focus_topic": "..."?}`.

Responses:
- 200 `{"ok": true, "status": "running", ...}` — background job admitted; poll the status endpoint.
- 200 `{"ok": true, "status": "done", "summary": {...}, "session": {...}}` — small histories can finish inside the start call.
- 409 — the session is streaming or the Agent runtime is stale; retry after the turn ends.
- 404 / 400 — unknown session / bad body.

`GET /api/session/compress/status?session_id=...` returns `{"ok": true, "status": "idle"|"running"|"done"|"error"|"cancelled", ...}`; terminal payloads merge `summary` / `error` and stay readable for the job TTL (10 minutes). `idle` in the middle of an observed job means it vanished server-side and must be reported, not polled forever.

The `summary` dict is `{headline, token_line, note, reference_message}`. After `done` the server has REPLACED the session history, so the client reloads the transcript via `GET /api/session`.

The mobile client polls with the WebUI cadence (700 ms, +300 ms per poll, capped at 2 s) and tolerates up to 2 consecutive transport failures before aborting.

## Automatic compression (client-side)

The server auto-compresses mid-turn on its own threshold. The client additionally offers an idle-between-turns trigger: when the `metering` fill percentage crosses the configured threshold (default 85%) while no stream is active and the user enabled the switch, one compression is started per arming cycle; the trigger re-arms only after the fill drops back below the threshold (hysteresis prevents compression loops). The preference persists in SharedPreferences `mars-auto-compression`.

## Reasoning effort

`GET /api/reasoning?model=…&provider=…&session_id=…` returns the effective reasoning state: `{show_reasoning, reasoning_effort, source, supported_efforts[], supports_reasoning_effort, supports_thinking_toggle, model_overrides}`. `reasoning_effort` is the server-COERCED effective value (`''`/absent = default); `supported_efforts` is the per-model ladder the client must offer verbatim (never invent levels). A thinking-toggle-only model (`supports_thinking_toggle:true`, empty ladder) has no selector.

`POST /api/reasoning` with `{"effort": "…", "model"?, "provider"?, "session_id"?}` persists the choice server-side and answers the same status payload. WebUI parity: `effort:""` means Default (clears the override) and the key must be present.

`POST /api/chat/start` carries NO reasoning field. The server resolves the effort per turn from session override > per-model pin > global config, so an explicit pick must be persisted via `POST /api/reasoning` before the next chat/start; the mobile client records a pick locally only after the server confirmed it, and hides the selector on any failed/unknown status.

## Voice turns: `voice_mode` and the unsupported-backend refusal

A voice turn adds `"voice_mode": true` to the `POST /api/chat/start` body (text turns omit the
key entirely, so the payload is byte-identical to the pre-voice contract).

Backend support is not universal (`SERVER-CONTRACT.md` §1): `legacy-direct` / `legacy-journal`
apply the flag and echo it in the start response, while `runtime-runner` refuses it BEFORE any
side effect:

```
HTTP 409 {"code": "unsupported_voice_mode_backend"}
```

Client contract for that refusal (implemented by `VoiceModeRejectionPolicy` +
`ChatTurnDispatcher.FrameDecision.REJECT_VOICE_MODE`):

- `describeHttpError` reads `code` as well as `error`, so the refusal reaches the app as
  `HTTP 409: unsupported_voice_mode_backend` and is classifiable (a bare `HTTP 409` is not —
  it is also "session is streaming" / stale clarify).
- It is NOT a transport drop: no recovery job, no reconnect, no `Last-Event-ID` attach.
- It is NOT retried: the same body yields the same 409, and retrying with `voice_mode: false`
  would silently downgrade the turn. The queued message leaves the auto-send FIFO and lands in
  the failed card with the text preserved; its retry button reads "Отправить текстом", i.e. the
  downgrade only ever happens as an explicit user action.
- It is NOT a success: the queue row is neither confirmed nor removed.
- The user is told the truth in Russian, and the voice dialog is switched off (the backend
  cannot serve it, so leaving the microphone open would only invite the same failure).
- A queued voice turn keeps `voice_mode` in its persisted record (`QueuedMessage.voiceMode`,
  SharedPreferences) and replays it at flush time — the voice loop is already off by then, and
  recomputing the flag from loop state would send `voice_mode: false` silently.

# Chat SSE: resume and reconnect

## Event ids and the resume cursor

`GET /api/chat/stream?stream_id=…` (and the legacy `POST /chat` SSE response) numbers every journal-worthy frame with an SSE `id:` field of the form `<stream_id>:<seq>` — tokens, tool frames, `todo_state`, `title` and `done` alike. Frames may also arrive without an `id:` (they carry nothing resumable).

The client treats ids as OPAQUE and records the last non-blank one via the `onEventId` transport callback of `ChatRepository.sendMessage` / `attachToStream`. It fires for EVERY id-bearing frame AFTER the frame was delivered — emit returned / the `todo_state` and side-event callbacks ran — never before, so a cancelled or failed delivery leaves the cursor BEFORE the undelivered frame and the server replay covers it on reconnect. Known limitation: "delivered" means entered the `flowOn` channel buffer, not processed by the terminal collector; consumers needing end-to-end acking feed `StreamResumeTracker` from their terminal collector instead (`ChatEvent.Chunk` carries `eventId` for exactly this). `StreamResumeTracker` implements the WHATWG "last event ID" buffer semantics: id-less frames keep the cursor, an empty `id:` clears it, a new turn starts with `reset()` so a cursor from a previous stream never leaks into a new request.

## Reattaching with Last-Event-ID

`attachToStream(baseUrl, credential, streamId, lastEventId = …)` sends the cursor as the spec-compliant `Last-Event-ID` request header (the server honors it as a FALLBACK after its explicit query params `after_event_id`/`after_seq`/`replay`). Server behaviour (verified read-only against `/opt/hermes-webui/api/routes.py` `_handle_sse_stream`):

- live stream + cursor → `_sse_replay_run_journal_gap_checked` replays the journaled events missed since that cursor (gap-checked), then the live drain loop continues with `id:`-bearing frames;
- dead-but-journaled stream → `_replay_run_journal` replays the journal from the cursor, then the terminal frames;
- no cursor → live-only subscription (the server-side snapshot backfill still applies);
- unusable cursor (invalid/foreign/ahead-of-stream `last_seq`) → the server NORMALIZES it to replay-from-start, never silently skipping journal events;
- unknown/expired stream (no journal) → `HTTP 404 {"error":"stream not found"}`.

A 404 means the journal is GONE — resume is impossible. The client surfaces it as `ChatEvent.Error "HTTP 404: stream not found"` (`ErrorOrigin.TRANSPORT`) and must fall back to reloading the session (`GET /api/session`) and re-rendering the transcript; a turn that is still running re-binds via `active_stream_id` and a fresh attach.

## Error origins (transport vs server)

`ChatEvent.Error` carries an `ErrorOrigin`:

- `TRANSPORT` (default) — the request itself failed: IOException/read timeout (`describeNetworkError` text) or a non-2xx response (`"HTTP <code>: <reason>"` from `describeHttpError`). Only these may drive reconnect decisions;
- `SERVER` — the SSE channel delivered a runtime verdict: an `error`/`apperror` frame mapped by `StreamEventMapper`. The connection was healthy; the AGENT failed. The message is arbitrary server text (tool output, tracebacks, anything containing "not found") and must NEVER be parsed as a network verdict or re-attempted as a transport retry.

`MidTurnRecoveryCoordinator.StreamFailure` mirrors this with an optional `origin` seam (`null` = legacy/unknown, treated as transport; `TRANSPORT`; `SERVER_EVENT` → always terminal `GiveUp`). Callers forwarding a `ChatEvent.Error` with `origin == SERVER` MUST set `StreamFailure.Origin.SERVER_EVENT`. Both new parameters are appended with defaults — existing call sites compile unchanged.

## Shared OkHttp clients (HttpClients)

`ru.mars.network.http.HttpClients` provides ONE connection pool + ONE dispatcher and two timeout profiles, used as constructor DEFAULTS (every repository still accepts an injected client):

- `stream` — SSE calls (`POST /api/chat/start`, `GET /api/chat/stream`, legacy `POST /chat`): 90 s read timeout (covers server stalls around the 5 s `: heartbeat` comment; no `callTimeout` — a call lasts a whole turn);
- `rest` — bounded request/response calls (sessions, models, uploads, cancel/steer, health): 10 s connect / 20 s read / 20 s write, no `callTimeout` (per-phase bounds catch stalled sockets without killing slow-but-steady 20 MB uploads).

`pingInterval` is deliberately NOT configured: OkHttp pings only exist on HTTP/2 and WebSockets, this backend serves SSE over HTTP/1.1 `Connection: close`, and an HTTP ping is NOT an SSE heartbeat — it says nothing about the event stream making progress. Liveness = the server's `: heartbeat` comment (every `_SSE_HEARTBEAT_INTERVAL_SECONDS = 5`) + the stream read timeout.

## Client reconnect semantics (mid-turn recovery)

`MidTurnRecoveryCoordinator` (pure JVM, no clocks/randomness of its own) turns one `StreamFailure {throwable, errorMessage, lastEventId, origin}` into exactly one sealed decision, first match wins:

0. `origin == SERVER_EVENT` (server-side terminal error frame) → `GiveUp("server-side stream error: …")` — reconnecting cannot fix an agent failure and its message text is never classified;
1. local network unavailable → `WaitForNetwork` (park; does not burn the retry budget); on the unavailable→available transition the next retry is IMMEDIATE (0 ms, one-shot);
2. gone stream — ANCHORED markers only: `HTTP 404` status extracted from the message/cause chain, the message equalling `stream not found` / `no such stream`, or a message containing `unknown stream`, or a bare `404` in a message that also says `stream` → `ReloadSession`. Broad `not found` or bare `404` do NOT match: tool output routinely contains "file not found"/"404 page not found", and misreading it would discard a resumable live turn mid-answer;
3. non-retryable class (401/403 auth, other 4xx, deliberate cancel, code bug — classified by `StreamRecoveryBackoffPolicy.shouldRetry`) → `GiveUp(reason)`;
4. per-turn reattach cap reached without VERIFIED progress → `GiveUp` (safety net, default 24; see below);
5. reconnect budget exhausted (`AdaptiveSseReconnectPolicy` streak) → `GiveUp`;
6. otherwise → `ReattachAfter(delayMs, attempt, resumeFromEventId)`: jittered exponential ladder (500 ms → cap, ×2 per consecutive failure, ±20% jitter), 1-based attempt within the streak, cursor for the `Last-Event-ID` header.

Budget resets are progress-aware to close the replay-only infinite loop. Feed every frame's id to `onStreamFrame(eventId)` (wire it from the same `onEventId` lambda as `StreamResumeTracker`): an id NEWER than the high-water cursor (`RunJournalCursor.isAfter` — same-run ids compare `<stream_id>:<seq>` numerically, different runs are always newer, opaque shapes fall back to inequality) resets the streak AND re-arms the per-turn cap; an id at/below it is a REPLAYED duplicate and resets nothing, so a reconnect loop fed only by journal replays still terminates in `GiveUp`. The legacy `onStreamHealthy()` (id-less liveness proof) resets only the streak and never re-arms the cap. A `/api/health` probe fed through `onServerProbe(...)` (`ServerHealthPolicy.evaluateStatus`) gates the one-shot immediate retry: while the server measures OFFLINE the arm is preserved and the ladder delay is used instead — the radio came back but the backend is still down, so a 0 ms hammer helps nobody. The coordinator never sleeps or re-opens anything itself; the caller owns threading, delays and the actual `attachToStream` call (a GET — never a repeated `POST /api/chat/start`, which would start a second turn), and must call `decide` exactly once per failure.

# Interactive attention: approval / clarify

Both prompts arrive as structured SSE frames carrying an id and explicit options. The client must
keep the payload structured end to end (id + options + gateway triple) and answer through the
matching `respond` endpoint — collapsing the frame into transcript text breaks the answer path.

## SSE frames

- `approval` — `{approval_id, command, description, pattern_key(s), allow_permanent, allow_session,
  run_id?, _gateway_mirror_token?, tool?, risk_level?, choices?, pending_count}` →
  `ChatEvent.ApprovalRequired` (`ApprovalPayload`).
- `clarify` — `{clarify_id, question, choices_offered, session_id, requested_at, timeout_seconds,
  expires_at, pending_count?}` → `ChatEvent.ClarifyRequired` (`ClarifyPayload`).

`approval_id`/`run_id`/`_gateway_mirror_token` must be preserved: the respond endpoint resolves the
exact queued entry with them, and a partial gateway triple is rejected (409 `gateway_run_unavailable`).

## Respond endpoints

`POST /api/approval/respond` (auth: `Authorization: Bearer <native credential>`)

```json
{"session_id":"...","choice":"once|session|always|deny","approval_id":"...","run_id":"...","mirror_token":"...","grant_autonomy":true}
```

- `run_id` + `mirror_token` are sent only when BOTH are present;
- `grant_autonomy` only when true (the autonomy-grant option), which flips session-wide autonomy
  server-side; the settled flag comes back in the response and is projected into the UI.

`POST /api/clarify/respond`

```json
{"session_id":"...","response":"...","clarify_id":"..."}
```

`session_id` and a non-blank `response` are required; the entry is matched by `clarify_id`.

Pending lookups (queue refresh after an answer): `GET /api/approval/pending?session_id=` →
`{"pending": {...}|null, "pending_count": N}`, `GET /api/clarify/pending?session_id=` →
`{"pending": {...}|null}`.

## Response taxonomy and retry rules

| Result | Meaning | Retry with the same choice |
|---|---|---|
| 200 `{"ok": true}` | accepted (`stale_cleared` possible for approval) | no — prompt resolved |
| 409 `{"ok": false, "stale": true}` (clarify) | expired/already resolved | no — dismiss, retrying can never succeed |
| 200 `{"ok": false}`, 400/401/403/409, 422 | refused; deterministic for this body | no — the UI shows the message only |
| 408, 429, 5xx | transient transport/server failure | yes |
| transport error (no HTTP response) | request may or may not have reached the server | yes |

`InteractiveRetryPolicy` (core-network) is the single place implementing this table. On a retryable
failure the mobile client KEEPS the user's decision (`ApprovalAttempt` / the clarify answer) and
shows one retry action labelled with that decision ("Повторить: Разрешить для этой сессии"), so a
retry re-sends exactly what the user approved — never a default and never a re-typed guess. The
choice is dropped on success, on stale, on dismissal and when the prompt is superseded by the next
queued entry.

Client states: idle (options enabled) → sending (all options disabled, indeterminate "Отправка…"
row) → failed (inline error strip + retry action). No silent automatic re-post: an approval is a
consent, so the retry is always an explicit user action.

# Live channels: sessions / approval / clarify

Three long-lived GET SSE feeds replace polling. Wire shapes were verified read-only against
`/opt/hermes-webui`; the client contract is `LiveEventsRepository` (core-network) and the
parsers live in `StreamEventMapper` (`sessionListEvent`, `approvalStreamUpdate`,
`clarifyStreamUpdate`).

| Channel | Path | Event names | Data |
|---|---|---|---|
| dialog list | `GET /api/sessions/events` | `sessions_changed` | `{type, version, reason, profile?, session_id?}` |
| approvals | `GET /api/approval/stream?session_id=` | `initial`, `approval` | `{pending: {...}\|null, pending_count: N}` |
| clarifications | `GET /api/clarify/stream?session_id=` | `initial`, `clarify` | `{pending: {...}\|null, pending_count: N}` |

- **The session feed carries no rows.** It is an invalidation: the client re-runs
  `GET /api/sessions` (the same request the manual refresh makes) and uses `reason` /
  `session_id` only to decide what kind of change happened (`session_new` → added,
  `session_delete` → removed, otherwise updated/refresh). Unknown reasons degrade to a full
  refresh.
- **`pending: null` is a state, not an error.** The server pushes it when the queue empties
  (answer accepted, stale sweep, session reset) and the card must come down. The client ignores
  only the FIRST empty snapshot after subscribing — it describes the state *before* the
  subscription and must not kill a card that the turn stream already showed.
- **Auth:** device Bearer on all three. `/api/sessions/events` is covered by the `/api/sessions`
  prefix in `api/auth.py`; the two attention streams are GET-only entries in
  `NATIVE_BEARER_READONLY_EXACT_PATHS` (POST on them never authenticates that way, answering
  still goes through `NATIVE_BEARER_EXACT_PATHS`).
- **Resilience:** reconnect with jittered exponential backoff (`AdaptiveSseReconnectPolicy`,
  500 ms → 30 s, ±20%); liveness is proven by any byte (the server's 5 s `: keepalive`
  comment), not by the 200 alone. When the retry budget is spent the flow polls
  (`GET /api/approval/pending`, `GET /api/clarify/pending`, or a synthetic list refresh) and
  retries SSE every 5 polls; a successful re-open emits `STREAMING` again so the screen can
  reconcile.
- **Lifecycle:** the feeds are owned by the screen — `ChatActivity`/`ChatSessionsActivity` open
  them in `onResume` and cancel in `onPause`/`onStop`. Cancelling the collector cancels the
  in-flight OkHttp call, so no socket survives in the background.

## Manual device checks (POCO F5, not covered by CI)

1. **Card latency.** With the chat open on a dialog, trigger an approval from another client
   (web UI on the same session) and measure the time until the card appears — target < 300 ms.
   Repeat for a clarify prompt.
2. **Background.** Send the app to the background (home) while the chat is open; the two
   `/api/*/stream` requests must disappear from `adb shell dumpsys netstats`/server logs within a
   second, not after the 90 s read timeout. Return to the app: both feeds must reconnect on
   their own (server log shows new `stream` requests without a manual refresh).
3. **Airplane mode.** Enable airplane mode while a card is pending → the card must stay (no
   stuck spinner), the app must fall back to polling (server log: `GET /api/approval/pending`
   every ~2 s) and no card may disappear. Disable airplane mode → within ~10 s the SSE
   subscription must come back and a newly injected approval must appear without touching the
   screen.
4. **Queue of two.** Inject two approvals for one session; answer the first — the card must
   switch to the second (`pending_count` 2 → 1) and come down only after the second is answered.
5. **Dialog list.** Create/rename/delete a dialog from the web UI while the Android dialog list
   is open: the row must appear/change/disappear without leaving the screen. With the app in the
   background the list must be correct on return (onResume refresh + reconnect reconcile).
