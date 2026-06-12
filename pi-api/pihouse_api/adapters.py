from __future__ import annotations

from datetime import UTC, datetime

from .config import AppConfig, ServiceId, SpeakerId


def utc_now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


class StubHardwareAdapter:
    """Phase 1 boundary for Pi hardware commands.

    Real PipeWire, Bluetooth, librespot, watchdog, and systemd calls must be
    added here with argument-array subprocess calls and config allowlist mapping.
    """

    boot_id = "stub-boot-id"
    boot_time = utc_now()

    def service_active_state(self, service_id: ServiceId) -> str:
        return "inactive" if service_id == "bt_watchdog" else "active"

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
