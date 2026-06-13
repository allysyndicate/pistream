from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from pihouse_api import bootstrap


@pytest.fixture()
def fake_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setenv("USERPROFILE", str(tmp_path))
    return tmp_path


def write_active_config(home: Path, **overrides) -> Path:
    config_dir = home / ".config" / "pihouse-api"
    config_dir.mkdir(parents=True, exist_ok=True)
    token_path = config_dir / "token"
    token_path.write_text("preflight-token", encoding="utf-8")
    token_path.chmod(0o600)
    config = {
        "deviceId": "pihouse-audio-01",
        "controllerInstanceId": "ctrl_01JXYZPIHOUSEAUDIO",
        "tokenFile": str(token_path),
        "adapterMode": "real",
    }
    config.update(overrides)
    config_path = config_dir / "config.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    return config_path


def seed_full_realmode_install(home: Path) -> None:
    write_active_config(home)
    systemd_dir = home / ".config" / "systemd" / "user"
    systemd_dir.mkdir(parents=True, exist_ok=True)
    for name in ("librespot@.service", "pihouse-api.service", "bt-watchdog.service", "bt-watchdog.timer"):
        (systemd_dir / name).write_text("# preflight\n", encoding="utf-8")
    pipewire_dir = home / ".config" / "pipewire" / "pipewire.conf.d"
    pipewire_dir.mkdir(parents=True, exist_ok=True)
    (pipewire_dir / "combine.conf").write_text(
        "context.modules = [\n  { args = { combine.mode = sink\n    node.name = \"whole_house\" } }\n]\n",
        encoding="utf-8",
    )
    bin_dir = home / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    script = bin_dir / "bt-watchdog.sh"
    script.write_text("#!/bin/sh\n", encoding="utf-8")
    script.chmod(0o755)


def test_bootstrap_reports_missing_config(fake_home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("PIHOUSE_CONFIG", raising=False)
    result, data = bootstrap.check_active_config()
    assert result.status == "missing"
    assert data is None


def test_bootstrap_flags_stub_adapter_mode(fake_home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config_path = write_active_config(fake_home, adapterMode="stub")
    monkeypatch.setenv("PIHOUSE_CONFIG", str(config_path))
    result, data = bootstrap.check_active_config()
    assert result.status == "ok"
    mode_check = bootstrap.check_adapter_mode(data)
    assert mode_check.status == "missing"
    assert "stub" in mode_check.detail


def test_bootstrap_accepts_real_adapter_mode(fake_home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config_path = write_active_config(fake_home)
    monkeypatch.setenv("PIHOUSE_CONFIG", str(config_path))
    _, data = bootstrap.check_active_config()
    assert bootstrap.check_adapter_mode(data).status == "ok"


def test_bootstrap_flags_missing_units(fake_home: Path) -> None:
    result = bootstrap.check_user_unit("librespot@.service")
    assert result.status == "missing"


def test_bootstrap_passes_after_full_install(fake_home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    seed_full_realmode_install(fake_home)
    config_path = fake_home / ".config" / "pihouse-api" / "config.json"
    monkeypatch.setenv("PIHOUSE_CONFIG", str(config_path))

    config_check, data = bootstrap.check_active_config()
    assert config_check.status == "ok"
    assert bootstrap.check_adapter_mode(data).status == "ok"
    # On Windows chmod can't reproduce Linux 0o600, so we allow "warn" on the
    # permission check but still require the token to be present and readable.
    assert bootstrap.check_token(data).status in {"ok", "warn"}
    assert bootstrap.check_combine_conf().status == "ok"
    assert bootstrap.check_watchdog_script().status in {"ok", "warn"}
    for name in ("librespot@.service", "pihouse-api.service", "bt-watchdog.service", "bt-watchdog.timer"):
        assert bootstrap.check_user_unit(name).status == "ok", name


def test_bootstrap_main_exits_nonzero_when_anything_missing(
    fake_home: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv("PIHOUSE_CONFIG", raising=False)
    exit_code = bootstrap.main([])
    captured = capsys.readouterr().out
    assert exit_code != 0
    assert "summary:" in captured
    assert "missing" in captured
