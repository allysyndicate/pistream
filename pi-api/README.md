# PiHouse Local API Scaffold

Runnable FastAPI scaffold for the Phase 3 Raspberry Pi local API contract.
Hardware, PipeWire, Bluetooth, librespot, and watchdog behavior is stubbed
behind allowlisted adapters in this phase.

## Run Locally On Windows

From `pi-api/`:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .[test]
$env:PIHOUSE_CONFIG="config.example.json"
.\.venv\Scripts\python -m uvicorn pihouse_api.app:app --host 127.0.0.1 --port 8765
```

The example bearer token is in `token.example` and is intentionally only for
local scaffold testing.

```powershell
curl http://127.0.0.1:8765/api/v1/identity
curl http://127.0.0.1:8765/api/v1/health
curl -H "Authorization: Bearer dev-phase3-token" http://127.0.0.1:8765/api/v1/status
```

## Run On A Raspberry Pi

From `pi-api/`:

```bash
python3 -m venv .venv
./.venv/bin/python -m pip install -e .
PIHOUSE_CONFIG=config.example.json ./.venv/bin/python -m uvicorn pihouse_api.app:app --host 127.0.0.1 --port 8765
```

For phone testing, bind to the Pi LAN address or `0.0.0.0` only after the local
network/firewall rule is constrained to trusted phone-to-Pi access on TCP
`8765`.

## Pi Notes

Create a real config and token file on the Pi with the same schema as
`config.example.json`. Keep the token file readable only by the service user.
Bind the API to the trusted LAN only, or use firewall/router rules to allow
phone-to-Pi TCP `8765` only on the local trusted network.

The scaffold accepts only configured IDs:

- `speakerId`: `indoor`, `outdoor`
- `serviceId`: `pipewire`, `wireplumber`, `librespot_indoor`,
  `librespot_outdoor`, `librespot_both`, `bt_watchdog`

It does not accept raw systemd unit names, raw sink names, Bluetooth MAC
addresses, shell commands, tokens, Wi-Fi secrets, or unbounded logs from API
requests.
