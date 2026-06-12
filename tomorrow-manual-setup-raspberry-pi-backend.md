# Tomorrow Manual Setup Guide: Raspberry Pi and Backend

Use this section after the Raspberry Pi audio setup is complete. It is written
for a manual setup session, so every step has a command, expected result, and
what to try next if the check fails.

The Android phone and Raspberry Pi must be on the same trusted home LAN. The Pi
owns Bluetooth pairing, PipeWire routing, Spotify Connect endpoints, watchdogs,
and recovery commands. Android is only a setup, status, and handoff companion.

## 1. Confirm Pi Service Readiness

1. SSH into the Pi.
2. Confirm the Pi user services can run without an open SSH session:

```bash
loginctl show-user "$USER" -p Linger
```

Expected result:

```text
Linger=yes
```

If it says `Linger=no`, enable it:

```bash
sudo loginctl enable-linger "$USER"
```

3. Check the core audio services:

```bash
systemctl --user is-active pipewire
systemctl --user is-active wireplumber
```

Expected result:

```text
active
active
```

4. Check Spotify Connect endpoint services:

```bash
systemctl --user is-active librespot@indoor
systemctl --user is-active librespot@outdoor
systemctl --user is-active librespot@both
```

Expected result:

```text
active
active
active
```

5. Check the Bluetooth reconnect watchdog:

```bash
systemctl --user is-active bt-watchdog.timer
systemctl --user status bt-watchdog.service --no-pager
```

Expected result:

- `bt-watchdog.timer` is `active`.
- `bt-watchdog.service` is either `inactive` after a successful oneshot run or
  shows the most recent successful run.

## 2. Start or Verify the Local API

The planned local API is:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

1. Check whether the API service is running:

```bash
systemctl --user is-active pihouse-api.service
```

Expected result:

```text
active
```

2. If the service is installed but not running, restart it:

```bash
systemctl --user restart pihouse-api.service
systemctl --user status pihouse-api.service --no-pager
```

3. If the service is not installed yet, record this as a setup blocker for the
   Android status workflow. The audio system can still be tested manually, but
   the Android companion cannot verify status or trigger recovery actions until
   this API exists.

4. Confirm the API is listening on port `8765`:

```bash
ss -ltnp | grep 8765
```

Expected result: a listener on `127.0.0.1:8765`, the Pi LAN IP on port `8765`,
or `0.0.0.0:8765` if firewall rules restrict access to the home subnet.

## 3. Run Local API and Status Checks

Run these checks from the Pi first.

1. Identity check:

```bash
curl -s http://127.0.0.1:8765/api/v1/identity
```

Expected fields:

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

Expected compatibility values:

```text
apiName=pihouse-audio-api
contractVersion=2026-06-phase3
apiVersion=1.x.y
```

2. Health check:

```bash
curl -s http://127.0.0.1:8765/api/v1/health
```

Expected result: `state` is `healthy`, `degraded`, `booting`, or `error`.

Use `healthy` as the target state before Android handoff. `degraded` can be
acceptable during troubleshooting if the response clearly identifies the failed
component.

3. Authenticated status check:

```bash
TOKEN="<api-token>"
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/api/v1/status
```

Expected status content:

- Identity fields match `/identity`.
- `reboot.bootId` is present.
- PipeWire and WirePlumber services are present.
- Indoor and outdoor speaker rows are present.
- Indoor, outdoor, and whole-house sinks are present.
- Indoor, outdoor, and whole-house Spotify endpoints are present.
- Watchdog status is present.
- Spotify summary uses `connect_status_handoff`.

If this returns `401`, the token is missing or wrong. Re-enter the Pi API token
before testing Android controls.

## 4. Check Network Requirements

1. From the Android phone, connect to the same Wi-Fi or LAN as the Pi.
2. Confirm the Pi address:

```bash
hostname -I
hostname
```

3. From another device on the same LAN, test:

```bash
curl -s http://<pi-ip>:8765/api/v1/identity
```

Expected result: the same identity JSON returned locally on the Pi.

Network requirements:

- Phone and Pi must be on the same trusted LAN.
- Port `8765/tcp` must be reachable from the phone.
- Client isolation on the Wi-Fi network must be disabled.
- VPN or guest Wi-Fi modes may block local LAN traffic.
- `audiopi.local` may work, but manual IP entry must also work.
- mDNS is helpful for discovery but is not required for Phase 3.

If the phone cannot reach the Pi by hostname, use the Pi IP address for manual
setup.

## 5. Verify Bluetooth and Audio Routing Assumptions

1. List Bluetooth adapters:

```bash
bluetoothctl list
```

Expected result: one adapter is acceptable, but two adapters are preferred for
more reliable whole-house playback.

2. Check each configured speaker:

```bash
bluetoothctl info <INDOOR_MAC>
bluetoothctl info <OUTDOOR_MAC>
```

Expected result for each speaker:

```text
Paired: yes
Trusted: yes
Connected: yes
```

3. Check available PipeWire sinks:

```bash
pactl list short sinks
wpctl status
```

Expected result:

- One sink for the indoor Bluetooth speaker.
- One sink for the outdoor Bluetooth speaker.
- One combined sink named `whole_house`.

4. If a Bluetooth speaker was re-paired, re-check sink names. Re-pairing can
   change generated `bluez_output...` names, which means the Pi config,
   `combine.conf`, and local API config must be updated together.

## 6. Run Dual-Stream Behavior Checks

Spotify can play to one selected Connect endpoint at a time. The `Whole House`
endpoint is the dual-speaker path because it routes one Spotify stream into the
Pi's combined `whole_house` sink.

1. Open Spotify on the phone or desktop.
2. Confirm these devices appear in Spotify's device picker:

```text
Indoor
Outdoor
Whole House
```

3. Select `Indoor`.

Expected result: only the indoor speaker plays.

4. Select `Outdoor`.

Expected result: only the outdoor speaker plays.

5. Select `Whole House`.

Expected result: both speakers play from the same Spotify stream.

6. Let `Whole House` play for at least 10 minutes during setup.

Watch for:

- Stutter.
- One speaker dropping out.
- Obvious echo or delay.
- Spotify switching to a different active device.

If both speakers use one Bluetooth radio and whole-house playback stutters,
move one speaker to a USB Bluetooth adapter and pair it again on that adapter.

## 7. Spotify Connect, Status, and Handoff Boundaries

For tomorrow's manual setup, treat Spotify as a Pi-owned Connect setup plus an
Android handoff.

Android should:

- Show Pi-reported Spotify endpoint readiness.
- Open Spotify so the user can pick `Indoor`, `Outdoor`, or `Whole House`.
- Show when Spotify appears to be using another active device, if the Pi knows.
- Offer Pi recovery actions for route or service failures.

Android should not:

- Store Spotify credentials.
- Run Spotify OAuth.
- Transfer playback through the Spotify Web API.
- Stream Spotify audio.
- Decide Bluetooth or PipeWire routes.

Expected `/status` Spotify boundary:

- `spotify.integrationMode` is `connect_status_handoff`.
- `spotify.connectOwnedBy` is `pi`.
- `spotifyEndpoints[]` includes `indoor`, `outdoor`, and `both`.
- `spotify.routeReadiness[]` has one row per endpoint.
- `spotify.recommendedAction` is usually `open_spotify`,
  `refresh_status`, `view_diagnostics`, `restart_endpoint_service`, or `none`.
- Account, active-device, and playback details may be `unknown` unless the Pi
  has safe local or Pi-owned Web API evidence.

If Spotify devices do not appear, first verify the `librespot@*` services and
the phone/Pi network. Do not try to fix this from Android OAuth.

## 8. Recovery and Restart Steps

Use the least disruptive recovery first.

1. Refresh status:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/api/v1/status
```

2. Run the Bluetooth watchdog:

```bash
systemctl --user start bt-watchdog.service
```

API equivalent:

```text
POST /api/v1/operations/run-watchdog
```

3. Reconnect one configured speaker:

```bash
bluetoothctl connect <SPEAKER_MAC>
```

API equivalent:

```text
POST /api/v1/operations/reconnect
```

4. Restart one Spotify endpoint:

```bash
systemctl --user restart librespot@indoor
systemctl --user restart librespot@outdoor
systemctl --user restart librespot@both
```

API equivalent:

```text
POST /api/v1/operations/restart-service
```

5. Restart PipeWire and WirePlumber only when sinks are missing or audio routing
   is broken:

```bash
systemctl --user restart pipewire wireplumber
```

6. Reboot the Pi only after service-level recovery fails:

```bash
sudo reboot
```

After any recovery action, re-run `/health` and `/status`. Android controls
must use the latest `bootId` and `observedAt`; stale controls should be
rejected by the Pi.

## 9. Likely Failure Modes

| Symptom | Likely cause | Recovery |
| --- | --- | --- |
| Android cannot find the Pi | Phone and Pi are on different networks, guest Wi-Fi isolation is enabled, port `8765` is blocked, or hostname resolution failed | Use the Pi IP address, disable client isolation, confirm `curl http://<pi-ip>:8765/api/v1/identity` works |
| `/identity` works but `/status` returns `401` | Missing or wrong bearer token | Re-enter the Pi API token and retry `/status` |
| API returns unsupported version | `contractVersion` or API major version does not match Phase 3 | Update the Pi API or Android fixture; classify as `api_unavailable` |
| API reports wrong device | `apiName`, saved `deviceId`, or saved `controllerInstanceId` does not match the paired Pi | Verify the hostname/IP and do not send controls to that host |
| PipeWire or WirePlumber is inactive | Audio stack failed to start | Restart the failed service; inspect `journalctl --user -u pipewire -u wireplumber` |
| Bluetooth speaker is paired but disconnected | Speaker is off, out of range, attached to another device, or adapter ownership changed | Power on speaker, disconnect other hosts, run watchdog or `bluetoothctl connect` |
| Bluetooth sink is missing | Speaker disconnected or `libspa-0.2-bluetooth`/PipeWire Bluetooth support is missing | Reconnect speaker, verify package install, restart PipeWire/WirePlumber |
| `whole_house` sink is missing | `combine.conf` has stale sink names or PipeWire did not load the module | Re-run `pactl list short sinks`, update `combine.conf`, restart PipeWire/WirePlumber |
| Spotify devices do not appear | `librespot` services stopped, phone and Pi are not on same LAN, or Spotify account/device discovery is delayed | Restart `librespot@*`, confirm network, reopen Spotify device picker |
| Spotify plays on another device | User selected a non-Pi Spotify device | Open Spotify and choose `Indoor`, `Outdoor`, or `Whole House` |
| Whole-house playback stutters | One Bluetooth radio is overloaded or speakers are near range limits | Use a USB Bluetooth adapter and pair one speaker per adapter |
| Whole-house playback has echo | Speaker latency differs | Tune PipeWire/Pulse latency offset and retest |
| Recovery action returns stale observation | Pi rebooted or Android status is old | Refresh status and retry with the new `bootId` and `observedAt` |
| Diagnostics logs expose too much data | Log endpoint is not filtering output | Restrict logs to allowlisted units and redact tokens, MACs, SSIDs, commands, and raw stderr |

## 10. Manual Setup Exit Criteria

The Pi/backend side is ready for Android handoff when all of these are true:

- `pipewire` and `wireplumber` are active.
- `librespot@indoor`, `librespot@outdoor`, and `librespot@both` are active.
- `bt-watchdog.timer` is active.
- `/identity` returns the expected Phase 3 identity fields.
- `/health` returns `healthy`, or any `degraded` result has an understood
  component reason.
- Authenticated `/status` returns speakers, sinks, Spotify endpoints, watchdog,
  and operation metadata.
- Phone can reach `http://<pi-ip>:8765/api/v1/identity`.
- Spotify shows `Indoor`, `Outdoor`, and `Whole House`.
- `Indoor` plays only indoor audio.
- `Outdoor` plays only outdoor audio.
- `Whole House` plays both speakers from one Spotify stream.
- Restart and recovery steps have been tested at least once without requiring
  Android to parse SSH output or own audio routing.
