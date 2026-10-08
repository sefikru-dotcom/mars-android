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
