"""Pairing-window-gated bearer token issuance for first-time app pairing.

Lets the Android app finish onboarding without the operator pasting a token. A
pairing window must be open for the public ``POST /api/v1/pairing/request-token``
endpoint to issue the shared bearer; while the window is closed the endpoint
returns 403.

Policy (matches the deployment story):

- ``adapterMode == "stub"`` (local dev): window is implicitly always open. No
  CLI gate so dev/QA can pair without an extra step.
- ``adapterMode == "real"`` (Raspberry Pi): window is closed by default and
  opened explicitly by the operator with ``python -m pihouse_api.pairing
  --open 5m``. ``setup-pi.sh`` opens an initial 5-minute window so the very
  first handshake works without a separate SSH session.

The state file at ``.state/pairing.json`` is the source of truth for the real
mode. The API re-reads it on each request, and the mDNS publisher polls it so
the ``pairing=open|disabled`` TXT record reflects current state.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field

DEFAULT_PAIRING_DURATION_SECONDS = 5 * 60
MAX_PAIRING_DURATION_SECONDS = 60 * 60
DURATION_PATTERN = re.compile(r"^\s*(\d+)\s*(s|sec|secs|m|min|mins)?\s*$", re.IGNORECASE)


class PairingRequest(BaseModel):
    clientName: str = Field(min_length=1, max_length=80)
    clientInstanceId: str


def _iso(dt: datetime) -> str:
    return dt.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _now() -> datetime:
    return datetime.now(UTC)


def parse_duration(value: str) -> int:
    match = DURATION_PATTERN.match(value)
    if not match:
        raise ValueError(f"invalid duration {value!r}; use e.g. '5m', '300s', '300'")
    number = int(match.group(1))
    unit = (match.group(2) or "s").lower()
    seconds = number * 60 if unit.startswith("m") else number
    if seconds <= 0:
        raise ValueError("duration must be positive")
    if seconds > MAX_PAIRING_DURATION_SECONDS:
        raise ValueError(f"duration cannot exceed {MAX_PAIRING_DURATION_SECONDS} seconds")
    return seconds


class PairingState(BaseModel):
    openUntil: datetime | None = None
    issuances: list[dict[str, Any]] = Field(default_factory=list)


class PairingStore:
    """Persistent pairing-window state plus an in-memory always-open override.

    The ``stub_always_open`` flag is for local dev/test only; nothing on disk
    encodes it because stub mode is never used on the Pi.
    """

    def __init__(self, path: Path, stub_always_open: bool = False) -> None:
        self.path = path
        self._stub_always_open = stub_always_open

    def _read(self) -> PairingState:
        if not self.path.exists():
            return PairingState()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return PairingState()
        return PairingState.model_validate(data)

    def _write(self, state: PairingState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = state.model_dump(mode="json")
        self.path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def snapshot(self) -> PairingState:
        return self._read()

    def is_window_open(self) -> bool:
        if self._stub_always_open:
            return True
        state = self._read()
        if state.openUntil is None:
            return False
        return state.openUntil > _now()

    def open_until(self) -> datetime | None:
        if self._stub_always_open:
            return None
        return self._read().openUntil

    def open_window(self, duration_seconds: int) -> datetime:
        if duration_seconds <= 0:
            raise ValueError("duration_seconds must be positive")
        duration_seconds = min(duration_seconds, MAX_PAIRING_DURATION_SECONDS)
        state = self._read()
        state.openUntil = _now() + timedelta(seconds=duration_seconds)
        self._write(state)
        return state.openUntil

    def close_window(self) -> None:
        state = self._read()
        state.openUntil = None
        self._write(state)

    def find_recent_issuance(self, client_instance_id: str) -> dict[str, Any] | None:
        state = self._read()
        for record in reversed(state.issuances):
            if record.get("clientInstanceId") == client_instance_id:
                return record
        return None

    def record_issuance(self, client_name: str, client_instance_id: str) -> dict[str, Any]:
        state = self._read()
        record = {
            "clientName": client_name,
            "clientInstanceId": client_instance_id,
            "issuedAt": _iso(_now()),
        }
        # Keep only the most recent 20 issuances to bound the file.
        state.issuances = state.issuances[-19:] + [record]
        self._write(state)
        return record


def validate_client_instance_id(value: str) -> None:
    parsed = uuid.UUID(value)
    if parsed.version != 4:
        raise ValueError("clientInstanceId must be a UUIDv4")


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m pihouse_api.pairing",
        description="Open or close the PiHouse pairing window for first-time Android pairing.",
    )
    sub = parser.add_subparsers(dest="command")

    open_parser = sub.add_parser("open", help="Open the pairing window for a duration (default 5m).")
    open_parser.add_argument("duration", nargs="?", default="5m", help="Window duration, e.g. '5m', '300s', '300'.")

    sub.add_parser("close", help="Close the pairing window immediately.")
    sub.add_parser("status", help="Print whether the pairing window is currently open.")

    # Convenience aliases so the message we surface to the app reads naturally.
    parser.add_argument("--open", dest="open_alias", metavar="DURATION", help="Same as 'open DURATION'.")
    parser.add_argument("--close", dest="close_alias", action="store_true", help="Same as 'close'.")
    parser.add_argument("--status", dest="status_alias", action="store_true", help="Same as 'status'.")
    return parser


def _resolve_state_path() -> Path:
    """Resolve the pairing state file path the same way ``app.py`` does.

    Importing the config here is heavy and unnecessary for the CLI - we mirror
    the convention: ``ROOT/.state/pairing.json`` where ``ROOT`` is the parent
    of this package.
    """

    return Path(__file__).resolve().parent.parent / ".state" / "pairing.json"


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    command = args.command
    duration_str: str | None = None
    if args.open_alias is not None:
        command = "open"
        duration_str = args.open_alias
    elif args.close_alias:
        command = "close"
    elif args.status_alias:
        command = "status"
    elif command == "open":
        duration_str = getattr(args, "duration", "5m")

    if command is None:
        parser.print_help()
        return 2

    store = PairingStore(_resolve_state_path())

    if command == "open":
        try:
            seconds = parse_duration(duration_str or "5m")
        except ValueError as exc:
            print(f"pairing: {exc}", file=sys.stderr)
            return 2
        until = store.open_window(seconds)
        print(f"pairing window open until {_iso(until)} ({seconds}s)")
        return 0

    if command == "close":
        store.close_window()
        print("pairing window closed")
        return 0

    if command == "status":
        state = store.snapshot()
        if state.openUntil is None or state.openUntil <= _now():
            print("pairing window: closed")
        else:
            remaining = int((state.openUntil - _now()).total_seconds())
            print(f"pairing window: open ({remaining}s remaining, until {_iso(state.openUntil)})")
        if state.issuances:
            print(f"issuances: {len(state.issuances)} (most recent: {state.issuances[-1].get('issuedAt')})")
        return 0

    parser.print_help()
    return 2


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    sys.exit(main())
