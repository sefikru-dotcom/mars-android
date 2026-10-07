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

- On register: `SESSION_RESTORE` with `{ "session": { track, queue, currentIndex, currentTime, volume, isPlaying, activeDeviceId, timestamp } }`.
- Updates: `DEVICES_UPDATE` with `devices[]` and `globalSession` (same session shape).
- Commands: `EXEC_CMD` with action, payload and fromDevice. Transfers use `RECEIVE_TRANSFER`.

Track shape: `{ "id":string, "title":string, "artist":string, "duration":string, "thumbnail":string|null }`. Position/currentTime is seconds; duration in catalog/search data is usually `m:ss`; volume is 0.0–1.0. Playback status is represented by `isPlaying` boolean. A websocket client is not given an independent authoritative browser audio state beyond these synchronized messages.

## HTTP endpoints relevant to basic library/search

- `GET /api/search?q=<query>[&filter=songs|albums|artists|playlists]`: `{ "success":true, "results":[...] }`. Song results include `{ "type":"song", "id", "title", "artist", "thumbnail", "duration" }`. Empty query responds HTTP 400; upstream failure responds 500.
- `GET /api/charts`: same envelope as search — `{ "success":true, "results":[track...] }`; not player status. `{ "success":false, "error":... }` must surface as a failure, never as an empty list. (`/api/home` shares the success-envelope shape with sections.)
- `GET /api/stream/{video_id}`: audio stream, not a control operation.

All API paths above are relative to the service root. The browser UI's playback queue is local and capped at 300 entries when synced; an empty queue does not overwrite global queue during `STATE_SYNC`.

## Android repository mapping

`MusicRepository.search(query)` and `MusicRepository.getCharts()` call HTTP `/api/search?q=...` and `/api/charts`, both parsing the `{success,results}` envelope. `getStatus()`/`getQueue()` register over Mars Connect and read `SESSION_RESTORE`. Commands register, wait for `SESSION_RESTORE`, then send `CONTROL_CMD` with `targetDeviceId` set to the session's `activeDeviceId` (falling back to `all` only when no active device is reported); `play()` and `pause()` both map to `playPause`, since the protocol offers no distinct play/pause action. `all` is a broadcast convenience and can toggle multiple devices, so it is not safe for paired play/pause control. After the command frame is delivered the socket is closed gracefully with code 1000 (never `cancel()` mid-handshake), and a server-initiated close is echoed to complete the handshake. No HTTP contract exists for these methods.
