# Android Phase 1 Implementation Notes

This workspace now includes an initial Android project scaffold for the
PiStream companion app under `android-app/`.

## Build

```powershell
cd android-app
.\gradlew.bat :app:assembleDebug
```

Local verification on June 12, 2026 is blocked because `java.exe` is not
installed or not on `PATH` in this environment. The wrapper currently fails
with:

```text
'"java.exe"' is not recognized as an internal or external command,
operable program or batch file.
```

The Gradle wrapper files live inside `android-app/`, including
`android-app/gradle/wrapper/gradle-wrapper.jar`.

## Scope

- Package: `com.pistream.companion`
- Min SDK: 26
- Target SDK: 35
- UI: Jetpack Compose and Material 3
- Networking: Ktor client with kotlinx.serialization
- Storage: DataStore for saved host and trusted identity; AndroidX Security
  Crypto for bearer token storage
- Base URL normalization: `http://<host>:8765/api/v1`
- Default host: `audiopi.local`
- Trust tuple: `apiName`, `contractVersion`, `deviceId`,
  `controllerInstanceId`

## Phase 1 Backend Surface

The scaffold targets the confirmed local Pi API at
`http://<pi-host>:8765/api/v1`:

- `GET /identity`
- `GET /health`
- `GET /status` with bearer auth
- `POST /operations/reconnect` with bearer auth
- `POST /operations/restart-service` with bearer auth
- `POST /operations/run-watchdog` with bearer auth
- `GET /operations/{operationId}` with bearer auth

Public identity and health responses are decoded as top-level backend payloads.
Status and operation responses are decoded against the Phase 1 FastAPI scaffold
under `pi-api/`.

## Boundaries

The app requests only:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE`

It does not request Android Bluetooth, location, notification, or multicast
permissions. It does not implement Android-side audio streaming, Spotify OAuth,
Spotify playback API calls, token refresh, QR login, cookies, or direct speaker
pairing.

Cleartext HTTP is explicitly enabled in the manifest for Phase 1 LAN-only API
testing.

## QA Notes

- Start wrong-device verification from clean app data, then pair once and
  point the same app data at a mock/Pi with a changed `deviceId` or
  `controllerInstanceId`.
- Operation placeholder verification needs a Pi API or mock server that records
  request bodies and bearer headers.
