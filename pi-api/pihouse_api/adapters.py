from __future__ import annotations

import json
import re
import subprocess
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Callable, Union

from .config import AppConfig, ServiceId, SpeakerId, SpotifyEndpointId


def utc_now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


SYSTEM_ORDER: tuple[SpeakerId, ...] = ("indoor", "outdoor")

# Which librespot endpoint services should run for a given enabled-system set.
# An endpoint is available only when every system it plays through is enabled.
ENDPOINT_MEMBER_SYSTEMS: dict[SpotifyEndpointId, frozenset[SpeakerId]] = {
    "indoor": frozenset({"indoor"}),
    "outdoor": frozenset({"outdoor"}),
    "both": frozenset({"indoor", "outdoor"}),
}

ENDPOINT_SERVICE_IDS: dict[SpotifyEndpointId, ServiceId] = {
    "indoor": "librespot_indoor",
    "outdoor": "librespot_outdoor",
    "both": "librespot_both",
}


class AdapterCommandError(RuntimeError):
    """A hardware command failed in a way the operation must report."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass
class CommandResult:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out


CommandRunner = Callable[[list[str], float], CommandResult]


def run_command(args: list[str], timeout: float = 10.0) -> CommandResult:
    try:
        completed = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return CommandResult(
            args=list(args),
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    except FileNotFoundError as exc:
        return CommandResult(args=list(args), returncode=127, stdout="", stderr=str(exc))
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout.decode() if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        stderr = exc.stderr.decode() if isinstance(exc.stderr, bytes) else (exc.stderr or "")
        return CommandResult(args=list(args), returncode=-1, stdout=stdout, stderr=stderr, timed_out=True)


def sorted_system_ids(system_ids: set[SpeakerId]) -> list[SpeakerId]:
    return [system_id for system_id in SYSTEM_ORDER if system_id in system_ids]


def system_ids_for_route(route_id: SpotifyEndpointId) -> list[SpeakerId]:
    return sorted_system_ids(set(ENDPOINT_MEMBER_SYSTEMS[route_id]))


MAC_RE = re.compile(r"^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
DEVICE_LINE_RE = re.compile(r"^Device\s+([0-9A-Fa-f:]{17})\s*(.*)$")
MAX_DISCOVERED_DEVICES = 16


def canonical_mac(address: str) -> str:
    return address.strip().upper()


def bluez_sink_prefix(mac: str) -> str:
    return "bluez_output." + canonical_mac(mac).replace(":", "_")


class SpeakerAssignments:
    """Persisted app-driven mapping of speaker systems to Bluetooth devices."""

    def __init__(self, path: Path | None) -> None:
        self.path = path
        self.assignments: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        for speaker_id, value in data.get("assignments", {}).items():
            if speaker_id in SYSTEM_ORDER and isinstance(value, dict) and MAC_RE.match(str(value.get("mac", ""))):
                self.assignments[speaker_id] = {
                    "mac": canonical_mac(value["mac"]),
                    "displayName": value.get("displayName") or None,
                }

    def _save(self) -> None:
        if self.path is None:
            return
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"assignments": self.assignments}, indent=2), encoding="utf-8")
        except OSError:
            pass

    def get(self, speaker_id: str) -> dict | None:
        return self.assignments.get(speaker_id)

    def set(self, speaker_id: str, mac: str, display_name: str | None) -> dict:
        record = {"mac": canonical_mac(mac), "displayName": display_name or None}
        self.assignments[speaker_id] = record
        self._save()
        return record


def legacy_route_id_for(system_ids: set[SpeakerId]) -> SpotifyEndpointId | None:
    if system_ids == {"indoor"}:
        return "indoor"
    if system_ids == {"outdoor"}:
        return "outdoor"
    if system_ids == {"indoor", "outdoor"}:
        return "both"
    return None


class StubHardwareAdapter:
    """Test/dev boundary for Pi hardware commands.

    Used when ``adapterMode`` is ``stub`` (the default), for development off
    the Pi and for the contract test suite. The real commands live in
    :class:`RealHardwareAdapter`.
    """

    boot_id = "stub-boot-id"
    boot_time = utc_now()

    def __init__(self) -> None:
        self.enabled_system_ids: set[SpeakerId] = {"indoor", "outdoor"}
        self.assignments = SpeakerAssignments(None)
        self.devices: dict[str, dict] = {
            "AA:BB:CC:DD:EE:FF": {"name": "Stub Indoor Speaker", "paired": True, "trusted": True, "connected": True},
            "11:22:33:44:55:66": {"name": "Stub Outdoor Speaker", "paired": True, "trusted": True, "connected": True},
            "77:88:99:AA:BB:CC": {"name": "Stub Portable Speaker", "paired": False, "trusted": False, "connected": False},
        }

    def assignment_for(self, speaker_id: SpeakerId) -> dict | None:
        return self.assignments.get(speaker_id)

    def discover_devices(self, scan_seconds: int = 0) -> list[dict]:
        return [
            {"address": address, **flags}
            for address, flags in self.devices.items()
        ]

    def pair_speaker(self, address: str) -> dict:
        mac = canonical_mac(address)
        device = self.devices.get(mac)
        if device is None:
            raise AdapterCommandError("bluetooth_pair_failed", "This Bluetooth device is not visible to the Pi.")
        device.update(paired=True, trusted=True, connected=True)
        return {"address": mac, "name": device["name"], "paired": True, "trusted": True, "connected": True, "adapterMode": "stub"}

    def assign_speaker(self, speaker_id: SpeakerId, address: str, display_name: str | None = None) -> dict:
        mac = canonical_mac(address)
        if mac not in self.devices:
            raise AdapterCommandError("unknown_bluetooth_device", "This Bluetooth device is not known to the Pi.")
        record = self.assignments.set(speaker_id, mac, display_name)
        return {
            "speakerId": speaker_id,
            "address": record["mac"],
            "displayName": record["displayName"],
            "sinkName": bluez_sink_prefix(mac) + ".1",
            "assignmentIssued": True,
            "adapterMode": "stub",
        }

    def service_active_state(self, service_id: ServiceId) -> str:
        return "inactive" if service_id == "bt_watchdog" else "active"

    def watchdog_timer_state(self) -> str:
        return "active"

    def health_reasons(self, config: AppConfig) -> tuple[str, list[str]]:
        return "healthy", []

    def speaker_state(self, speaker_id: SpeakerId) -> dict:
        return {
            "paired": True,
            "trusted": True,
            "connected": True,
            "adapter": "hci0",
            "lastConnectedAt": utc_now().isoformat().replace("+00:00", "Z"),
            "lastError": None,
        }

    def sink_present(self, sink_id: str) -> bool:
        return True

    def route_state(self) -> dict:
        route_id = self.legacy_route_id()
        return {
            "selectedRouteId": route_id,
            "activeRouteId": route_id,
            "lastChangedAt": None,
            "adapterMode": "stub",
        }

    def audio_output_state(self) -> dict:
        enabled = sorted_system_ids(self.enabled_system_ids)
        return {
            "enabledSystemIds": enabled,
            "activeSystemIds": enabled,
            "lastChangedAt": None,
            "adapterMode": "stub",
        }

    def select_route(self, config: AppConfig, route_id: SpotifyEndpointId) -> dict:
        endpoint = config.spotifyEndpoints[route_id]
        enabled_system_ids = system_ids_for_route(route_id)
        self.enabled_system_ids = set(enabled_system_ids)
        route_state = self.route_state()
        return {
            "routeId": route_id,
            "selectedRouteId": route_state["selectedRouteId"],
            "activeRouteId": route_state["activeRouteId"],
            "enabledSystemIds": enabled_system_ids,
            "activeSystemIds": enabled_system_ids,
            "sinkId": endpoint.sinkId,
            "serviceId": endpoint.serviceId,
            "routeSelectionIssued": True,
            "adapterMode": "stub",
        }

    def set_speaker_systems(self, enabled_system_ids: list[SpeakerId]) -> dict:
        self.enabled_system_ids = set(enabled_system_ids)
        route_state = self.route_state()
        output_state = self.audio_output_state()
        return {
            "enabledSystemIds": output_state["enabledSystemIds"],
            "activeSystemIds": output_state["activeSystemIds"],
            "selectedRouteId": route_state["selectedRouteId"],
            "activeRouteId": route_state["activeRouteId"],
            "speakerSystemSelectionIssued": True,
            "adapterMode": "stub",
        }

    def legacy_route_id(self) -> SpotifyEndpointId | None:
        return legacy_route_id_for(self.enabled_system_ids)

    @staticmethod
    def system_ids_for_route(route_id: SpotifyEndpointId) -> list[SpeakerId]:
        return system_ids_for_route(route_id)

    @staticmethod
    def sorted_system_ids(system_ids: set[SpeakerId]) -> list[SpeakerId]:
        return sorted_system_ids(system_ids)

    def restart_service(self, config: AppConfig, service_id: ServiceId) -> dict:
        service = config.services[service_id]
        return {
            "serviceId": service_id,
            "activeState": "active",
            "restartIssued": True,
            "adapterMode": "stub",
            "unitDisplayName": service.displayName,
        }

    def reconnect_speaker(self, speaker_id: SpeakerId) -> dict:
        return {
            "connected": True,
            "speakerId": speaker_id,
            "bluetoothInfo": {"paired": True, "trusted": True, "connected": True},
            "adapterMode": "stub",
        }

    def run_watchdog(self, config: AppConfig) -> dict:
        return {
            "serviceId": "bt_watchdog",
            "started": True,
            "adapterMode": "stub",
            "speakers": [
                {"speakerId": speaker_id, "connected": True}
                for speaker_id in config.speakers
            ],
        }

    def reboot(self) -> dict:
        now = utc_now()
        return {
            "bootId": self.boot_id,
            "bootTime": self.boot_time.isoformat().replace("+00:00", "Z"),
            "uptimeSeconds": int((now - self.boot_time).total_seconds()),
        }


class RealHardwareAdapter:
    """Pi hardware boundary backed by allowlisted commands.

    Only argument-array commands mapped through the config allowlists are
    issued: ``systemctl --user``, ``bluetoothctl``, ``pactl``, and reads of
    ``/proc``. Request bodies never reach a command line directly.
    """

    COMMAND_TIMEOUT = 10.0
    BLUETOOTH_TIMEOUT = 15.0
    CACHE_TTL = 1.0

    def __init__(
        self,
        config: AppConfig,
        runner: CommandRunner = run_command,
        boot_id_path: Path = Path("/proc/sys/kernel/random/boot_id"),
        uptime_path: Path = Path("/proc/uptime"),
        state_path: Path | None = None,
        assignments_path: Path | None = None,
        librespot_env_dir: Path | None = None,
        combine_conf_path: Path | None = None,
    ) -> None:
        self.config = config
        self.runner = runner
        self.boot_id_path = boot_id_path
        self.uptime_path = uptime_path
        self.state_path = state_path
        self.assignments = SpeakerAssignments(assignments_path)
        self.librespot_env_dir = librespot_env_dir or Path.home() / ".config" / "librespot"
        self.combine_conf_path = combine_conf_path or Path.home() / ".config" / "pipewire" / "pipewire.conf.d" / "combine.conf"
        self._boot_id: str | None = None
        self._boot_time: datetime | None = None
        self._cache: dict[object, tuple[float, object]] = {}
        self._last_changed_at: str | None = None
        self._desired_system_ids: set[SpeakerId] | None = self._load_desired()

    def _load_desired(self) -> set[SpeakerId] | None:
        if self.state_path is None or not self.state_path.exists():
            return None
        try:
            data = json.loads(self.state_path.read_text(encoding="utf-8"))
            ids = data.get("enabledSystemIds")
            if isinstance(ids, list):
                return {system_id for system_id in ids if system_id in SYSTEM_ORDER}
        except (OSError, ValueError):
            pass
        return None

    def _save_desired(self, desired: set[SpeakerId]) -> None:
        if self.state_path is None:
            return
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            self.state_path.write_text(
                json.dumps({"enabledSystemIds": sorted_system_ids(desired)}),
                encoding="utf-8",
            )
        except OSError:
            pass

    # -- caching -----------------------------------------------------------

    def _cached(self, key: object, factory: Callable[[], object]) -> object:
        now = time.monotonic()
        hit = self._cache.get(key)
        if hit is not None and now - hit[0] < self.CACHE_TTL:
            return hit[1]
        value = factory()
        self._cache[key] = (now, value)
        return value

    def _invalidate(self) -> None:
        self._cache.clear()

    # -- boot --------------------------------------------------------------

    @property
    def boot_id(self) -> str:
        if self._boot_id is None:
            try:
                self._boot_id = self.boot_id_path.read_text(encoding="utf-8").strip()
            except OSError:
                self._boot_id = "unknown-boot-id"
        return self._boot_id

    def _uptime_seconds(self) -> int:
        try:
            return int(float(self.uptime_path.read_text(encoding="utf-8").split()[0]))
        except (OSError, ValueError, IndexError):
            return 0

    def reboot(self) -> dict:
        uptime = self._uptime_seconds()
        if self._boot_time is None:
            self._boot_time = utc_now() - timedelta(seconds=uptime)
        return {
            "bootId": self.boot_id,
            "bootTime": self._boot_time.isoformat().replace("+00:00", "Z"),
            "uptimeSeconds": uptime,
        }

    # -- systemd -----------------------------------------------------------

    def _unit(self, service_id: ServiceId) -> str:
        return self.config.services[service_id].unit

    def _is_active(self, unit: str) -> str:
        def probe() -> str:
            result = self.runner(["systemctl", "--user", "is-active", unit], self.COMMAND_TIMEOUT)
            state = result.stdout.strip().splitlines()[0].strip() if result.stdout.strip() else ""
            if state:
                return state
            return "unknown"

        return self._cached(("is-active", unit), probe)  # type: ignore[return-value]

    def service_active_state(self, service_id: ServiceId) -> str:
        return self._is_active(self._unit(service_id))

    def watchdog_timer_state(self) -> str:
        return self._is_active(f"{self._unit('bt_watchdog')}.timer")

    def restart_service(self, config: AppConfig, service_id: ServiceId) -> dict:
        service = config.services[service_id]
        result = self.runner(["systemctl", "--user", "restart", service.unit], 30.0)
        self._invalidate()
        if not result.ok:
            raise AdapterCommandError(
                "service_restart_failed",
                f"Restart of {service.displayName} failed.",
            )
        return {
            "serviceId": service_id,
            "activeState": self.service_active_state(service_id),
            "restartIssued": True,
            "adapterMode": "real",
            "unitDisplayName": service.displayName,
        }

    # -- bluetooth ---------------------------------------------------------

    @staticmethod
    def _parse_bluetoothctl_info(output: str) -> dict:
        flags = {"paired": False, "trusted": False, "connected": False}
        labels = {"Paired:": "paired", "Trusted:": "trusted", "Connected:": "connected"}
        for line in output.splitlines():
            stripped = line.strip()
            for label, key in labels.items():
                if stripped.startswith(label):
                    flags[key] = stripped[len(label):].strip().lower() == "yes"
        return flags

    def assignment_for(self, speaker_id: SpeakerId) -> dict | None:
        return self.assignments.get(speaker_id)

    def _speaker_mac(self, speaker_id: SpeakerId) -> str | None:
        assignment = self.assignments.get(speaker_id)
        if assignment:
            return assignment["mac"]
        mac = self.config.speakers[speaker_id].mac
        return canonical_mac(mac) if mac else None

    def _known_devices(self) -> dict[str, str]:
        def probe() -> dict[str, str]:
            result = self.runner(["bluetoothctl", "devices"], self.COMMAND_TIMEOUT)
            devices: dict[str, str] = {}
            for line in result.stdout.splitlines():
                match = DEVICE_LINE_RE.match(line.strip())
                if match:
                    devices[canonical_mac(match.group(1))] = match.group(2).strip()
            return devices

        return self._cached("bt-devices", probe)  # type: ignore[return-value]

    def discover_devices(self, scan_seconds: int = 0) -> list[dict]:
        if scan_seconds > 0:
            # Blocking discovery window; bluetoothctl exits when the timeout ends.
            self.runner(["bluetoothctl", "--timeout", str(scan_seconds), "scan", "on"], scan_seconds + 10.0)
            self._invalidate()
        devices = []
        for mac, name in sorted(self._known_devices().items())[:MAX_DISCOVERED_DEVICES]:
            info = self.runner(["bluetoothctl", "info", mac], self.COMMAND_TIMEOUT)
            flags = self._parse_bluetoothctl_info(info.stdout)
            devices.append({"address": mac, "name": name or None, **flags})
        return devices

    def pair_speaker(self, address: str) -> dict:
        mac = canonical_mac(address)
        if mac not in self._known_devices():
            raise AdapterCommandError(
                "unknown_bluetooth_device",
                "This Bluetooth device is not visible to the Pi. Scan again with the speaker in pairing mode.",
            )
        info = self.runner(["bluetoothctl", "info", mac], self.COMMAND_TIMEOUT)
        if not self._parse_bluetoothctl_info(info.stdout)["paired"]:
            pair = self.runner(["bluetoothctl", "--timeout", "30", "pair", mac], 40.0)
            if not pair.ok and "already" not in pair.stderr.lower():
                raise AdapterCommandError("bluetooth_pair_failed", "Pairing failed. Put the speaker in pairing mode and retry.")
        self.runner(["bluetoothctl", "trust", mac], self.COMMAND_TIMEOUT)
        self.runner(["bluetoothctl", "connect", mac], self.BLUETOOTH_TIMEOUT)
        self._invalidate()
        info = self.runner(["bluetoothctl", "info", mac], self.COMMAND_TIMEOUT)
        flags = self._parse_bluetoothctl_info(info.stdout)
        if not flags["paired"]:
            raise AdapterCommandError("bluetooth_pair_failed", "Pairing failed. Put the speaker in pairing mode and retry.")
        return {
            "address": mac,
            "name": self._known_devices().get(mac) or None,
            **flags,
            "adapterMode": "real",
        }

    def _sink_name_for_mac(self, mac: str) -> str:
        prefix = bluez_sink_prefix(mac)
        for name in self._sink_names():
            if name.startswith(prefix):
                return name
        return prefix + ".1"

    def _zone_env_values(self, speaker_id: SpeakerId) -> tuple[str, str] | None:
        mac = self._speaker_mac(speaker_id)
        if mac is None:
            return None
        assignment = self.assignments.get(speaker_id)
        display_name = (assignment or {}).get("displayName") or self.config.speakers[speaker_id].displayName
        return display_name, self._sink_name_for_mac(mac)

    def _write_audio_config_files(self) -> None:
        zone_values = {speaker_id: self._zone_env_values(speaker_id) for speaker_id in SYSTEM_ORDER}
        self.librespot_env_dir.mkdir(parents=True, exist_ok=True)
        for speaker_id, values in zone_values.items():
            if values is None:
                continue
            display_name, sink_name = values
            env_path = self.librespot_env_dir / f"{speaker_id}.env"
            env_path.write_text(f"SPEAKER_NAME={display_name}\nPULSE_SINK={sink_name}\n", encoding="utf-8")
        both_endpoint = self.config.spotifyEndpoints["both"]
        whole_house_sink = self.config.sinks["whole_house"].name or "whole_house"
        (self.librespot_env_dir / "both.env").write_text(
            f"SPEAKER_NAME={both_endpoint.displayName}\nPULSE_SINK={whole_house_sink}\n",
            encoding="utf-8",
        )
        member_sinks = [values[1] for values in zone_values.values() if values is not None]
        if len(member_sinks) == len(SYSTEM_ORDER):
            stream_rules = "\n".join(
                "        { matches = [ { media.class = \"Audio/Sink\" node.name = \"%s\" } ]\n"
                "          actions = { create-stream = { } } }" % sink_name
                for sink_name in member_sinks
            )
            combine_conf = (
                "context.modules = [\n"
                "  { name = libpipewire-module-combine-stream\n"
                "    args = {\n"
                "      combine.mode = sink\n"
                f"      node.name = \"{whole_house_sink}\"\n"
                "      node.description = \"Whole House\"\n"
                "      combine.latency-compensate = true\n"
                "      combine.props = { audio.position = [ FL FR ] }\n"
                "      stream.rules = [\n"
                f"{stream_rules}\n"
                "      ]\n"
                "    }\n"
                "  }\n"
                "]\n"
            )
            self.combine_conf_path.parent.mkdir(parents=True, exist_ok=True)
            self.combine_conf_path.write_text(combine_conf, encoding="utf-8")

    def assign_speaker(self, speaker_id: SpeakerId, address: str, display_name: str | None = None) -> dict:
        mac = canonical_mac(address)
        if mac not in self._known_devices():
            raise AdapterCommandError(
                "unknown_bluetooth_device",
                "This Bluetooth device is not known to the Pi. Pair it first.",
            )
        record = self.assignments.set(speaker_id, mac, display_name)
        sink_name = self._sink_name_for_mac(mac)
        self._write_audio_config_files()
        failures: list[str] = []
        for unit in ("pipewire", "wireplumber"):
            result = self.runner(["systemctl", "--user", "restart", self._unit(unit)], 30.0)
            if not result.ok:
                failures.append(unit)
        self._invalidate()
        # Pick up the new env file in any endpoint that is currently running.
        for endpoint_id, members in ENDPOINT_MEMBER_SYSTEMS.items():
            if speaker_id not in members:
                continue
            service_id = ENDPOINT_SERVICE_IDS[endpoint_id]
            if self.service_active_state(service_id) == "active":
                result = self.runner(["systemctl", "--user", "restart", self._unit(service_id)], 30.0)
                if not result.ok:
                    failures.append(service_id)
        self._invalidate()
        if failures:
            raise AdapterCommandError(
                "speaker_assignment_apply_failed",
                f"Speaker was assigned but these services failed to restart: {', '.join(sorted(failures))}.",
            )
        return {
            "speakerId": speaker_id,
            "address": record["mac"],
            "displayName": record["displayName"],
            "sinkName": sink_name,
            "assignmentIssued": True,
            "adapterMode": "real",
        }

    def speaker_state(self, speaker_id: SpeakerId) -> dict:
        def probe() -> dict:
            mac = self._speaker_mac(speaker_id)
            if mac is None:
                return {
                    "paired": False,
                    "trusted": False,
                    "connected": False,
                    "adapter": None,
                    "lastConnectedAt": None,
                    "lastError": "speaker_unassigned",
                }
            result = self.runner(["bluetoothctl", "info", mac], self.COMMAND_TIMEOUT)
            if not result.ok and not result.stdout.strip():
                return {
                    "paired": False,
                    "trusted": False,
                    "connected": False,
                    "adapter": None,
                    "lastConnectedAt": None,
                    "lastError": "bluetoothctl_info_failed",
                }
            flags = self._parse_bluetoothctl_info(result.stdout)
            return {
                **flags,
                "adapter": None,
                "lastConnectedAt": None,
                "lastError": None,
            }

        return self._cached(("speaker", speaker_id), probe)  # type: ignore[return-value]

    def reconnect_speaker(self, speaker_id: SpeakerId) -> dict:
        mac = self._speaker_mac(speaker_id)
        if mac is None:
            raise AdapterCommandError(
                "speaker_unassigned",
                f"No Bluetooth device is assigned to the {self.config.speakers[speaker_id].displayName} system yet.",
            )
        result = self.runner(["bluetoothctl", "connect", mac], self.BLUETOOTH_TIMEOUT)
        self._invalidate()
        state = self.speaker_state(speaker_id)
        if not state["connected"]:
            raise AdapterCommandError(
                "bluetooth_connect_failed",
                f"The {self.config.speakers[speaker_id].displayName} speaker did not connect.",
            )
        return {
            "connected": state["connected"],
            "speakerId": speaker_id,
            "bluetoothInfo": {
                "paired": state["paired"],
                "trusted": state["trusted"],
                "connected": state["connected"],
            },
            "adapterMode": "real",
        }

    def run_watchdog(self, config: AppConfig) -> dict:
        unit = self._unit("bt_watchdog")
        result = self.runner(["systemctl", "--user", "start", f"{unit}.service"], 30.0)
        self._invalidate()
        if not result.ok:
            raise AdapterCommandError("watchdog_start_failed", "The Bluetooth watchdog could not be started.")
        return {
            "serviceId": "bt_watchdog",
            "started": True,
            "adapterMode": "real",
            "speakers": [
                {"speakerId": speaker_id, "connected": self.speaker_state(speaker_id)["connected"]}
                for speaker_id in config.speakers
            ],
        }

    # -- sinks -------------------------------------------------------------

    def _sink_names(self) -> set[str]:
        def probe() -> set[str]:
            result = self.runner(["pactl", "list", "short", "sinks"], self.COMMAND_TIMEOUT)
            if not result.ok:
                return set()
            names: set[str] = set()
            for line in result.stdout.splitlines():
                fields = line.split("\t")
                if len(fields) >= 2:
                    names.add(fields[1].strip())
            return names

        return self._cached("sinks", probe)  # type: ignore[return-value]

    def sink_present(self, sink_id: str) -> bool:
        # Speaker-backed sinks follow the assigned Bluetooth device.
        for speaker_id in SYSTEM_ORDER:
            if self.config.speakers[speaker_id].sinkId == sink_id:
                mac = self._speaker_mac(speaker_id)
                if mac is not None:
                    prefix = bluez_sink_prefix(mac)
                    return any(name.startswith(prefix) for name in self._sink_names())
        name = self.config.sinks[sink_id].name
        if name is None:
            return False
        return name in self._sink_names()

    # -- speaker systems and routes ------------------------------------------

    def _enabled_system_ids(self) -> set[SpeakerId]:
        if self._desired_system_ids is not None:
            return set(self._desired_system_ids)
        # No saved assignment yet: infer it from which endpoint services run.
        enabled: set[SpeakerId] = set()
        for endpoint_id, members in ENDPOINT_MEMBER_SYSTEMS.items():
            if self.service_active_state(ENDPOINT_SERVICE_IDS[endpoint_id]) == "active":
                enabled |= members
        return enabled

    def _active_system_ids(self, enabled: set[SpeakerId]) -> set[SpeakerId]:
        active: set[SpeakerId] = set()
        for system_id in enabled:
            speaker = self.config.speakers[system_id]
            endpoint_running = self.service_active_state(ENDPOINT_SERVICE_IDS[system_id]) == "active"
            if endpoint_running and self.speaker_state(system_id)["connected"] and self.sink_present(speaker.sinkId):
                active.add(system_id)
        return active

    def audio_output_state(self) -> dict:
        enabled = self._enabled_system_ids()
        return {
            "enabledSystemIds": sorted_system_ids(enabled),
            "activeSystemIds": sorted_system_ids(self._active_system_ids(enabled)),
            "lastChangedAt": self._last_changed_at,
            "adapterMode": "real",
        }

    def route_state(self) -> dict:
        enabled = self._enabled_system_ids()
        route_id = legacy_route_id_for(enabled)
        active_route_id = legacy_route_id_for(self._active_system_ids(enabled))
        return {
            "selectedRouteId": route_id,
            "activeRouteId": active_route_id,
            "lastChangedAt": self._last_changed_at,
            "adapterMode": "real",
        }

    def set_speaker_systems(self, enabled_system_ids: list[SpeakerId]) -> dict:
        desired = set(enabled_system_ids)
        self._desired_system_ids = desired
        self._save_desired(desired)
        failures: list[str] = []
        for endpoint_id, members in ENDPOINT_MEMBER_SYSTEMS.items():
            service_id = ENDPOINT_SERVICE_IDS[endpoint_id]
            unit = self._unit(service_id)
            want_active = members <= desired and bool(members)
            is_active = self.service_active_state(service_id) == "active"
            if want_active and not is_active:
                result = self.runner(["systemctl", "--user", "start", unit], 30.0)
                if not result.ok:
                    failures.append(service_id)
            elif not want_active and is_active:
                result = self.runner(["systemctl", "--user", "stop", unit], 30.0)
                if not result.ok:
                    failures.append(service_id)
            self._invalidate()
        self._last_changed_at = utc_now().isoformat().replace("+00:00", "Z")
        if failures:
            raise AdapterCommandError(
                "speaker_system_control_failed",
                f"Speaker system services could not be updated: {', '.join(sorted(failures))}.",
            )
        route_state = self.route_state()
        output_state = self.audio_output_state()
        return {
            "enabledSystemIds": output_state["enabledSystemIds"],
            "activeSystemIds": output_state["activeSystemIds"],
            "selectedRouteId": route_state["selectedRouteId"],
            "activeRouteId": route_state["activeRouteId"],
            "speakerSystemSelectionIssued": True,
            "adapterMode": "real",
        }

    def select_route(self, config: AppConfig, route_id: SpotifyEndpointId) -> dict:
        endpoint = config.spotifyEndpoints[route_id]
        result = self.set_speaker_systems(system_ids_for_route(route_id))
        return {
            "routeId": route_id,
            "selectedRouteId": result["selectedRouteId"],
            "activeRouteId": result["activeRouteId"],
            "enabledSystemIds": result["enabledSystemIds"],
            "activeSystemIds": result["activeSystemIds"],
            "sinkId": endpoint.sinkId,
            "serviceId": endpoint.serviceId,
            "routeSelectionIssued": True,
            "adapterMode": "real",
        }

    def legacy_route_id(self) -> SpotifyEndpointId | None:
        return legacy_route_id_for(self._enabled_system_ids())

    # -- health --------------------------------------------------------------

    def health_reasons(self, config: AppConfig) -> tuple[str, list[str]]:
        reasons: list[str] = []
        if self.service_active_state("pipewire") != "active":
            reasons.append("pipewire_down")
        if self.service_active_state("wireplumber") != "active":
            reasons.append("wireplumber_down")
        for speaker_id in config.speakers:
            if self._speaker_mac(speaker_id) is None:
                reasons.append(f"{speaker_id}_speaker_unassigned")
            elif not self.speaker_state(speaker_id)["connected"]:
                reasons.append(f"{speaker_id}_speaker_disconnected")
        if not self.sink_present("whole_house"):
            reasons.append("whole_house_sink_missing")
        enabled = self._enabled_system_ids()
        for endpoint_id, members in ENDPOINT_MEMBER_SYSTEMS.items():
            expected = members <= enabled and bool(members)
            if expected and self.service_active_state(ENDPOINT_SERVICE_IDS[endpoint_id]) != "active":
                reasons.append(f"spotify_{endpoint_id}_unhealthy")
        if self.watchdog_timer_state() != "active":
            reasons.append("watchdog_inactive")
        return ("healthy" if not reasons else "degraded"), reasons


HardwareAdapter = Union[StubHardwareAdapter, RealHardwareAdapter]
