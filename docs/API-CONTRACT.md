# API contract: device pairing v3

## Create a pairing

`POST /api/auth/device-pairings`

The server returns `pair_id`, `code`, `poll_secret`, `expires_in`, and `status`.
`poll_secret` is confidential and is never logged or sent in a URL for credential exchange.

## Poll pairing status

`GET /api/v1/auth/device-pairings/status?pair_id={pair_id}&poll_secret={poll_secret}`

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

`POST /api/v1/auth/device-pairings/revoke` with `Authorization: Bearer {access_token}`.

Approval remains browser-only and requires the browser session plus CSRF protection. A native client must not emulate browser approval.
