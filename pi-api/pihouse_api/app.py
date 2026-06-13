from __future__ import annotations

from datetime import UTC, datetime

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .adapters import RealHardwareAdapter, StubHardwareAdapter
from .auth import maybe_health_auth, require_bearer
from .config import ROOT, load_config
from .operations import (
    AssignSpeakerRequest,
    OperationRequest,
    OperationStore,
    PairSpeakerRequest,
    ReconnectRequest,
    RestartServiceRequest,
    SelectRouteRequest,
    SetSpeakerSystemsRequest,
    create_operation,
    validate_freshness,
)
from .status import health_payload, identity_payload, observed_at, status_payload


def error_body(code: str, message: str, details: dict | None = None) -> dict:
    return {
        "ok": False,
        "error": {"code": code, "message": message, "details": details or {}},
        "observedAt": observed_at(),
    }


def create_app() -> FastAPI:
    app = FastAPI(title="PiHouse Local API", version="1.0.0")
    app.state.config = load_config()
    if app.state.config.adapterMode == "real":
        app.state.adapter = RealHardwareAdapter(
            app.state.config,
            state_path=ROOT / ".state" / "audio-output.json",
            assignments_path=ROOT / ".state" / "speaker-assignments.json",
        )
    else:
        app.state.adapter = StubHardwareAdapter()
    app.state.operations = OperationStore(ROOT / ".state" / "operations.json")

    @app.exception_handler(HTTPException)
    async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
        if isinstance(exc.detail, dict) and "code" in exc.detail:
            body = error_body(exc.detail["code"], exc.detail["message"], exc.detail.get("details"))
        else:
            body = error_body("internal_error", "The request could not be completed.")
        return JSONResponse(status_code=exc.status_code, content=body, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
        first = exc.errors()[0] if exc.errors() else {}
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content=error_body(
                "invalid_request",
                "The request body is invalid.",
                {"field": ".".join(str(item) for item in first.get("loc", []))},
            ),
        )

    @app.get("/api/v1/identity")
    async def identity(request: Request) -> dict:
        return {"ok": True, **identity_payload(request.app.state.config), "observedAt": observed_at()}

    @app.get("/api/v1/health", dependencies=[Depends(maybe_health_auth)])
    async def health(request: Request) -> dict:
        return {**health_payload(request.app.state.config, request.app.state.adapter), "observedAt": observed_at()}

    @app.get("/api/v1/status", dependencies=[Depends(require_bearer)])
    async def status_route(request: Request) -> dict:
        return {**status_payload(request.app.state.config, request.app.state.adapter, request.app.state.operations), "observedAt": observed_at()}

    @app.get("/api/v1/bluetooth/devices", dependencies=[Depends(require_bearer)])
    async def bluetooth_devices(request: Request, scanSeconds: int = 0) -> dict:
        scan_seconds = max(0, min(scanSeconds, 20))
        devices = request.app.state.adapter.discover_devices(scan_seconds)
        return {"ok": True, "devices": devices, "scanSeconds": scan_seconds, "observedAt": observed_at()}

    def preflight_operation(request_obj: OperationRequest, request: Request) -> None:
        try:
            valid, error = validate_freshness(request_obj, request.app.state.adapter, request.app.state.config)
        except Exception:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": "invalid_observation", "message": "The observation metadata is invalid.", "details": {}},
            )
        if not valid and error:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=error)

    def operation_response(record, created: bool) -> JSONResponse:
        code = status.HTTP_202_ACCEPTED if created and record.status in {"queued", "running", "succeeded"} else status.HTTP_200_OK
        return JSONResponse(status_code=code, content={"ok": True, "operation": record.model_dump(), "observedAt": observed_at()})

    @app.post("/api/v1/operations/reconnect", dependencies=[Depends(require_bearer)])
    async def reconnect(payload: ReconnectRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "reconnect", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/restart-service", dependencies=[Depends(require_bearer)])
    async def restart_service(payload: RestartServiceRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        service = request.app.state.config.services[payload.serviceId]
        if not service.restartAllowed:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": "forbidden", "message": "This control is not permitted for the current authorization policy.", "details": {"requiredCapability": "advanced_controls"}},
            )
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "restart_service", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/select-route", dependencies=[Depends(require_bearer)])
    async def select_route(payload: SelectRouteRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "select_route", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/set-speaker-systems", dependencies=[Depends(require_bearer)])
    async def set_speaker_systems(payload: SetSpeakerSystemsRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "set_speaker_systems", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/pair-speaker", dependencies=[Depends(require_bearer)])
    async def pair_speaker(payload: PairSpeakerRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "pair_speaker", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/assign-speaker", dependencies=[Depends(require_bearer)])
    async def assign_speaker(payload: AssignSpeakerRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "assign_speaker", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.post("/api/v1/operations/run-watchdog", dependencies=[Depends(require_bearer)])
    async def run_watchdog(payload: OperationRequest, request: Request) -> JSONResponse:
        preflight_operation(payload, request)
        try:
            record, created = create_operation(request.app.state.operations, request.app.state.adapter, request.app.state.config, "run_watchdog", payload)
        except ValueError as exc:
            return idempotency_error(exc)
        return operation_response(record, created)

    @app.get("/api/v1/operations/{operation_id}", dependencies=[Depends(require_bearer)])
    async def operation(operation_id: str, request: Request) -> dict:
        record = request.app.state.operations.get(operation_id)
        if record is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "operation_not_found", "message": "This operation was not found.", "details": {"operationId": operation_id}},
            )
        expires_at = datetime.fromisoformat(record.expiresAt.replace("Z", "+00:00"))
        if expires_at < datetime.now(UTC):
            raise HTTPException(
                status_code=status.HTTP_410_GONE,
                detail={"code": "operation_expired", "message": "This operation is no longer available.", "details": {"operationId": operation_id}},
            )
        return {"ok": True, "operation": record.model_dump(), "observedAt": observed_at()}

    return app


def idempotency_error(exc: ValueError) -> JSONResponse:
    if str(exc) == "idempotency_conflict":
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT,
            content=error_body(
                "idempotency_conflict",
                "The client request id was already used for a different operation.",
            ),
        )
    raise exc


app = create_app()
