# Phase 1 Android QA Checklist

Use this checklist for the first local verification pass after Java/Android build
tooling is available.

## Current Blockers

| Item | Status | QA impact |
| --- | --- | --- |
| `java.exe` on `PATH` | Blocked | `.\gradlew.bat :app:assembleDebug` cannot run yet. |
| Android SDK/emulator or physical device | Required | Needed for install, permissions, cleartext HTTP, and first-run storage checks. |
| Reachable Pi API or mock server | Required | Needed for `/identity`, `/health`, `/status`, and operation placeholder checks. |

## Build Smoke Test

| ID | Check | Expected result |
| --- | --- | --- |
| BLD-001 | Run `java -version`. | Java 17-compatible runtime is available on `PATH`. |
| BLD-002 | Run `.\gradlew.bat :app:assembleDebug`. | Debug APK builds successfully. |
| BLD-003 | Inspect generated manifest or APK permissions. | Only `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_WIFI_STATE` are present. |
| BLD-004 | Install debug APK on emulator/device. | App installs as `com.pistream.companion` with no runtime permission prompts. |
| BLD-005 | Launch app cold. | App opens to host/token setup with default host `audiopi.local`. |

## First-Run Host And Token Setup

| ID | Check | Expected result |
| --- | --- | --- |
| SETUP-001 | Start from clean app data. | Host field defaults to `audiopi.local`; token field is empty and masked. |
| SETUP-002 | Leave token blank. | Connect button remains disabled. |
| SETUP-003 | Enter `audiopi.local`. | Base URL used by API calls is `http://audiopi.local:8765/api/v1`. |
| SETUP-004 | Enter raw host/IP such as `<pi-host>` or `192.168.1.20`. | Base URL uses `http://<host>:8765/api/v1`. |
| SETUP-005 | Enter `http://<pi-host>:8765/api/v1`. | App strips scheme/path and still targets `http://<pi-host>:8765/api/v1`. |
| SETUP-006 | Complete a successful connect, close, and relaunch. | Saved host and bearer token are restored; token remains masked. |
| SETUP-007 | Clear app data. | Saved host, trusted identity, and stored token are removed. |

## Pi Reachability And Trust

| ID | Check | Expected result |
| --- | --- | --- |
| PI-001 | With reachable Pi and valid token, tap connect. | App calls public `GET /identity`, public `GET /health`, then bearer-protected `GET /status`. |
| PI-002 | `/identity` returns supported `apiName`, `contractVersion=2026-06-phase3`, API major `1`, non-empty `deviceId`, and non-empty `controllerInstanceId`. | App accepts identity and stores trusted tuple after status succeeds. |
| PI-003 | `/identity` has wrong API name, unsupported contract, unsupported API major, blank `deviceId`, or blank `controllerInstanceId`. | App reports API unavailable/unsupported and does not save trust. |
| PI-004 | First successful pairing, then point same app data at a Pi/mock with changed `deviceId` or `controllerInstanceId`. | App reports `wrong_device` and blocks dashboard/operations. |
| PI-005 | Same trusted Pi changes host/IP but keeps the same identity tuple. | App accepts the Pi and updates saved host after a successful status call. |

## Auth Failures

| ID | Check | Expected result |
| --- | --- | --- |
| AUTH-001 | Valid `/identity` and `/health`, but `/status` returns `401`. | App shows `unauthorized` and prompts for bearer token retry; no dashboard is shown. |
| AUTH-002 | `/status` returns `403`. | App shows forbidden/API error and does not expose operations. |
| AUTH-003 | `/status` returns `404`, `409`, `410`, or `503`. | App shows mapped error text without crashing. |
| AUTH-004 | Invalid token succeeds after user edits token and reconnects. | Token is saved only after successful protected status. |

## Offline And Error States

| ID | Check | Expected result |
| --- | --- | --- |
| ERR-001 | Host is offline or DNS fails. | App shows `not_found` or API unavailable without crashing. |
| ERR-002 | Pi returns malformed JSON for any endpoint. | App shows API unavailable/malformed error without crashing. |
| ERR-003 | `/identity` succeeds but `/health` fails. | App can still use `/status.health` if available. |
| ERR-004 | Network drops during connect. | Loading state clears and retry remains possible. |
| ERR-005 | Device rotates or app backgrounds/returns during request. | UI remains coherent; no duplicate operation is triggered by lifecycle change. |

## Dashboard Rendering

| ID | Check | Expected result |
| --- | --- | --- |
| DASH-001 | Healthy `/status` with identity, health, reboot, speakers, sinks, spotify endpoints, spotify summary, watchdog, and operations. | Dashboard renders all sections with expected labels and states. |
| DASH-002 | Optional arrays are empty. | Sections render `No Pi-reported rows.` rather than crashing. |
| DASH-003 | Optional spotify/health/watchdog fields are missing. | Dashboard renders `unknown` or omits optional section as appropriate. |
| DASH-004 | `observedBootId` or `observedAt` is missing. | Operation controls are disabled. |
| DASH-005 | Long host, device IDs, reason codes, and operation IDs. | Text remains readable and does not overlap controls. |

## Operation Placeholders

| ID | Check | Expected result |
| --- | --- | --- |
| OPS-001 | With `observedBootId` and `observedAt`, tap Reconnect. | App posts to `/operations/reconnect` with bearer token, `clientRequestId`, observed fields, and selected `speakerId`. |
| OPS-002 | Tap Run Watchdog. | App posts to `/operations/run-watchdog` with bearer token, `clientRequestId`, and observed fields. |
| OPS-003 | Tap Restart Service. | App posts to `/operations/restart-service` with bearer token, `clientRequestId`, observed fields, and `serviceId=bt_watchdog`. |
| OPS-004 | Operation returns success envelope. | App displays operation status and operation ID. |
| OPS-005 | Operation returns `401`, `403`, `409`, `410`, or `503`. | App displays mapped error and remains usable. |
| OPS-006 | Double tap or rotate during operation. | No duplicate operation should be sent from one user action. |

## Forbidden Phase 1 Paths

These are explicit regressions to check during every Phase 1 verification pass.

| ID | Forbidden path | Expected result |
| --- | --- | --- |
| REG-001 | Android Bluetooth permissions or Bluetooth scan/connect APIs. | Not present. App does not request `BLUETOOTH`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, or direct speaker pairing. |
| REG-002 | Android location permissions. | Not present. App does not request fine/coarse/background location. |
| REG-003 | Notification permission. | Not present. App does not request `POST_NOTIFICATIONS`. |
| REG-004 | Android audio streaming or local playback pipeline. | Not present. No Android audio route, `AudioTrack`, or playback engine behavior. |
| REG-005 | Spotify OAuth, Spotify Web API playback control, refresh token, cookies, or QR login. | Not present. `Open Spotify` only launches the Spotify app or web page for handoff. |
| REG-006 | Direct speaker pairing or route ownership on Android. | Not present. Android reads Pi status and sends Pi-owned operation placeholders only. |

## Critical QA Gaps To Track

- Build verification is blocked until Java 17-compatible tooling is available.
- No automated Android unit/instrumentation tests are present yet; first pass is manual plus Gradle build.
- Wrong-device tests require app data isolation because trusted identity persists after the first successful connect.
- Operation placeholder verification requires a Pi API or mock server that records request bodies and bearer headers.
- Cleartext HTTP is enabled for `http://<pi-host>:8765`; verify this is intentional for Phase 1 LAN-only testing.
