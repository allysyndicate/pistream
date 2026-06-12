# PiStream Real-Device Setup Checklist

Use this checklist during the real-device setup session on Saturday, June 13,
2026. It is written for the person doing the manual setup, not for app or API
development.

Goal: confirm that one Raspberry Pi can expose three Spotify Connect playback
targets, route audio to two Bluetooth speakers, and be checked from the Android
companion app.

Playback targets:

- `Indoor`: indoor speaker only
- `Outdoor`: outdoor speaker only
- `Whole House`: both speakers

## 1. Required Equipment and Access

Confirm these before starting.

- [ ] Raspberry Pi 3, 4, 5, or Zero 2 W.
- [ ] Raspberry Pi OS Lite 64-bit, Bookworm, already flashed or ready to flash.
- [ ] Pi power supply.
- [ ] MicroSD card.
- [ ] Wi-Fi or Ethernet access for the Pi.
- [ ] Android phone on the same local network as the Pi.
- [ ] Two Bluetooth speakers, charged and available for pairing.
- [ ] Recommended: one Linux-compatible USB Bluetooth 5.0 dongle.
- [ ] Spotify Premium account signed in on the Android phone.
- [ ] Access to the router or Wi-Fi password.
- [ ] SSH access to the Pi, or a keyboard/monitor attached to the Pi.
- [ ] If Mobile provides an approved Android build: debug/internal APK file or
  link and install instructions.
- [ ] If Backend provides a Pi local API build artifact: install/start
  instructions and the approved bearer-token entry flow.

Record setup values:

```text
Pi hostname:
Pi IP address:
Pi username:
Bearer/API token entry method, if Backend provides one:
Indoor speaker name:
Outdoor speaker name:
Indoor speaker MAC, Pi-side only:
Outdoor speaker MAC, Pi-side only:
Indoor sink name:
Outdoor sink name:
```

Do not put speaker MAC addresses, Wi-Fi passwords, or bearer tokens into
screenshots or support messages.

## 2. Raspberry Pi Prep

Skip this section only if the Pi audio stack was already prepared and verified.

- [ ] Flash Raspberry Pi OS Lite 64-bit, Bookworm.
- [ ] Set hostname, preferably `audiopi` unless Backend gives a different final
  hostname.
- [ ] Enable SSH.
- [ ] Configure Wi-Fi, or connect Ethernet.
- [ ] Set the Pi username. Existing docs assume `pi`; if the username differs,
  commands that mention `pi` must be adjusted.
- [ ] Boot the Pi.
- [ ] Confirm the Android phone and Pi are on the same local network.
- [ ] SSH into the Pi.
- [ ] Update the Pi:

```bash
sudo apt update
sudo apt full-upgrade -y
sudo reboot
```

After the reboot:

- [ ] Reconnect to the Pi.
- [ ] Install audio packages:

```bash
sudo apt install -y pipewire pipewire-pulse wireplumber libspa-0.2-bluetooth pulseaudio-utils
```

- [ ] Keep user services running after SSH logout:

```bash
sudo loginctl enable-linger pi
```

- [ ] Confirm PipeWire is running:

```bash
systemctl --user status pipewire
```

- [ ] Install `librespot` through Raspotify, then disable the default Raspotify
  service:

```bash
curl -sL https://dtcooper.github.io/raspotify/install.sh | sh
sudo systemctl disable --now raspotify
which librespot
```

Expected result:

```text
/usr/bin/librespot
```

## 3. Local Network Setup

- [ ] Keep the phone on the same Wi-Fi/LAN as the Pi.
- [ ] Avoid guest Wi-Fi if it blocks device-to-device traffic.
- [ ] Disable phone VPN temporarily if it prevents LAN access.
- [ ] Disable router client isolation or move both devices to a trusted network.
- [ ] Confirm the Pi can be reached by hostname:

```bash
hostname -I
```

- [ ] Record the Pi IP address.
- [ ] Try `audiopi.local` from the phone or another computer if available.
- [ ] If hostname lookup fails, use the Pi IP address in the Android app.

Phase 3 does not require mDNS discovery. The Android app should work with a
saved host, `audiopi.local`, or a manual host/IP entry. The Android app's Pi
reachability check is `GET /identity`, followed by `GET /health` when
available; it must not depend on ICMP ping support from the phone or router.

## 4. Pair and Trust Bluetooth Speakers

Pair one speaker at a time. If using the USB Bluetooth dongle, pair each speaker
to a different Bluetooth adapter.

- [ ] Put only the first speaker into pairing mode.
- [ ] Start Bluetooth setup:

```bash
bluetoothctl
```

Inside `bluetoothctl`:

```text
list
select <ADAPTER_MAC>
power on
agent on
default-agent
scan on
```

When the first speaker appears:

```text
pair <SPEAKER_MAC>
trust <SPEAKER_MAC>
connect <SPEAKER_MAC>
```

- [ ] Repeat for the second speaker.
- [ ] If using two Bluetooth adapters, run `select <OTHER_ADAPTER_MAC>` before
  pairing the second speaker.
- [ ] Exit Bluetooth setup:

```text
exit
```

Acceptance checks:

- [ ] Indoor speaker is paired.
- [ ] Indoor speaker is trusted.
- [ ] Indoor speaker is connected.
- [ ] Outdoor speaker is paired.
- [ ] Outdoor speaker is trusted.
- [ ] Outdoor speaker is connected.
- [ ] If using the USB dongle, each speaker is paired to the intended adapter.

## 5. Audio Routing Setup

- [ ] List audio sinks:

```bash
wpctl status
pactl list short sinks
```

- [ ] Record the exact Bluetooth sink names:

```text
INDOOR_SINK_NAME=
OUTDOOR_SINK_NAME=
```

- [ ] Create the PipeWire config directory:

```bash
mkdir -p ~/.config/pipewire/pipewire.conf.d
```

- [ ] Create this file:

```bash
nano ~/.config/pipewire/pipewire.conf.d/combine.conf
```

Paste this, replacing the two sink placeholders exactly:

```text
context.modules = [
  { name = libpipewire-module-combine-stream
    args = {
      combine.mode = sink
      node.name = "whole_house"
      node.description = "Whole House"
      combine.latency-compensate = true
      combine.props = { audio.position = [ FL FR ] }
      stream.rules = [
        { matches = [ { media.class = "Audio/Sink" node.name = "<INDOOR_SINK_NAME>" } ]
          actions = { create-stream = { } } }
        { matches = [ { media.class = "Audio/Sink" node.name = "<OUTDOOR_SINK_NAME>" } ]
          actions = { create-stream = { } } }
      ]
    }
  }
]
```

- [ ] Restart PipeWire:

```bash
systemctl --user restart pipewire wireplumber
```

- [ ] Confirm `whole_house` appears:

```bash
wpctl status
```

## 6. Spotify Connect Services

- [ ] Create service config folders:

```bash
mkdir -p ~/.config/systemd/user
mkdir -p ~/.config/librespot
```

- [ ] Create the service template:

```bash
nano ~/.config/systemd/user/librespot@.service
```

Paste:

```ini
[Unit]
Description=librespot (%i)
After=pipewire.service network-online.target

[Service]
EnvironmentFile=%h/.config/librespot/%i.env
ExecStart=/usr/bin/librespot --backend pulseaudio --name "${SPEAKER_NAME}" --bitrate 320 --disable-audio-cache
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

- [ ] Create `~/.config/librespot/indoor.env`:

```text
SPEAKER_NAME=Indoor
PULSE_SINK=<INDOOR_SINK_NAME>
```

- [ ] Create `~/.config/librespot/outdoor.env`:

```text
SPEAKER_NAME=Outdoor
PULSE_SINK=<OUTDOOR_SINK_NAME>
```

- [ ] Create `~/.config/librespot/both.env`:

```text
SPEAKER_NAME=Whole House
PULSE_SINK=whole_house
```

- [ ] Enable the three Spotify Connect endpoints:

```bash
systemctl --user daemon-reload
systemctl --user enable --now librespot@indoor librespot@outdoor librespot@both
```

- [ ] Confirm all three are running:

```bash
systemctl --user status librespot@indoor
systemctl --user status librespot@outdoor
systemctl --user status librespot@both
```

## 7. Bluetooth Reconnect Watchdog

- [ ] Create a local bin directory:

```bash
mkdir -p ~/bin
```

- [ ] Create the watchdog script:

```bash
nano ~/bin/bt-watchdog.sh
```

Paste this, replacing only the Pi-side MAC placeholders:

```bash
#!/bin/bash
for mac in <INDOOR_MAC> <OUTDOOR_MAC>; do
  if ! bluetoothctl info "$mac" | grep -q "Connected: yes"; then
    bluetoothctl connect "$mac"
  fi
done
```

- [ ] Make it executable:

```bash
chmod +x ~/bin/bt-watchdog.sh
```

- [ ] Create `~/.config/systemd/user/bt-watchdog.service`:

```ini
[Unit]
Description=Reconnect trusted Bluetooth speakers

[Service]
Type=oneshot
ExecStart=%h/bin/bt-watchdog.sh
```

- [ ] Create `~/.config/systemd/user/bt-watchdog.timer`:

```ini
[Unit]
Description=Run Bluetooth reconnect watchdog every minute

[Timer]
OnBootSec=30
OnUnitActiveSec=60
Unit=bt-watchdog.service

[Install]
WantedBy=timers.target
```

- [ ] Enable the watchdog:

```bash
systemctl --user daemon-reload
systemctl --user enable --now bt-watchdog.timer
```

## 8. Pi Local API Setup

Complete this section only if Backend provides the real API build artifact or
install steps. As of the June 12, 2026 handoff, no runnable Pi local API service
scaffold, final install command, or token provisioning CLI/file format is
available in this workspace. Treat API checks as contract targets, mocks, or
blocked checks until Backend supplies a build.

Expected base URL:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

Operator checks:

- [ ] Backend has provided API install/start instructions. Do not run or publish
  a `systemctl enable/start pi-local-api` command unless Backend supplies it.
- [ ] Backend has provided the bearer token or approved token-entry flow. Do not
  invent a token file path, QR flow, or generation command.
- [ ] The API service is running.
- [ ] `GET /identity` works without a bearer token on the trusted LAN.
- [ ] The identity response has:
  - [ ] `apiName=pihouse-audio-api`
  - [ ] compatible `apiVersion=1.x.y`
  - [ ] `contractVersion=2026-06-phase3`
  - [ ] non-empty `deviceId`
  - [ ] non-empty `controllerInstanceId`
- [ ] `GET /health` returns `healthy`, `booting`, `degraded`, or `error`.
- [ ] `GET /status` works after authorization.

Target network behavior, once the build exists: LAN-local HTTP API on port
`8765`, reachable from the Android phone on the same trusted LAN. Do not expose
the API publicly. Firewall/router rules should allow phone-to-Pi TCP `8765`
only on the local trusted network.

Do not continue Android trust/pairing if the host is a wrong device or the API
contract is unsupported.

## 9. Android App Install

Final Mobile assumption for June 13: there is no release APK, Play Store track,
Firebase App Distribution link, or checked-in Android build artifact in this
workspace. Treat Android real-device execution as blocked unless Mobile provides
an approved debug/internal test APK out of band before the session.

If Mobile provides an approved debug/internal APK:

- [ ] Install only that approved APK on the Android phone.
- [ ] Record the APK filename/version/build label in the session notes.
- [ ] Do not install a release APK, public app-store build, or APK from an
  unapproved link.
- [ ] Keep the phone on the same local network as the Pi.
- [ ] Open the app.
- [ ] If prompted for host, enter either:

```text
audiopi.local
```

or the recorded Pi IP address.

- [ ] First-run should start in an unpaired setup state, then allow discovery or
  manual host/IP entry, identity verification, authorization-required token
  entry, and dashboard/status after trust and authorization succeed.
- [ ] Enter the Backend-provided bearer token exactly as provided. Do not claim
  QR scanning, automatic token provisioning, login, refresh token, or token
  renewal exists unless the provided build proves it.
- [ ] Confirm the app identifies the Pi audio controller.
- [ ] Confirm the app shows the dashboard instead of `wrong_device` or
  `api_unavailable`.

Expected Android permissions for Phase 3:

- `INTERNET` is expected.
- `ACCESS_NETWORK_STATE` is expected.
- `ACCESS_WIFI_STATE` is optional if the app uses Wi-Fi state.
- Bluetooth permissions are not expected.
- `POST_NOTIFICATIONS` is not expected.
- Location permission is not expected.
- Multicast permission is not expected for Phase 3.

## 10. Pairing and Trust Flow in the App

- [ ] Start with the app unpaired or forget the old pairing if this is a fresh
  setup.
- [ ] Let the app ping the Pi with `GET /identity`. Android should not depend on
  ICMP ping.
- [ ] After identity passes, let the app call `GET /health` for readiness.
- [ ] Confirm the displayed Pi looks like the intended audio controller.
- [ ] Authorize with manual bearer-token entry when authorization is required.
- [ ] Save/pair the Pi in the app.
- [ ] Confirm the app loads status.

Trusted identity is based on the Pi API identity values, not the hostname alone.
The hostname or IP address may change later without breaking trust if the saved
identity still matches. The identity response includes `apiName`, `apiVersion`,
`contractVersion`, `deviceId`, and required non-null `controllerInstanceId`.
The saved trust tuple is `apiName`, `contractVersion`, `deviceId`, and
`controllerInstanceId`; `apiVersion` is checked for compatibility.

Failure handling expectations:

- `not_found`: no saved, default, or manual Pi target can be reached.
- `api_unavailable`: a host responds but the API is down, malformed,
  incompatible, missing an expected contract field, or uses an unsupported API
  major version/contract version.
- `wrong_device`: compatibility passes, but the saved identity tuple does not
  match the trusted Pi.
- `unauthorized`: protected status, diagnostics, or operation calls lack a
  valid bearer token.
- Degraded status: the app found the right Pi, but Pi, speaker, Spotify,
  PipeWire, watchdog, or route health needs attention.

## 11. Spotify Handoff and Status

The Android app does not stream Spotify audio and does not control Spotify
playback directly in Phase 3. Playback selection happens in Spotify. For June
13, use normal librespot/Spotify Connect behavior unless Backend provides a new
Pi-side setup flow.

- [ ] Open Spotify on the Android phone.
- [ ] Open the Spotify device picker.
- [ ] Confirm these devices appear:
  - [ ] `Indoor`
  - [ ] `Outdoor`
  - [ ] `Whole House`
- [ ] In the companion app, confirm Spotify status is visible if the API
  provides it. Fields may be `unknown` or `null` unless the Pi has safe local
  evidence.
- [ ] If the companion app says the active device is another Spotify device,
  open Spotify and choose `Indoor`, `Outdoor`, or `Whole House`.
- [ ] If the app says Spotify is logged out, token expired, or not configured,
  repair normal Pi-side Spotify Connect/librespot setup. Android does not run
  Spotify OAuth, token refresh, playback transfer, queue control, or account
  repair in Phase 3.

## 12. Verification Checklist

Use this section before calling the setup complete. For a table with result and
notes columns, use `phase3-manual-smoke-test-checklist.md` during the live run.
Record each item as pass, fail, skipped, or blocked.

### 12.1 Pi reachability and API

If Backend has not provided a build artifact, mark these checks `BLOCKED` or
run them only against an approved mock/stub contract target.

- [ ] `PI-01`: Request
  `http://<host>:8765/api/v1/identity`.
- [ ] Confirm HTTP `200` and these identity fields:
  - [ ] `apiName=pihouse-audio-api`
  - [ ] `contractVersion=2026-06-phase3`
  - [ ] compatible `apiVersion=1.x.y`
  - [ ] non-empty `deviceId`
  - [ ] non-null `controllerInstanceId`
- [ ] `PI-02`: Request `/api/v1/health`.
- [ ] Confirm health is one of `booting`, `healthy`, `degraded`, or `error`.
- [ ] `PI-03`: Request `/api/v1/status` without a token, then with the approved
  token.
- [ ] Confirm missing token returns `401`.
- [ ] Confirm valid token returns status with identity, health, services,
  speakers, sinks, Spotify, watchdog, controls, and observations.
- [ ] `PI-04`: Try a known-bad host or stop the API if safe to do so.
- [ ] Confirm the app shows `not_found` or `api_unavailable` and does not expose
  protected controls.

### 12.2 Android install and permissions

If Mobile has not provided an approved debug/internal APK, mark these
checks `BLOCKED`.

- [ ] `AND-01`: Install the approved Android test build.
- [ ] Confirm the app launches and shows setup, discovery, or manual host entry.
- [ ] `AND-02`: Check Android app permission prompts and system app settings.
- [ ] Confirm network/Wi-Fi state permissions are the only expected Phase 3
  permissions.
- [ ] Confirm Bluetooth permissions are not requested for Phase 3.
- [ ] Confirm `POST_NOTIFICATIONS` is not requested for Phase 3.
- [ ] `AND-03`: Enter the Pi as a bare hostname/IP and, if practical, with an
  `http://` prefix.
- [ ] Confirm the app normalizes the target to
  `http://<host>:8765/api/v1` and does not require mDNS.
- [ ] `AND-04`: Enter the approved token.
- [ ] Confirm a valid token reaches dashboard/status after identity trust.
- [ ] Confirm an invalid token shows an authorization-required state without
  losing the saved host.

### 12.3 Pairing and trust

- [ ] `TRUST-01`: Clear app data, enter the real Pi host, accept identity, and
  enter the token.
- [ ] Confirm the app saves the trusted tuple: `apiName`, `contractVersion`,
  `deviceId`, and `controllerInstanceId`.
- [ ] `TRUST-02`: Relaunch the app with the saved host and same Pi.
- [ ] Confirm the app revalidates identity before dashboard actions and reaches
  `found_healthy` or `found_unhealthy`.
- [ ] `TRUST-03`: Use Pi IP instead of hostname, or hostname instead of IP.
- [ ] Confirm matching identity stays trusted and hostname/IP alone does not
  trigger `wrong_device`.
- [ ] `TRUST-04`, if practical: point the app at a mock or alternate service
  with wrong `apiName`, changed `deviceId`, changed `controllerInstanceId`, or
  missing/null `controllerInstanceId`.
- [ ] Confirm the app maps this to `wrong_device` and blocks health, status,
  diagnostics, and mutating controls for that host.
- [ ] `TRUST-05`, if practical: use a missing or unsupported
  `contractVersion`, or unsupported API major version.
- [ ] Confirm the app maps this to `api_unavailable`, not `wrong_device`.

### 12.4 Bluetooth routes

- [ ] `BT-01`: Open the dashboard after valid status.
- [ ] Confirm Indoor and Outdoor speaker rows show paired, trusted, connected,
  and assigned state, or explicit degradation.
- [ ] `BT-02`: In Spotify or the supported source, select `Indoor`.
- [ ] Confirm audio plays on the indoor speaker only.
- [ ] `BT-03`: Select `Outdoor`.
- [ ] Confirm audio plays on the outdoor speaker only.
- [ ] `BT-04`: Select `Whole House`.
- [ ] Confirm audio plays on both speakers without severe stutter or dropout
  beyond known hardware limits.
- [ ] `BT-05`: Power off or disconnect one speaker while watching status.
- [ ] Confirm the affected speaker, sink, or route becomes degraded and no
  broken route is shown as fully healthy.

### 12.5 Reconnect, watchdog, and stale-operation protection

- [ ] `REC-01`: With one speaker disconnected, trigger authorized reconnect for
  that speaker.
- [ ] Confirm the operation returns accepted/running state, polling reaches a
  terminal state or timeout, and the app refreshes `/status`.
- [ ] `REC-02`: Trigger authorized run-watchdog.
- [ ] Confirm the watchdog completes or reports a clear failure reason, and
  final status reflects the real speaker state.
- [ ] `REC-03`, if practical: start one operation, then attempt a conflicting
  repair.
- [ ] Confirm the app/server show busy conflict or disabled controls instead of
  stacking operations blindly.
- [ ] `REC-04`: Wait beyond the freshness window or reboot the Pi, then try a
  mutation from an old dashboard state.
- [ ] Confirm the server rejects the request with `stale_observation` or
  `boot_changed`, and the app refreshes status before retry.
- [ ] `REC-05`, if authorized for the run: restart a safe target service from
  advanced diagnostics.
- [ ] Confirm the operation is idempotent on retry and final status matches the
  actual service result.

### 12.6 Spotify handoff and status

Phase 3 Spotify behavior is handoff/status only. Android opens Spotify and
displays safe Pi-reported Spotify status. Android does not own Spotify auth,
OAuth, token refresh, playback transfer, queue control, streaming, or mutating
Spotify API calls. For June 13 setup, use normal librespot/Spotify Connect
behavior; richer account/device/playback fields may be `unknown` or `null`
unless the Pi has safe local evidence.

- [ ] `SP-01`: Open the dashboard on the configured Pi.
- [ ] Confirm the app shows Spotify as `connect_status_handoff`, with endpoint,
  readiness, and status data from the Pi.
- [ ] Confirm the app does not expose Spotify credentials, tokens, user account
  data, tracks, playlists, or queue data.
- [ ] `SP-02`: Tap `Open Spotify` or the equivalent app action.
- [ ] Confirm Spotify opens, or the web fallback opens if Spotify is not
  installed.
- [ ] Confirm Android does not initiate Spotify OAuth or playback transfer.
- [ ] `SP-03`: In Spotify, choose `Indoor`, `Outdoor`, and `Whole House`.
- [ ] Confirm Pi status reflects the active/ready endpoint where Backend can
  observe it, and route readiness/playback status stays safe and non-secret.
- [ ] `SP-04`: Play on the phone or another computer instead of a Pi endpoint.
- [ ] Confirm the app prompts the operator to choose `Indoor`, `Outdoor`, or
  `Whole House` in Spotify, without transferring playback from Android.
- [ ] `SP-05`, if practical: stop or fail one `librespot@*` service.
- [ ] Confirm the affected endpoint/service is degraded and restart-service is
  offered only when authorization and controls allow it.
- [ ] `SP-06`, if practical and approved: remove or revoke Pi-side Spotify
  authorization.
- [ ] Confirm the Pi reports only a safe account/session reason, such as logged
  out or token expired.
- [ ] Confirm no token, user, track, playlist, or queue data appears in the app,
  diagnostics, screenshots, or logs.

## 13. Troubleshooting

| Symptom | First check | Manual action |
| --- | --- | --- |
| App cannot find Pi | Phone and Pi may be on different networks | Put both on the same LAN; enter the Pi IP manually. |
| `/identity` works but dashboard fails | Token is missing, invalid, or not being sent to protected endpoints | Re-enter the approved token; do not expose it in logs or screenshots. |
| `audiopi.local` fails | Hostname or mDNS resolution failed | Use the recorded Pi IP; mDNS is not required for Phase 3. |
| App says wrong device | Host is compatible but not the saved Pi audio controller | Compare `apiName`, `contractVersion`, `deviceId`, and `controllerInstanceId`; do not authorize controls for that host. |
| App says API unavailable | Pi API may be stopped, unsupported, malformed, or blocked | Ask Backend to check the API service, contract version, API version, and port `8765`. |
| App asks for authorization | Token is missing or invalid | Re-enter the approved token. |
| Dashboard controls are disabled | Unauthorized, stale status, degraded route, wrong device, API unavailable, or operation already running | Refresh status, reauthorize if needed, wait for active operations, or repair the Pi-side issue shown by status. |
| Health stays degraded after boot | Pi service, audio stack, speaker, or watchdog is degraded | Wait for boot to settle, inspect component rows, then use targeted repair. |
| Spotify devices do not appear | Phone cannot discover Spotify Connect endpoints | Confirm phone/Pi same LAN; check `librespot@indoor`, `librespot@outdoor`, and `librespot@both`. |
| Speaker connects but no audio sink appears | Bluetooth audio plugin or PipeWire issue | Confirm `libspa-0.2-bluetooth` is installed; restart PipeWire/WirePlumber. |
| `Whole House` missing | Combined sink config issue | Recheck exact sink names in `combine.conf`; restart PipeWire/WirePlumber. |
| One speaker does not reconnect | Speaker not trusted, off, or out of range | Power speaker on; run watchdog; reconnect from app if available. |
| Whole House stutters | One Bluetooth radio may be overloaded | Use the USB Bluetooth dongle and pair one speaker per adapter. |
| App action says stale or boot changed | Pi rebooted or dashboard status is old | Refresh status, then retry the action. |
| App action says busy | Another operation is running | Wait for the operation to finish, then refresh status. |
| Spotify says playing elsewhere | Wrong active Spotify device | Open Spotify and select `Indoor`, `Outdoor`, or `Whole House`. |
| Spotify endpoint is degraded | `librespot@*` service is stopped or Spotify Connect endpoint is unavailable | Restart only an approved `librespot@*` service or follow Backend's Pi-side repair instructions. |
| Spotify says logged out or token expired | Pi-side Spotify Connect/librespot setup needs repair | Repair Pi-side Spotify setup manually; Android does not run Spotify OAuth in Phase 3. |

## 14. Current Build and Handoff Status

Mobile status for June 13:

- No Android Gradle project, APK, release APK, Play Store track, Firebase App
  Distribution link, or approved install channel is checked into this workspace
  as of June 12, 2026.
- Final install assumption: Android real-device execution is blocked unless
  Mobile provides an approved debug/internal test APK out of band before the
  session. Do not substitute an unapproved release APK or public app-store
  install.
- Expected first-run flow is unpaired setup/discovery/manual host entry,
  identity verification, authorization-required bearer-token entry, then
  dashboard/status after trust and authorization succeed.
- Token scanning behavior is not finalized. Use re-enter wording as the baseline
  recovery text; use QR/scanner wording only if the provided build proves that
  support.
- Phase 3 Android must not request Bluetooth, notification, location, or
  multicast permissions.

Backend status for June 13:

- No runnable Pi local API service scaffold, final install/start command, or
  token provisioning format is available in this workspace as of June 12, 2026.
- Keep only the existing Pi audio/librespot setup commands as real setup steps.
  Label local API service checks as pending build artifact, blocked, or
  mock/stub checks.
- Hostname/IP may change and must not be the trust anchor.
- Device trust uses `/api/v1/identity`: `apiName`, `apiVersion`,
  `contractVersion`, `deviceId`, and required non-null
  `controllerInstanceId`.
- Target API behavior is LAN-local HTTP on TCP `8765`, reachable from the phone
  on the same trusted LAN and not exposed publicly.
- Spotify setup uses normal librespot/Spotify Connect behavior. Android does
  not own Spotify OAuth, token refresh, playback control, queue control, account
  repair, or Spotify API control in Phase 3.

QA has provided `phase3-manual-smoke-test-checklist.md`. Use it as the run log
for Section 12 results.

## 15. Stop Conditions

Stop and ask for technical help if any of these happen:

- The Pi cannot join the local network.
- Neither speaker can pair or trust through `bluetoothctl`.
- Bluetooth speakers connect but no audio sinks appear after installing
  `libspa-0.2-bluetooth` and restarting PipeWire.
- `Indoor`, `Outdoor`, and `Whole House` do not appear in Spotify after services
  are running.
- If API testing is in scope, `/identity`, `/health`, or `/status` is missing or
  malformed.
- If API testing is in scope, identity is missing `controllerInstanceId` or has
  the wrong `contractVersion`.
- The Android app identifies the Pi as `wrong_device`.
- The Android app requests out-of-scope Bluetooth, notification, location, or
  multicast permissions.
- Route commands can run against the wrong Pi or stale identity.
- The API returns an unsupported contract version.
- A token, credential, Wi-Fi password, raw MAC address, or unbounded journal
  output appears in logs, screenshots, or diagnostics output.
