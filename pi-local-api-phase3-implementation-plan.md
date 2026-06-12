# Pi Local API Phase 3 Implementation Plan

This plan turns the finalized Phase 2 API contract into an implementation
blueprint for the Raspberry Pi local service. The current repository contains
documentation only, so this is an implementation plan rather than direct service
code.

## 1. Service Goal

Run a small HTTP JSON API on the Raspberry Pi so the Android companion app can
inspect and repair the Pi-owned audio system without SSH or arbitrary command
execution.

Default base URL:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

The API should bind to the trusted LAN only. If it binds to `0.0.0.0`, firewall
rules must limit access to the home subnet.

## 2. Recommended Stack

Use Python with FastAPI because it is lightweight, typed enough for this API,
simple to deploy as a systemd user service, and easy to test.

Suggested files when the service scaffold is added:

```text
pi-api/
  pyproject.toml
  README.md
  pihouse_api/
    __init__.py
    app.py
    auth.py
    config.py
    commands.py
    diagnostics.py
    health.py
    operations.py
    schemas.py
    store.py
  tests/
    test_auth.py
    test_health.py
    test_operations.py
    test_allowlists.py
```

Suggested runtime state path:

```text
~/.local/state/pihouse-api/operations.json
```

Suggested config path:

```text
~/.config/pihouse-api/config.json
```

## 3. Configuration

All device-specific values must come from config. Do not accept service names,
MAC addresses, sink names, or journal units from request bodies or query strings
unless they are ids that map through these allowlists.

Example config:

```json
{
  "deviceId": "pihouse-audio-01",
  "controllerInstanceId": "ctrl_01JXYZPIHOUSEAUDIO",
  "apiName": "pihouse-audio-api",
  "apiVersion": "1.0.0",
  "contractVersion": "2026-06-phase3",
  "tokenFile": "/home/pi/.config/pihouse-api/token",
  "speakers": {
    "indoor": {
      "displayName": "Indoor",
      "mac": "AA:BB:CC:DD:EE:FF",
      "sinkName": "bluez_output.AA_BB_CC_DD_EE_FF.1"
    },
    "outdoor": {
      "displayName": "Outdoor",
      "mac": "11:22:33:44:55:66",
      "sinkName": "bluez_output.11_22_33_44_55_66.1"
    }
  },
  "spotifyEndpoints": {
    "indoor": {
      "displayName": "Indoor",
      "service": "librespot@indoor",
      "sinkName": "bluez_output.AA_BB_CC_DD_EE_FF.1"
    },
    "outdoor": {
      "displayName": "Outdoor",
      "service": "librespot@outdoor",
      "sinkName": "bluez_output.11_22_33_44_55_66.1"
    },
    "both": {
      "displayName": "Whole House",
      "service": "librespot@both",
      "sinkName": "whole_house"
    }
  },
  "services": {
    "pipewire": "pipewire",
    "wireplumber": "wireplumber",
    "librespot_indoor": "librespot@indoor",
    "librespot_outdoor": "librespot@outdoor",
    "librespot_both": "librespot@both",
    "bt_watchdog": "bt-watchdog"
  },
  "logUnits": {
    "pipewire": "pipewire",
    "wireplumber": "wireplumber",
    "librespot_indoor": "librespot@indoor",
    "librespot_outdoor": "librespot@outdoor",
    "librespot_both": "librespot@both",
    "bt_watchdog": "bt-watchdog"
  },
  "sinks": {
    "indoor": "bluez_output.AA_BB_CC_DD_EE_FF.1",
    "outdoor": "bluez_output.11_22_33_44_55_66.1",
    "whole_house": "whole_house"
  },
  "spotifyIntegration": {
    "mode": "connect_status_handoff",
    "webApiEnabled": false,
    "tokenFile": null
  }
}
```

## 4. Command Boundary

The service must invoke commands with argument arrays, not shell strings.

Allowed command families:

```text
systemctl --user is-active <allowed-service>
systemctl --user show <allowed-service> --property=...
systemctl --user restart <allowed-service>
systemctl --user start bt-watchdog.service
systemctl --user is-active bt-watchdog.timer
journalctl --user -u <allowed-log-unit> --lines <bounded-count> --no-pager --output=json
bluetoothctl info <configured-speaker-mac>
bluetoothctl connect <configured-speaker-mac>
pactl list short sinks
wpctl status
cat /proc/sys/kernel/random/boot_id
```

Rules:

- Never pass user-provided raw strings to subprocess.
- Validate request ids against config allowlists before command execution.
- Keep command timeouts short, usually 3 to 15 seconds.
- Return structured degraded states on timeout instead of blocking the API.
- Bound all log reads with a server-side maximum, for example 200 lines.

## 5. Authentication

Authentication requirements:

- `GET /identity` and `GET /health` may be public on the LAN.
- All mutating endpoints must require `Authorization: Bearer <token>`.
- Diagnostics endpoints should require auth unless the product explicitly
  decides they are safe on the trusted LAN.
- Compare bearer tokens with constant-time comparison.
- Read the token from a file whose permissions are limited to the service user.

Spotify token rules:

- Phase 3 does not require Spotify Web API OAuth.
- Android must not receive, store, or exchange Spotify access/refresh tokens.
- If a later Pi-side Web API integration is enabled, store Spotify tokens only
  on the Pi under service-user-readable permissions and redact all token-like
  values from status, diagnostics, events, and logs.

Mutating endpoints:

```text
POST /operations/reconnect
POST /operations/restart-service
POST /operations/run-watchdog
```

## 6. Stale-Control Prevention

Every mutating request must include:

```json
{
  "clientRequestId": "android-generated-uuid",
  "observedBootId": "boot-id-from-last-status-or-health",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Validation:

- Reject missing `clientRequestId`, `observedBootId`, or `observedAt`.
- Reject if `observedBootId` does not match the current Pi boot id.
- Reject if `observedAt` is older than the configured freshness window, such as
  120 seconds.
- Return an app-safe error with code `stale_observation` or `boot_changed`.

This prevents Android from sending repair commands based on stale state after a
Pi reboot or network reconnect.

## 7. Operation Persistence and Idempotency

Persist operation records to `operations.json` so Android can poll after app
restarts or network reconnects.

Operation schema:

```json
{
  "operationId": "server-generated-uuid",
  "clientRequestId": "android-generated-uuid",
  "type": "reconnect",
  "status": "succeeded",
  "target": {
    "speakerId": "outdoor"
  },
  "requestedAt": "2026-06-12T07:41:00Z",
  "startedAt": "2026-06-12T07:41:00Z",
  "finishedAt": "2026-06-12T07:41:08Z",
  "observedBootId": "8f6d2f...",
  "result": {
    "connected": true
  },
  "error": null
}
```

Allowed operation states:

```text
queued
running
succeeded
failed
rejected
```

Idempotency:

- Index records by `clientRequestId`.
- If the same `clientRequestId` is received again with the same operation type
  and target, return the existing operation record.
- If the same `clientRequestId` is reused for a different type or target, reject
  with `idempotency_conflict`.
- Keep at least the most recent 100 operations or the last 7 days.
- Write state atomically: write temp file, fsync, then replace.

## 8. Endpoint Behavior

### `GET /identity`

Returns stable API and Pi identity.

Response fields:

```text
ok
deviceId
controllerInstanceId
hostname
apiName
apiVersion
contractVersion
observedAt
```

`controllerInstanceId` is required and non-null in Phase 3. Android must reject
controls when `deviceId` or `controllerInstanceId` does not match the saved Pi
controller identity.

### `GET /health`

Fast summary endpoint for startup and badges.

Collect:

- Current boot id.
- Boot time and uptime.
- PipeWire active state.
- WirePlumber active state.
- Configured speaker connection state.
- Librespot service states.
- Whole-house sink presence.
- Watchdog timer/service state.

Return `state` as one of:

```text
booting
healthy
degraded
error
```

Return stable reason codes, for example:

```text
pipewire_down
wireplumber_down
indoor_speaker_disconnected
outdoor_speaker_disconnected
whole_house_sink_missing
spotify_indoor_unhealthy
spotify_outdoor_unhealthy
spotify_both_unhealthy
spotify_logged_out
spotify_token_expired
spotify_upstream_unavailable
spotify_wrong_active_device
spotify_playback_unavailable
spotify_playback_interrupted
watchdog_inactive
```

### `GET /status`

Detailed state for the main status screen.

Include:

- Identity fields.
- Boot info.
- Service states for all allowed services.
- Speaker states for configured speakers.
- Spotify endpoint states.
- Spotify status extension: account/session state if known, active device
  summary if safely available, bounded playback summary, per-endpoint route
  readiness, and recommended Android action.
- Sink states for configured sinks.
- Watchdog timer state.
- Last known operations summary.
- `observedAt`.

### Spotify Integration Boundary

First version should keep Spotify as status, health, and diagnostics data on
existing `/api/v1` endpoints. Do not add `/spotify/*` resources unless a future
version adds a dedicated Pi-side Spotify account workflow.

Ownership:

- The Pi owns Spotify Connect endpoints through `librespot`.
- The Pi owns Bluetooth routing, PipeWire sinks, watchdogs, and recovery.
- Android opens Spotify for device selection and never becomes a Spotify
  streamer, Spotify Connect controller, Bluetooth router, or route source of
  truth.

Recommended `/status.spotify` shape:

```json
{
  "integrationMode": "connect_status_handoff",
  "connectOwnedBy": "pi",
  "accountState": "available",
  "accountStateDetail": null,
  "activeDevice": {
    "category": "pi_endpoint",
    "endpointId": "both",
    "displayName": "Whole House",
    "isExpectedPiEndpoint": true
  },
  "playback": {
    "state": "playing",
    "isPlaying": true,
    "lastKnownAt": "2026-06-12T07:39:58Z",
    "interruptionReason": null
  },
  "routeReadiness": [
    {
      "endpointId": "both",
      "ready": true,
      "reasonCodes": []
    }
  ],
  "recommendedAction": "open_spotify"
}
```

Implementation guidance:

- If only `librespot` and local service checks are available, return
  `accountState=unknown`, `activeDevice.category=unknown`, and
  `playback.state=unknown` rather than guessing.
- Phase 3 must always include `spotifyEndpoints[]`,
  `spotify.integrationMode=connect_status_handoff`, `spotify.connectOwnedBy=pi`,
  `spotify.routeReadiness[]`, and `spotify.recommendedAction`.
- `spotify.accountState`, `spotify.activeDevice`, and `spotify.playback` are v1
  fields, but richer values are conditional. Return `unknown`, `not_configured`,
  or `null` subfields unless the Pi has safe local or Pi-owned Web API evidence.
- If Pi-side Spotify Web API is later enabled, expose only app-safe summaries:
  logged out, token expired, upstream unavailable, active device category, and
  bounded playback state. Do not expose track, playlist, user, account, or token
  data in Phase 3.
- Do not add Android Spotify OAuth, token exchange, playback transfer,
  queue/track controls, `/api/v1/spotify/*` resources, or Spotify-specific
  mutating operations in Phase 3.
- Wrong active device is not a Pi repair action. Recommend `open_spotify`.
- Librespot service failure uses existing `restart_service` for
  `librespot_indoor`, `librespot_outdoor`, or `librespot_both`.
- Route failure uses existing `reconnect` or `run_watchdog`, followed by a fresh
  `/status` read.

### `POST /operations/reconnect`

Request body:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z",
  "speakerId": "outdoor"
}
```

Allowed `speakerId` values come only from config, normally `indoor` and
`outdoor`.

Behavior:

1. Authenticate.
2. Validate stale-control fields.
3. Validate speaker id against config.
4. Apply idempotency lookup.
5. Run `bluetoothctl connect <configured-mac>`.
6. Re-read `bluetoothctl info <configured-mac>`.
7. Persist and return the operation.

### `POST /operations/restart-service`

Request body:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z",
  "serviceId": "librespot_outdoor"
}
```

Allowed `serviceId` values come only from config.

Behavior:

1. Authenticate.
2. Validate stale-control fields.
3. Validate service id against config.
4. Apply idempotency lookup.
5. Run `systemctl --user restart <allowed-service>`.
6. Re-read active state.
7. Persist and return the operation.

### `POST /operations/run-watchdog`

Request body:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Behavior:

1. Authenticate.
2. Validate stale-control fields.
3. Apply idempotency lookup.
4. Run `systemctl --user start bt-watchdog.service`.
5. Re-read speaker connection states.
6. Persist and return the operation.

### `GET /operations/{operationId}`

Returns the persisted operation by server-generated operation id.

Do not expose a list-all operation endpoint unless it is explicitly needed.

### `GET /diagnostics/summary`

Returns app-safe troubleshooting groups:

```text
network
pipewire
bluetooth
spotify
watchdog
whole_house
```

Each group should include:

```text
state
reasonCodes
userMessage
recommendedAction
androidCanTriggerAction
relatedOperation
observedAt
```

### `GET /diagnostics/events`

Returns recent structured events derived from operation records and health
snapshots. This avoids forcing Android to parse logs for common workflows.

Support only bounded query parameters:

```text
limit: 1..100, default 50
category: optional enum from the diagnostics groups
```

### `GET /diagnostics/logs`

Returns bounded logs for allowlisted units only.

Supported query parameters:

```text
unitId: enum from config.logUnits
lines: 1..200, default 50
```

Behavior:

1. Authenticate unless explicitly configured otherwise.
2. Validate `unitId` against config.
3. Clamp `lines` to server maximum.
4. Run `journalctl --user -u <allowed-unit> --lines <lines> --no-pager --output=json`.
5. Return parsed entries with timestamp, unit id, priority, and message.

Never accept raw `journalctl` flags from Android.

## 9. Common Response Shapes

Success:

```json
{
  "ok": true,
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Error:

```json
{
  "ok": false,
  "error": {
    "code": "stale_observation",
    "message": "Refresh Pi status before sending this control.",
    "details": {
      "maxAgeSeconds": 120
    }
  },
  "observedAt": "2026-06-12T07:42:30Z"
}
```

Recommended HTTP mapping:

```text
400 invalid request or unsupported enum
401 missing or invalid bearer token
404 operation not found
409 stale observation, boot changed, or idempotency conflict
422 configured target is unavailable
500 unexpected service error
503 command timeout or dependency unavailable
```

## 10. Deployment

Install dependencies in a virtual environment owned by the Pi user.

Suggested systemd user unit:

```ini
[Unit]
Description=Pi House Audio Local API
After=network-online.target pipewire.service wireplumber.service

[Service]
WorkingDirectory=%h/pi-api
Environment=PIHOUSE_API_CONFIG=%h/.config/pihouse-api/config.json
ExecStart=%h/pi-api/.venv/bin/uvicorn pihouse_api.app:app --host 0.0.0.0 --port 8765
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

Enable with:

```bash
systemctl --user daemon-reload
systemctl --user enable --now pihouse-api.service
```

## 11. Test Plan

Unit tests:

- Auth accepts only the configured bearer token.
- Unknown speaker, service, log unit, and sink ids are rejected.
- Commands are built as argument arrays from allowlists.
- Stale `observedBootId` is rejected.
- Old `observedAt` is rejected.
- Duplicate `clientRequestId` returns the original operation.
- Conflicting `clientRequestId` reuse is rejected.
- Operation store survives process restart.
- Log line count is clamped to the server maximum.

Integration tests on Pi:

- `GET /identity` returns stable device id.
- `GET /health` works while one speaker is powered off and returns degraded.
- `GET /status` reports all configured services, speakers, endpoints, and sinks.
- Reconnect operation connects a powered-on trusted speaker.
- Restart-service operation restarts only allowlisted services.
- Run-watchdog operation starts only `bt-watchdog.service`.
- Operation can be polled after API restart.
- Diagnostics logs return bounded entries from an allowlisted unit.

Manual acceptance:

- Reboot the Pi and confirm Android can detect boot id changes.
- Send a mutating request with the old boot id and verify `409 boot_changed`.
- Power-cycle each speaker and confirm reconnect results are persisted.
- Run whole-house playback for at least one hour and inspect diagnostics summary.
- Confirm Spotify status fixtures cover logged out, token expired/revoked if Web
  API is enabled, upstream unavailable, wrong active device, playback
  unavailable/interrupted, endpoint service failure, and route-not-ready states.

## 12. Blockers Before Code Implementation

The service code can be implemented after these values are confirmed for the
target Pi:

- Final Pi hostname and `deviceId`.
- Indoor and outdoor Bluetooth MAC addresses.
- Indoor and outdoor PipeWire sink names.
- Whether diagnostics endpoints require bearer auth.
- Whether the API should bind to `0.0.0.0` with firewall rules or a specific LAN
  interface address.
- Whether the Pi should ever use Spotify Web API OAuth for richer account,
  active-device, or playback state. First version does not require it.
