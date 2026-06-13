from __future__ import annotations

import pytest
from pydantic import ValidationError

from pihouse_api.adapters import (
    AdapterCommandError,
    CommandResult,
    RealHardwareAdapter,
)
from pihouse_api.config import AppConfig

INDOOR_MAC = "AA:BB:CC:DD:EE:FF"
OUTDOOR_MAC = "11:22:33:44:55:66"
INDOOR_SINK = "bluez_output.AA_BB_CC_DD_EE_FF.1"
OUTDOOR_SINK = "bluez_output.11_22_33_44_55_66.1"


def real_config_data() -> dict:
    return {
        "deviceId": "pihouse-audio-01",
        "controllerInstanceId": "ctrl_01JXYZPIHOUSEAUDIO",
        "hostname": "audiopi",
        "tokenFile": "token.example",
        "adapterMode": "real",
        "speakers": {
            "indoor": {"displayName": "Indoor", "sinkId": "indoor", "mac": INDOOR_MAC},
            "outdoor": {"displayName": "Outdoor", "sinkId": "outdoor", "mac": OUTDOOR_MAC},
        },
        "sinks": {
            "indoor": {"displayName": "Indoor Sink", "name": INDOOR_SINK},
            "outdoor": {"displayName": "Outdoor Sink", "name": OUTDOOR_SINK},
            "whole_house": {"displayName": "Whole House Sink", "name": "whole_house"},
        },
        "services": {
            "pipewire": {"displayName": "PipeWire", "unit": "pipewire"},
            "wireplumber": {"displayName": "WirePlumber", "unit": "wireplumber"},
            "librespot_indoor": {"displayName": "Spotify Indoor", "unit": "librespot@indoor"},
            "librespot_outdoor": {"displayName": "Spotify Outdoor", "unit": "librespot@outdoor"},
            "librespot_both": {"displayName": "Spotify Whole House", "unit": "librespot@both"},
            "bt_watchdog": {"displayName": "Bluetooth Watchdog", "unit": "bt-watchdog"},
        },
        "spotifyEndpoints": {
            "indoor": {"displayName": "Indoor", "serviceId": "librespot_indoor", "sinkId": "indoor"},
            "outdoor": {"displayName": "Outdoor", "serviceId": "librespot_outdoor", "sinkId": "outdoor"},
            "both": {"displayName": "Whole House", "serviceId": "librespot_both", "sinkId": "whole_house"},
        },
    }


class FakeHardware:
    """Stateful stand-in for systemctl/bluetoothctl/pactl on the Pi."""

    def __init__(self) -> None:
        self.units: dict[str, str] = {
            "pipewire": "active",
            "wireplumber": "active",
            "librespot@indoor": "inactive",
            "librespot@outdoor": "inactive",
            "librespot@both": "inactive",
            "bt-watchdog": "inactive",
            "bt-watchdog.timer": "active",
        }
        self.speakers: dict[str, dict[str, bool]] = {
            INDOOR_MAC: {"paired": True, "trusted": True, "connected": True},
            OUTDOOR_MAC: {"paired": True, "trusted": True, "connected": True},
        }
        self.device_names: dict[str, str] = {INDOOR_MAC: "Indoor JBL", OUTDOOR_MAC: "Outdoor Sony"}
        self.known_devices: set[str] = {INDOOR_MAC, OUTDOOR_MAC}
        self.discoverable: dict[str, str] = {}
        self.sinks: list[str] = [INDOOR_SINK, OUTDOOR_SINK, "whole_house"]
        self.fail_unit_control: set[str] = set()
        self.fail_connect: set[str] = set()
        self.fail_pair: set[str] = set()
        self.calls: list[list[str]] = []

    def __call__(self, args: list[str], timeout: float) -> CommandResult:
        self.calls.append(list(args))
        if args[:3] == ["systemctl", "--user", "is-active"]:
            state = self.units.get(args[3], "inactive")
            return CommandResult(args, 0 if state == "active" else 3, f"{state}\n", "")
        if args[:3] in (["systemctl", "--user", "start"], ["systemctl", "--user", "stop"], ["systemctl", "--user", "restart"]):
            unit = args[3].removesuffix(".service")
            if unit in self.fail_unit_control:
                return CommandResult(args, 1, "", "Failed to control unit.")
            self.units[unit] = "active" if args[2] in ("start", "restart") else "inactive"
            return CommandResult(args, 0, "", "")
        if args[0] == "bluetoothctl":
            rest = args[1:]
            if rest == ["devices"]:
                lines = [
                    f"Device {mac} {self.device_names.get(mac, 'Speaker')}"
                    for mac in sorted(self.known_devices)
                ]
                return CommandResult(args, 0, "\n".join(lines) + "\n", "")
            if "scan" in rest:
                for mac, name in self.discoverable.items():
                    self.known_devices.add(mac)
                    self.device_names[mac] = name
                    self.speakers.setdefault(mac, {"paired": False, "trusted": False, "connected": False})
                return CommandResult(args, 0, "", "")
            if "pair" in rest:
                mac = rest[-1]
                if mac in self.fail_pair:
                    return CommandResult(args, 1, "", "Failed to pair")
                self.speakers.setdefault(mac, {"paired": False, "trusted": False, "connected": False})
                self.speakers[mac]["paired"] = True
                return CommandResult(args, 0, "Pairing successful\n", "")
            if "trust" in rest:
                mac = rest[-1]
                self.speakers.setdefault(mac, {"paired": False, "trusted": False, "connected": False})
                self.speakers[mac]["trusted"] = True
                return CommandResult(args, 0, "trust succeeded\n", "")
            if rest[:1] == ["info"]:
                flags = self.speakers.get(rest[1])
                if flags is None:
                    return CommandResult(args, 1, "", "Device not available")
                yes_no = {True: "yes", False: "no"}
                stdout = (
                    f"Device {rest[1]} (public)\n"
                    f"\tName: {self.device_names.get(rest[1], 'Test Speaker')}\n"
                    f"\tPaired: {yes_no[flags['paired']]}\n"
                    f"\tTrusted: {yes_no[flags['trusted']]}\n"
                    f"\tConnected: {yes_no[flags['connected']]}\n"
                )
                return CommandResult(args, 0, stdout, "")
            if rest[:1] == ["connect"]:
                if rest[1] in self.fail_connect:
                    return CommandResult(args, 1, "", "Failed to connect")
                self.speakers[rest[1]]["connected"] = True
                return CommandResult(args, 0, "Connection successful\n", "")
        if args == ["pactl", "list", "short", "sinks"]:
            lines = [
                f"{index}\t{name}\tmodule-bluez5-device.c\ts16le 2ch 44100Hz\tIDLE"
                for index, name in enumerate(self.sinks)
            ]
            return CommandResult(args, 0, "\n".join(lines) + "\n", "")
        return CommandResult(args, 127, "", f"unexpected command: {args}")


@pytest.fixture()
def config() -> AppConfig:
    return AppConfig.model_validate(real_config_data())


@pytest.fixture()
def hardware() -> FakeHardware:
    return FakeHardware()


@pytest.fixture()
def adapter(config: AppConfig, hardware: FakeHardware, tmp_path) -> RealHardwareAdapter:
    boot_id_path = tmp_path / "boot_id"
    boot_id_path.write_text("8f6d2f00-0000-4000-8000-000000000001\n", encoding="utf-8")
    uptime_path = tmp_path / "uptime"
    uptime_path.write_text("4242.42 8000.00\n", encoding="utf-8")
    return RealHardwareAdapter(
        config,
        runner=hardware,
        boot_id_path=boot_id_path,
        uptime_path=uptime_path,
        assignments_path=tmp_path / "speaker-assignments.json",
        librespot_env_dir=tmp_path / "librespot",
        combine_conf_path=tmp_path / "pipewire.conf.d" / "combine.conf",
        user_systemd_dir=tmp_path / "systemd-user",
    )


def test_real_config_allows_missing_speaker_macs() -> None:
    data = real_config_data()
    data["speakers"]["indoor"].pop("mac")
    config = AppConfig.model_validate(data)
    assert config.speakers["indoor"].mac is None


def test_real_config_requires_whole_house_sink_name() -> None:
    data = real_config_data()
    data["sinks"]["whole_house"].pop("name")
    with pytest.raises(ValidationError):
        AppConfig.model_validate(data)


def test_boot_id_and_uptime_come_from_proc_files(adapter: RealHardwareAdapter) -> None:
    reboot = adapter.reboot()
    assert reboot["bootId"] == "8f6d2f00-0000-4000-8000-000000000001"
    assert reboot["uptimeSeconds"] == 4242


def test_service_active_state_uses_systemctl(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    assert adapter.service_active_state("pipewire") == "active"
    assert adapter.service_active_state("librespot_indoor") == "inactive"
    assert ["systemctl", "--user", "is-active", "pipewire"] in hardware.calls


def test_speaker_state_parses_bluetoothctl_info(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.speakers[OUTDOOR_MAC]["connected"] = False
    indoor = adapter.speaker_state("indoor")
    outdoor = adapter.speaker_state("outdoor")
    assert indoor["paired"] is True
    assert indoor["connected"] is True
    assert outdoor["connected"] is False
    assert outdoor["paired"] is True


def test_speaker_state_handles_unavailable_device(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    del hardware.speakers[INDOOR_MAC]
    state = adapter.speaker_state("indoor")
    assert state["connected"] is False
    assert state["lastError"] == "bluetoothctl_info_failed"


def test_sink_present_uses_configured_pipewire_names(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    assert adapter.sink_present("indoor") is True
    assert adapter.sink_present("whole_house") is True
    hardware.sinks.remove("whole_house")
    adapter._invalidate()
    assert adapter.sink_present("whole_house") is False


def test_enabled_systems_follow_librespot_units(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    assert adapter.audio_output_state()["enabledSystemIds"] == []
    hardware.units["librespot@indoor"] = "active"
    adapter._invalidate()
    assert adapter.audio_output_state()["enabledSystemIds"] == ["indoor"]
    hardware.units["librespot@both"] = "active"
    adapter._invalidate()
    assert adapter.audio_output_state()["enabledSystemIds"] == ["indoor", "outdoor"]


def test_set_speaker_systems_indoor_only(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    result = adapter.set_speaker_systems(["indoor"])
    assert hardware.units["librespot@indoor"] == "active"
    assert hardware.units["librespot@outdoor"] == "inactive"
    assert hardware.units["librespot@both"] == "inactive"
    assert result["enabledSystemIds"] == ["indoor"]
    assert result["selectedRouteId"] == "indoor"
    assert result["adapterMode"] == "real"


def test_set_speaker_systems_both_starts_all_endpoints(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    result = adapter.set_speaker_systems(["indoor", "outdoor"])
    assert hardware.units["librespot@indoor"] == "active"
    assert hardware.units["librespot@outdoor"] == "active"
    assert hardware.units["librespot@both"] == "active"
    assert result["enabledSystemIds"] == ["indoor", "outdoor"]
    assert result["selectedRouteId"] == "both"


def test_set_speaker_systems_all_off_stops_endpoints(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    adapter.set_speaker_systems(["indoor", "outdoor"])
    result = adapter.set_speaker_systems([])
    assert hardware.units["librespot@indoor"] == "inactive"
    assert hardware.units["librespot@outdoor"] == "inactive"
    assert hardware.units["librespot@both"] == "inactive"
    assert result["enabledSystemIds"] == []
    assert result["selectedRouteId"] is None


def test_set_speaker_systems_failure_raises(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.fail_unit_control.add("librespot@indoor")
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.set_speaker_systems(["indoor"])
    assert excinfo.value.code == "speaker_system_control_failed"


def test_select_route_maps_to_speaker_systems(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    result = adapter.select_route(config, "both")
    assert result["routeId"] == "both"
    assert result["enabledSystemIds"] == ["indoor", "outdoor"]
    assert result["sinkId"] == "whole_house"
    assert hardware.units["librespot@both"] == "active"


def test_reconnect_speaker_success(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.speakers[OUTDOOR_MAC]["connected"] = False
    result = adapter.reconnect_speaker("outdoor")
    assert result["connected"] is True
    assert ["bluetoothctl", "connect", OUTDOOR_MAC] in hardware.calls


def test_reconnect_speaker_failure_raises(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.speakers[INDOOR_MAC]["connected"] = False
    hardware.fail_connect.add(INDOOR_MAC)
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.reconnect_speaker("indoor")
    assert excinfo.value.code == "bluetooth_connect_failed"


def test_restart_service_uses_allowlisted_unit(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    result = adapter.restart_service(config, "librespot_outdoor")
    assert ["systemctl", "--user", "restart", "librespot@outdoor"] in hardware.calls
    assert result["activeState"] == "active"


def test_run_watchdog_starts_oneshot_service(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    result = adapter.run_watchdog(config)
    assert ["systemctl", "--user", "start", "bt-watchdog.service"] in hardware.calls
    assert result["started"] is True
    assert {item["speakerId"] for item in result["speakers"]} == {"indoor", "outdoor"}


def test_health_reasons_healthy_when_everything_up(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    adapter.set_speaker_systems(["indoor", "outdoor"])
    state, reasons = adapter.health_reasons(config)
    assert state == "healthy"
    assert reasons == []


def test_health_reasons_reports_degradation(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    hardware.units["pipewire"] = "inactive"
    hardware.units["bt-watchdog.timer"] = "inactive"
    hardware.speakers[OUTDOOR_MAC]["connected"] = False
    hardware.sinks.remove("whole_house")
    adapter._invalidate()
    state, reasons = adapter.health_reasons(config)
    assert state == "degraded"
    assert "pipewire_down" in reasons
    assert "outdoor_speaker_disconnected" in reasons
    assert "whole_house_sink_missing" in reasons
    assert "watchdog_inactive" in reasons


def test_health_reports_expected_endpoint_down(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    adapter.set_speaker_systems(["indoor"])
    hardware.units["librespot@indoor"] = "failed"
    adapter._invalidate()
    state, reasons = adapter.health_reasons(config)
    assert state == "degraded"
    assert "spotify_indoor_unhealthy" in reasons


def test_commands_never_contain_request_strings(adapter: RealHardwareAdapter, hardware: FakeHardware, config: AppConfig) -> None:
    adapter.set_speaker_systems(["indoor"])
    adapter.reconnect_speaker("indoor")
    adapter.restart_service(config, "pipewire")
    allowed_executables = {"systemctl", "bluetoothctl", "pactl"}
    assert all(call[0] in allowed_executables for call in hardware.calls)


NEW_MAC = "DE:AD:BE:EF:00:01"


def test_discover_devices_lists_known_devices_with_flags(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.speakers[OUTDOOR_MAC]["connected"] = False
    devices = {device["address"]: device for device in adapter.discover_devices()}
    assert devices[INDOOR_MAC]["name"] == "Indoor JBL"
    assert devices[INDOOR_MAC]["paired"] is True
    assert devices[OUTDOOR_MAC]["connected"] is False


def test_scan_discovers_new_devices(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.discoverable[NEW_MAC] = "New Patio Speaker"
    addresses = {device["address"] for device in adapter.discover_devices(scan_seconds=5)}
    assert NEW_MAC in addresses
    assert ["bluetoothctl", "--timeout", "5", "scan", "on"] in hardware.calls


def test_pair_speaker_pairs_trusts_and_connects(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.discoverable[NEW_MAC] = "New Patio Speaker"
    adapter.discover_devices(scan_seconds=5)
    result = adapter.pair_speaker(NEW_MAC)
    assert result["paired"] is True
    assert result["connected"] is True
    assert ["bluetoothctl", "trust", NEW_MAC] in hardware.calls


def test_pair_unknown_device_raises(adapter: RealHardwareAdapter) -> None:
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.pair_speaker(NEW_MAC)
    assert excinfo.value.code == "unknown_bluetooth_device"


def test_pair_failure_raises(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    hardware.discoverable[NEW_MAC] = "New Patio Speaker"
    hardware.fail_pair.add(NEW_MAC)
    adapter.discover_devices(scan_seconds=5)
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.pair_speaker(NEW_MAC)
    assert excinfo.value.code == "bluetooth_pair_failed"


def test_assign_speaker_updates_state_and_audio_config(adapter: RealHardwareAdapter, hardware: FakeHardware, tmp_path) -> None:
    hardware.discoverable[NEW_MAC] = "New Patio Speaker"
    adapter.discover_devices(scan_seconds=5)
    adapter.pair_speaker(NEW_MAC)
    new_sink = "bluez_output.DE_AD_BE_EF_00_01.1"
    hardware.sinks.append(new_sink)
    adapter._invalidate()

    result = adapter.assign_speaker("outdoor", NEW_MAC, "Patio")
    assert result["address"] == NEW_MAC
    assert result["displayName"] == "Patio"
    assert result["sinkName"] == new_sink

    # speaker state now follows the newly assigned device
    assert adapter._speaker_mac("outdoor") == NEW_MAC
    assert adapter.sink_present("outdoor") is True

    env_text = (tmp_path / "librespot" / "outdoor.env").read_text(encoding="utf-8")
    assert "SPEAKER_NAME=Patio" in env_text
    assert f"PULSE_SINK={new_sink}" in env_text
    combine_text = (tmp_path / "pipewire.conf.d" / "combine.conf").read_text(encoding="utf-8")
    assert new_sink in combine_text
    assert INDOOR_SINK in combine_text
    assert ["systemctl", "--user", "restart", "pipewire"] in hardware.calls

    # assignment persists across adapter restarts
    reloaded = RealHardwareAdapter(
        adapter.config,
        runner=hardware,
        boot_id_path=adapter.boot_id_path,
        uptime_path=adapter.uptime_path,
        assignments_path=tmp_path / "speaker-assignments.json",
        librespot_env_dir=tmp_path / "librespot",
        combine_conf_path=tmp_path / "pipewire.conf.d" / "combine.conf",
        user_systemd_dir=tmp_path / "systemd-user",
    )
    assert reloaded._speaker_mac("outdoor") == NEW_MAC


def test_assign_speaker_self_heals_missing_librespot_unit(
    adapter: RealHardwareAdapter, hardware: FakeHardware, tmp_path
) -> None:
    unit_path = tmp_path / "systemd-user" / "librespot@.service"
    assert not unit_path.exists()
    adapter.assign_speaker("indoor", INDOOR_MAC, None)
    assert unit_path.exists()
    body = unit_path.read_text(encoding="utf-8")
    assert "[Unit]" in body
    assert "EnvironmentFile=%h/.config/librespot/%i.env" in body
    # daemon-reload runs so the freshly written template is picked up before
    # any later systemctl --user start/restart from the adapter.
    assert ["systemctl", "--user", "daemon-reload"] in hardware.calls


def test_set_speaker_systems_self_heals_missing_librespot_unit(
    adapter: RealHardwareAdapter, hardware: FakeHardware, tmp_path
) -> None:
    unit_path = tmp_path / "systemd-user" / "librespot@.service"
    assert not unit_path.exists()
    adapter.set_speaker_systems(["indoor"])
    assert unit_path.exists()


def test_assign_speaker_restarts_running_endpoints(adapter: RealHardwareAdapter, hardware: FakeHardware) -> None:
    adapter.set_speaker_systems(["indoor", "outdoor"])
    hardware.calls.clear()
    adapter.assign_speaker("outdoor", OUTDOOR_MAC, None)
    assert ["systemctl", "--user", "restart", "librespot@outdoor"] in hardware.calls
    assert ["systemctl", "--user", "restart", "librespot@both"] in hardware.calls
    assert ["systemctl", "--user", "restart", "librespot@indoor"] not in hardware.calls


def test_assign_unknown_device_raises(adapter: RealHardwareAdapter) -> None:
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.assign_speaker("indoor", NEW_MAC, None)
    assert excinfo.value.code == "unknown_bluetooth_device"


def test_unassigned_speaker_reports_unassigned(hardware: FakeHardware, tmp_path) -> None:
    data = real_config_data()
    data["speakers"]["indoor"]["mac"] = None
    config = AppConfig.model_validate(data)
    adapter = RealHardwareAdapter(
        config,
        runner=hardware,
        boot_id_path=tmp_path / "boot_id",
        uptime_path=tmp_path / "uptime",
    )
    state = adapter.speaker_state("indoor")
    assert state["connected"] is False
    assert state["lastError"] == "speaker_unassigned"
    health_state, reasons = adapter.health_reasons(config)
    assert health_state == "degraded"
    assert "indoor_speaker_unassigned" in reasons
    with pytest.raises(AdapterCommandError) as excinfo:
        adapter.reconnect_speaker("indoor")
    assert excinfo.value.code == "speaker_unassigned"
