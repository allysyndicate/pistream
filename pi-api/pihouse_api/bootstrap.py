"""Preflight diagnostics for a real-mode PiHouse audio install.

Run on the Pi after ``deploy/setup-pi.sh`` (or instead of, to find out what is
missing). Probes every host-side precondition the real adapter assumes, using
the same kinds of allowlisted commands the adapter itself runs.

Usage::

    python -m pihouse_api.bootstrap

Exits 0 when everything required is present, non-zero otherwise. Each check is
printed with status ``OK``/``MISSING``/``WARN`` and a short reason, so the
operator can fix the gaps before the API is restarted.

This file deliberately has no dependencies outside the standard library so it
runs before the venv is fully set up.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

APT_PACKAGES: tuple[str, ...] = (
    "pipewire",
    "pipewire-pulse",
    "wireplumber",
    "libspa-0.2-bluetooth",
    "pulseaudio-utils",
    "bluez",
)


def _home() -> Path:
    return Path(os.path.expanduser("~"))


def _user_systemd_dir() -> Path:
    return _home() / ".config" / "systemd" / "user"


def _config_dir() -> Path:
    return _home() / ".config" / "pihouse-api"


@dataclass
class CheckResult:
    name: str
    status: str  # "ok", "missing", "warn"
    detail: str

    @property
    def required(self) -> bool:
        return self.status == "missing"


def _run(args: list[str], timeout: float = 5.0) -> subprocess.CompletedProcess:
    return subprocess.run(
        args,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )


def check_apt_packages() -> list[CheckResult]:
    if shutil.which("dpkg") is None:
        return [CheckResult("apt packages", "warn", "dpkg not found (not on Debian); skipped")]
    results: list[CheckResult] = []
    for pkg in APT_PACKAGES:
        try:
            proc = _run(["dpkg", "-s", pkg])
        except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
            results.append(CheckResult(f"apt:{pkg}", "missing", f"dpkg failed: {exc}"))
            continue
        if proc.returncode != 0:
            results.append(CheckResult(f"apt:{pkg}", "missing", "not installed (apt-get install)"))
        else:
            results.append(CheckResult(f"apt:{pkg}", "ok", "installed"))
    return results


def check_librespot() -> CheckResult:
    path = shutil.which("librespot")
    if path:
        return CheckResult("librespot", "ok", path)
    return CheckResult(
        "librespot",
        "missing",
        "not on PATH (install Raspotify or copy librespot to /usr/bin/)",
    )


def check_linger(user: str) -> CheckResult:
    if shutil.which("loginctl") is None:
        return CheckResult("linger", "warn", "loginctl not found; cannot check")
    try:
        proc = _run(["loginctl", "show-user", user])
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return CheckResult("linger", "missing", f"loginctl failed: {exc}")
    if proc.returncode != 0:
        return CheckResult("linger", "missing", f"loginctl show-user failed: {proc.stderr.strip()}")
    if "Linger=yes" in proc.stdout:
        return CheckResult("linger", "ok", f"linger enabled for {user}")
    return CheckResult(
        "linger",
        "missing",
        f"loginctl enable-linger {user} (so user services keep running after logout)",
    )


def check_user_unit(name: str) -> CheckResult:
    unit_path = _user_systemd_dir() / name
    if unit_path.exists():
        return CheckResult(f"unit:{name}", "ok", str(unit_path))
    return CheckResult(f"unit:{name}", "missing", f"expected at {unit_path}")


def check_user_file(name: str, path: Path, hint: str | None = None) -> CheckResult:
    if path.exists():
        return CheckResult(name, "ok", str(path))
    detail = hint or f"expected at {path}"
    return CheckResult(name, "missing", detail)


def check_systemctl_enabled(unit: str) -> CheckResult:
    if shutil.which("systemctl") is None:
        return CheckResult(f"enabled:{unit}", "warn", "systemctl not found; skipped")
    try:
        proc = _run(["systemctl", "--user", "is-enabled", unit])
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return CheckResult(f"enabled:{unit}", "missing", f"systemctl failed: {exc}")
    state = proc.stdout.strip() or proc.stderr.strip()
    if state in {"enabled", "static", "enabled-runtime", "alias", "indirect"}:
        return CheckResult(f"enabled:{unit}", "ok", state)
    return CheckResult(f"enabled:{unit}", "missing", f"systemctl --user enable {unit} ({state or 'unknown'})")


def check_active_config() -> tuple[CheckResult, dict | None]:
    config_path = Path(os.environ.get("PIHOUSE_CONFIG", str(_config_dir() / "config.json")))
    if not config_path.exists():
        return (
            CheckResult(
                "active-config",
                "missing",
                f"no config at {config_path} (run setup-pi.sh)",
            ),
            None,
        )
    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return CheckResult("active-config", "missing", f"unreadable JSON: {exc}"), None
    return CheckResult("active-config", "ok", str(config_path)), data


def check_adapter_mode(config_data: dict | None) -> CheckResult:
    if config_data is None:
        return CheckResult("adapterMode", "missing", "no active config")
    mode = config_data.get("adapterMode")
    if mode == "real":
        return CheckResult("adapterMode", "ok", "real")
    if mode is None:
        return CheckResult(
            "adapterMode",
            "missing",
            'adapterMode not set (config refuses to load — must be "real" on the Pi)',
        )
    return CheckResult(
        "adapterMode",
        "missing",
        f'adapterMode={mode!r} (expected "real" on the Pi)',
    )


def check_token(config_data: dict | None) -> CheckResult:
    if config_data is None:
        return CheckResult("token", "missing", "no active config")
    token_field = config_data.get("tokenFile")
    if not token_field:
        return CheckResult("token", "missing", "tokenFile missing from config")
    token_path = Path(token_field)
    if not token_path.exists():
        return CheckResult("token", "missing", f"token file {token_path} not present")
    try:
        contents = token_path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        return CheckResult("token", "missing", f"cannot read token: {exc}")
    if not contents:
        return CheckResult("token", "missing", f"token file {token_path} is empty")
    mode = token_path.stat().st_mode & 0o777
    if mode & 0o077:
        return CheckResult(
            "token",
            "warn",
            f"token {token_path} has permissive mode {mode:o} (chmod 600 recommended)",
        )
    return CheckResult("token", "ok", str(token_path))


def check_librespot_envs() -> list[CheckResult]:
    base = _home() / ".config" / "librespot"
    results: list[CheckResult] = []
    for endpoint in ("indoor", "outdoor", "both"):
        env_path = base / f"{endpoint}.env"
        if env_path.exists():
            results.append(CheckResult(f"librespot-env:{endpoint}", "ok", str(env_path)))
        else:
            results.append(
                CheckResult(
                    f"librespot-env:{endpoint}",
                    "warn",
                    f"{env_path} not present (assign-speaker writes it)",
                )
            )
    return results


def check_combine_conf() -> CheckResult:
    path = _home() / ".config" / "pipewire" / "pipewire.conf.d" / "combine.conf"
    if not path.exists():
        return CheckResult(
            "combine-sink",
            "missing",
            f"{path} not present (setup-pi.sh installs the default)",
        )
    text = path.read_text(encoding="utf-8")
    if "combine.mode = sink" not in text or "whole_house" not in text:
        return CheckResult(
            "combine-sink",
            "warn",
            f"{path} present but does not declare combine.mode=sink whole_house",
        )
    return CheckResult("combine-sink", "ok", str(path))


def check_watchdog_script() -> CheckResult:
    path = _home() / "bin" / "bt-watchdog.sh"
    if not path.exists():
        return CheckResult("watchdog-script", "missing", f"{path} not present (setup-pi.sh installs it)")
    mode = path.stat().st_mode & 0o111
    if not mode:
        return CheckResult("watchdog-script", "warn", f"{path} present but not executable")
    return CheckResult("watchdog-script", "ok", str(path))


def collect_checks() -> list[CheckResult]:
    results: list[CheckResult] = []
    results.extend(check_apt_packages())
    results.append(check_librespot())

    user = os.environ.get("USER") or os.environ.get("LOGNAME") or _home().name
    results.append(check_linger(user))

    for name in ("librespot@.service", "pihouse-api.service", "bt-watchdog.service", "bt-watchdog.timer"):
        results.append(check_user_unit(name))

    results.append(check_watchdog_script())
    results.append(check_combine_conf())
    results.extend(check_librespot_envs())

    config_check, config_data = check_active_config()
    results.append(config_check)
    results.append(check_adapter_mode(config_data))
    results.append(check_token(config_data))

    results.append(check_systemctl_enabled("bt-watchdog.timer"))
    results.append(check_systemctl_enabled("pihouse-api.service"))

    return results


_STATUS_LABELS = {
    "ok": "OK     ",
    "warn": "WARN   ",
    "missing": "MISSING",
}


def render(results: list[CheckResult]) -> str:
    lines = []
    for item in results:
        label = _STATUS_LABELS.get(item.status, item.status.upper())
        lines.append(f"[{label}] {item.name:<28} {item.detail}")
    missing = sum(1 for item in results if item.status == "missing")
    warns = sum(1 for item in results if item.status == "warn")
    oks = sum(1 for item in results if item.status == "ok")
    lines.append("")
    lines.append(f"summary: {oks} ok, {warns} warn, {missing} missing")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    _ = argv  # currently no flags
    results = collect_checks()
    print(render(results))
    return 1 if any(item.status == "missing" for item in results) else 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    sys.exit(main())
