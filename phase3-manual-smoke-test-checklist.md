# Phase 3 Manual Smoke-Test Checklist

Use this checklist for the June 13, 2026 setup run. Record pass, fail, skipped,
and notes for each check. Hardware checks require the real Raspberry Pi,
configured Pi local API, Android test build, two Bluetooth speakers, local
network access, and Spotify account/device access where noted.

## Prerequisites

| Check | Pass criteria | Result | Notes |
|---|---|---|---|
| Pi powered and on LAN | Pi is powered, reachable on the same trusted LAN as the Android device, and expected hostname/IP is known. |  |  |
| Pi API running | `GET http://<host>:8765/api/v1/identity` returns JSON from the Pi audio API. |  |  |
| Test token available | Bearer token for protected endpoints is available to tester and not stored in screenshots/log notes. |  |  |
| Speakers ready | Indoor and Outdoor speakers are charged/powered, paired/trusted on the Pi, and not paired to another controller. |  |  |
| Spotify ready | Spotify Premium/account setup is available if Spotify handoff/status checks are in scope. |  |  |

## Pi Reachability And API Identity

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| PI-01 | Identity endpoint reachable | In browser/curl or app setup, request `http://<host>:8765/api/v1/identity`. | HTTP `200`; body includes `apiName=pihouse-audio-api`, `contractVersion=2026-06-phase3`, compatible `apiVersion=1.x.y`, non-empty `deviceId`, and non-null `controllerInstanceId`. |  |  |
| PI-02 | Health endpoint usable | Request `/api/v1/health` with expected auth policy. | Health returns valid state: `booting`, `healthy`, `degraded`, or `error`; app does not treat public or bearer-required health as contract drift. |  |  |
| PI-03 | Status endpoint protected | Request `/api/v1/status` without token, then with token. | Missing token returns `401`; valid token returns status with identity, health, services, speakers, sinks, Spotify, watchdog, controls, and observations. |  |  |
| PI-04 | Unreachable host handling | Stop API or use known-bad host/IP. | App distinguishes `not_found` or `api_unavailable`; no protected controls are shown. |  |  |

## Android Install And Permissions

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| AND-01 | Install cleanly | Install test APK/build on Android device. | App launches without crash and shows setup/discovery/manual host flow. |  |  |
| AND-02 | Required permissions only | Inspect Android app permission prompt/settings. | App may use network/Wi-Fi state permissions; it does not request Android Bluetooth permissions or `POST_NOTIFICATIONS` for Phase 3. |  |  |
| AND-03 | Manual host entry | Enter host as bare hostname/IP and, if practical, with `http://` prefix. | App normalizes to `http://<host>:8765/api/v1` and does not require mDNS. |  |  |
| AND-04 | Auth entry | Enter valid token for protected endpoints. | App reaches dashboard/status after identity trust and token acceptance; invalid token shows authorization-required state without losing saved host. |  |  |

## Pairing And Trust

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| TRUST-01 | First pairing | Clear app data, enter real Pi host, accept identity, enter token. | App saves the trusted tuple after compatible identity: `apiName`, `contractVersion`, `deviceId`, `controllerInstanceId`. |  |  |
| TRUST-02 | Saved host reuse | Relaunch app with saved host and same Pi. | App revalidates identity before dashboard actions and reaches `found_healthy` or `found_unhealthy`. |  |  |
| TRUST-03 | Host/IP change | Connect using Pi IP instead of hostname, or hostname instead of IP. | Matching identity tuple remains trusted; hostname/IP alone does not trigger `wrong_device`. |  |  |
| TRUST-04 | Wrong-device behavior if practical | Point app at a mock/fake host or alternate service returning wrong `apiName`, changed `deviceId`, changed `controllerInstanceId`, or missing/null `controllerInstanceId`. | App maps to `wrong_device`; health/status/diagnostics/mutating controls are blocked for that host. |  |  |
| TRUST-05 | Compatibility failure | Use a mock/fake response with missing/unsupported `contractVersion` or unsupported API major version. | App maps to `api_unavailable`, not `wrong_device`. |  |  |

## Bluetooth Stream And Route Behavior

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| BT-01 | Speaker status visible | Open dashboard after valid status. | Indoor and Outdoor rows show paired/trusted/connected/assigned state; degraded state is explicit if not ready. |  |  |
| BT-02 | Indoor route | From Spotify or supported source, select/use Indoor endpoint. | Audio plays on Indoor speaker only; app status remains healthy or accurately reports any degraded component. |  |  |
| BT-03 | Outdoor route | Select/use Outdoor endpoint. | Audio plays on Outdoor speaker only; app status remains healthy or accurately reports degradation. |  |  |
| BT-04 | Whole House route | Select/use Whole House endpoint. | Audio plays on both speakers; no severe stutter/dropout beyond known hardware limits. |  |  |
| BT-05 | Route degradation | Power off or disconnect one speaker while observing status. | App/Pi reports affected speaker/sink/route degraded; it does not report full route healthy. |  |  |

## Reconnect And Recovery

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| REC-01 | Reconnect operation | With one speaker disconnected, trigger authorized reconnect for that speaker. | Operation returns accepted/running state, polling reaches terminal state or timeout, and app refreshes `/status`. |  |  |
| REC-02 | Run watchdog | Trigger authorized run-watchdog operation. | Watchdog operation completes or reports failure with reason; final status reflects real speaker state. |  |  |
| REC-03 | Busy/conflict handling | Start one operation, then attempt conflicting repair if possible. | App/server show busy conflict or disabled controls; operations are not stacked blindly. |  |  |
| REC-04 | Stale status protection | Capture status, wait beyond freshness window or reboot Pi, then try a mutation. | Server rejects stale request with `stale_observation` or `boot_changed`; app refreshes status before retry. |  |  |
| REC-05 | Service restart recovery | In advanced diagnostics, restart a safe target service if authorized and agreed for test. | Operation is idempotent on retry; final status matches actual service result. |  |  |

## Spotify Handoff And Status

| ID | Check | Steps | Pass criteria | Result | Notes |
|---|---|---|---|---|---|
| SP-01 | Spotify status row | Open dashboard on configured Pi. | App shows Spotify as `connect_status_handoff`, with endpoint/readiness/status data from Pi, not Spotify credentials or user account data. |  |  |
| SP-02 | Open Spotify handoff | Tap `Open Spotify` or equivalent. | Spotify app opens when installed, or web fallback opens; Android does not initiate Spotify OAuth or playback transfer. |  |  |
| SP-03 | Expected endpoint playback | In Spotify, choose Indoor, Outdoor, and Whole House endpoints. | Pi status reflects active/ready endpoint where backend can observe it; route readiness and playback status are safe and non-secret. |  |  |
| SP-04 | Wrong active Spotify device | Play on phone/computer speaker instead of Pi endpoint. | App prompts user to choose Indoor/Outdoor/Whole House in Spotify; it does not transfer playback from Android. |  |  |
| SP-05 | Spotify service unavailable | Stop/fail one `librespot@*` service if practical. | Affected endpoint/service is degraded; restart-service is offered only when authorization and controls allow it. |  |  |
| SP-06 | Logged out/token issue if practical | Remove/revoke Pi Spotify authorization in a controlled test. | Pi reports safe account/session reason such as logged out or token expired; no token/user/track/playlist data appears in app, diagnostics, screenshots, or logs. |  |  |

## Troubleshooting Matrix

| Symptom | Likely cause | Quick checks | Expected recovery |
|---|---|---|---|
| App cannot find Pi | Wrong Wi-Fi/LAN, Pi off, bad host, API down | Confirm Android and Pi are on same LAN; try manual host/IP; request `/identity`; check Pi service status. | Restore network/API, then refresh or re-enter host. |
| `/identity` works but dashboard fails | Missing/invalid bearer token or `/status` auth failure | Retry `/status` with token; check app auth state; avoid exposing token in logs. | Re-enter/rescan token; app keeps host but disables protected controls until authorized. |
| Host is rejected as wrong device | Wrong host, Pi reinstall, changed/missing `controllerInstanceId`, changed `deviceId`, wrong `apiName` | Compare `/identity` tuple with saved pairing; verify host/IP is expected. | Use correct Pi or intentionally re-pair after confirming identity. |
| App shows API unavailable | Unsupported/missing `contractVersion`, incompatible API major version, malformed response, service down | Inspect `/identity`, `/health`, `/status`; check API version and contract version. | Update service/app compatibility or restart Pi API. |
| Health degraded after boot | Pi still booting, PipeWire/WirePlumber/librespot/watchdog/speaker degraded | Wait for booting state to settle; inspect status component rows and diagnostics summary. | Use targeted repair: reconnect speaker, run watchdog, or restart approved service. |
| Speaker paired but no audio sink | Missing Bluetooth audio plugin, PipeWire issue, sink renamed after re-pair | Check Pi setup, `wpctl status`, speaker assignment, and diagnostics. | Restart audio stack if approved; update configured sink mapping after re-pair. |
| Whole House stutters | Single Bluetooth radio overloaded or weak speaker connection | Compare Indoor/Outdoor alone vs Whole House; check adapter assignment. | Use one Bluetooth radio per speaker or improve speaker proximity/power. |
| Reconnect does not restore audio | Speaker off/out of range, connected to another phone, wrong adapter trust | Power-cycle speaker; ensure it is not paired/connected elsewhere; run watchdog; refresh status. | Reconnect trusted speaker on correct adapter; re-pair only if needed. |
| Operation rejected as stale | Dashboard observation expired or Pi rebooted | Check error code `stale_observation` or `boot_changed`. | Refresh `/status`, then retry only from current dashboard state. |
| Spotify endpoint missing | librespot service down, Spotify account issue, device on wrong network/account | Check Spotify app device list, Pi status Spotify endpoints, and service health. | Restart allowed `librespot@*` service or repair Pi-side Spotify account setup. |
| Spotify plays on wrong device | User selected phone/computer instead of Pi endpoint | Check Spotify active device and app status row. | Open Spotify and select Indoor, Outdoor, or Whole House manually. |
| Spotify account/token failure | Pi-side Spotify auth expired/revoked/logged out | Confirm safe reason code in status/diagnostics; ensure no secret values are exposed. | Follow Pi-side account repair instructions; Android does not run Spotify OAuth in Phase 3. |
