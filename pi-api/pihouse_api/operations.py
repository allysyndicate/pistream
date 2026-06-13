from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field

from .adapters import AdapterCommandError, HardwareAdapter, utc_now
from .config import AppConfig, ServiceId, SpeakerId, SpotifyEndpointId


OperationType = Literal[
    "reconnect",
    "restart_service",
    "run_watchdog",
    "select_route",
    "set_speaker_systems",
    "pair_speaker",
    "assign_speaker",
]
RouteRequestId = Literal["indoor", "outdoor", "both", "whole_house"]
MAC_PATTERN = r"^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"


class OperationRecord(BaseModel):
    operationId: str
    clientRequestId: str
    type: OperationType
    status: Literal["queued", "running", "succeeded", "failed", "rejected", "expired"]
    target: dict[str, Any]
    requestedAt: str
    startedAt: str | None = None
    finishedAt: str | None = None
    expiresAt: str
    observedBootId: str
    pollAfterMs: int = 1000
    result: dict[str, Any] | None = None
    error: dict[str, Any] | None = None


class OperationStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.records: dict[str, OperationRecord] = {}
        self.by_client_request: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        data = json.loads(self.path.read_text(encoding="utf-8"))
        for item in data.get("operations", []):
            record = OperationRecord.model_validate(item)
            self.records[record.operationId] = record
            self.by_client_request[record.clientRequestId] = record.operationId

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        recent = sorted(self.records.values(), key=lambda item: item.requestedAt)[-100:]
        payload = {"operations": [item.model_dump() for item in recent]}
        self.path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def find_by_client_request(self, client_request_id: str) -> OperationRecord | None:
        operation_id = self.by_client_request.get(client_request_id)
        return self.records.get(operation_id) if operation_id else None

    def put(self, record: OperationRecord) -> OperationRecord:
        self.records[record.operationId] = record
        self.by_client_request[record.clientRequestId] = record.operationId
        self._save()
        return record

    def get(self, operation_id: str) -> OperationRecord | None:
        return self.records.get(operation_id)

    def active(self) -> OperationRecord | None:
        for record in self.records.values():
            if record.status in {"queued", "running"}:
                return record
        return None

    def recent(self) -> list[OperationRecord]:
        return sorted(self.records.values(), key=lambda item: item.requestedAt)[-10:]


class OperationRequest(BaseModel):
    clientRequestId: str
    observedBootId: str
    observedAt: datetime


class ReconnectRequest(OperationRequest):
    speakerId: SpeakerId


class RestartServiceRequest(OperationRequest):
    serviceId: ServiceId


class SelectRouteRequest(OperationRequest):
    routeId: RouteRequestId

    @property
    def canonical_route_id(self) -> SpotifyEndpointId:
        if self.routeId == "whole_house":
            return "both"
        return self.routeId


class SetSpeakerSystemsRequest(OperationRequest):
    enabledSystemIds: list[SpeakerId]

    @property
    def canonical_enabled_system_ids(self) -> list[SpeakerId]:
        return [system_id for system_id in ("indoor", "outdoor") if system_id in self.enabledSystemIds]


class PairSpeakerRequest(OperationRequest):
    address: str = Field(pattern=MAC_PATTERN)

    @property
    def canonical_address(self) -> str:
        return self.address.upper()


class AssignSpeakerRequest(OperationRequest):
    speakerId: SpeakerId
    address: str = Field(pattern=MAC_PATTERN)
    displayName: str | None = Field(default=None, max_length=40)

    @property
    def canonical_address(self) -> str:
        return self.address.upper()

    @property
    def canonical_display_name(self) -> str | None:
        if self.displayName is None:
            return None
        cleaned = self.displayName.strip()
        return cleaned or None


def ensure_uuid(value: str) -> None:
    uuid.UUID(value)


def validate_freshness(request: OperationRequest, adapter: HardwareAdapter, config: AppConfig) -> tuple[bool, dict[str, Any] | None]:
    if request.observedBootId != adapter.boot_id:
        return False, {
            "code": "boot_changed",
            "message": "The Raspberry Pi rebooted. Refresh status before retrying.",
            "details": {"requestBootId": request.observedBootId, "currentBootId": adapter.boot_id},
        }
    observed_at = request.observedAt
    if observed_at.tzinfo is None:
        observed_at = observed_at.replace(tzinfo=UTC)
    age = utc_now() - observed_at.astimezone(UTC)
    if age > timedelta(seconds=config.freshnessWindowSeconds):
        return False, {
            "code": "stale_observation",
            "message": "The status observation is too old. Refresh status before retrying.",
            "details": {"freshnessWindowSeconds": config.freshnessWindowSeconds},
        }
    return True, None


def target_for(operation_type: OperationType, request: OperationRequest) -> dict[str, Any]:
    if operation_type == "reconnect":
        return {"speakerId": getattr(request, "speakerId")}
    if operation_type == "restart_service":
        return {"serviceId": getattr(request, "serviceId")}
    if operation_type == "select_route":
        return {"routeId": getattr(request, "canonical_route_id")}
    if operation_type == "set_speaker_systems":
        return {"enabledSystemIds": getattr(request, "canonical_enabled_system_ids")}
    if operation_type == "pair_speaker":
        return {"address": getattr(request, "canonical_address")}
    if operation_type == "assign_speaker":
        return {
            "speakerId": getattr(request, "speakerId"),
            "address": getattr(request, "canonical_address"),
            "displayName": getattr(request, "canonical_display_name"),
        }
    return {}


def create_operation(
    store: OperationStore,
    adapter: HardwareAdapter,
    config: AppConfig,
    operation_type: OperationType,
    request: OperationRequest,
) -> tuple[OperationRecord, bool]:
    ensure_uuid(request.clientRequestId)
    target = target_for(operation_type, request)
    existing = store.find_by_client_request(request.clientRequestId)
    if existing:
        if existing.type != operation_type or existing.target != target:
            raise ValueError("idempotency_conflict")
        return existing, False

    now = utc_now()
    record = OperationRecord(
        operationId=f"op_{uuid.uuid4().hex}",
        clientRequestId=request.clientRequestId,
        type=operation_type,
        status="running",
        target=target,
        requestedAt=now.isoformat().replace("+00:00", "Z"),
        startedAt=now.isoformat().replace("+00:00", "Z"),
        expiresAt=(now + timedelta(days=7)).isoformat().replace("+00:00", "Z"),
        observedBootId=request.observedBootId,
    )
    try:
        if operation_type == "reconnect":
            record.result = adapter.reconnect_speaker(getattr(request, "speakerId"))
        elif operation_type == "restart_service":
            record.result = adapter.restart_service(config, getattr(request, "serviceId"))
        elif operation_type == "select_route":
            record.result = adapter.select_route(config, getattr(request, "canonical_route_id"))
        elif operation_type == "set_speaker_systems":
            record.result = adapter.set_speaker_systems(getattr(request, "canonical_enabled_system_ids"))
        elif operation_type == "pair_speaker":
            record.result = adapter.pair_speaker(getattr(request, "canonical_address"))
        elif operation_type == "assign_speaker":
            record.result = adapter.assign_speaker(
                getattr(request, "speakerId"),
                getattr(request, "canonical_address"),
                getattr(request, "canonical_display_name"),
            )
        else:
            record.result = adapter.run_watchdog(config)
        record.status = "succeeded"
    except AdapterCommandError as exc:
        record.status = "failed"
        record.error = {
            "code": exc.code,
            "message": str(exc),
            "details": {},
        }
    except Exception as exc:
        record.status = "failed"
        record.error = {
            "code": "operation_failed",
            "message": "The operation failed.",
            "details": {"reason": exc.__class__.__name__},
        }
    record.finishedAt = utc_now().isoformat().replace("+00:00", "Z")
    return store.put(record), True
