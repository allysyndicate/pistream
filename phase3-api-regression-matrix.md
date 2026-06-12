# Phase 3 API Regression Matrix

Canonical source: `pi-local-api-phase3-test-contract.md`

Base URL for every route below:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

## Mockable Contract Tests

These cases should run against fake API fixtures without Raspberry Pi, Bluetooth, speaker, Spotify, or systemd dependencies.

| ID | Area | Route | Fixture/input | Expected result |
| --- | --- | --- | --- | --- |
| API-001 | Base URL | all | Saved host, `audiopi.local:8765`, manual host/IP | Client calls `/api/v1` routes on port `8765`; no Phase 3 tests require mDNS discovery. |
| API-002 | Legacy route rejection | `/actions/*` | Any former planning route | Android must not call `/actions/*`; canonical mutations use `/operations/*`. |
| AUTH-001 | Public identity | `GET /identity` | No `Authorization` header | `200`, JSON body has `ok`, `deviceId`, non-null `controllerInstanceId`, `hostname`, `apiName`, `apiVersion`, `contractVersion`, `observedAt`. |
| AUTH-002 | Bearer-required status | `GET /status` | Missing header | `401`, `WWW-Authenticate: Bearer`, error `code=unauthorized`, `details.reason=missing_or_invalid_bearer_token`. |
| AUTH-003 | Bearer format | bearer-required routes | Malformed header, empty bearer token, invalid token | Same `401 unauthorized` shape as AUTH-002. |
| AUTH-004 | Forbidden capability | `POST /operations/restart-service` | Valid token without advanced controls | `403`, error `code=forbidden`, `details.requiredCapability=advanced_controls`; no operation is started. |
| AUTH-005 | Health auth configuration | `GET /health` | Public-health fixture and bearer-required-health fixture | Client handles both default public health and configured `401` health without treating the contract as inconsistent. |
| ID-001 | Identity acceptance | `GET /identity` | `apiName=pihouse-audio-api`, `contractVersion=2026-06-phase3`, compatible `apiVersion=1.x.y` | Host can be accepted after user authorization. |
| ID-002 | Wrong device | `GET /identity` | Wrong `apiName`, changed saved `deviceId`, changed saved `controllerInstanceId`, or missing/null `controllerInstanceId` for an already paired Pi | Client maps to `wrong_device`; controls and diagnostics are not trusted. |
| ID-003 | Unsupported API | `GET /identity` | Missing/different `contractVersion` or `apiVersion` major not `1` | Client maps to `api_unavailable`, not `wrong_device`; saved identity comparison is not trusted until compatibility passes. |
| HEALTH-001 | Healthy health | `GET /health` | `state=healthy`, `reasons=[]` | `ok=true`; client maps matched identity plus healthy state to `found_healthy`. |
| HEALTH-002 | Booting/degraded/error health | `GET /health` | `state=booting`, `degraded`, or `error` | `ok=false`; client maps matched identity to `found_unhealthy` and displays `summary` plus reason codes. |
| HEALTH-003 | Stale-control token | `GET /health` | Valid `reboot.bootId` | Client stores `reboot.bootId` for `observedBootId` in mutating requests. |
| STATUS-001 | Status schema | `GET /status` | Healthy full status fixture | Response maps `identity`, `health`, `reboot`, `services`, `speakers`, `sinks`, `spotifyEndpoints`, `spotify`, `watchdog`, `controls`, `operations`, `observedAt`. |
| STATUS-002 | Array completeness | `GET /status` | All configured values present | Fixtures include all configured `ServiceId`, `SpeakerId`, `SinkId`, and `SpotifyEndpointId` values. |
| STATUS-003 | Nullable optional values | `GET /status` | Unknown values represented as `null` | Client accepts `null` for unknown optional values, not missing fields where the contract requires the field. |
| STATUS-004 | Config-driven controls | `GET /status` | `freshnessWindowSeconds`, `restartServiceMode`, `supportedOperations` vary | Client uses server values for UI/control availability; does not hard-code `120` seconds or advanced restart visibility except in fixtures. |
| SPOT-001 | Spotify logged out | `GET /status`, `GET /health`, `GET /diagnostics/summary` | `spotify.accountState=logged_out`, health/diagnostics reason `spotify_logged_out` | Client scopes the issue to Spotify account/session readiness, does not start Android OAuth, and keeps Pi dashboard usable. |
| SPOT-002 | Spotify token expired/revoked | `GET /status`, `GET /diagnostics/events?category=spotify` | `spotify.accountState=token_expired`, reason `spotify_token_expired`, no token values exposed | Client shows Spotify account repair guidance if supported, offers safe handoff/diagnostics, and avoids logging token material. |
| SPOT-003 | Pi Spotify service unavailable | `GET /status`, `GET /health` | `librespot_indoor`, `librespot_outdoor`, or `librespot_both` service `activeState=failed|inactive`, endpoint `componentState=degraded|error` | Health reason includes the matching `spotify_*_unhealthy`; endpoint row is degraded; restart-service is available only when `controls.restartServiceMode` and authorization allow it. |
| SPOT-004 | Spotify API unavailable | `GET /status`, `GET /diagnostics/summary`, `GET /diagnostics/events?category=spotify` | `spotify.accountState=upstream_unavailable`, reason `spotify_upstream_unavailable`, Pi local API otherwise healthy | Client keeps Pi dashboard usable and scopes the error to Spotify, not overall Pi discovery. |
| SPOT-005 | Wrong active Spotify device | `GET /status` | `spotify.activeDevice.category=other_spotify_device`, `isExpectedPiEndpoint=false`, reason `spotify_wrong_active_device` | Client prompts user to choose `Indoor`, `Outdoor`, or `Whole House` in Spotify rather than run Pi repair. |
| SPOT-006 | Playback interrupted | `GET /status`, `GET /diagnostics/events?category=spotify` | `spotify.playback.state=interrupted|unavailable`, reason `spotify_playback_interrupted|spotify_playback_unavailable` | Client distinguishes service failure, route failure, and user-paused playback where backend state allows it. |
| SPOT-007 | Stale Spotify repair observation | `POST /operations/restart-service` | Restart `serviceId=librespot_outdoor` with stale `observedAt` or old `observedBootId` | Same stale-control behavior as other mutations: `409 stale_observation` or `409 boot_changed`; no librespot restart occurs; client refreshes `/status` before allowing retry. |
| SPOT-008 | Spotify repair idempotency | `POST /operations/restart-service` | Same `clientRequestId`, same `serviceId=librespot_*`, repeated after timeout/app retry | Original operation is returned and no duplicate service restart is started. A reused `clientRequestId` with a different `serviceId` returns `409 idempotency_conflict`. |
| SPOT-009 | Spotify repair busy conflict | `POST /operations/restart-service` | Start restart for one `librespot_*` service, then submit conflicting restart/reconnect/watchdog per server rules | `409 busy` includes active `operationId`, `status`, and `retryAfterMs`; client shows active operation and does not stack repair commands. |
| SPOT-010 | Bluetooth route after Spotify repair | `GET /operations/{operationId}`, then `GET /status` | Terminal `restart_service` success for `librespot_both` followed by degraded speaker/sink fixture | Client does not assume Spotify repair fixed Bluetooth routing; it refreshes status and offers `reconnect` or `run_watchdog` for remaining speaker/sink degradation. |
| STALE-001 | Required mutation fields | all `POST /operations/*` | Omit `clientRequestId`, `observedBootId`, or `observedAt` | `400 invalid_request` or `invalid_observation` with common error shape. |
| STALE-002 | Stale observation | all `POST /operations/*` | `observedAt` older than freshness window | `409 stale_observation`, details include `maxAgeSeconds`, request `observedAt`, and `serverObservedAt`; Android refreshes `/status` before retry. |
| STALE-003 | Boot changed | all `POST /operations/*` | Old `observedBootId` | `409 boot_changed`, details include `requestBootId` and `currentBootId`; Android refreshes `/status` before retry. |
| OP-001 | Reconnect accepted | `POST /operations/reconnect` | Valid UUID `clientRequestId`, fresh observation, `speakerId=indoor|outdoor` | `202` with `operation.type=reconnect`, `status=queued|running`, target `speakerId`, `expiresAt`, `observedBootId`, `pollAfterMs`. |
| OP-002 | Reconnect invalid target | `POST /operations/reconnect` | Unsupported `speakerId` | `400 unsupported_enum` or `invalid_request` with allowed values. |
| OP-003 | Restart service accepted | `POST /operations/restart-service` | Valid `serviceId` from `ServiceId` and advanced authorization | `202` with `operation.type=restart_service` and target service. |
| OP-004 | Restart service invalid target | `POST /operations/restart-service` | Unsupported `serviceId` | `400 unsupported_enum`; raw systemd unit names are not accepted as targets. |
| OP-005 | Run watchdog accepted | `POST /operations/run-watchdog` | Fresh observation, valid auth | `202` with `operation.type=run_watchdog`; no speaker MACs or sink names supplied by Android. |
| OP-006 | Operation polling | `GET /operations/{operationId}` | `queued` then `running` then terminal fixture | Client honors `pollAfterMs`, clamps to `500..5000` ms, stops at terminal state, then refreshes `/status`. |
| OP-007 | Polling transient failures | `GET /operations/{operationId}` | Network timeout, connection refused, or `5xx` while polling | Client retries with `1000`, `2000`, then `5000` ms backoff plus jitter if available until terminal state or 60 seconds. |
| OP-008 | Polling non-transient failures | `GET /operations/{operationId}` | `401`, `403`, `404`, `409`, or `410` while polling | Client does not retry as transient; handles auth, conflict, not-found, or expired state and refreshes `/status` where appropriate. |
| OP-009 | Terminal success schemas | `GET /operations/{operationId}` | `succeeded` reconnect, restart-service, run-watchdog fixtures | `finishedAt` and `result` are set; `error=null`; result matches operation-specific schema. |
| OP-010 | Terminal failure schemas | `GET /operations/{operationId}` | `failed` and `rejected` fixtures | `finishedAt` and `error` are set; UI shows failure without assuming the command is still running. |
| OP-011 | Operation expiry | `GET /operations/{operationId}` | Expired retained operation | `410 operation_expired` with `details.operationId`; client stops polling and refreshes `/status`. |
| OP-012 | Missing operation | `GET /operations/{operationId}` | Unknown operation ID | `404 operation_not_found`; client stops polling and refreshes `/status`. |
| IDEMP-001 | Duplicate same request | `POST /operations/*` | Same `clientRequestId`, same type, same target | Server returns original operation; no duplicate command starts; `200` is valid for existing terminal operation. |
| IDEMP-002 | Duplicate conflicting request | `POST /operations/*` | Same `clientRequestId`, different type or target | `409 idempotency_conflict`, details include `clientRequestId` and `existingOperationId`. |
| IDEMP-003 | Active operation conflict | `POST /operations/*` | Conflicting operation already `queued` or `running` | `409 busy`, details include active `operationId`, `status`, and `retryAfterMs`; client disables conflicting controls or prompts retry later. |
| DIAG-001 | Diagnostics summary | `GET /diagnostics/summary` | Degraded Bluetooth fixture | `200`, groups contain `category`, `state`, `reasonCodes`, `userMessage`, `recommendedAction`, `androidCanTriggerAction`, `relatedOperation`, `observedAt`. |
| DIAG-002 | Diagnostics events | `GET /diagnostics/events?limit=50&category=bluetooth` | Events fixture | `200`, events include `eventId`, `timestamp`, `category`, `severity`, `reasonCode`, `message`, optional `operationId`, `component`. |
| DIAG-003 | Events query limits | `GET /diagnostics/events` | `limit` below 1, above 100, invalid `category` | Limits are clamped to server max where valid; invalid category returns `400 unsupported_enum`. |
| DIAG-004 | Diagnostics logs | `GET /diagnostics/logs?unitId=librespot_outdoor&lines=50` | Valid allowlisted `unitId` | `200`, response includes `unitId`, display `unit`, requested/returned counts, entries with `timestamp`, `priority`, `message`, `cursor`, `observedAt`. |
| DIAG-005 | Logs allowlist | `GET /diagnostics/logs` | Raw journal unit name, shell flags, unsupported `unitId` | `400 unsupported_enum`; no arbitrary journalctl flags are accepted. |
| DIAG-006 | Log line limits | `GET /diagnostics/logs` | `lines` below 1 or above configured cap | Valid requests clamp to configured max; default cap fixture is `200`. |
| SEC-001 | Diagnostics redaction | all diagnostics routes | Fixtures containing allowed IDs only | Responses never include bearer tokens, speaker MAC/raw Bluetooth addresses, SSIDs/passwords, arbitrary LAN hostnames, env vars, full shell commands, or unbounded journal output. |
| SEC-002 | Android notification scope | app permission behavior | Phase 3 app install | App does not request `POST_NOTIFICATIONS`; background alerts are out of scope. |

## Hardware/Pi/Speaker-Required Tests

These require a real Raspberry Pi service plus configured audio stack, Bluetooth speakers, and local network.

| ID | Area | Route/flow | Setup | Expected result |
| --- | --- | --- | --- | --- |
| HW-001 | Real identity | `GET /identity` | Pi service running on `:8765` | Real response matches `apiName=pihouse-audio-api`, `contractVersion=2026-06-phase3`, compatible `apiVersion=1.x.y`, configured `deviceId`, and non-null configured `controllerInstanceId`. |
| HW-002 | Real health | `GET /health` | Pi freshly booted, then stable | Booting state transitions to running healthy/degraded/error according to actual service state. |
| HW-003 | Real status completeness | `GET /status` | Pi configured with indoor/outdoor speakers and Spotify endpoints | Status includes every configured service, speaker, sink, Spotify endpoint, watchdog, active operation, and recent operation expected by the contract. |
| HW-004 | Reconnect speaker | `POST /operations/reconnect` then poll | Disconnect one trusted speaker | Operation reaches `succeeded` with `connected=true`, or terminal `failed/rejected` with a meaningful error if hardware cannot reconnect. |
| HW-005 | Restart service | `POST /operations/restart-service` then poll | Advanced token and one allowlisted service | Operation restarts the allowlisted service only and returns terminal result with final `activeState`. |
| HW-006 | Run watchdog | `POST /operations/run-watchdog` then poll | Watchdog service installed | Operation starts watchdog and reports each configured speaker connection result. |
| HW-007 | Busy protection | two conflicting operations | Start reconnect/watchdog and submit conflicting control while active | Second request returns `409 busy`; first operation continues without duplicate side effects. |
| HW-008 | Boot-change protection | mutation after Pi reboot | Capture status, reboot Pi, submit mutation with old `observedBootId` | `409 boot_changed`; no stale command executes. |
| HW-009 | Freshness protection | mutation after stale window | Wait beyond configured freshness window before submitting control | `409 stale_observation`; no stale command executes. |
| HW-010 | Diagnostics real summary | `GET /diagnostics/summary` | Force outdoor speaker disconnect or service degradation | Summary category, reason codes, recommendation, and action availability match observed degradation. |
| HW-011 | Diagnostics real events | `GET /diagnostics/events` | Perform reconnect/restart/watchdog operation | Events include operation-linked entries with allowed component IDs and no sensitive data. |
| HW-012 | Diagnostics real logs | `GET /diagnostics/logs` | Query allowlisted unit | Logs return bounded entries for that unit only and reject unsupported/raw units. |
| HW-013 | Network/API unavailable | client discovery flow | Pi powered off, service stopped, malformed fake host, wrong token | Client distinguishes `not_found`, `api_unavailable`, `wrong_device`, and authorization-required states according to contract behavior. |
| SPOTHW-001 | Spotify account logged out | Spotify setup/status flow | Real Pi with Spotify credentials removed or invalidated | Pi reports `spotify.accountState=logged_out` or an equivalent safe Pi-known account/session state; no bearer token, Spotify token, or credential material appears in status, events, or logs. |
| SPOTHW-002 | Token expired/revoked | Spotify playback flow | Revoke/expire Spotify authorization used by the Pi, then attempt playback to a Pi endpoint | Endpoint does not appear or playback fails in Spotify; Pi diagnostics expose `spotify_token_expired` through safe status/diagnostic fields; Android shows account repair guidance without exposing secrets. |
| SPOTHW-003 | Pi Spotify service unavailable | `POST /operations/restart-service`, then poll | Stop or fail one `librespot@*` service | Status marks only the affected endpoint degraded; restart-service operation returns terminal success/failure and status refresh reflects final service state. |
| SPOTHW-004 | Wrong active Spotify device | Spotify Connect handoff | Start playback on a phone/computer speaker instead of `Indoor`, `Outdoor`, or `Whole House` | Pi reports `spotify.activeDevice.category=other_spotify_device` when it can determine active-device state; Android offers `Open Spotify` handoff and does not transfer playback. |
| SPOTHW-005 | Playback interrupted | Long playback session | Start playback to each Pi endpoint, then interrupt via network drop, Spotify device switch, service restart, or speaker power loss | App/Pi distinguish Spotify interruption, service failure, and Bluetooth route failure through safe Pi-owned status/diagnostic fields; playback does not silently report healthy if route is broken. |
| SPOTHW-006 | Stale Spotify control protection | `POST /operations/restart-service` | Capture status, wait beyond freshness window or reboot Pi, then restart `librespot_*` | Server rejects with `stale_observation` or `boot_changed`; no stale Spotify repair runs. |
| SPOTHW-007 | Spotify repair retry/idempotency | `POST /operations/restart-service` | Retry same repair after client timeout; then retry same `clientRequestId` against another `librespot_*` service | Same request returns original operation; conflicting reuse returns `409 idempotency_conflict`; service restarts once. |
| SPOTHW-008 | Bluetooth route recovery after repair | Spotify repair plus route validation | Restart `librespot_both`, then verify `Whole House` playback with one speaker disconnected and after watchdog/reconnect | Spotify endpoint recovery does not mask speaker degradation; after reconnect/watchdog, `Whole House` routes to both speakers and status is healthy. |

## Required Fixture Set

- Healthy identity, health, and status.
- Wrong identity with valid JSON.
- Reinstall/new pairing with no saved identity, requiring fresh identity plus authorization before trust.
- Saved host IP/hostname change with matching identity tuple.
- Saved host with mismatched `deviceId`.
- Saved host with mismatched or missing/null `controllerInstanceId`.
- Unsupported `contractVersion` and unsupported `apiVersion` major version.
- Public `/health` and bearer-required `/health`.
- Degraded outdoor speaker.
- Booting Pi.
- PipeWire down error.
- `401 unauthorized` and `403 forbidden`.
- Reconnect accepted, running, succeeded, failed, and busy.
- Restart-service forbidden in non-advanced mode.
- Run-watchdog succeeded and failed.
- `stale_observation`, `boot_changed`, and `idempotency_conflict`.
- Operation polling `404 operation_not_found` and `410 operation_expired`.
- Diagnostics summary degraded Bluetooth.
- Spotify logged-out, token-expired/revoked, upstream/API-unavailable, wrong-active-device, playback-interrupted, route-not-ready, and service-unavailable fixtures using canonical `spotify` status fields and reason codes.
- Spotify repair restart-service fixtures for `librespot_indoor`, `librespot_outdoor`, and `librespot_both`: accepted, duplicate retry, busy, stale observation, boot changed, succeeded, failed, and rejected.
- Diagnostics events with clamped limit.
- Diagnostics logs with valid allowlisted unit and rejected invalid unit.
- Diagnostics privacy redaction for forbidden token, MAC address, SSID, and raw command content.
- Discovery without mDNS support.
- No background notification permission in Phase 3.

## Configurable Defaults To Keep Out Of Hard-Coded Tests

- `/health` auth policy is configurable; provisional default is public on trusted LAN.
- Transport is LAN HTTP plus bearer token; future HTTPS or mTLS is not selected.
- `deviceId=pihouse-audio-01` is a fixture example only.
- Freshness window provisional default is `120` seconds.
- Diagnostics log cap provisional default is `200` lines.
- Diagnostics events cap provisional default is `100` events.
- Operation retention provisional default is 100 newest operations or 7 days.
- Service restart visibility provisional default is advanced-only.
- API bind behavior is configurable.
- Speaker MACs and sink names are configured on the Pi only and are never supplied by Android.
- Spotify account, token, active device, and playback-state details must use the canonical safe `spotify` status fields and reason codes, with no token, track, user, playlist, or account data in fixtures.

## Remaining Blockers

- Human approval is still pending for configurable defaults listed above, so QA should parameterize them in fixtures.
- Hardware validation needs the actual Pi service build, service token policy, and configured speakers. The canonical contract is sufficient for mock/API fixture tests.
- Spotify account validation needs a Premium Spotify account, credentials or pairing flow approved for the Pi, and at least one phone/computer running Spotify on the same network.
- Spotify account repair/re-auth flow still needs product/backend definition for Pi-side setup and recovery instructions. The canonical API exposes safe account/session/playback state, but Phase 3 still has no Android Spotify OAuth, no Spotify token relay, and no Spotify-specific mutating operation. Existing operations only cover `restart_service`, `reconnect`, and `run_watchdog`.
