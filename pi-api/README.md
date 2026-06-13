# PiHouse Local API

Runnable FastAPI service for the Phase 3 Raspberry Pi local API contract.

Two adapter modes are available via `adapterMode` in the config:

- `stub`: hardware behavior is simulated. Use for development off the Pi and
  for the contract test suite. `config.example.json` ships with this mode.
- `real`: the service drives the actual Pi audio stack through allowlisted
  argument-array commands only (`systemctl --user`, `bluetoothctl`, `pactl`,
  and `/proc` reads). Use this on the Raspberry Pi.

`adapterMode` is a required field. A config without it refuses to load so an
unconfigured deploy never silently runs stub mode.

In real mode, enabling speaker systems maps to the librespot Spotify Connect
endpoint services: a Spotify endpoint runs only when every speaker system it
plays through is enabled (`indoor` -> `librespot@indoor`, `outdoor` ->
`librespot@outdoor`, both -> all three including `librespot@both`). Reconnect
runs `bluetoothctl connect` with the configured speaker MAC; run-watchdog
starts `bt-watchdog.service`; restart-service restarts only allowlisted units.
Health and status report real degraded states with reason codes such as
`pipewire_down`, `indoor_speaker_disconnected`, `whole_house_sink_missing`,
and `watchdog_inactive`.

## Run Locally On Windows

From `pi-api/`:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .[test]
$env:PIHOUSE_CONFIG="config.example.json"
.\.venv\Scripts\python -m uvicorn pihouse_api.app:app --host 127.0.0.1 --port 8765
```

For local testing, place the bearer token in `.env`:

```text
PISTREAM_API_TOKEN=<api-token>
```

The service also accepts `PISTREAM_API_TOKEN` from the process environment. If
neither is set, it falls back to the configured `tokenFile`. The example bearer
token is in `token.example` and is intentionally only for local scaffold
testing.

```powershell
curl http://127.0.0.1:8765/api/v1/identity
curl http://127.0.0.1:8765/api/v1/health
$token = (Get-Content .env | Where-Object { $_ -match '^PISTREAM_API_TOKEN=' }) -replace '^PISTREAM_API_TOKEN=', ''
curl -H "Authorization: Bearer $token" http://127.0.0.1:8765/api/v1/status
```

## Run On A Raspberry Pi (Real Mode)

One-command install. On a fresh Raspberry Pi OS Lite 64-bit (Bookworm) image
with SSH and Wi-Fi configured, copy this repo to the Pi (for example
`~/pihouse/`) and run:

```bash
cd ~/pihouse/pi-api
./deploy/setup-pi.sh
```

`deploy/setup-pi.sh` is idempotent. It installs every prerequisite the API does
not own, in the order needed:

- `pipewire`, `pipewire-pulse`, `wireplumber`, `libspa-0.2-bluetooth`,
  `pulseaudio-utils`, `bluez`, `python3-venv`, `python3-pip`, `curl`,
  `ca-certificates` via `apt`.
- `librespot` via the Raspotify installer, then disables the default
  `raspotify.service` so it does not fight the per-endpoint units.
- `loginctl enable-linger` for the target user so user services keep running
  after SSH logout.
- `~/.config/pipewire/pipewire.conf.d/combine.conf` for the `whole_house`
  PipeWire combine sink (auto-includes any `bluez_output.*` sink so the
  app-driven assign flow can rewrite speakers without re-editing this file).
- `~/.config/systemd/user/librespot@.service` template and the
  `~/.config/librespot/{indoor,outdoor,both}.env` files (only seeded when
  missing - the real adapter rewrites them during `assign-speaker`).
- `~/bin/bt-watchdog.sh`, `bt-watchdog.service`, and `bt-watchdog.timer` (the
  script reconnects every trusted device returned by
  `bluetoothctl devices Trusted`).
- `~/.config/pihouse-api/config.json` (copied from `config.real.example.json`
  with `adapterMode: "real"`).
- `~/.config/pihouse-api/token` (generated with `secrets.token_urlsafe(32)` if
  missing, mode `600`).
- A Python venv at `pi-api/.venv` with `pihouse_api` installed editable.
- `~/.config/systemd/user/pihouse-api.service`, enabled and started.

After the script finishes the service is reachable on `:8765`. The status
payload now exposes a top-level `adapterMode` field so the Android app can show
an honest banner instead of inferring the mode from health:

```bash
curl http://127.0.0.1:8765/api/v1/identity
curl http://127.0.0.1:8765/api/v1/health
curl -H "Authorization: Bearer $(cat ~/.config/pihouse-api/token)" \
     http://127.0.0.1:8765/api/v1/status
```

Enter the Pi's host/IP and the token from `~/.config/pihouse-api/token` in the
Android app. Pair the speakers from the app via `pair-speaker` and assign them
with `assign-speaker`; the real adapter rewrites the librespot env files and
restarts the affected user services on each assign.

If `adapterMode` ever reports `stub` from a real Pi, the active config at
`~/.config/pihouse-api/config.json` is wrong - either the file is missing
`"adapterMode": "real"` or `setup-pi.sh` was never run. Fix the config and
restart `pihouse-api.service`.

### Preflight Diagnostics

To see exactly which Pi-side preconditions are missing on the host (apt
packages, systemd user units, linger, librespot, combine sink, active config,
token, `librespot@.service` template):

```bash
python -m pihouse_api.bootstrap
```

The preflight exits 0 when everything required is present and prints a
per-check `OK`/`WARN`/`MISSING` summary. It runs from any working directory
and probes the host using the same kinds of checks the real adapter would do.

## Pi Notes

Keep the token file readable only by the service user. Bind the API to the
trusted LAN only, or use firewall/router rules to allow phone-to-Pi TCP `8765`
only on the local trusted network.

## Bluetooth Speaker Setup From The App

Speakers no longer need MACs hardcoded in the config. The app can drive the
whole flow against these endpoints (all bearer-auth):

- `GET /api/v1/bluetooth/devices?scanSeconds=0..20` - lists Bluetooth devices
  visible to the Pi; `scanSeconds > 0` runs a blocking discovery scan first.
- `POST /api/v1/operations/pair-speaker` - body adds `address` (MAC from a
  scan result). Pairs, trusts, and connects the device.
- `POST /api/v1/operations/assign-speaker` - body adds `speakerId`
  (`indoor`/`outdoor`), `address`, and optional `displayName` (e.g.
  "Indoor Downstairs"). Saves the assignment, rewrites the librespot env files
  and the whole-house combine sink config, and restarts the affected services
  so the change is live.

Assignments persist in `.state/speaker-assignments.json` and override the
optional `speakers.*.mac` config values. An unassigned speaker system reports
`speaker_unassigned` instead of pretending to be healthy.

Addresses are validated as strict MAC format and must already be visible to
the Pi (scan first); they are never passed to a shell.

The scaffold accepts only configured IDs:

- `speakerId`: `indoor`, `outdoor`
- `enabledSystemIds`: any combination of `indoor` and `outdoor`; use an empty
  array to turn all systems off
- `routeId`: `indoor`, `outdoor`, `both`; `whole_house` is accepted as an
  alias for `both`. This compatibility operation maps to `enabledSystemIds`.
- `serviceId`: `pipewire`, `wireplumber`, `librespot_indoor`,
  `librespot_outdoor`, `librespot_both`, `bt_watchdog`

Speaker-system selection uses:

```json
{
  "clientRequestId": "<uuid>",
  "observedBootId": "<boot id from /api/v1/status>",
  "observedAt": "<observedAt from /api/v1/status>",
  "enabledSystemIds": ["indoor", "outdoor"]
}
```

`POST /api/v1/operations/set-speaker-systems` accepts `["indoor"]`,
`["outdoor"]`, `["indoor", "outdoor"]`, or `[]`.

It does not accept raw systemd unit names, raw sink names, Bluetooth MAC
addresses, shell commands, tokens, Wi-Fi secrets, or unbounded logs from API
requests.
