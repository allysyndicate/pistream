"""mDNS / Bonjour advertisement of the local API.

Publishes ``_pihouse-audio._tcp`` on port 8765 with the TXT records the Android
app uses to discover and verify the Pi before pasting any address or token:

- ``apiName=pihouse-audio-api`` (literal, matches /api/v1/identity)
- ``contractVersion=2026-06-phase3`` (matches /api/v1/identity)
- ``deviceId=<config.deviceId>`` (matches /api/v1/identity; the app re-checks
  this against /identity over IP before trusting the host)
- ``pairing=open|disabled`` (whether /api/v1/pairing/request-token will issue
  a token right now; reflects PairingStore state)

The publisher supervises an ``avahi-publish-service`` subprocess, since Avahi
is the mDNS responder shipped on Raspberry Pi OS. We never rely on a static
``/etc/avahi/services/*.service`` file because ``deviceId`` is config-driven
and ``pairing`` flips at runtime.

When ``avahi-publish-service`` is not on PATH (Windows dev hosts, CI), the
advertiser logs a warning once and stays disabled - the app's manual IP entry
fallback covers that case.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import threading
import time
from typing import Callable

logger = logging.getLogger(__name__)

SERVICE_TYPE = "_pihouse-audio._tcp"
SERVICE_NAME = "PiHouse Audio API"
SERVICE_PORT = 8765
POLL_INTERVAL_SECONDS = 5.0


class MdnsAdvertiser:
    """Supervises an ``avahi-publish-service`` subprocess.

    The subprocess holds the mDNS registration as long as it runs; killing it
    withdraws the record. We restart it whenever the ``pairing`` TXT record
    flips so the Android app sees current state without polling /identity.
    """

    def __init__(
        self,
        device_id: str,
        api_name: str,
        contract_version: str,
        pairing_open_provider: Callable[[], bool],
        *,
        port: int = SERVICE_PORT,
        publisher_binary: str | None = None,
        poll_interval_seconds: float = POLL_INTERVAL_SECONDS,
    ) -> None:
        self._device_id = device_id
        self._api_name = api_name
        self._contract_version = contract_version
        self._pairing_open_provider = pairing_open_provider
        self._port = port
        self._publisher_binary = publisher_binary
        self._poll_interval = poll_interval_seconds
        self._proc: subprocess.Popen | None = None
        self._stop_event = threading.Event()
        self._supervisor_thread: threading.Thread | None = None
        self._current_pairing: bool | None = None
        self._missing_logged = False

    def _resolve_binary(self) -> str | None:
        if self._publisher_binary:
            return self._publisher_binary
        return shutil.which("avahi-publish-service")

    def _build_args(self, binary: str, pairing_open: bool) -> list[str]:
        return [
            binary,
            SERVICE_NAME,
            SERVICE_TYPE,
            str(self._port),
            f"apiName={self._api_name}",
            f"contractVersion={self._contract_version}",
            f"deviceId={self._device_id}",
            f"pairing={'open' if pairing_open else 'disabled'}",
        ]

    def _spawn(self, pairing_open: bool) -> None:
        binary = self._resolve_binary()
        if binary is None:
            if not self._missing_logged:
                logger.warning(
                    "avahi-publish-service not found on PATH; mDNS advertisement disabled "
                    "(Android clients can still connect by manual IP)."
                )
                self._missing_logged = True
            return
        args = self._build_args(binary, pairing_open)
        try:
            self._proc = subprocess.Popen(  # noqa: S603 - args are allowlisted literals
                args,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                stdin=subprocess.DEVNULL,
                close_fds=True,
            )
        except (FileNotFoundError, OSError) as exc:
            logger.warning("failed to start avahi-publish-service (%s); mDNS disabled", exc)
            self._proc = None
            return
        self._current_pairing = pairing_open
        logger.info(
            "mDNS published: %s on %s:%d (pairing=%s)",
            SERVICE_TYPE,
            self._device_id,
            self._port,
            "open" if pairing_open else "disabled",
        )

    def _terminate(self) -> None:
        if self._proc is None:
            return
        try:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=2.0)
        except Exception:  # noqa: BLE001 - best-effort cleanup
            pass
        finally:
            self._proc = None
            self._current_pairing = None

    def _supervise(self) -> None:
        while not self._stop_event.is_set():
            pairing_open = False
            try:
                pairing_open = bool(self._pairing_open_provider())
            except Exception:  # noqa: BLE001 - never crash the supervisor
                logger.exception("pairing state provider raised; assuming closed")
                pairing_open = False
            if self._proc is None:
                self._spawn(pairing_open)
            else:
                if self._proc.poll() is not None:
                    logger.warning("avahi-publish-service exited; respawning")
                    self._proc = None
                    self._spawn(pairing_open)
                elif self._current_pairing != pairing_open:
                    self._terminate()
                    self._spawn(pairing_open)
            self._stop_event.wait(self._poll_interval)

    def start(self) -> None:
        if self._supervisor_thread is not None:
            return
        if os.environ.get("PIHOUSE_MDNS_DISABLED") == "1":
            logger.info("PIHOUSE_MDNS_DISABLED=1; mDNS advertisement skipped")
            return
        self._stop_event.clear()
        self._supervisor_thread = threading.Thread(
            target=self._supervise,
            name="pihouse-mdns",
            daemon=True,
        )
        self._supervisor_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        thread = self._supervisor_thread
        self._supervisor_thread = None
        if thread is not None:
            thread.join(timeout=3.0)
        self._terminate()

    def is_running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def current_pairing_state(self) -> bool | None:
        return self._current_pairing


def _sleep_briefly() -> None:  # pragma: no cover - timing helper for manual tests
    time.sleep(POLL_INTERVAL_SECONDS)
