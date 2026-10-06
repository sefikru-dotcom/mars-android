# API contract: device pairing

This document records the server contract available to the Android client at the baseline. It is intentionally limited to the pairing flow; it does not invent a native credential or chat API.

## Create a pairing

`POST /api/auth/device-pairings`

The server returns:

- `pair_id`: pairing identifier used for polling.
- `code`: user-facing pairing code.
- `poll_secret`: secret used by the pairing client when polling.
- `expires_in`: lifetime of the pairing, in seconds.
- `status`: current pairing status.

The client must keep `poll_secret` confidential and must not log, commit, upload, or expose it in diagnostics. The CI workflow does not call this endpoint and has no pairing secrets.

## Poll pairing status

`GET /api/auth/device-pairings/status` with `pair_id` and `poll_secret` returns:

- `status`: current pairing status.
- `expires_at`: server expiration timestamp.

The exact transport for the two parameters (query or otherwise) is owned by the server implementation and must not be inferred from this document. Clients must handle expiration and terminal statuses without treating polling as approval.

## Approve pairing

Approval is **browser-only** and requires the browser session plus CSRF protection. A native client must not emulate browser approval or manufacture a session/CSRF token.

## Explicitly blocked capabilities

- **Native device credential: BLOCKED.** The current contract provides no native device-credential endpoint or credential exchange. Do not add a client-side credential flow until a documented server contract and security review exist.
- **Native chat: BLOCKED.** Native chat is not part of the current contract. Do not ship, test, or claim a native chat implementation from this baseline.

Any future API addition must specify authentication, authorization, transport, error/expiry behavior, redaction rules, and security review before client implementation.
