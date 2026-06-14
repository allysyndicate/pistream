from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

os.environ["PIHOUSE_CONFIG"] = "config.example.json"
# Tests must not try to spawn the avahi-publish-service supervisor.
os.environ["PIHOUSE_MDNS_DISABLED"] = "1"

from pihouse_api.auth import TOKEN_ENV_KEY, load_token  # noqa: E402

os.environ[TOKEN_ENV_KEY] = "dev-phase3-token"

from pihouse_api.app import app  # noqa: E402


client = TestClient(app)
AUTH = {"Authorization": "Bearer dev-phase3-token"}


def test_identity_is_public_and_phase3_compatible() -> None:
    response = client.get("/api/v1/identity")
    assert response.status_code == 200
    body = response.json()
    assert body["apiName"] == "pihouse-audio-api"
    assert body["contractVersion"] == "2026-06-phase3"
    assert body["apiVersion"].startswith("1.")
    assert body["deviceId"]
    assert body["controllerInstanceId"]


def test_status_requires_bearer() -> None:
    response = client.get("/api/v1/status")
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["error"]["details"]["reason"] == "missing_or_invalid_bearer_token"


def test_token_can_come_from_env_file(tmp_path, monkeypatch) -> None:
    config = app.state.config.model_copy()
    config.tokenFile = str(tmp_path / "fallback.token")
    Path(config.tokenFile).write_text("fallback-token", encoding="utf-8")
    (tmp_path / ".env").write_text(f"{TOKEN_ENV_KEY}=env-file-token\n", encoding="utf-8")
    monkeypatch.delenv(TOKEN_ENV_KEY, raising=False)

    assert load_token(config) == "env-file-token"


def test_status_with_bearer_exposes_allowlisted_stub_state() -> None:
    response = client.get("/api/v1/status", headers=AUTH)
    assert response.status_code == 200
    body = response.json()
    assert {item["speakerId"] for item in body["speakers"]} == {"indoor", "outdoor"}
    assert {item["serviceId"] for item in body["services"]} == {
        "pipewire",
        "wireplumber",
        "librespot_indoor",
        "librespot_outdoor",
        "librespot_both",
        "bt_watchdog",
    }
    assert body["spotify"]["integrationMode"] == "connect_status_handoff"
    assert {item["routeId"] for item in body["routes"]} == {"indoor", "outdoor", "both"}
    assert body["audioRoute"]["selectedRouteId"] in {"indoor", "outdoor", "both", None}
    assert {item["systemId"] for item in body["speakerSystems"]} == {"indoor", "outdoor"}
    assert set(body["audioOutput"]["enabledSystemIds"]).issubset({"indoor", "outdoor"})
    assert "select_route" in body["controls"]["supportedOperations"]
    assert "set_speaker_systems" in body["controls"]["supportedOperations"]
    assert body["adapterMode"] == "stub"


def test_reconnect_operation_and_polling() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "outdoor",
    }
    accepted = client.post("/api/v1/operations/reconnect", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["type"] == "reconnect"
    assert operation["target"] == {"speakerId": "outdoor"}
    polled = client.get(f"/api/v1/operations/{operation['operationId']}", headers=AUTH)
    assert polled.status_code == 200
    assert polled.json()["operation"]["status"] == "succeeded"


def test_invalid_service_id_is_rejected_without_raw_unit() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "serviceId": "librespot@outdoor",
    }
    response = client.post("/api/v1/operations/restart-service", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_select_route_operation_updates_status_and_polling() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "routeId": "outdoor",
    }
    accepted = client.post("/api/v1/operations/select-route", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["type"] == "select_route"
    assert operation["target"] == {"routeId": "outdoor"}
    assert operation["result"]["selectedRouteId"] == "outdoor"

    polled = client.get(f"/api/v1/operations/{operation['operationId']}", headers=AUTH)
    assert polled.status_code == 200
    assert polled.json()["operation"]["status"] == "succeeded"

    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    assert updated_status["audioRoute"]["selectedRouteId"] == "outdoor"
    outdoor_route = next(item for item in updated_status["routes"] if item["routeId"] == "outdoor")
    assert outdoor_route["selected"] is True
    assert outdoor_route["active"] is True


def test_select_route_accepts_whole_house_alias() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "routeId": "whole_house",
    }
    accepted = client.post("/api/v1/operations/select-route", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["target"] == {"routeId": "both"}
    assert operation["result"]["selectedRouteId"] == "both"
    assert operation["result"]["enabledSystemIds"] == ["indoor", "outdoor"]


def test_select_route_requires_bearer() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "routeId": "indoor",
    }
    response = client.post("/api/v1/operations/select-route", json=payload)
    assert response.status_code == 401
    assert response.json()["error"]["details"]["reason"] == "missing_or_invalid_bearer_token"


def test_invalid_route_id_is_rejected() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "routeId": "garage",
    }
    response = client.post("/api/v1/operations/select-route", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_select_route_rejects_stale_observation() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    stale_observed_at = datetime.now(UTC) - timedelta(seconds=app.state.config.freshnessWindowSeconds + 1)
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": stale_observed_at.isoformat().replace("+00:00", "Z"),
        "routeId": "both",
    }
    response = client.post("/api/v1/operations/select-route", headers=AUTH, json=payload)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "stale_observation"


def test_set_speaker_systems_single_on_updates_status_and_polling() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": ["indoor"],
    }
    accepted = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["type"] == "set_speaker_systems"
    assert operation["target"] == {"enabledSystemIds": ["indoor"]}
    assert operation["result"]["enabledSystemIds"] == ["indoor"]
    assert operation["result"]["selectedRouteId"] == "indoor"

    polled = client.get(f"/api/v1/operations/{operation['operationId']}", headers=AUTH)
    assert polled.status_code == 200
    assert polled.json()["operation"]["status"] == "succeeded"

    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    assert updated_status["audioOutput"]["enabledSystemIds"] == ["indoor"]
    assert updated_status["audioRoute"]["selectedRouteId"] == "indoor"
    systems = {item["systemId"]: item for item in updated_status["speakerSystems"]}
    assert systems["indoor"]["enabled"] is True
    assert systems["outdoor"]["enabled"] is False


def test_set_speaker_systems_both_on() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": ["indoor", "outdoor"],
    }
    accepted = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["target"] == {"enabledSystemIds": ["indoor", "outdoor"]}
    assert operation["result"]["selectedRouteId"] == "both"
    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    assert updated_status["audioOutput"]["enabledSystemIds"] == ["indoor", "outdoor"]


def test_set_speaker_systems_all_off() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": [],
    }
    accepted = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["target"] == {"enabledSystemIds": []}
    assert operation["result"]["selectedRouteId"] is None
    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    assert updated_status["audioOutput"]["enabledSystemIds"] == []
    assert updated_status["audioRoute"]["selectedRouteId"] is None
    assert all(item["enabled"] is False for item in updated_status["speakerSystems"])


def test_set_speaker_systems_rejects_unknown_system_id() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": ["garage"],
    }
    response = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_set_speaker_systems_rejects_stale_observation() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    stale_observed_at = datetime.now(UTC) - timedelta(seconds=app.state.config.freshnessWindowSeconds + 1)
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": stale_observed_at.isoformat().replace("+00:00", "Z"),
        "enabledSystemIds": ["outdoor"],
    }
    response = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=payload)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "stale_observation"


def test_set_speaker_systems_requires_bearer() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": ["indoor"],
    }
    response = client.post("/api/v1/operations/set-speaker-systems", json=payload)
    assert response.status_code == 401
    assert response.json()["error"]["details"]["reason"] == "missing_or_invalid_bearer_token"


def test_set_speaker_systems_idempotency_conflict() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    client_request_id = str(uuid.uuid4())
    first_payload = {
        "clientRequestId": client_request_id,
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "enabledSystemIds": ["indoor"],
    }
    first = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=first_payload)
    assert first.status_code == 202

    second_payload = {**first_payload, "enabledSystemIds": ["outdoor"]}
    second = client.post("/api/v1/operations/set-speaker-systems", headers=AUTH, json=second_payload)
    assert second.status_code == 409
    assert second.json()["error"]["code"] == "idempotency_conflict"


def test_bluetooth_devices_requires_bearer() -> None:
    response = client.get("/api/v1/bluetooth/devices")
    assert response.status_code == 401


def test_bluetooth_devices_lists_pi_visible_devices() -> None:
    response = client.get("/api/v1/bluetooth/devices", headers=AUTH)
    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    addresses = {device["address"] for device in body["devices"]}
    assert "77:88:99:AA:BB:CC" in addresses
    sample = body["devices"][0]
    assert {"address", "name", "paired", "trusted", "connected"} <= set(sample)


def test_pair_speaker_operation_assigns_slot_in_one_call() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "indoor",
        "address": "77:88:99:aa:bb:cc",
        "displayName": "Patio",
    }
    accepted = client.post("/api/v1/operations/pair-speaker", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["type"] == "pair_speaker"
    assert operation["target"] == {
        "speakerId": "indoor",
        "address": "77:88:99:AA:BB:CC",
        "displayName": "Patio",
    }
    assert operation["status"] == "succeeded"
    result = operation["result"]
    assert result["paired"] is True
    assert result["connected"] is True
    assert result["speakerId"] == "indoor"
    assert result["address"] == "77:88:99:AA:BB:CC"
    assert result["displayName"] == "Patio"
    assert result["pairIssued"] is True
    assert result["assignmentIssued"] is True

    # The slot assignment must reflect in /status on the next poll, which is
    # what clears the speaker_unassigned banner in the app.
    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    indoor = next(item for item in updated_status["speakers"] if item["speakerId"] == "indoor")
    assert indoor["address"] == "77:88:99:AA:BB:CC"
    assert indoor["displayName"] == "Patio"


def test_pair_speaker_rejects_malformed_address() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "indoor",
        "address": "not-a-mac; rm -rf /",
    }
    response = client.post("/api/v1/operations/pair-speaker", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_pair_speaker_requires_speaker_id() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "address": "77:88:99:AA:BB:CC",
    }
    response = client.post("/api/v1/operations/pair-speaker", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_pair_speaker_rejects_unknown_slot() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "garage",
        "address": "77:88:99:AA:BB:CC",
    }
    response = client.post("/api/v1/operations/pair-speaker", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_assign_speaker_operation_updates_status() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "outdoor",
        "address": "77:88:99:AA:BB:CC",
        "displayName": "Patio",
    }
    accepted = client.post("/api/v1/operations/assign-speaker", headers=AUTH, json=payload)
    assert accepted.status_code == 202
    operation = accepted.json()["operation"]
    assert operation["type"] == "assign_speaker"
    assert operation["status"] == "succeeded"
    assert operation["result"]["speakerId"] == "outdoor"
    assert operation["result"]["address"] == "77:88:99:AA:BB:CC"
    assert operation["result"]["displayName"] == "Patio"

    updated_status = client.get("/api/v1/status", headers=AUTH).json()
    outdoor = next(item for item in updated_status["speakers"] if item["speakerId"] == "outdoor")
    assert outdoor["displayName"] == "Patio"
    assert outdoor["address"] == "77:88:99:AA:BB:CC"
    systems = {item["systemId"]: item for item in updated_status["speakerSystems"]}
    assert systems["outdoor"]["displayName"] == "Patio"
    assert "pair_speaker" in updated_status["controls"]["supportedOperations"]
    assert "assign_speaker" in updated_status["controls"]["supportedOperations"]


def test_assign_speaker_rejects_unknown_system() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    payload = {
        "clientRequestId": str(uuid.uuid4()),
        "observedBootId": status_body["reboot"]["bootId"],
        "observedAt": status_body["observedAt"],
        "speakerId": "garage",
        "address": "77:88:99:AA:BB:CC",
    }
    response = client.post("/api/v1/operations/assign-speaker", headers=AUTH, json=payload)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"


def test_select_route_compatibility_updates_speaker_systems() -> None:
    status_body = client.get("/api/v1/status", headers=AUTH).json()
    cases = [
        ("indoor", ["indoor"]),
        ("outdoor", ["outdoor"]),
        ("both", ["indoor", "outdoor"]),
        ("whole_house", ["indoor", "outdoor"]),
    ]
    for route_id, expected_system_ids in cases:
        payload = {
            "clientRequestId": str(uuid.uuid4()),
            "observedBootId": status_body["reboot"]["bootId"],
            "observedAt": status_body["observedAt"],
            "routeId": route_id,
        }
        accepted = client.post("/api/v1/operations/select-route", headers=AUTH, json=payload)
        assert accepted.status_code == 202
        operation = accepted.json()["operation"]
        assert operation["result"]["enabledSystemIds"] == expected_system_ids
        updated_status = client.get("/api/v1/status", headers=AUTH).json()
        assert updated_status["audioOutput"]["enabledSystemIds"] == expected_system_ids
        status_body = updated_status


def test_config_without_adapter_mode_refuses_to_load() -> None:
    import json
    from pathlib import Path

    import pytest

    from pihouse_api.config import AppConfig

    raw = json.loads(Path("config.example.json").read_text(encoding="utf-8"))
    raw.pop("adapterMode", None)
    with pytest.raises(Exception) as info:
        AppConfig.model_validate(raw)
    # Pydantic surfaces a "missing" field error for the required adapterMode.
    assert "adapterMode" in str(info.value)
