from __future__ import annotations

import os
import sys
import uuid
from pathlib import Path

import pytest

# Disable the mDNS supervisor thread so the test process never tries to spawn
# avahi-publish-service. The contract test suite runs the app via TestClient
# which fires the startup hook synchronously.
os.environ["PIHOUSE_CONFIG"] = "config.example.json"
os.environ["PIHOUSE_MDNS_DISABLED"] = "1"

from fastapi.testclient import TestClient  # noqa: E402

from pihouse_api.auth import TOKEN_ENV_KEY  # noqa: E402

os.environ[TOKEN_ENV_KEY] = "dev-phase3-token"

from pihouse_api.app import app  # noqa: E402
from pihouse_api.discovery import MdnsAdvertiser  # noqa: E402
from pihouse_api.pairing import (  # noqa: E402
    PairingStore,
    parse_duration,
    validate_client_instance_id,
)


client = TestClient(app)


def _payload() -> dict:
    return {
        "clientName": "PiStream Companion (test)",
        "clientInstanceId": str(uuid.uuid4()),
    }


def test_stub_mode_pairing_window_is_always_open() -> None:
    response = client.post("/api/v1/pairing/request-token", json=_payload())
    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert body["token"] == "dev-phase3-token"
    assert body["expiresAt"] is None
    assert "observedAt" in body


def test_pairing_records_issuance_audit(tmp_path: Path) -> None:
    state_path = tmp_path / "pairing.json"
    store = PairingStore(state_path, stub_always_open=True)
    record = store.record_issuance("PiStream Companion (Pixel 7)", "id-1")
    assert record["clientName"] == "PiStream Companion (Pixel 7)"
    assert record["clientInstanceId"] == "id-1"
    assert record["issuedAt"].endswith("Z")
    snapshot = store.snapshot()
    assert snapshot.issuances[-1]["clientInstanceId"] == "id-1"


def test_pairing_endpoint_rejects_non_uuid_client_instance_id() -> None:
    response = client.post(
        "/api/v1/pairing/request-token",
        json={"clientName": "PiStream", "clientInstanceId": "not-a-uuid"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"
    assert response.json()["error"]["details"]["field"] == "clientInstanceId"


def test_pairing_endpoint_rejects_missing_client_name() -> None:
    response = client.post(
        "/api/v1/pairing/request-token",
        json={"clientName": "", "clientInstanceId": str(uuid.uuid4())},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_pairing_endpoint_closed_returns_403_when_real_and_window_closed(tmp_path: Path) -> None:
    # Swap in a real-mode pairing store with a closed window; verify the
    # endpoint refuses while the rest of the app stays stub.
    real_store = PairingStore(tmp_path / "pairing-real.json", stub_always_open=False)
    original = app.state.pairing
    app.state.pairing = real_store
    try:
        response = client.post("/api/v1/pairing/request-token", json=_payload())
        assert response.status_code == 403
        body = response.json()
        assert body["error"]["code"] == "pairing_closed"
        assert "--open 5m" in body["error"]["message"]
    finally:
        app.state.pairing = original


def test_pairing_endpoint_open_window_issues_token(tmp_path: Path) -> None:
    real_store = PairingStore(tmp_path / "pairing-real.json", stub_always_open=False)
    real_store.open_window(300)
    original = app.state.pairing
    app.state.pairing = real_store
    try:
        payload = _payload()
        response = client.post("/api/v1/pairing/request-token", json=payload)
        assert response.status_code == 200
        body = response.json()
        assert body["token"] == "dev-phase3-token"
        # Audit log was written.
        recorded = real_store.find_recent_issuance(payload["clientInstanceId"])
        assert recorded is not None
        assert recorded["clientName"] == payload["clientName"]
    finally:
        app.state.pairing = original


def test_pairing_endpoint_idempotent_for_same_client_instance_id(tmp_path: Path) -> None:
    real_store = PairingStore(tmp_path / "pairing-real.json", stub_always_open=False)
    real_store.open_window(300)
    original = app.state.pairing
    app.state.pairing = real_store
    try:
        payload = _payload()
        first = client.post("/api/v1/pairing/request-token", json=payload)
        second = client.post("/api/v1/pairing/request-token", json=payload)
        assert first.status_code == 200
        assert second.status_code == 200
        assert first.json()["token"] == second.json()["token"]
    finally:
        app.state.pairing = original


def test_pairing_endpoint_does_not_require_bearer() -> None:
    # The endpoint must NOT consult the Authorization header; pairing is the
    # mechanism by which the app obtains the bearer in the first place.
    response = client.post("/api/v1/pairing/request-token", json=_payload())
    assert response.status_code == 200


def test_window_state_persists_across_store_instances(tmp_path: Path) -> None:
    path = tmp_path / "pairing.json"
    first = PairingStore(path, stub_always_open=False)
    until = first.open_window(60)
    second = PairingStore(path, stub_always_open=False)
    assert second.is_window_open() is True
    assert second.open_until() is not None
    assert abs((second.open_until() - until).total_seconds()) < 1
    second.close_window()
    assert second.is_window_open() is False


def test_parse_duration_accepts_seconds_and_minutes_shorthand() -> None:
    assert parse_duration("5m") == 300
    assert parse_duration("300s") == 300
    assert parse_duration("300") == 300
    assert parse_duration("1 min") == 60
    with pytest.raises(ValueError):
        parse_duration("0m")
    with pytest.raises(ValueError):
        parse_duration("forever")
    with pytest.raises(ValueError):
        parse_duration("-5m")


def test_validate_client_instance_id_rejects_non_v4() -> None:
    validate_client_instance_id(str(uuid.uuid4()))
    with pytest.raises(ValueError):
        validate_client_instance_id("00000000-0000-1000-8000-000000000000")  # v1
    with pytest.raises(ValueError):
        validate_client_instance_id("totally-not-a-uuid")


def test_pairing_cli_open_close_status_roundtrip(tmp_path: Path, monkeypatch, capsys) -> None:
    state_path = tmp_path / "pairing.json"
    monkeypatch.setattr("pihouse_api.pairing._resolve_state_path", lambda: state_path)

    from pihouse_api import pairing as pairing_module

    # status when closed
    code = pairing_module.main(["status"])
    assert code == 0
    assert "closed" in capsys.readouterr().out

    # open via positional
    code = pairing_module.main(["open", "60s"])
    assert code == 0
    out = capsys.readouterr().out
    assert "open until" in out
    assert state_path.exists()

    # status when open
    code = pairing_module.main(["status"])
    assert code == 0
    assert "open" in capsys.readouterr().out

    # open via --open alias
    code = pairing_module.main(["--open", "2m"])
    assert code == 0
    capsys.readouterr()

    # close
    code = pairing_module.main(["close"])
    assert code == 0
    assert "closed" in capsys.readouterr().out

    # rejected duration
    code = pairing_module.main(["open", "forever"])
    assert code == 2
    err = capsys.readouterr().err
    assert "invalid duration" in err or "duration" in err


def test_mdns_advertiser_logs_and_stays_off_when_avahi_missing(monkeypatch) -> None:
    monkeypatch.delenv("PIHOUSE_MDNS_DISABLED", raising=False)
    advertiser = MdnsAdvertiser(
        device_id="pihouse-audio-01",
        api_name="pihouse-audio-api",
        contract_version="2026-06-phase3",
        pairing_open_provider=lambda: True,
        publisher_binary=None,
        poll_interval_seconds=0.05,
    )

    # When the binary is missing the supervisor must not raise and must not
    # leave a subprocess running.
    monkeypatch.setattr("pihouse_api.discovery.shutil.which", lambda name: None)
    advertiser.start()
    try:
        # Give the supervisor at least one tick.
        import time

        time.sleep(0.2)
        assert advertiser.is_running() is False
    finally:
        advertiser.stop()


def test_mdns_advertiser_builds_expected_args(monkeypatch) -> None:
    captured: list[list[str]] = []

    class _StubProc:
        def __init__(self) -> None:
            self.returncode = None

        def poll(self):
            return self.returncode

        def terminate(self):
            self.returncode = 0

        def wait(self, timeout=None):
            self.returncode = 0
            return 0

        def kill(self):
            self.returncode = 0

    def _stub_popen(args, **kwargs):  # noqa: ANN001 - test helper
        captured.append(args)
        return _StubProc()

    monkeypatch.delenv("PIHOUSE_MDNS_DISABLED", raising=False)
    monkeypatch.setattr(
        "pihouse_api.discovery.shutil.which",
        lambda name: "/usr/bin/avahi-publish-service",
    )
    monkeypatch.setattr("pihouse_api.discovery.subprocess.Popen", _stub_popen)

    pairing_flag = {"open": True}
    advertiser = MdnsAdvertiser(
        device_id="pihouse-audio-01",
        api_name="pihouse-audio-api",
        contract_version="2026-06-phase3",
        pairing_open_provider=lambda: pairing_flag["open"],
        poll_interval_seconds=0.05,
    )
    advertiser.start()
    try:
        import time

        time.sleep(0.2)
        # Flip pairing state -- supervisor must re-spawn with the new TXT.
        pairing_flag["open"] = False
        time.sleep(0.3)
    finally:
        advertiser.stop()

    assert captured, "expected at least one Popen call"
    first = captured[0]
    assert first[0] == "/usr/bin/avahi-publish-service"
    assert first[1] == "PiHouse Audio API"
    assert first[2] == "_pihouse-audio._tcp"
    assert first[3] == "8765"
    assert "apiName=pihouse-audio-api" in first
    assert "contractVersion=2026-06-phase3" in first
    assert "deviceId=pihouse-audio-01" in first
    assert "pairing=open" in first
    # At least one later spawn carried the disabled pairing flag.
    assert any("pairing=disabled" in call for call in captured)
