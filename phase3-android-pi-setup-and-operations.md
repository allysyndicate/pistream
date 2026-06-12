# Phase 3 Android Companion and Pi Local API Guide

This guide describes how Phase 3 should work once the Raspberry Pi local API
and Android companion app are implemented. It does not replace the base
Raspberry Pi audio setup in `pi-whole-house-audio.md`; complete that setup
first.

Sources for this guide:

- `android-companion-foundation.md`
- `pi-local-api-phase3-implementation-plan.md`
- `pi-local-api-phase3-test-contract.md`, when a canonical endpoint or auth
  detail conflicts with earlier planning notes

## 1. System Roles

The Raspberry Pi owns all audio behavior:

- Spotify Connect endpoints: `Indoor`, `Outdoor`, and `Whole House`
- Bluetooth speaker pairing and reconnects
- PipeWire, WirePlumber, Bluetooth sinks, and the `whole_house` combined sink
- Local diagnostics and repair commands

The Android app is a setup and status companion. It discovers the Pi, verifies
that it is the expected audio controller, shows health and diagnostics, and
triggers explicit Pi-side recovery actions through the local API.

Android must not stream audio, act as a Bluetooth bridge, or parse SSH output.

## 2. Android Install and Use Flow

No Android Gradle project or APK is currently checked into this workspace. When
the app exists, the user-facing flow should be:

1. Install the Android companion app from the approved distribution channel for
   this project.
2. Connect the phone to the same LAN as the Raspberry Pi.
3. Open the app and let it try the saved host, then the default host, then any
   future mDNS-discovered host if Backend later implements service discovery.
4. If discovery fails, enter the Pi hostname or IP address manually.
5. Confirm the app has verified the Pi identity before using dashboard actions.
6. Review `Indoor`, `Outdoor`, `Whole House`, speaker, and watchdog status.
7. Use refresh status, reconnect, or run-watchdog actions from the dashboard.
   Use reconnect and run-watchdog only when the dashboard marks a component
   degraded.
8. Use advanced diagnostics for service restart and logs.
9. Use Spotify's device picker for playback selection.

Minimum Android permissions for Phase 3:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

Do not request Android Bluetooth permissions in Phase 3. Speaker pairing and
speaker connection state are Pi-owned and exposed through the local API.
Do not request `CHANGE_WIFI_MULTICAST_STATE` in Phase 3; mDNS service discovery
is future scope and the app must work with saved host, `audiopi.local`, and
manual host/IP entry.
Do not request `POST_NOTIFICATIONS` in Phase 3; background notifications are
future scope.

## 3. Pi Local API Setup

The planned API base URL is:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

Recommended implementation stack:

- Python
- FastAPI
- `uvicorn`
- systemd user service
- JSON config under `~/.config/pihouse-api/config.json`
- operation state under `~/.local/state/pihouse-api/operations.json`

The API should bind only to the trusted LAN. If it binds to `0.0.0.0`, firewall
rules must restrict access to the home subnet.

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

Enable it with:

```bash
systemctl --user daemon-reload
systemctl --user enable --now pihouse-api.service
```

## 4. Pi API Configuration

All device-specific values must come from Pi config. Android should receive
stable ids and labels from the API instead of hard-coding local Pi values.

The config must define:

- `deviceId`
- `controllerInstanceId`
- API name, API version, and contract version
- token file path
- indoor and outdoor speaker ids, display names, MAC addresses, and sink names
- Spotify endpoint services for `Indoor`, `Outdoor`, and `Whole House`
- allowed systemd service ids
- allowed journal log unit ids
- allowed sink ids

Never accept raw service names, MAC addresses, sink names, journal units, or
command flags from Android. Requests may pass only ids that map through
server-side allowlists.

## 5. Authentication and Token Handling

Planned auth behavior from the canonical test contract:

- `GET /identity` may be public on the trusted LAN.
- `GET /health` is configurable and defaults to public on the trusted LAN.
- `GET /status` requires bearer auth.
- All mutating endpoints must require `Authorization: Bearer <token>`.
- Diagnostics endpoints require bearer auth by default.
- `GET /operations/{operationId}` requires bearer auth.
- The Pi service reads the token from a file with permissions limited to the Pi
  service user.
- Token comparison must use constant-time comparison.

Android should store the host, last verified identity summary, and user
preferences in DataStore. Phase 3 has no cookie, refresh token, login session,
or token-renewal endpoint. Token entry, scanning, provisioning, and Android
at-rest token storage still need a final product and security decision; until
then, document those choices as implementation blockers rather than assuming a
QR code, shared secret screen, or automatic pairing flow.

If Android receives `401 unauthorized`, it should keep the host visible, clear
or disable action controls and diagnostics, show an authorization-required
state, let the user re-enter or rescan the bearer token, then retry
`GET /status`.

## 6. Discovery, Manual IP, and Identity Verification

Discovery order:

1. If a saved host exists, call `GET /identity`.
2. Try `audiopi.local` on port `8765`.
3. If mDNS service discovery is later implemented, resolve the advertised
   service and call `GET /identity`.
4. Let the user enter a hostname or IP manually.

mDNS service discovery is not required for Phase 3 fixtures or QA. Tests should
cover saved host, `audiopi.local`, and manual host/IP entry without requiring
mDNS.

Manual entry should normalize only the host portion. Android constructs:

```text
http://<host>:8765/api/v1
```

`GET /identity` gates the rest of the app. The API should return:

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

The canonical identity tuple is `apiName`, `contractVersion`, `deviceId`, and
`controllerInstanceId`. `controllerInstanceId` is required, non-null, and should
be present in both `/identity` and `/status.identity`.

Phase 3 supports only `contractVersion` `2026-06-phase3`, API name
`pihouse-audio-api`, and API version `1.x.y` with minimum version `1.0.0`.
Android should validate product and API compatibility from `apiName`,
`contractVersion`, and `apiVersion`.

For a new pairing, Android may accept a non-empty `deviceId` only after API
compatibility passes and the user provides valid authorization. Android should
save the returned `controllerInstanceId` with the paired Pi identity. For an
existing pairing, Android must compare the saved identity values:

```text
apiName
contractVersion
deviceId
controllerInstanceId
```

Hostname and IP address are display and reachability data, not trust anchors.
Hostname or IP changes should not become `wrong_device` when the saved identity
values still match.

Wrong-device behavior:

- If the host responds but `apiName`, saved `deviceId`, or saved
  `controllerInstanceId` does not match the expected Pi audio controller, show
  `wrong_device`.
- Unsupported or missing `contractVersion`, unsupported API major version, or
  malformed compatibility fields should map to `api_unavailable`.
- Do not send health, status, diagnostics, or mutating requests to a wrong
  device.

## 7. Dashboard and Status Meanings

`GET /health` provides the quick connection badge. Expected health states:

```text
booting
healthy
degraded
error
```

`GET /status` provides the main dashboard and action-safety metadata. It should
include:

- identity fields
- boot id, boot time, uptime, and `observedAt`
- PipeWire and WirePlumber state
- configured speaker states
- Spotify endpoint states
- configured sink states
- watchdog timer/service state
- recent operation summary

Top-level Android states:

| State | Meaning |
| --- | --- |
| `not_found` | No saved, default, discovered, or manual target can be reached. |
| `found_healthy` | Identity is valid and required components are healthy. |
| `found_unhealthy` | Identity is valid but one or more Pi, speaker, Spotify, PipeWire, or watchdog checks are degraded. |
| `wrong_device` | A host responded but product or saved controller identity does not match the expected Pi audio controller. |
| `api_unavailable` | Host exists but the API is down, unsupported, malformed, or unavailable. |

Dashboard rows should cover:

- `Indoor`
- `Outdoor`
- `Whole House`
- indoor speaker
- outdoor speaker
- watchdog
- current or recent operation

Speaker rows should show paired, trusted, connected, assigned adapter if
available, sink name, and last Pi-side reconnect error or attempt.

## 8. Safe Mutating Actions

All mutating requests must include:

```json
{
  "clientRequestId": "android-generated-uuid",
  "observedBootId": "boot-id-from-latest-status",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Android must take `observedBootId` and `observedAt` from the latest accepted
status response. Android should not hard-code a freshness window or compare
timestamps against a fixed client-side age limit; the Pi owns freshness
validation.

The Pi should reject stale or unsafe controls when:

- required observation fields are missing
- `observedBootId` does not match the current Pi boot id
- `observedAt` is older than the configured backend freshness window.
- a reused `clientRequestId` conflicts with a different action or target

Expected error codes include:

```text
stale_observation
boot_changed
idempotency_conflict
busy
unauthorized
forbidden
target_unavailable
speaker_connect_failed
service_restart_failed
command_timeout
dependency_unavailable
```

## 9. Reconnect, Restart, and Watchdog Actions

Phase 3 backend plan uses these mutating endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/operations/reconnect` | Reconnect a configured speaker. |
| `POST` | `/operations/restart-service` | Restart an allowlisted Pi-side service. |
| `POST` | `/operations/run-watchdog` | Start the Bluetooth watchdog immediately. |
| `GET` | `/operations/{operationId}` | Poll a persisted operation. |

These `/operations/*` routes are canonical for Phase 3. Older `/actions/*`
names in planning notes are not part of the current test contract.

Reconnect request:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z",
  "speakerId": "outdoor"
}
```

Restart-service request:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z",
  "serviceId": "librespot_outdoor"
}
```

Run-watchdog request:

```json
{
  "clientRequestId": "uuid",
  "observedBootId": "boot-id",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Allowed restart targets should come from backend-supported values. Planned
service ids:

```text
pipewire
wireplumber
librespot_indoor
librespot_outdoor
librespot_both
bt_watchdog
```

Android should expose reconnect and run-watchdog as primary recovery actions.
Refresh status is also a primary dashboard action. Restart-service should stay
in advanced diagnostics until confirmed as a normal user-facing repair path.

Operation flow:

1. Read the latest dashboard `bootId` and `observedAt`.
2. Generate a UUID `clientRequestId`.
3. Submit the action.
4. If the response returns `operationId`, poll
   `GET /operations/{operationId}`.
5. Start with `operation.pollAfterMs` or `1000` ms. Poll no faster than `500`
   ms and no slower than `5000` ms.
6. For network timeout, connection refused, or HTTP `5xx` during polling, retry
   with exponential backoff of `1000`, `2000`, then `5000` ms until the total
   client wait reaches 60 seconds.
7. Do not retry `401`, `403`, `404`, `409`, or `410` polling responses as
   transient failures.
8. Stop polling when the operation reaches a terminal state: `succeeded`,
   `failed`, `rejected`, or `expired`.
9. Stop after 60 seconds of client-side waiting even if the operation is still
   non-terminal.
10. Refresh `GET /status`. If an action is rejected with `stale_observation`,
   refresh status before allowing the user to retry.

Do not hide the dashboard while an operation runs. Disable only the pressed
action or affected controls.

## 10. Diagnostics Summary, Events, and Logs

Diagnostics should be grouped by Pi-owned component:

```text
network
pipewire
bluetooth
spotify
watchdog
whole_house
```

`GET /diagnostics/summary` should return app-safe troubleshooting groups with:

```text
state
reasonCodes
userMessage
recommendedAction
androidCanTriggerAction
relatedOperation
observedAt
```

`GET /diagnostics/events` should return recent structured events from operation
records and health snapshots. Supported bounded query parameters:

```text
limit: 1..100, default 50
category: optional diagnostics group enum
```

`GET /diagnostics/logs` should return bounded logs for allowlisted units only.
Supported bounded query parameters:

```text
unitId: enum from config.logUnits
lines: 1..200, default 50
```

Logs endpoint rules:

- Require bearer auth for Phase 3.
- Validate `unitId` against config.
- Clamp `lines` to the server maximum.
- Run `journalctl` only with server-built argument arrays.
- Return parsed timestamp, unit id, priority, and message fields.
- Never accept raw `journalctl` flags from Android.

Diagnostics may include allowlisted service ids, display service names, sink
ids, speaker ids, Pi hostname, `operationId`, `clientRequestId`, and `eventId`.
Diagnostics must not include bearer tokens, speaker MAC addresses, raw
Bluetooth addresses, Wi-Fi SSIDs or passwords, arbitrary LAN hostnames,
environment variables, full shell commands, unbounded journal output, or raw
command stderr that may contain secrets.

## 11. Troubleshooting

| Symptom | Likely state | User action |
| --- | --- | --- |
| App cannot find the Pi | `not_found` | Confirm phone and Pi are on the same network, then try manual IP. |
| Host responds but app rejects it | `wrong_device` | Check the hostname/IP and connect to the Pi audio controller. |
| Host exists but controls do not load | `api_unavailable` | Check `pihouse-api.service` on the Pi and API version compatibility. |
| Action buttons are disabled | `unauthorized` or stale status | Re-authorize if required, then refresh status. |
| Action returns stale observation | `stale_observation` or `boot_changed` | Refresh status and retry only after the dashboard updates. |
| Speaker is not playing | speaker degraded | Use reconnect for the affected speaker, then run watchdog if needed. |
| Spotify endpoint is missing | Spotify endpoint degraded | Check the related `librespot` service in diagnostics. |
| Whole House is missing | `whole_house_sink_missing` | Recheck PipeWire sink names and `combine.conf` on the Pi. |
| Logs do not load | diagnostics auth or allowlist issue | Confirm diagnostics auth policy and requested `unitId`. |

## 12. Known Limitations and Unresolved Defaults

Do not invent these values in Android or documentation examples. They must be
confirmed for the target Pi:

- Final Pi hostname.
- Final `deviceId`.
- Final `controllerInstanceId` value for the target Pi install.
- Indoor Bluetooth speaker MAC address.
- Outdoor Bluetooth speaker MAC address.
- Indoor PipeWire sink name.
- Outdoor PipeWire sink name.
- Final API bind address and firewall decision.
- Final freshness window for stale-control validation if the provisional
  120-second default changes.
- Final operation retention cap if the provisional default changes. Current
  contract default is at least the newest 100 operations and terminal
  operations for 7 days.
- Final diagnostics event retention cap.
- Final diagnostics log line cap, if different from the current 200-line server
  maximum and 50-line default.
- Final token provisioning and Android token storage flow.
- Final LAN transport decision: HTTP plus bearer token, HTTPS, or mTLS.
- Final `/health` auth policy if it changes from the public trusted-LAN default.
- Final service restart control placement if advanced-only changes.
- Whether the Pi should later use Spotify Web API OAuth for richer account,
  active-device, or playback state. First version does not require it.

Fixture-only values in `pi-local-api-phase3-test-contract.md` are examples, not
final deployment defaults:

- `deviceId`: `pihouse-audio-01`
- `controllerInstanceId`: `ctrl_01JXYZPIHOUSEAUDIO`
- `hostname`: `pihouse`

## 13. Spotify Integration Boundary

First version should use Pi-reported Spotify readiness plus handoff to the
Spotify app. Spotify is represented through existing `/status`, `/health`, and
diagnostics fields, not new `/api/v1/spotify/*` resources.

The Pi remains responsible for Spotify Connect endpoint services, Bluetooth
routing, PipeWire sinks, watchdogs, and recovery. Android must not stream
Spotify audio, transfer active playback, call Spotify Web API playback-control
endpoints, or make routing decisions.

`GET /status` should include `spotifyEndpoints[]` and a `spotify` summary. The
following fields are safe for Android display because they are Pi-reported
status, not Spotify credentials or user account data:

| Field | Android use |
| --- | --- |
| `integrationMode` | Must be `connect_status_handoff`; show Spotify as a handoff/status integration, not an Android controller. |
| `connectOwnedBy` | Must be `pi`; keep Spotify Connect endpoint ownership on the Pi. |
| `accountState` | Display Pi-known account/session state: `unknown`, `available`, `logged_out`, `token_expired`, `upstream_unavailable`, or `not_configured`. Use `unknown` when the Pi only knows service readiness. |
| `accountStateDetail` | Optional app-safe detail such as `credentials_missing`; never display or log token material. |
| `activeDevice` | Display a bounded active-device summary when known: `category`, optional `endpointId`, optional `displayName`, and `isExpectedPiEndpoint`. Unknown active device state should not block the rest of the dashboard. |
| `playback` | Display bounded playback state only: `unknown`, `playing`, `paused`, `stopped`, `unavailable`, or `interrupted`, plus safe timestamps/reasons if present. Do not expose track, playlist, user, account, or queue data. |
| `routeReadiness[]` | Show one readiness row per configured Spotify endpoint, using endpoint, sink, and speaker state from the Pi. |
| `recommendedAction` | Use Pi-reported recommendation: `open_spotify`, `restart_endpoint_service`, `refresh_status`, `view_diagnostics`, or `none`. |

Phase 3 field support:

| Field or capability | Decision |
| --- | --- |
| `spotifyEndpoints[]`, `integrationMode`, `connectOwnedBy`, `routeReadiness[]`, and `recommendedAction` | v1 required. |
| `accountState` | v1 field, but may be `unknown` or `not_configured` unless the Pi has safe account/session evidence. |
| `activeDevice` | v1 field, but should be `category=unknown` unless the Pi has safe active-device evidence. Rich detection is deferred to optional Pi-side Web API support. |
| `playback` | v1 field, but should be `state=unknown` unless the Pi has safe bounded playback evidence. Rich validation is deferred to optional Pi-side Web API support. |
| Android Spotify OAuth, token refresh, playback transfer, queue/track controls | Out of scope for Phase 3. Android does not own Spotify credentials or playback-control calls. |
| `/api/v1/spotify/*` resources or Spotify-specific mutating operations | Deferred. Use existing status, diagnostics, and generic service/speaker/watchdog operations. |

Android's primary Spotify action is `Open Spotify`, using the Spotify app when
installed and `https://open.spotify.com/` as fallback. Spotify account/token
repair is not an Android-owned OAuth flow in Phase 3.

Spotify Web API OAuth is optional Pi-only future scope. If enabled later,
Spotify tokens stay on the Pi and must never appear in Android storage, API
responses, diagnostics, events, or logs.

Still pending product/setup decisions:

- Whether Pi deployments require a Spotify account setup step beyond existing
  `librespot`/Spotify Connect behavior.
- Whether the Pi will enable Spotify Web API OAuth for richer account,
  active-device, or playback state.
- Final Pi-side token provisioning, token refresh, and recovery instructions if
  Spotify Web API OAuth is enabled.

Troubleshooting mappings:

| State | Android behavior |
| --- | --- |
| `logged_out` or `token_expired` | Show Pi-reported account setup guidance and keep audio routing controls unchanged. |
| `upstream_unavailable` | Scope the issue to Spotify; keep the Pi dashboard usable. |
| `activeDevice.category=other_spotify_device` | Prompt the user to open Spotify and choose `Indoor`, `Outdoor`, or `Whole House`; do not transfer playback from Android. |
| `spotifyEndpoints[].componentState=degraded\|error` | Offer restart-service only in advanced diagnostics when authorized. |
| `routeReadiness[].ready=false` | Prefer Pi repair actions: reconnect speaker or run watchdog. |
| `playback.state=unavailable\|interrupted` | Refresh status and show diagnostics without assuming Bluetooth repair will fix Spotify. |

## 14. Verification Notes

Documentation-only verification for this update:

- Checked that Android flows map to the companion-app scope and do not imply
  Android audio streaming.
- Checked that Pi API behavior uses allowlisted ids and avoids raw command
  execution from request data.
- Preserved unresolved defaults instead of filling placeholder values.
- Updated `controllerInstanceId` language to match the SEC-01 through SEC-08
  cleanup: required, non-null, and part of saved identity comparison.
- Reconciled the older `/actions` planning names against the canonical
  `/operations` test contract.
- Added the Phase 3 Spotify boundary: status/deep-link handoff first, no
  Android-owned Spotify OAuth or playback/routing control.
