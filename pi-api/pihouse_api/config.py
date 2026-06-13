from __future__ import annotations

import json
import os
import socket
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator


ROOT = Path(__file__).resolve().parents[1]

SpeakerId = Literal["indoor", "outdoor"]
SinkId = Literal["indoor", "outdoor", "whole_house"]
ServiceId = Literal[
    "pipewire",
    "wireplumber",
    "librespot_indoor",
    "librespot_outdoor",
    "librespot_both",
    "bt_watchdog",
]
SpotifyEndpointId = Literal["indoor", "outdoor", "both"]


class SpeakerConfig(BaseModel):
    displayName: str
    sinkId: SinkId
    mac: str | None = None


class SinkConfig(BaseModel):
    displayName: str
    name: str | None = None


class ServiceConfig(BaseModel):
    displayName: str
    unit: str
    restartAllowed: bool = True


class SpotifyEndpointConfig(BaseModel):
    displayName: str
    serviceId: ServiceId
    sinkId: SinkId


class SpotifyIntegrationConfig(BaseModel):
    mode: Literal["connect_status_handoff"] = "connect_status_handoff"
    webApiEnabled: bool = False


class AppConfig(BaseModel):
    deviceId: str = Field(min_length=1)
    controllerInstanceId: str = Field(min_length=1)
    hostname: str | None = None
    apiName: Literal["pihouse-audio-api"] = "pihouse-audio-api"
    apiVersion: str = "1.0.0"
    contractVersion: Literal["2026-06-phase3"] = "2026-06-phase3"
    tokenFile: str
    adapterMode: Literal["stub", "real"] = "stub"
    healthRequiresAuth: bool = False
    freshnessWindowSeconds: int = Field(default=120, ge=10, le=900)
    restartServiceMode: Literal["advanced"] = "advanced"
    speakers: dict[SpeakerId, SpeakerConfig]
    sinks: dict[SinkId, SinkConfig]
    services: dict[ServiceId, ServiceConfig]
    spotifyEndpoints: dict[SpotifyEndpointId, SpotifyEndpointConfig]
    spotifyIntegration: SpotifyIntegrationConfig = Field(
        default_factory=SpotifyIntegrationConfig
    )

    @model_validator(mode="after")
    def require_phase3_allowlists(self) -> "AppConfig":
        if set(self.speakers) != {"indoor", "outdoor"}:
            raise ValueError("speakers must contain indoor and outdoor")
        if set(self.sinks) != {"indoor", "outdoor", "whole_house"}:
            raise ValueError("sinks must contain indoor, outdoor, and whole_house")
        if set(self.services) != {
            "pipewire",
            "wireplumber",
            "librespot_indoor",
            "librespot_outdoor",
            "librespot_both",
            "bt_watchdog",
        }:
            raise ValueError("services must contain the Phase 3 service allowlist")
        if set(self.spotifyEndpoints) != {"indoor", "outdoor", "both"}:
            raise ValueError("spotifyEndpoints must contain indoor, outdoor, and both")
        return self

    @model_validator(mode="after")
    def require_real_adapter_values(self) -> "AppConfig":
        if self.adapterMode != "real":
            return self
        # Speaker MACs may come from config or from app-driven assignment; only
        # the whole-house combine sink needs a fixed PipeWire node name.
        if not self.sinks["whole_house"].name:
            raise ValueError("sink 'whole_house' needs a PipeWire sink name when adapterMode is real")
        return self

    @property
    def display_hostname(self) -> str:
        return self.hostname or socket.gethostname()


def _resolve_path(path_value: str, config_path: Path) -> Path:
    path = Path(path_value)
    if path.is_absolute():
        return path
    return (config_path.parent / path).resolve()


def load_config() -> AppConfig:
    raw_path = os.environ.get("PIHOUSE_CONFIG", str(ROOT / "config.example.json"))
    config_path = Path(raw_path).resolve()
    with config_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    config = AppConfig.model_validate(data)
    config.tokenFile = str(_resolve_path(config.tokenFile, config_path))
    return config
