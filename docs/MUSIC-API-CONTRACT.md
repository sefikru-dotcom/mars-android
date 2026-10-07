# Mars Music API contract (port 8796)

Source reviewed: `/root/tools/mars_music/server.py` and `static/player.js`. The server binds `127.0.0.1:8796`; public installations commonly proxy it under `/music/`.

## Important limitation

There is **no HTTP player-control or status API** (`/status`, `/play`, `/pause`, `/next`, `/prev`, `/volume`, `/queue` are not registered routes). The UI owns the audio element, queue and playback state in the browser. Remote control is implemented through the unauthenticated WebSocket endpoint `GET /api/connect/ws` (typically externally `/music/api/connect/ws`). Thus status and controls require a WebSocket client; they are not REST calls.

## Mars Connect WebSocket

Connect with `ws://host:8796/api/connect/ws` (or `wss://host/music/api/connect/ws` via TLS/proxy).

Client messages:

- `REGISTER`: `{ "type":"REGISTER", "id":string, "name":string, "deviceType":string, "isActive":boolean, "state":{ "track":track|null, "currentTime":number, "volume":number, "isPlaying":boolean } }`
- `STATE_SYNC`: `{ "type":"STATE_SYNC", "isActive":boolean, "state":{ ... }, "queue":[track], "currentIndex":integer }`. `queue` and `currentIndex` are optional; player sends queue only when changed/forced.
- `CONTROL_CMD`: `{ "type":"CONTROL_CMD", "targetDeviceId":string|"all", "action":string, "payload":object }`. The server relays as `EXEC_CMD` to matching *other* connected clients; actions recognized by player.js are `playPause`, `next`, `prev`, `setVolume` (payload `{ "volume":number }`), `seek` (payload `{ "position":number }`), and `transferPlayback`.

Server messages:

- On register: `SESSION_RESTORE` with `{ "type":"SESSION_RESTORE", "session": { track, queue, currentIndex, currentTime, volume, isPlaying, activeDeviceId, timestamp } }`, sent only to the registering client. Immediately after, every client (including this one) also receives a `DEVICES_UPDATE` broadcast — clients MUST match the `type` field exactly and handle `SESSION_RESTORE` once, never consuming `DEVICES_UPDATE` as a restore.
- Updates: `DEVICES_UPDATE` with `devices[]` and `globalSession` (same session shape).
- Commands: `EXEC_CMD` with action, payload and fromDevice. Transfers use `RECEIVE_TRANSFER`.

Track shape: `{ "id":string, "title":string, "artist":string, "duration":string, "thumbnail":string|null }`. Position/currentTime is seconds; duration in catalog/search data is usually `m:ss`; volume is 0.0–1.0. Playback status is represented by `isPlaying` boolean. A websocket client is not given an independent authoritative browser audio state beyond these synchronized messages.

## HTTP endpoints relevant to basic library/search

- `GET /api/search?q=<query>[&type=songs|albums|artists|playlists]`: `{ "success":true, "results":[...] }`. Verified against the live `handle_search` in `/root/tools/mars_music/server.py`: the filter query parameter is **`type`** (default `songs`) — there is no `filter` parameter. Song results include `{ "type":"song", "id", "title", "artist", "thumbnail", "duration" }`. An empty/missing `q` is **not** an error: the handler delegates to `handle_charts` and returns the charts envelope with HTTP 200 (the 400-on-empty-query behavior belongs to an unrouted legacy function). Upstream failure responds HTTP 500 with `{ "success":false, "error":... }`.
- `GET /api/charts`: same envelope as search — `{ "success":true, "results":[track...] }`; not player status. On upstream failure the live handler falls back to cached charts and still returns `{ "success":true, "results":[...], "fallback":true }` with HTTP 200; clients must nevertheless surface any `{ "success":false, "error":... }` as a failure, never as an empty list. (`/api/home` shares the success-envelope shape with sections.)
- `GET /api/stream/{video_id}`: audio stream, not a control operation.

All API paths above are relative to the service root. The browser UI's playback queue is local and capped at 300 entries when synced; an empty queue does not overwrite global queue during `STATE_SYNC`.

## Android repository mapping

`MusicRepository.search(query)` and `MusicRepository.getCharts()` call HTTP `/api/search?q=...` and `/api/charts`, both parsing the `{success,results}` envelope. `getStatus()`/`getQueue()` register over Mars Connect and read `SESSION_RESTORE`. Commands register, wait for `SESSION_RESTORE`, then send `CONTROL_CMD` with `targetDeviceId` set to the session's `activeDeviceId`. The wait is strictly typed and bounded: only frames with `type == "SESSION_RESTORE"` are accepted (`DEVICES_UPDATE` broadcasts carrying `globalSession` are ignored), the restore is processed exactly once (duplicate frames cannot re-resume or fire a second `CONTROL_CMD`), and the wait runs under `withTimeout` (default 10 s) — coroutine cancellation (including timeout) cancels the socket. A server close before the restore completes the call with an `IOException`, and a rejected `send()` for `REGISTER` or `CONTROL_CMD` fails the call. When the session reports no `activeDeviceId`, commands FAIL with an `IllegalStateException` and no `CONTROL_CMD` frame is sent at all — the repository never falls back to `targetDeviceId:"all"`, because the live server relays `all` to every other connected client and would toggle unrelated devices; paired play/pause control must never broadcast. `play()` and `pause()` both map to `playPause`, since the protocol offers no distinct play/pause action. After the command frame is delivered the socket is closed gracefully with code 1000 (never `cancel()` mid-handshake), and a server-initiated close is echoed to complete the handshake. The WS URL is derived from the base URL by explicit scheme mapping (`http://`→`ws://`, `https://`→`wss://`; Kotlin's `String.replaceFirst(String, String)` is literal, so regex-style patterns like `"^http"` must not be used). No HTTP contract exists for these methods.
