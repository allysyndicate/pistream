from __future__ import annotations

from .adapters import (
    ENDPOINT_MEMBER_SYSTEMS,
    ENDPOINT_SERVICE_IDS,
    HardwareAdapter,
    utc_now,
)
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


def health_payload(config: AppConfig, adapter: HardwareAdapter) -> dict:
    state, reasons = adapter.health_reasons(config)
    if state == "healthy":
        summary = "All configured audio paths are available."
    else:
        summary = "Some audio components need attention: " + ", ".join(reasons) + "."
    return {
        "ok": state == "healthy",
        "state": state,
        "phase": "running",
        "reboot": adapter.reboot(),
        "reasons": reasons,
        "summary": summary,
    }


def status_payload(config: AppConfig, adapter: HardwareAdapter, store: OperationStore) -> dict:
    output_state_for_services = adapter.audio_output_state()
    enabled_for_services = set(output_state_for_services["enabledSystemIds"])
    endpoint_service_to_members = {
        ENDPOINT_SERVICE_IDS[endpoint_id]: members
        for endpoint_id, members in ENDPOINT_MEMBER_SYSTEMS.items()
    }

    services = []
    for service_id, service in config.services.items():
        active_state = adapter.service_active_state(service_id)
        if service_id == "bt_watchdog":
            # Oneshot service: judged by its timer, not by is-active.
            healthy = adapter.watchdog_timer_state() == "active"
            reason_codes = [] if healthy else ["watchdog_inactive"]
        elif service_id in endpoint_service_to_members:
            expected = endpoint_service_to_members[service_id] <= enabled_for_services
            healthy = (active_state == "active") if expected else (active_state != "failed")
            reason_codes = [] if healthy else ["service_inactive"]
        else:
            healthy = active_state == "active"
            reason_codes = [] if healthy else ["service_inactive"]
        services.append(
            {
                "serviceId": service_id,
                "displayName": service.displayName,
                "unit": service.unit,
                "activeState": active_state,
                "componentState": "healthy" if healthy else "degraded",
                "reasonCodes": reason_codes,
                "restartAllowed": service.restartAllowed,
                "lastChangedAt": None,
            }
        )

    speakers = []
    for speaker_id, speaker in config.speakers.items():
        state = adapter.speaker_state(speaker_id)
        assignment = adapter.assignment_for(speaker_id)
        connected = bool(state.get("connected"))
        unassigned = state.get("lastError") == "speaker_unassigned"
        if connected:
            component_state = "healthy"
            reason_codes = []
        elif unassigned:
            component_state = "unassigned"
            reason_codes = ["speaker_unassigned"]
        else:
            component_state = "degraded"
            reason_codes = ["speaker_disconnected"]
        speakers.append(
            {
                "speakerId": speaker_id,
                "displayName": (assignment or {}).get("displayName") or speaker.displayName,
                "sinkId": speaker.sinkId,
                "address": (assignment or {}).get("mac") or speaker.mac,
                "componentState": component_state,
                "reasonCodes": reason_codes,
                **state,
            }
        )

    sinks = []
    for sink_id, sink in config.sinks.items():
        present = adapter.sink_present(sink_id)
        sinks.append(
            {
                "sinkId": sink_id,
                "displayName": sink.displayName,
                "name": sink.name or sink_id,
                "present": present,
                "componentState": "healthy" if present else "degraded",
                "reasonCodes": [] if present else ["sink_missing"],
            }
        )

    spotify_endpoints = []
    routes = []
    route_readiness = []
    route_state = adapter.route_state()
    output_state = adapter.audio_output_state()
    enabled_system_ids = set(output_state["enabledSystemIds"])
    active_system_ids = set(output_state["activeSystemIds"])
    for endpoint_id, endpoint in config.spotifyEndpoints.items():
        ready = adapter.sink_present(endpoint.sinkId) and adapter.service_active_state(endpoint.serviceId) == "active"
        expected = ENDPOINT_MEMBER_SYSTEMS[endpoint_id] <= enabled_system_ids
        if ready:
            endpoint_state = "healthy"
        elif not expected:
            endpoint_state = "disabled"
        else:
            endpoint_state = "degraded"
        spotify_endpoints.append(
            {
                "endpointId": endpoint_id,
                "displayName": endpoint.displayName,
                "serviceId": endpoint.serviceId,
                "sinkId": endpoint.sinkId,
                "activeState": adapter.service_active_state(endpoint.serviceId),
                "componentState": endpoint_state,
                "reasonCodes": [] if ready else ["route_not_ready"],
            }
        )
        routes.append(
            {
                "routeId": endpoint_id,
                "displayName": endpoint.displayName,
                "sinkId": endpoint.sinkId,
                "serviceId": endpoint.serviceId,
                "ready": ready,
                "selected": route_state["selectedRouteId"] == endpoint_id,
                "active": route_state["activeRouteId"] == endpoint_id,
                "reasonCodes": [] if ready else ["route_not_ready"],
            }
        )
        route_readiness.append({"endpointId": endpoint_id, "routeId": endpoint_id, "ready": ready, "reasonCodes": [] if ready else ["route_not_ready"]})

    speaker_systems = []
    for system_id, speaker in config.speakers.items():
        sink_ready = adapter.sink_present(speaker.sinkId)
        system_state = adapter.speaker_state(system_id)
        speaker_connected = bool(system_state.get("connected"))
        system_assignment = adapter.assignment_for(system_id)
        ready = sink_ready and speaker_connected
        system_reasons = []
        if system_state.get("lastError") == "speaker_unassigned":
            system_reasons.append("speaker_unassigned")
        elif not speaker_connected:
            system_reasons.append("speaker_disconnected")
        if not sink_ready:
            system_reasons.append("sink_missing")
        speaker_systems.append(
            {
                "systemId": system_id,
                "displayName": (system_assignment or {}).get("displayName") or speaker.displayName,
                "speakerId": system_id,
                "sinkId": speaker.sinkId,
                "enabled": system_id in enabled_system_ids,
                "active": system_id in active_system_ids,
                "ready": ready,
                "reasonCodes": system_reasons,
            }
        )

    recent = [record.model_dump() for record in store.recent()]
    active = store.active()
    health = health_payload(config, adapter)
    return {
        "ok": health["state"] == "healthy",
        "adapterMode": config.adapterMode,
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
        "routes": routes,
        "speakerSystems": speaker_systems,
        "audioOutput": {
            "enabledSystemIds": output_state["enabledSystemIds"],
            "activeSystemIds": output_state["activeSystemIds"],
            "lastChangedAt": output_state["lastChangedAt"],
            "adapterMode": output_state["adapterMode"],
        },
        "audioRoute": {
            "selectedRouteId": route_state["selectedRouteId"],
            "activeRouteId": route_state["activeRouteId"],
            "lastChangedAt": route_state["lastChangedAt"],
            "availableRouteIds": list(config.spotifyEndpoints.keys()),
        },
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
            "timerActiveState": adapter.watchdog_timer_state(),
            "serviceActiveState": adapter.service_active_state("bt_watchdog"),
            "componentState": "healthy" if adapter.watchdog_timer_state() == "active" else "degraded",
            "reasonCodes": [] if adapter.watchdog_timer_state() == "active" else ["watchdog_inactive"],
            "lastRunAt": None,
            "lastResult": None,
        },
        "controls": {
            "freshnessWindowSeconds": config.freshnessWindowSeconds,
            "restartServiceMode": config.restartServiceMode,
            "supportedOperations": [
                "reconnect",
                "restart_service",
                "run_watchdog",
                "select_route",
                "set_speaker_systems",
                "pair_speaker",
                "assign_speaker",
            ],
        },
        "operations": {"active": active.model_dump() if active else None, "recent": recent},
    }
