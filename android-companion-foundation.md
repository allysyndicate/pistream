# Android Companion App Foundation

This foundation keeps the Raspberry Pi responsible for Spotify Connect,
Bluetooth routing, PipeWire, and audio playback. Android only discovers the Pi,
verifies it is the expected audio controller, displays state, and triggers
explicit Pi-side operations through the local API.

## Target API

Base URL:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

Required endpoints and auth:

| Method | Path | Auth | Android use |
| --- | --- | --- | --- |
| `GET` | `/identity` | none | Verify this is the Pi audio controller and reject wrong devices. |
| `GET` | `/health` | configurable, default none | Fast reachability and readiness check. |
| `GET` | `/status` | bearer required | Build dashboard and capture observation metadata for safe mutations. |
| `POST` | `/operations/reconnect` | bearer required | Ask the Pi to reconnect one or more speakers. |
| `POST` | `/operations/restart-service` | bearer required | Ask the Pi to restart a known Pi-side service. |
| `POST` | `/operations/run-watchdog` | bearer required | Ask the Pi to run the Bluetooth watchdog now. |
| `GET` | `/operations/{operationId}` | bearer required | Poll long-running operation result. |
| `GET` | `/diagnostics/summary` | bearer required | Load app-safe grouped troubleshooting state. |
| `GET` | `/diagnostics/events` | bearer required | Load bounded structured events. |
| `GET` | `/diagnostics/logs` | bearer required | Load bounded logs for allowlisted units. |

Bearer-protected endpoints use only:

```text
Authorization: Bearer <token>
```

Phase 3 has no login, cookie session, refresh-token flow, or token-renewal
endpoint. Android recovery from `401 unauthorized` is to keep the saved host
visible, disable protected controls and diagnostics, ask the user to re-enter
or rescan the Pi token, then retry `GET /status`.

All mutating requests should include:

```json
{
  "observedBootId": "boot-id-from-status",
  "observedAt": "2026-06-12T07:30:00Z",
  "clientRequestId": "uuid-generated-on-android"
}
```

`observedBootId` and `observedAt` must come from the latest accepted
`GET /status` response. Android should treat freshness as a backend-owned rule:
do not hard-code an allowed age or compare against a fixed client-side freshness
window. If the backend rejects an action with `stale_observation`, or if the
status response lacks the required observation metadata, refresh status before
allowing another action attempt.

## Android Stack

- Kotlin
- Jetpack Compose
- Material 3
- MVVM with repository layer
- Coroutines and `StateFlow`
- Retrofit or Ktor client with kotlinx serialization
- DataStore for saved host, last verified identity summary, and user preferences
- WorkManager only if background notifications are added later

Recommended package structure:

```text
app/src/main/java/com/pistream/companion/
  MainActivity.kt
  app/
    PiCompanionApp.kt
    AppNavGraph.kt
  data/
    PiApiClient.kt
    PiRepository.kt
    SavedPiStore.kt
    dto/
      IdentityDto.kt
      HealthDto.kt
      StatusDto.kt
      SpotifyDto.kt
      ActionDto.kt
      OperationDto.kt
      ApiErrorDto.kt
  domain/
    AppConnectionState.kt
    DashboardModel.kt
    EndpointStatus.kt
    SpotifyStatus.kt
    SpeakerStatus.kt
    PiAction.kt
    PiError.kt
    OperationState.kt
  ui/
    discovery/
      DiscoveryScreen.kt
      DiscoveryViewModel.kt
    dashboard/
      DashboardScreen.kt
      DashboardViewModel.kt
    components/
      StatusRow.kt
      EndpointCard.kt
      OperationBanner.kt
      ErrorBanner.kt
```

## Permissions

Start with the minimum app permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

Do not request `CHANGE_WIFI_MULTICAST_STATE` for Phase 3. mDNS service
discovery is future scope and Phase 3 must work through saved host,
`audiopi.local:8765`, and manual host/IP entry. Add multicast lock handling only
with a future backend-advertised mDNS service and matching QA fixtures.

Do not request Android Bluetooth permissions for Phase 3. Speaker pairing,
speaker connection state, and routing are Pi-owned concerns exposed through the
Pi API. Add `POST_NOTIFICATIONS` later only if the app introduces disconnect or
watchdog alerts.

Background notifications are out of scope for Phase 3. The Phase 3 app should
not request `POST_NOTIFICATIONS`.

Spotify integration should not add Android permissions in the first version.
Opening Spotify uses an `Intent` or web URL fallback. Android does not own
Spotify OAuth, token refresh, playback control, transfer, audio streaming,
Bluetooth routing, or PipeWire routing in Phase 3. If Spotify Web API OAuth is
added later, it is Pi-only scope; tokens stay on the Pi and never appear in
Android, API responses, diagnostics, events, or logs.

## Discovery Flow

Discovery should try saved and default targets before requiring manual input:

1. If a saved host exists, call `GET /identity`.
2. Try `audiopi.local` at port `8765`.
3. If mDNS service discovery is implemented by Backend later, resolve the
   advertised service and call `GET /identity`.
4. Let the user enter hostname or IP manually.

mDNS service discovery is future backend scope, not a Phase 3 requirement.
Phase 3 tests should cover saved host, default hostname, and manual host/IP
entry without requiring mDNS.

Manual host input should normalize only the host portion. The app constructs the
base URL as `http://<host>:8765/api/v1`.

`GET /identity` gates the rest of the app. Android should validate identity by
the backend contract fields `apiName`, `contractVersion`, `apiVersion`,
`deviceId`, and `controllerInstanceId`.

For a new pairing, Android may accept any non-empty `deviceId` only after API
compatibility passes, `controllerInstanceId` is present and non-empty, and the
user provides valid authorization. Save `controllerInstanceId` with the paired
identity. For an existing pairing, Android must compare `deviceId` and
`controllerInstanceId` to the saved values. A returned conflicting or missing
`controllerInstanceId` maps to `wrong_device` for an already paired Pi.
Hostname or IP changes are allowed when the identity tuple still matches. Saved
host identity is not enough after Android reinstall because the saved identity
and token are gone; the app must treat the host as unpaired until identity is
accepted and authorization is provided again.

Phase 3 compatibility rules:

- `apiName` must be `pihouse-audio-api`.
- `contractVersion` must be `2026-06-phase3`.
- minimum compatible `apiVersion` is `1.0.0`.
- compatible `apiVersion` major version is `1`.
- unsupported contract or API major version maps to `api_unavailable`.
- `deviceId` is configurable backend data. The fixture value
  `pihouse-audio-01` is not a hard-coded Android trust anchor.
- `controllerInstanceId` is required, non-null, and part of the saved identity
  tuple for `/identity` and `/status.identity`.

- Expected Pi audio controller: persist host and identity, then load health and
  status.
- Reachable but wrong product/device: show `wrong_device`.
- Timeout, DNS failure, refused connection: show `not_found` or
  `api_unavailable` depending on whether the host resolved.
- Unauthorized response: show an authorization error and do not proceed.

## App States

The top-level state machine should be explicit:

```kotlin
sealed interface AppConnectionState {
    data object NotFound : AppConnectionState
    data class FoundHealthy(val dashboard: DashboardModel) : AppConnectionState
    data class FoundUnhealthy(val dashboard: DashboardModel) : AppConnectionState
    data class WrongDevice(val host: String, val identitySummary: String?) : AppConnectionState
    data class ApiUnavailable(val host: String, val cause: String) : AppConnectionState
}
```

State mapping:

| State | Trigger |
| --- | --- |
| `not_found` | No saved/default/manual target can be reached. |
| `found_healthy` | Identity is valid and health/status report all required components healthy. |
| `found_unhealthy` | Identity is valid but health/status reports degraded Pi, speaker, Spotify, PipeWire, or watchdog state. |
| `wrong_device` | Host responds but `apiName`, saved `deviceId`, or saved `controllerInstanceId` does not match the expected Pi audio controller. |
| `api_unavailable` | Host exists but API service is down, unsupported, malformed, or unavailable. |

## Dashboard Model

`GET /health` should feed the quick connection badge. `GET /status` should feed
the full dashboard and safe-action metadata.

Dashboard fields Android should expect from repository mapping:

```kotlin
data class DashboardModel(
    val host: String,
    val hostname: String,
    val apiVersion: String,
    val deviceId: String,
    val controllerInstanceId: String,
    val bootId: String,
    val observedAt: Instant,
    val overallHealthy: Boolean,
    val piReachable: Boolean,
    val pipeWireHealthy: Boolean,
    val wirePlumberHealthy: Boolean,
    val bluetoothAdapters: List<BluetoothAdapterStatus>,
    val speakers: List<SpeakerStatus>,
    val endpoints: List<EndpointStatus>,
    val spotify: SpotifyStatus,
    val watchdog: WatchdogStatus,
    val activeOperation: OperationState?
)
```

Endpoint rows:

- `Indoor`
- `Outdoor`
- `Whole House`

Speaker rows:

- paired
- trusted
- connected
- assigned adapter, if available
- sink name
- last Pi-side error or reconnect attempt

`deviceId`, component ids, speaker ids, service names, and freshness policy
should all be treated as backend data. Android can cache and echo these values
when required by the API, but should avoid assuming final literal ids outside
the stable endpoint labels `Indoor`, `Outdoor`, and `Whole House`.

## Spotify UX Option

First-version recommendation: **show Pi-reported Spotify readiness and provide a
handoff to Spotify's device picker**. Do not make Android a Spotify streamer,
Spotify Connect controller, Bluetooth router, or source of truth for playback
routing.

Main dashboard Spotify state:

- Pi Spotify Connect status: ready, degraded, offline, or unknown.
- Endpoint readiness for `Indoor`, `Outdoor`, and `Whole House`, mapped from
  `spotifyEndpoints`.
- Account/token state only if reported by the Pi, such as authorized, missing,
  expired, or unsupported account. Do not infer account state on Android.
- Active Spotify device mismatch only if reported by the Pi or a backend
  Spotify integration. Example: "Playing on another Spotify device" with an
  action to open Spotify.
- Playback unavailable or interrupted only if the backend exposes a bounded
  playback summary or reason code. Otherwise show endpoint health only.

Endpoint rows remain Pi infrastructure health, not playback controls. The
primary Spotify action is `Open Spotify`, which launches the Spotify app when
installed and falls back to `https://open.spotify.com/`.

The first version does not include Android Spotify OAuth/API controls. The
backend contract exposes safe account state, active device category, playback
summary, route readiness, and recommended action when the Pi can determine
them. The Android app can deep-link users into Spotify, but Spotify device
selection and playback control stay inside Spotify. This keeps credentials out
of Android and avoids Android becoming responsible for audio routing.

Suggested domain model:

```kotlin
data class SpotifyStatus(
    val endpoints: List<EndpointStatus>,
    val integrationMode: String,
    val connectOwnedBy: String,
    val accountState: SpotifyAccountState?,
    val activeDevice: SpotifyActiveDevice?,
    val playback: SpotifyPlaybackSummary?,
    val routeReadiness: List<SpotifyRouteReadiness>,
    val recommendedAction: SpotifyRecommendedAction?
)

enum class SpotifyAccountState {
    Available,
    LoggedOut,
    TokenExpired,
    UpstreamUnavailable,
    NotConfigured,
    Unknown
}

data class SpotifyActiveDevice(
    val displayName: String?,
    val expectedEndpointId: String?,
    val mismatch: Boolean
)

data class SpotifyPlaybackSummary(
    val available: Boolean,
    val interrupted: Boolean,
    val reasonCode: String?
)

data class SpotifyRouteReadiness(
    val endpointId: String,
    val ready: Boolean,
    val reasonCodes: List<String>
)

enum class SpotifyRecommendedAction {
    OpenSpotify,
    RestartEndpointService,
    RefreshStatus,
    ViewDiagnostics,
    None
}
```

Spotify state handling:

| State | Android behavior |
| --- | --- |
| Pi unreachable | Show the normal Pi connection error. Disable Spotify-specific actions except retry/open fallback help. |
| Stale status | Refresh `/status`; if an operation returns `stale_observation` or `boot_changed`, refresh before retry. |
| Conflicting operation running | Show the active operation banner from `busy` details or status. Poll the active operation when an operation id is provided; otherwise refresh status. Do not start a Spotify repair action until the conflict clears. |
| Spotify logged out | Show account state only when backend reports it. Primary action remains `Open Spotify` or backend-provided setup instructions; Android does not perform OAuth in v1. |
| Spotify token expired or revoked | Show Pi-reported account repair state. Do not attempt token refresh from Android. Optional Spotify Web API OAuth is future Pi-only scope. |
| Pi Spotify unavailable | Mark affected endpoint degraded or unavailable from `spotifyEndpoints`. Offer refresh or diagnostics first; keep `Open Spotify` secondary until the endpoint is ready. |
| Spotify Connect unavailable | Mark affected endpoint degraded. Offer `Open Spotify` only after the Pi endpoint is ready; otherwise offer refresh or diagnostics. |
| Active Spotify device mismatch | Show the current/expected device label if backend provides it. Primary action: open Spotify. Do not force-transfer playback from Android in Phase 3. |
| Speaker route not ready | Prioritize Pi repair actions for the speaker/sink: reconnect speaker or run watchdog. Keep Spotify playback handoff disabled or secondary until the route is ready. |
| Playback unavailable or interrupted | Display backend-provided reason and refresh. If the reason maps to speaker or endpoint health, surface the Pi-owned repair action. |
| Hardware or account validation unavailable | Show unknown or pending validation. Do not block the whole dashboard; expose endpoint health that is available and mark richer Spotify state as pending backend validation. |
| Spotify account/token missing or expired | Show account state as Pi-reported setup work. Do not start Android OAuth. Optional Spotify Web API OAuth is future Pi-only scope. |

Normal controls:

- Refresh status.
- Open Spotify.
- Reconnect affected speaker, when the route problem is a speaker state.
- Run watchdog, when the backend recommends it for Bluetooth route recovery.

Diagnostics/advanced controls:

- Restart `librespot_*`, PipeWire, WirePlumber, or watchdog services.
- Detailed service active states, bounded diagnostics, and recent Spotify
  events.
- Manual token/account repair instructions until product defines a safe setup
  flow.

Deep-link and fallback behavior:

- Prefer `Intent.ACTION_VIEW` to a Spotify app URI if a reliable target is
  defined during implementation.
- Always provide a browser fallback to `https://open.spotify.com/`.
- If Android cannot open Spotify, leave the dashboard intact and show a
  recoverable banner.
- Do not assume Spotify app installation, Premium eligibility, or active account
  state unless the Pi API returns explicit safe state.

Backend/API dependencies for Spotify UI:

- `GET /status.spotifyEndpoints[]` provides endpoint readiness and service/sink
  mapping.
- `GET /status.spotify.accountState` provides safe account/session state when
  known by the Pi, or `unknown`.
- `GET /status.spotify.activeDevice` provides active device category when known
  by the Pi, or `unknown`.
- `GET /status.spotify.playback` provides only a bounded summary. Do not expose
  track, user, playlist, account, or token data in Phase 3.
- `GET /status.spotify.routeReadiness[]` maps endpoint readiness to existing
  speaker/sink/service reason codes.
- Add operation recommendations through existing diagnostics or status fields,
  not Android-local heuristics.

Version-1 Spotify recommendation:

- Status and repair guidance are in scope.
- Opening Spotify or its web fallback is in scope.
- Spotify OAuth, token refresh, playback transfer, queue/track controls, and
  Android-owned active-device decisions are out of scope. Pi-side Spotify Web
  API OAuth is a future product/security decision and does not put Spotify
  credentials in Android.

## API Error Handling

Map backend error codes to stable Android behavior:

| Error code | UI behavior |
| --- | --- |
| `stale_observation` | Disable action result, refresh `GET /status`, then let the user retry. |
| `boot_changed` | Refresh `GET /status`; do not retry until the dashboard reflects the current boot. |
| `busy` | Keep dashboard visible, show active operation banner, poll the operation if an ID is provided. |
| `unauthorized` | Clear action controls and diagnostics, keep host visible, show authorization-required state, let the user re-enter or rescan the bearer token, then retry `GET /status`. |
| `forbidden` | Keep status visible if available, disable the forbidden control, and direct the user to re-authorize or use a permitted token. |
| `idempotency_conflict` | Keep the dashboard visible, generate a new `clientRequestId` for a changed request, and do not auto-retry. |
| `operation_not_found` | Stop polling, refresh status, and clear the stale operation banner. |
| `operation_expired` | Stop polling, refresh status, and show that the previous operation can no longer be inspected. |
| `target_unavailable` | Mark the target component degraded and refresh status before offering another action. |
| `speaker_connect_failed` | Show failed reconnect result, refresh status, and keep reconnect/watchdog available if still allowed. |
| `service_restart_failed` | Keep the user in diagnostics, show failed operation result, and refresh status. |
| `invalid_observation` | Refresh status and block mutation until required observation metadata is available. |
| `unsupported_enum` | Treat the affected component as unknown/degraded and include safe diagnostic detail only. |
| `command_timeout` | Show a recoverable failure, refresh status, and leave retry decisions to the refreshed component state. |
| `dependency_unavailable` | Keep the dashboard visible, mark dependent components degraded, and offer diagnostics or backend-recommended repair. |
| operation failure | Show failed operation banner, refresh status, leave the user on dashboard. |

Network and parsing failures:

- Timeout/DNS: `not_found` during discovery, transient banner on dashboard.
- Connection refused: `api_unavailable`.
- Unsupported API version: `api_unavailable` with version-specific message.
- Malformed body: `api_unavailable`; include diagnostic detail in logs only.

## Mutating Actions

Actions are user-initiated and Pi-side only:

```kotlin
sealed interface PiAction {
    data class Reconnect(val target: SpeakerTarget) : PiAction
    data class RestartService(val service: RestartableService) : PiAction
    data object RunWatchdog : PiAction
}
```

Primary dashboard actions for Phase 3:

- reconnect an affected speaker
- run the Bluetooth watchdog now
- refresh status

Service restart is supported by the backend contract, but Android should expose
it as an advanced/diagnostics control until confirmed as a normal user-facing
repair path. It should not appear as the first-line call to action on the main
dashboard.

Allowed restart targets should be explicit and driven by backend-supported
values:

- `pipewire`
- `wireplumber`
- `librespot_indoor`
- `librespot_outdoor`
- `librespot_both`
- `bt_watchdog`

If the backend provides a supported-actions or restart-target list in
`GET /status`, prefer that list over Android's static enum.

Action flow:

1. Read latest `DashboardModel.bootId` and `DashboardModel.observedAt`.
2. Generate a UUID `clientRequestId`.
3. Submit the action.
4. If the response returns `operationId`, enter operation polling.
5. Poll `GET /operations/{operationId}` until succeeded, failed, rejected,
   expired, or timed out.
6. Refresh `GET /status` after terminal operation state.

Polling starts with `operation.pollAfterMs` or `1000` ms, bounded to `500..5000`
ms. Retry transient polling timeout, connection-refused, and HTTP `5xx` failures
with `1000`, `2000`, then `5000` ms backoff until 60 seconds total client wait.
Do not retry `401`, `403`, `404`, `409`, or `410` as transient polling failures.

Use idempotent UI behavior keyed by `clientRequestId`: generate one UUID per
user-submitted operation and keep it stable for retries of the same submitted
request. If the same `clientRequestId`, operation type, and target are submitted
again, the server returns the original operation. If the same `clientRequestId`
is reused for a different type or target, handle `409 idempotency_conflict`,
generate a new UUID only after the user starts a new request, and do not
auto-retry the changed request. Disabling the pressed button is enough for
Phase 3. Do not hide the dashboard while an operation runs.

## Diagnostics

Diagnostics are bearer-protected and advanced-oriented. Android should expose
them from the dashboard as a diagnostics view, not as a first-run requirement.

Phase 3 diagnostics endpoints:

- `GET /diagnostics/summary`
- `GET /diagnostics/events?limit=<1..100>&category=<optional>`
- `GET /diagnostics/logs?unitId=<allowlisted LogUnitId>&lines=<1..200 default cap>`

Diagnostics must be rendered as app-safe support data. Android can display
allowlisted service ids, display service names, sink ids, speaker ids, Spotify
endpoint ids, Pi hostname, `operationId`, `clientRequestId`, and `eventId`.
Android must not display or persist bearer tokens, speaker MAC addresses, raw
Bluetooth addresses, Wi-Fi SSIDs or passwords, arbitrary LAN hostnames,
environment variables, full shell commands, or unbounded raw journal output.

Diagnostics error behavior:

- `401 unauthorized`: disable diagnostics and ask for the Pi token.
- `403 forbidden`: keep dashboard status visible and hide restricted diagnostic
  details.
- `400 unsupported_enum`: reject the invalid filter or unit selection and return
  to backend-provided allowlisted values.
- Network or `5xx`: keep existing dashboard visible and show diagnostics as
  temporarily unavailable.

## Config-Driven Values

These values come from the backend contract, status payload, diagnostics payload,
or local pairing state. Android must not hard-code them as final product values:

| Value | Android behavior |
| --- | --- |
| `/health` auth policy | Treat as configurable. Support public health and bearer-required health. |
| Transport/security | Default to LAN HTTP plus bearer token for Phase 3; keep base URL construction isolated for future HTTPS or mTLS. |
| `deviceId` | Save and compare for pairing, but do not hard-code the fixture value. |
| `controllerInstanceId` | Required, non-null backend identity data. Save and compare for pairing; a missing or changed value blocks protected controls for an already paired Pi. |
| Freshness window | Display or log backend-provided `freshnessWindowSeconds` if useful; do not enforce a fixed client-side age limit. |
| Operation retention | Rely on `expiresAt`, `operation_not_found`, and `operation_expired`; do not assume local retention beyond the contract defaults. |
| Log/event caps | Use server defaults and clamp UI inputs to backend limits when exposed. |
| Service restart visibility | Read `controls.restartServiceMode` and permissions; keep restart controls advanced-only unless product changes it. |
| API bind behavior | Do not assume one interface. User enters or discovers the reachable host. |
| Speaker MACs | Pi-owned config only. Android never supplies or displays raw MAC addresses. |
| Sink names | Pi-owned config only. Android displays backend-safe sink ids/names and never invents sink names. |
| mDNS discovery | Future optional feature only; not required for Phase 3 tests or permissions. |

## Compose Screens

Discovery screen:

- Saved host status, if any.
- Manual hostname/IP input.
- Find/retry button.
- Inline state for `not_found`, `wrong_device`, and `api_unavailable`.

Dashboard screen:

- Compact Pi identity and connection badge.
- Overall status summary.
- Spotify status row with endpoint readiness and `Open Spotify`.
- Three endpoint rows/cards for Indoor, Outdoor, Whole House.
- Speaker section for indoor/outdoor Bluetooth state.
- Watchdog row with last run and action button.
- Operation banner for in-flight or failed operations.
- Action buttons near the degraded component they affect.
- Advanced diagnostics entry for service restart controls and detailed Pi-side
  component state.

Avoid any UI that implies Android is streaming audio. Playback instructions
should direct the user to Spotify's device picker when needed.

## Verification Plan

Unit tests:

- URL normalization for manual host input.
- Identity mapping to valid/wrong-device states.
- Health/status mapping to `found_healthy` and `found_unhealthy`.
- API error-code mapping for all required error codes.
- Spotify dashboard mapping from endpoint readiness, account state, active
  device mismatch, route readiness, recommended action, and bounded playback
  summary.
- Mutation request body includes `observedBootId`, `observedAt`, and
  `clientRequestId`.
- `stale_observation` handling refreshes status without using a fixed
  client-side freshness window.
- Identity handling accepts backend-compatible devices without hard-coding one
  final device id.
- Existing saved host accepts hostname/IP changes when `deviceId` and
  `controllerInstanceId` match the saved identity tuple.
- Existing saved host maps mismatched `deviceId`, mismatched
  `controllerInstanceId`, or missing `controllerInstanceId`, to `wrong_device`.
- Unsupported `contractVersion` or `apiVersion` major version maps to
  `api_unavailable`.
- Unauthorized handling covers missing, malformed, empty, and invalid bearer
  token headers.
- Operation polling terminal-state handling.
- Operation polling transient timeout/`5xx` backoff and 60-second timeout.
- Restart-service controls are kept out of primary dashboard actions.
- Phase 3 does not require mDNS or `POST_NOTIFICATIONS`.

Integration tests with fake API:

- `GET /identity` success followed by healthy status.
- Reachable host with wrong identity.
- API service unavailable after DNS/host reachability.
- `stale_observation` on action followed by status refresh.
- `busy` with operation polling.
- Operation failure followed by refreshed degraded dashboard.
- Spotify endpoint unhealthy with `Open Spotify` kept secondary until endpoint
  readiness is restored.
- Active Spotify device mismatch from fake API maps to open-Spotify handoff,
  not Android playback transfer.

Manual QA:

- Fresh install with no saved host.
- Saved host unavailable.
- Manual IP success.
- `audiopi.local` success and failure.
- Speaker disconnected state.
- Spotify endpoint unhealthy state.
- Spotify Connect unavailable and wrong active Spotify device states when the
  fake API supports them.
- Reconnect and run-watchdog primary action flows.
- Restart-service action flow from advanced diagnostics.

## Current Workspace Blocker

The workspace currently contains documentation only:

```text
pi-whole-house-audio.md
pi-local-api-phase3-test-contract.md
pi-local-api-phase3-implementation-plan.md
phase3-android-pi-setup-and-operations.md
android-companion-foundation.md
```

There is no Android Gradle project, package namespace, generated API schema, or
mock backend fixture checked in yet. The next implementation step is to create a
new Android module or attach this foundation to an existing Android repository,
then encode the DTOs, repository, ViewModels, and Compose screens above.
