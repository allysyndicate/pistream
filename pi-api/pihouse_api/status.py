from __future__ import annotations

from .adapters import StubHardwareAdapter, utc_now
from .config import AppConfig
from .operations import OperationStore


def observed_at() -> str:
    return utc_now().isoformat().replace("+00:00", "Z")


def identity_payload(config: AppConfig) -> dict:
    return {
        "deviceId": config.deviceId,
        "controllerInstanceId": config.controllerInstanceId,
        "hostname": config.display_hostname,
        "apiName": config.apiName,
        "apiVersion": config.apiVersion,
        "contractVersion": config.contractVersion,
    }


def health_payload(config: AppConfig, adapter: StubHardwareAdapter) -> dict:
    reasons: list[str] = []
    return {
        "ok": True,
        "state": "healthy",
        "phase": "running",
        "reboot": adapter.reboot(),
        "reasons": reasons,
        "summary": "All configured audio paths are available.",
    }


def status_payload(config: AppConfig, adapter: StubHardwareAdapter, store: OperationStore) -> dict:
    services = []
    for service_id, service in config.services.items():
        active_state = adapter.service_active_state(service_id)
        services.append(
            {
                "serviceId": service_id,
                "displayName": service.displayName,
                "unit": service.unit,
                "activeState": active_state,
                "componentState": "healthy",
                "reasonCodes": [],
                "restartAllowed": service.restartAllowed,
                "lastChangedAt": None,
            }
        )

    speakers = []
    for speaker_id, speaker in config.speakers.items():
        state = adapter.speaker_state(speaker_id)
        speakers.append(
            {
                "speakerId": speaker_id,
                "displayName": speaker.displayName,
                "sinkId": speaker.sinkId,
                "componentState": "healthy",
                "reasonCodes": [],
                **state,
            }
        )

    sinks = [
        {
            "sinkId": sink_id,
            "displayName": sink.displayName,
            "name": sink_id,
            "present": adapter.sink_present(sink_id),
            "componentState": "healthy",
            "reasonCodes": [],
        }
        for sink_id, sink in config.sinks.items()
    ]

    spotify_endpoints = []
    route_readiness = []
    for endpoint_id, endpoint in config.spotifyEndpoints.items():
        spotify_endpoints.append(
            {
                "endpointId": endpoint_id,
                "displayName": endpoint.displayName,
                "serviceId": endpoint.serviceId,
                "sinkId": endpoint.sinkId,
                "activeState": adapter.service_active_state(endpoint.serviceId),
                "componentState": "healthy",
                "reasonCodes": [],
            }
        )
        route_readiness.append({"endpointId": endpoint_id, "ready": True, "reasonCodes": []})

    recent = [record.model_dump() for record in store.recent()]
    active = store.active()
    health = health_payload(config, adapter)
    return {
        "ok": health["state"] == "healthy",
        "identity": identity_payload(config),
        "health": {
            "state": health["state"],
            "phase": health["phase"],
            "reasons": health["reasons"],
            "summary": health["summary"],
        },
        "reboot": health["reboot"],
        "services": services,
        "speakers": speakers,
        "sinks": sinks,
        "spotifyEndpoints": spotify_endpoints,
        "spotify": {
            "integrationMode": config.spotifyIntegration.mode,
            "connectOwnedBy": "pi",
            "accountState": "unknown",
            "accountStateDetail": None,
            "activeDevice": {
                "category": "unknown",
                "endpointId": None,
                "displayName": None,
                "isExpectedPiEndpoint": None,
            },
            "playback": {
                "state": "unknown",
                "isPlaying": None,
                "lastKnownAt": None,
                "interruptionReason": None,
            },
            "routeReadiness": route_readiness,
            "recommendedAction": "open_spotify",
        },
        "watchdog": {
            "serviceId": "bt_watchdog",
            "timerActiveState": "unknown",
            "serviceActiveState": adapter.service_active_state("bt_watchdog"),
            "componentState": "healthy",
            "reasonCodes": [],
            "lastRunAt": None,
            "lastResult": None,
        },
        "controls": {
            "freshnessWindowSeconds": config.freshnessWindowSeconds,
            "restartServiceMode": config.restartServiceMode,
            "supportedOperations": ["reconnect", "restart_service", "run_watchdog"],
        },
        "operations": {"active": active.model_dump() if active else None, "recent": recent},
    }
