from __future__ import annotations

import os
import uuid
from pathlib import Path

from fastapi.testclient import TestClient

os.environ["PIHOUSE_CONFIG"] = "config.example.json"

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
