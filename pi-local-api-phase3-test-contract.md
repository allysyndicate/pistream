# Pi Local API Phase 3 Test Contract

This is the canonical API fixture contract for QA and Android Phase 3 work.
Routes are under:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

Canonical mutation routes use `/operations/*`. Older `/actions/*` names in
planning notes are not part of this contract.

## 1. HTTP And Auth Rules

All responses are JSON and include `ok` and `observedAt`.

Default auth policy until human approval:

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/identity` | none |
| `GET` | `/health` | configurable, default none |
| `GET` | `/status` | bearer required |
| `POST` | `/operations/reconnect` | bearer required |
| `POST` | `/operations/restart-service` | bearer required |
| `POST` | `/operations/run-watchdog` | bearer required |
| `GET` | `/operations/{operationId}` | bearer required |
| `GET` | `/diagnostics/summary` | bearer required |
| `GET` | `/diagnostics/events` | bearer required |
| `GET` | `/diagnostics/logs` | bearer required |

Bearer format:

```text
Authorization: Bearer <token>
```

Missing, malformed, or invalid bearer token:

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
Content-Type: application/json
```

```json
{
  "ok": false,
  "error": {
    "code": "unauthorized",
    "message": "Authorization is required.",
    "details": {
      "reason": "missing_or_invalid_bearer_token"
    }
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Authenticated but not permitted, for example service restart is configured as
advanced-only and this token does not allow advanced controls:

```http
HTTP/1.1 403 Forbidden
Content-Type: application/json
```

```json
{
  "ok": false,
  "error": {
    "code": "forbidden",
    "message": "This control is not permitted for the current authorization policy.",
    "details": {
      "requiredCapability": "advanced_controls"
    }
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

## 2. Security And Compatibility Decisions

These decisions close the SEC-01 through SEC-08 dependencies and are normative
for Android DTO fixtures, fake API tests, and QA regression cases.

| ID | Decision |
| --- | --- |
| `SEC-01` | Unauthorized is triggered by any request to a bearer-required endpoint when the `Authorization` header is missing, does not match `Bearer <token>`, contains an empty token, or contains a token that does not equal the Pi service token. There is no cookie, refresh token, login session, or token renewal endpoint in Phase 3. Android recovery UX: keep the saved host visible, clear/disable controls and diagnostics, show an authorization-required state, let the user re-enter or rescan the token, then retry `GET /status`. |
| `SEC-02` | Saved host identity alone is not enough to trust a Pi after app reinstall, Pi reinstall, hostname change, or IP change. Trust requires a fresh successful `GET /identity` response whose identity tuple matches the saved/paired identity tuple. Hostname/IP may change without becoming `wrong_device` if the identity tuple still matches. If the Android app has no saved identity after reinstall, it must treat the host as unpaired until identity is accepted and the user authorizes the token again. |
| `SEC-03` | The expected saved Pi audio controller identity tuple is `apiName`, `contractVersion`, `deviceId`, and `controllerInstanceId`, but compatibility is classified before saved-device matching. A host is `wrong_device` when `apiName != "pihouse-audio-api"`, `deviceId` differs from the saved device id for an already paired Pi, or `controllerInstanceId` differs from the saved controller instance id for an already paired Pi. Unsupported or missing `contractVersion` is `api_unavailable`, not `wrong_device`. `hostname` and IP address are display and reachability data, not trust anchors. |
| `SEC-04` | Phase 3 supports contract version `2026-06-phase3` only. Minimum compatible `apiVersion` is `1.0.0`; compatible implementation versions are `1.x.y` with the same `contractVersion`. `apiVersion` major versions other than `1`, missing `contractVersion`, or a different `contractVersion` are `api_unavailable` for Android compatibility handling. There is no multi-version negotiation endpoint in Phase 3. |
| `SEC-05` | Operation polling starts with `operation.pollAfterMs` or `1000` ms. Poll no faster than `500` ms and no slower than `5000` ms. For network timeout, connection refused, or HTTP `5xx` during polling, retry with exponential backoff of `1000`, `2000`, then `5000` ms plus small client jitter if available. Stop after a terminal operation state or `60000` ms total client wait, then refresh `/status`. Do not retry `401`, `403`, `404`, `409`, or `410` polling responses as transient failures. |
| `SEC-06` | Diagnostics may include allowlisted service ids, allowlisted display service names, sink ids, speaker ids, Pi hostname, `operationId`, `clientRequestId`, and `eventId`. Diagnostics must not include bearer tokens, speaker MAC addresses, raw Bluetooth addresses, Wi-Fi SSIDs/passwords, arbitrary hostnames from the LAN, environment variables, full shell commands, or unbounded raw journal output. |
| `SEC-07` | mDNS service discovery is not required for Phase 3. Android should use saved host, `audiopi.local:8765`, and manual host/IP entry. A future backend may add an advertised mDNS service, but Phase 3 fixtures and tests must not require it. |
| `SEC-08` | Background notifications are out of scope for Phase 3. Android must not request `POST_NOTIFICATIONS` for the current companion app behavior. Add it later only if the product adds background disconnect, watchdog, or long-running-operation alerts. |

## 3. Common Types

Timestamps are ISO-8601 UTC strings. Unknown optional values are `null`, not
omitted, unless the whole object is not applicable.

```ts
type SpeakerId = "indoor" | "outdoor";
type SinkId = "indoor" | "outdoor" | "whole_house";
type SpotifyEndpointId = "indoor" | "outdoor" | "both";
type ServiceId =
  | "pipewire"
  | "wireplumber"
  | "librespot_indoor"
  | "librespot_outdoor"
  | "librespot_both"
  | "bt_watchdog";
type LogUnitId = ServiceId;

type ComponentType =
  | "pipewire"
  | "wireplumber"
  | "speaker"
  | "sink"
  | "spotify_endpoint"
  | "watchdog"
  | "service";

type ComponentState =
  | "healthy"
  | "degraded"
  | "error"
  | "unknown"
  | "not_configured";

type SystemdActiveState =
  | "active"
  | "activating"
  | "inactive"
  | "failed"
  | "unknown";

type PiHealthState = "booting" | "healthy" | "degraded" | "error";
type PiRuntimePhase = "booting" | "starting_services" | "running" | "recovering";
type PiDiscoveryState =
  | "not_found"
  | "found_healthy"
  | "found_unhealthy"
  | "wrong_device"
  | "api_unavailable";

type OperationType = "reconnect" | "restart_service" | "run_watchdog";
type OperationStatus =
  | "queued"
  | "running"
  | "succeeded"
  | "failed"
  | "rejected"
  | "expired";

type SpotifyAccountState =
  | "unknown"
  | "available"
  | "logged_out"
  | "token_expired"
  | "upstream_unavailable"
  | "not_configured";

type SpotifyActiveDeviceCategory =
  | "pi_endpoint"
  | "other_spotify_device"
  | "none"
  | "unknown";

type SpotifyPlaybackState =
  | "unknown"
  | "playing"
  | "paused"
  | "stopped"
  | "unavailable"
  | "interrupted";

type SpotifyRecommendedAction =
  | "open_spotify"
  | "restart_endpoint_service"
  | "refresh_status"
  | "view_diagnostics"
  | "none";
```

Canonical health and error meanings:

| Value | Meaning |
| --- | --- |
| `not_found` | No host responded at the configured or discovered address. This is a client discovery state, not an API response. |
| `found_healthy` | Identity matches and health state is `healthy`. |
| `found_unhealthy` | Identity matches and health state is `booting`, `degraded`, or `error`. |
| `wrong_device` | Host responds but the product or saved controller identity is not acceptable for the saved Pi: `apiName`, `deviceId`, or `controllerInstanceId`. Unsupported or missing `contractVersion` is `api_unavailable`. |
| `api_unavailable` | Host is reachable but API is down, unauthorized, malformed, unsupported, timed out, or repeatedly returns 5xx. |
| `booting` | Pi API is up but the boot grace period or required service startup is still in progress. |
| `degraded` | Some audio path is impaired, but at least one expected playback path may still work. |
| `error` | Core audio control is unavailable, usually PipeWire/WirePlumber down or multiple critical dependencies failed. |
| `busy` | A conflicting operation is already `queued` or `running`. |
| `unauthorized` | Missing or invalid bearer token. |
| `stale_observation` | `observedAt` is older than the server freshness window. |
| `boot_changed` | `observedBootId` does not match the Pi's current boot id. |

Reason codes:

```ts
type ReasonCode =
  | "pipewire_down"
  | "wireplumber_down"
  | "indoor_speaker_disconnected"
  | "outdoor_speaker_disconnected"
  | "indoor_sink_missing"
  | "outdoor_sink_missing"
  | "whole_house_sink_missing"
  | "spotify_indoor_unhealthy"
  | "spotify_outdoor_unhealthy"
  | "spotify_both_unhealthy"
  | "spotify_logged_out"
  | "spotify_token_expired"
  | "spotify_upstream_unavailable"
  | "spotify_wrong_active_device"
  | "spotify_playback_unavailable"
  | "spotify_playback_interrupted"
  | "watchdog_inactive"
  | "watchdog_failed"
  | "operation_failed"
  | "command_timeout"
  | "dependency_unavailable";
```

Common error shape:

```json
{
  "ok": false,
  "error": {
    "code": "invalid_request",
    "message": "The request body is invalid.",
    "details": {
      "field": "speakerId",
      "allowedValues": ["indoor", "outdoor"]
    }
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

HTTP status mapping:

| HTTP | Error code |
| --- | --- |
| `400` | `invalid_request`, `unsupported_enum`, `invalid_observation` |
| `401` | `unauthorized` |
| `403` | `forbidden` |
| `404` | `operation_not_found` |
| `409` | `stale_observation`, `boot_changed`, `idempotency_conflict`, `busy` |
| `410` | `operation_expired` |
| `422` | `target_unavailable`, `speaker_connect_failed`, `service_restart_failed` |
| `500` | `internal_error` |
| `503` | `command_timeout`, `dependency_unavailable` |

## 4. Identity

`GET /identity`

Response `200`:

```json
{
  "ok": true,
  "deviceId": "pihouse-audio-01",
  "controllerInstanceId": "ctrl_01JXYZPIHOUSEAUDIO",
  "hostname": "pihouse",
  "apiName": "pihouse-audio-api",
  "apiVersion": "1.0.0",
  "contractVersion": "2026-06-phase3",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Field requirements:

| Field | Type | Notes |
| --- | --- | --- |
| `deviceId` | string | Configurable Pi audio device id. Stable for the configured audio controller. |
| `controllerInstanceId` | string | Stable generated id for this API/controller install. Changes on Pi API reinstall unless restored from backup. |
| `hostname` | string | Pi hostname at response time. Display and reachability only; not a trust anchor. |
| `apiName` | string | Must be `pihouse-audio-api` for Phase 3 fixtures. |
| `apiVersion` | semver string | Implementation version. Minimum compatible version is `1.0.0`; compatible major version is `1`. |
| `contractVersion` | string | Must be `2026-06-phase3`. |

Identity matching:

- `controllerInstanceId` is required, non-null, and never omitted from
  `/identity` or `/status.identity` in Phase 3.
- New pairing: Android may accept any non-empty `deviceId` and
  `controllerInstanceId` only after `apiName`, `contractVersion`, and
  `apiVersion` compatibility pass and the user provides valid authorization.
- Existing pairing: Android must compare `deviceId` and `controllerInstanceId`
  to the saved values.
- Hostname or IP changes do not make the host `wrong_device` when the identity
  tuple matches.
- Classification order is compatibility first, saved controller identity second:
  missing or unsupported `contractVersion`, unsupported `apiVersion`, or
  malformed compatibility fields map to `api_unavailable`; mismatched `apiName`,
  mismatched `deviceId`, or mismatched `controllerInstanceId` map to
  `wrong_device`.

## 5. Health

`GET /health`

Response `200`:

```json
{
  "ok": false,
  "state": "degraded",
  "phase": "running",
  "reboot": {
    "bootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
    "bootTime": "2026-06-12T07:35:00Z",
    "uptimeSeconds": 302
  },
  "reasons": [
    "outdoor_speaker_disconnected",
    "spotify_outdoor_unhealthy",
    "spotify_wrong_active_device"
  ],
  "summary": "Indoor playback is available. Outdoor speaker is disconnected.",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Rules:

- `ok` is true only when `state == "healthy"`.
- `reboot.bootId` is the stale-control token Android echoes as
  `observedBootId`.
- `reasons` is empty for `healthy`.
- `/health` auth remains configurable. QA should cover both public health and
  bearer-required health.

## 6. Status

`GET /status`

Response `200`:

```json
{
  "ok": true,
  "identity": {
    "deviceId": "pihouse-audio-01",
    "controllerInstanceId": "ctrl_01JXYZPIHOUSEAUDIO",
    "hostname": "pihouse",
    "apiName": "pihouse-audio-api",
    "apiVersion": "1.0.0",
    "contractVersion": "2026-06-phase3"
  },
  "health": {
    "state": "healthy",
    "phase": "running",
    "reasons": [],
    "summary": "All configured audio paths are available."
  },
  "reboot": {
    "bootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
    "bootTime": "2026-06-12T07:35:00Z",
    "uptimeSeconds": 302
  },
  "services": [
    {
      "serviceId": "pipewire",
      "displayName": "PipeWire",
      "unit": "pipewire",
      "activeState": "active",
      "componentState": "healthy",
      "reasonCodes": [],
      "restartAllowed": true,
      "lastChangedAt": "2026-06-12T07:35:07Z"
    },
    {
      "serviceId": "librespot_outdoor",
      "displayName": "Spotify Outdoor",
      "unit": "librespot@outdoor",
      "activeState": "active",
      "componentState": "healthy",
      "reasonCodes": [],
      "restartAllowed": true,
      "lastChangedAt": "2026-06-12T07:35:11Z"
    }
  ],
  "speakers": [
    {
      "speakerId": "indoor",
      "displayName": "Indoor",
      "paired": true,
      "trusted": true,
      "connected": true,
      "adapter": "hci0",
      "sinkId": "indoor",
      "componentState": "healthy",
      "reasonCodes": [],
      "lastConnectedAt": "2026-06-12T07:36:00Z",
      "lastError": null
    },
    {
      "speakerId": "outdoor",
      "displayName": "Outdoor",
      "paired": true,
      "trusted": true,
      "connected": false,
      "adapter": "hci0",
      "sinkId": "outdoor",
      "componentState": "degraded",
      "reasonCodes": ["outdoor_speaker_disconnected"],
      "lastConnectedAt": null,
      "lastError": "bluetoothctl reports not connected"
    }
  ],
  "sinks": [
    {
      "sinkId": "indoor",
      "displayName": "Indoor Sink",
      "name": "bluez_output.AA_BB_CC_DD_EE_FF.1",
      "present": true,
      "componentState": "healthy",
      "reasonCodes": []
    },
    {
      "sinkId": "whole_house",
      "displayName": "Whole House Sink",
      "name": "whole_house",
      "present": true,
      "componentState": "healthy",
      "reasonCodes": []
    }
  ],
  "spotifyEndpoints": [
    {
      "endpointId": "indoor",
      "displayName": "Indoor",
      "serviceId": "librespot_indoor",
      "sinkId": "indoor",
      "activeState": "active",
      "componentState": "healthy",
      "reasonCodes": []
    },
    {
      "endpointId": "both",
      "displayName": "Whole House",
      "serviceId": "librespot_both",
      "sinkId": "whole_house",
      "activeState": "active",
      "componentState": "healthy",
      "reasonCodes": []
    }
  ],
  "spotify": {
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
        "endpointId": "indoor",
        "ready": true,
        "reasonCodes": []
      },
      {
        "endpointId": "both",
        "ready": true,
        "reasonCodes": []
      }
    ],
    "recommendedAction": "open_spotify"
  },
  "watchdog": {
    "serviceId": "bt_watchdog",
    "timerActiveState": "active",
    "serviceActiveState": "inactive",
    "componentState": "healthy",
    "reasonCodes": [],
    "lastRunAt": "2026-06-12T07:38:00Z",
    "lastResult": "succeeded"
  },
  "controls": {
    "freshnessWindowSeconds": 120,
    "restartServiceMode": "advanced",
    "supportedOperations": [
      "reconnect",
      "restart_service",
      "run_watchdog"
    ]
  },
  "operations": {
    "active": null,
    "recent": [
      {
        "operationId": "op_01JXYZ1234567890",
        "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
        "type": "reconnect",
        "status": "succeeded",
        "target": {
          "speakerId": "outdoor"
        },
        "requestedAt": "2026-06-12T07:39:00Z",
        "startedAt": "2026-06-12T07:39:01Z",
        "finishedAt": "2026-06-12T07:39:09Z",
        "expiresAt": "2026-06-19T07:39:09Z",
        "result": {
          "connected": true
        },
        "error": null
      }
    ]
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Array completeness:

- `services` includes all configured `ServiceId` values.
- `speakers` includes all configured `SpeakerId` values.
- `sinks` includes all configured `SinkId` values.
- `spotifyEndpoints` includes all configured `SpotifyEndpointId` values.
- `spotify.routeReadiness` includes all configured `SpotifyEndpointId` values.
- `observedAt` plus `reboot.bootId` are required for all mutating requests.

## 7. Spotify Integration Boundary

First version recommendation: Spotify is an extension of current
`/status`, `/health`, and diagnostics, not a new family of `/api/v1/spotify/*`
resources. Android remains a companion/control surface. The Pi continues to own
Spotify Connect endpoint processes, Bluetooth routing, watchdogs, and recovery.

Android's primary Spotify action is `open_spotify`, which opens the Spotify app
or `https://open.spotify.com/` so the user can select `Indoor`, `Outdoor`, or
`Whole House` in Spotify's device picker. Android must not stream Spotify audio,
transfer active Spotify playback, call Spotify Web API playback-control
endpoints, or make Bluetooth/PipeWire routing decisions in Phase 3.

Spotify Web API OAuth is optional backend-only future scope. If enabled later,
Spotify refresh/access tokens must be stored on the Pi in service-user-readable
state, never in Android and never in API responses, diagnostics, or logs. Phase
3 has no Android Spotify OAuth, no client secret in the APK, no Spotify token
relay through the Pi local API, and no Pi endpoint for token exchange.

Status object requirements:

| Field | Type | Notes |
| --- | --- | --- |
| `integrationMode` | string | Must be `connect_status_handoff` for Phase 3. |
| `connectOwnedBy` | string | Must be `pi`. |
| `accountState` | `SpotifyAccountState` | Safe account/session state if known by the Pi. Use `unknown` when the Pi only knows service state. |
| `accountStateDetail` | string or null | App-safe detail such as `credentials_missing`; never token material. |
| `activeDevice` | object | Current Spotify active device summary if safely known; otherwise `category=unknown`. |
| `playback` | object | Bounded playback summary only. Do not expose track, user, playlist, or account data in Phase 3 fixtures. |
| `routeReadiness` | array | Per configured endpoint readiness derived from endpoint, sink, and speaker state. |
| `recommendedAction` | `SpotifyRecommendedAction` | Usually `open_spotify`, `restart_endpoint_service`, `refresh_status`, `view_diagnostics`, or `none`. |

Spotify field support:

| Field or capability | Phase 3 decision |
| --- | --- |
| `spotifyEndpoints[]` | v1 required; reports every configured `indoor`, `outdoor`, and `both` endpoint from local service/sink readiness. |
| `spotify.integrationMode` and `connectOwnedBy` | v1 required; values are `connect_status_handoff` and `pi`. |
| `spotify.routeReadiness[]` | v1 required; one row per configured endpoint, derived from endpoint, sink, and speaker state. |
| `spotify.recommendedAction` | v1 required; use `open_spotify`, `refresh_status`, `view_diagnostics`, `restart_endpoint_service`, or `none`. |
| `spotify.accountState` | v1 field; return `unknown` or `not_configured` unless the Pi has safe account/session evidence. Pi-side Web API may later return `available`, `logged_out`, `token_expired`, or `upstream_unavailable`. |
| `spotify.activeDevice` | v1 field with `category=unknown` unless the Pi has safe active-device evidence. Rich active-device detection is deferred to optional Pi-side Web API support. |
| `spotify.playback` | v1 field with `state=unknown` unless the Pi has safe bounded playback evidence. Rich playback validation is deferred to optional Pi-side Web API support. |
| Android Spotify OAuth, token refresh, playback transfer, queue/track controls | Out of Phase 3; no Android-owned Spotify credentials or playback-control calls. |
| New `/api/v1/spotify/*` resources or Spotify-specific mutating operations | Deferred; Phase 3 uses existing status, diagnostics, and generic service/speaker/watchdog operations only. |

Spotify-specific state mapping:

| Condition | Contract fields |
| --- | --- |
| Logged out or credentials missing | `spotify.accountState=logged_out`, health reason `spotify_logged_out`, diagnostics category `spotify`. |
| Token expired or revoked, if Pi uses Spotify Web API | `spotify.accountState=token_expired`, reason `spotify_token_expired`; no token values exposed. |
| Spotify upstream/API unavailable | `spotify.accountState=upstream_unavailable`, reason `spotify_upstream_unavailable`; Pi local API remains usable. |
| Librespot service inactive/failed | matching `spotifyEndpoints[].componentState=degraded|error`, matching `spotify_*_unhealthy`; repair is existing `restart_service`. |
| Active playback on a non-Pi Spotify device | `spotify.activeDevice.category=other_spotify_device`, `isExpectedPiEndpoint=false`, reason `spotify_wrong_active_device`, recommended action `open_spotify`. |
| Playback unavailable/interrupted | `spotify.playback.state=unavailable|interrupted`, reason `spotify_playback_unavailable` or `spotify_playback_interrupted`; distinguish from user pause when possible. |
| Speaker or sink route not ready | `spotify.routeReadiness[].ready=false` with existing speaker/sink reason codes; repair uses `reconnect` or `run_watchdog`, not Spotify commands. |

Android sends no Spotify-specific mutating command in Phase 3. Spotify repair
uses existing operations only:

- `POST /operations/restart-service` for allowlisted `librespot_*` services.
- `POST /operations/reconnect` for affected configured speakers.
- `POST /operations/run-watchdog` when route recovery is needed.

The same stale-control, busy, and idempotency rules apply to Spotify-related
repairs. A Spotify endpoint restart must not be treated as a Bluetooth route
repair; Android must refresh `/status` after any terminal operation.

## 8. Mutating Operation Requests

Every mutating request must include:

```json
{
  "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
  "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Server-side stale-control validation is required. Android should also avoid
submitting obviously stale controls, but the server is authoritative.

Stale observation response:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "ok": false,
  "error": {
    "code": "stale_observation",
    "message": "Refresh Pi status before sending this control.",
    "details": {
      "maxAgeSeconds": 120,
      "observedAt": "2026-06-12T07:35:00Z",
      "serverObservedAt": "2026-06-12T07:40:00Z"
    }
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Boot changed response:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "ok": false,
  "error": {
    "code": "boot_changed",
    "message": "The Raspberry Pi rebooted. Refresh status before retrying.",
    "details": {
      "requestBootId": "old-boot-id",
      "currentBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1"
    }
  },
  "observedAt": "2026-06-12T07:40:00Z"
}
```

### Reconnect

`POST /operations/reconnect`

Request:

```json
{
  "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
  "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
  "observedAt": "2026-06-12T07:40:00Z",
  "speakerId": "outdoor"
}
```

Response `202` when queued/running, or `200` when idempotently returning an
existing terminal operation:

```json
{
  "ok": true,
  "operation": {
    "operationId": "op_01JXYZ1234567890",
    "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
    "type": "reconnect",
    "status": "queued",
    "target": {
      "speakerId": "outdoor"
    },
    "requestedAt": "2026-06-12T07:40:01Z",
    "startedAt": null,
    "finishedAt": null,
    "expiresAt": "2026-06-19T07:40:01Z",
    "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
    "pollAfterMs": 1000,
    "result": null,
    "error": null
  },
  "observedAt": "2026-06-12T07:40:01Z"
}
```

Terminal success result:

```json
{
  "connected": true,
  "speakerId": "outdoor",
  "bluetoothInfo": {
    "paired": true,
    "trusted": true,
    "connected": true
  }
}
```

### Restart Service

`POST /operations/restart-service`

Request:

```json
{
  "clientRequestId": "9f805af0-f290-4f66-a278-c96f76b5102c",
  "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
  "observedAt": "2026-06-12T07:40:00Z",
  "serviceId": "librespot_outdoor"
}
```

Target `serviceId` must be one of `ServiceId`.

Terminal success result:

```json
{
  "serviceId": "librespot_outdoor",
  "activeState": "active",
  "restartIssued": true
}
```

### Run Watchdog

`POST /operations/run-watchdog`

Request:

```json
{
  "clientRequestId": "6d3ac7f9-dfb8-45e3-b7e3-78c936e9b715",
  "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Terminal success result:

```json
{
  "serviceId": "bt_watchdog",
  "started": true,
  "speakers": [
    {
      "speakerId": "indoor",
      "connected": true
    },
    {
      "speakerId": "outdoor",
      "connected": true
    }
  ]
}
```

## 9. Operation Polling And Lifecycle

`GET /operations/{operationId}`

Response `200`:

```json
{
  "ok": true,
  "operation": {
    "operationId": "op_01JXYZ1234567890",
    "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
    "type": "reconnect",
    "status": "running",
    "target": {
      "speakerId": "outdoor"
    },
    "requestedAt": "2026-06-12T07:40:01Z",
    "startedAt": "2026-06-12T07:40:02Z",
    "finishedAt": null,
    "expiresAt": "2026-06-19T07:40:01Z",
    "observedBootId": "8f6d2f2c-8b8d-4a7d-9508-4c8778a23ad1",
    "pollAfterMs": 1000,
    "result": null,
    "error": null
  },
  "observedAt": "2026-06-12T07:40:03Z"
}
```

Lifecycle:

| State | Meaning |
| --- | --- |
| `queued` | Accepted and persisted, not yet executing. |
| `running` | Command execution or verification is in progress. |
| `succeeded` | Terminal success. `finishedAt` and `result` are set. |
| `failed` | Terminal execution failure. `finishedAt` and `error` are set. |
| `rejected` | Terminal request rejection persisted for idempotency, such as `target_unavailable`. |
| `expired` | Operation record existed but passed retention. Polling returns `410 operation_expired`. |

Polling cadence:

- Use `operation.pollAfterMs` when present.
- Default is `1000`.
- Minimum client poll interval is `500`.
- Maximum client poll interval is `5000`.
- On network timeout, connection refused, or HTTP `5xx`, retry polling with
  backoff delays of `1000`, `2000`, then `5000` ms until the total client wait
  reaches 60 seconds.
- Do not retry `401`, `403`, `404`, `409`, or `410` polling responses as
  transient failures. Handle them through the matching error state.
- Stop polling after terminal state or after 60 seconds of client-side waiting;
  then refresh `/status`.

Retention and expiry defaults:

- Retain at least the newest 100 operations.
- Retain terminal operations for 7 days by default.
- `expiresAt` is required on every operation.
- After expiry, `GET /operations/{operationId}` returns `410`.

Expired response:

```json
{
  "ok": false,
  "error": {
    "code": "operation_expired",
    "message": "This operation is no longer available.",
    "details": {
      "operationId": "op_01JXYZ1234567890"
    }
  },
  "observedAt": "2026-06-19T07:40:02Z"
}
```

Idempotency:

- `clientRequestId` must be a UUID string.
- The server indexes operations by `clientRequestId`.
- Same `clientRequestId`, same operation type, same target: return the original
  operation without starting another command.
- Same `clientRequestId`, different operation type or target: return `409
  idempotency_conflict`.

Conflict response:

```json
{
  "ok": false,
  "error": {
    "code": "idempotency_conflict",
    "message": "This clientRequestId was already used for a different operation.",
    "details": {
      "clientRequestId": "8c978a62-33df-4d7b-bc3a-a1340c9058fd",
      "existingOperationId": "op_01JXYZ1234567890"
    }
  },
  "observedAt": "2026-06-12T07:40:03Z"
}
```

Busy response for conflicting active operation:

```json
{
  "ok": false,
  "error": {
    "code": "busy",
    "message": "A conflicting operation is already running.",
    "details": {
      "operationId": "op_01JXYZ1234567890",
      "status": "running",
      "retryAfterMs": 1000
    }
  },
  "observedAt": "2026-06-12T07:40:03Z"
}
```

## 10. Diagnostics

### Summary

`GET /diagnostics/summary`

Response `200`:

```json
{
  "ok": true,
  "groups": [
    {
      "category": "bluetooth",
      "state": "degraded",
      "reasonCodes": ["outdoor_speaker_disconnected"],
      "userMessage": "Outdoor speaker is disconnected.",
      "recommendedAction": "reconnect",
      "androidCanTriggerAction": true,
      "relatedOperation": null,
      "observedAt": "2026-06-12T07:40:00Z"
    },
    {
      "category": "spotify",
      "state": "degraded",
      "reasonCodes": ["spotify_wrong_active_device"],
      "userMessage": "Spotify is playing on another device.",
      "recommendedAction": "open_spotify",
      "androidCanTriggerAction": false,
      "relatedOperation": null,
      "observedAt": "2026-06-12T07:40:00Z"
    }
  ],
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Diagnostics categories:

```ts
type DiagnosticsCategory =
  | "network"
  | "pipewire"
  | "bluetooth"
  | "spotify"
  | "watchdog"
  | "whole_house";
```

### Events

`GET /diagnostics/events?limit=50&category=bluetooth`

Query parameters:

| Name | Type | Default | Limits |
| --- | --- | --- | --- |
| `limit` | integer | `50` | `1..100`, clamped to server max |
| `category` | diagnostics category | none | optional |

Response `200`:

```json
{
  "ok": true,
  "events": [
    {
      "eventId": "evt_01JXYZ9876543210",
      "timestamp": "2026-06-12T07:39:09Z",
      "category": "spotify",
      "severity": "warning",
      "reasonCode": "spotify_wrong_active_device",
      "message": "Spotify active device is not a configured Pi endpoint.",
      "operationId": "op_01JXYZ1234567890",
      "component": {
        "type": "spotify_endpoint",
        "id": "both"
      }
    }
  ],
  "observedAt": "2026-06-12T07:40:00Z"
}
```

```ts
type EventSeverity = "debug" | "info" | "warning" | "error";
```

### Logs

`GET /diagnostics/logs?unitId=librespot_outdoor&lines=50`

Query parameters:

| Name | Type | Default | Limits |
| --- | --- | --- | --- |
| `unitId` | `LogUnitId` | required | allowlisted only |
| `lines` | integer | `50` | `1..200` by default, clamped to configured server max |

Response `200`:

```json
{
  "ok": true,
  "unitId": "librespot_outdoor",
  "unit": "librespot@outdoor",
  "linesRequested": 50,
  "linesReturned": 2,
  "entries": [
    {
      "timestamp": "2026-06-12T07:38:00Z",
      "priority": "6",
      "message": "librespot started",
      "cursor": "s=abc;i=123;b=boot"
    },
    {
      "timestamp": "2026-06-12T07:39:00Z",
      "priority": "4",
      "message": "sink unavailable, retrying",
      "cursor": "s=abc;i=124;b=boot"
    }
  ],
  "observedAt": "2026-06-12T07:40:00Z"
}
```

Invalid `unitId` returns `400 unsupported_enum`; raw journal unit names and
extra journalctl flags are never accepted.

Diagnostics privacy:

- Allowed: Pi hostname, allowlisted `ServiceId`, allowlisted display service
  names, `SinkId`, `SpeakerId`, `SpotifyEndpointId`, `operationId`,
  `clientRequestId`, and `eventId`.
- Forbidden: bearer tokens, speaker MAC addresses, raw Bluetooth addresses,
  Wi-Fi SSIDs/passwords, arbitrary LAN hostnames, environment variables, full
  shell commands, unbounded journal output, or raw command stderr containing
  secrets.

## 11. Configurable Defaults Pending Human Approval

These values must remain config-driven and should not be hard-coded in Android
or QA fixtures except as test fixture examples:

| Setting | Phase 3 provisional default |
| --- | --- |
| `/health` auth policy | public on trusted LAN |
| Transport/security | LAN HTTP plus bearer token |
| Future stronger transport | HTTPS or mTLS, not selected yet |
| Final `deviceId` | `pihouse-audio-01` fixture only |
| Freshness window | `120` seconds |
| Log query cap | `200` lines |
| Diagnostics events cap | `100` events |
| Operation retention | 100 newest or 7 days |
| Service restart visibility | advanced-only |
| mDNS discovery | future scope, not required for Phase 3 |
| Background notifications | future scope, no Phase 3 `POST_NOTIFICATIONS` |
| API bind behavior | configurable LAN interface or `0.0.0.0` with firewall |
| Speaker MACs | configured on Pi only, never supplied by Android |
| Sink names | configured on Pi only, never supplied by Android |
| Spotify Web API OAuth | not used by Android in Phase 3; Pi-only future option |
| Spotify playback controls | out of scope for Phase 3 |

## 12. Required QA Fixture Set

QA should create fixtures for:

- Healthy identity, health, and status.
- Wrong identity with valid JSON.
- Reinstall/new pairing with no saved identity, requiring fresh identity plus
  authorization before trust.
- Saved host IP/hostname change with matching identity tuple.
- Saved host with mismatched `deviceId`.
- Saved host with mismatched `controllerInstanceId`.
- Unsupported `contractVersion` and unsupported `apiVersion` major version.
- Public `/health` and bearer-required `/health`.
- Degraded outdoor speaker.
- Booting Pi.
- PipeWire down error.
- Unauthorized `401` for missing header, malformed header, empty token, and
  invalid token; forbidden `403`.
- Reconnect accepted, running, succeeded, failed, and busy.
- Restart-service forbidden in non-advanced mode.
- Run-watchdog succeeded and failed.
- `stale_observation`, `boot_changed`, and `idempotency_conflict`.
- Operation polling `404 operation_not_found` and `410 operation_expired`.
- Operation polling transient `5xx`/timeout backoff and 60-second total timeout.
- Diagnostics summary degraded Bluetooth.
- Spotify logged out.
- Spotify token expired or revoked, if Pi Web API credentials are enabled in a
  fixture.
- Spotify upstream/API unavailable.
- Wrong active Spotify device.
- Spotify playback unavailable/interrupted.
- Spotify route not ready because a speaker or sink is degraded.
- Spotify service-unavailable fixture for each `librespot_*` endpoint.
- Diagnostics events with clamped limit.
- Diagnostics logs with valid allowlisted unit and rejected invalid unit.
- Diagnostics privacy redaction for forbidden token, MAC address, SSID, and raw
  command content.
- Discovery without mDNS support.
- No background notification permission in Phase 3.
