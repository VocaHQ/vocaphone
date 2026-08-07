from __future__ import annotations

from pathlib import Path

import pytest
from pytest import MonkeyPatch, CaptureFixture

from app.config import Settings, format_host_port, local_webui_url


def _isolate_home(monkeypatch: MonkeyPatch, tmp_path: Path) -> Path:
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    for name in list(__import__("os").environ):
        if name.startswith("LOCALFLOW_") or name.startswith("VOCAPHONE_"):
            monkeypatch.delenv(name, raising=False)
    return home


def test_environment_defaults_to_all_interface_listener(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setenv("VOCAPHONE_TOKEN", "test-" + ("x" * 48))
    monkeypatch.delenv("VOCAPHONE_BIND_HOST", raising=False)

    settings = Settings.from_env()

    assert settings.bind_host == "0.0.0.0"
    assert format_host_port(settings.bind_host, settings.port) == "0.0.0.0:8765"
    assert local_webui_url(settings.bind_host, settings.port) == "http://127.0.0.1:8765/"


def test_ipv6_listener_and_local_url_are_bracketed() -> None:
    assert format_host_port("::", 8765) == "[::]:8765"
    assert local_webui_url("::", 8765) == "http://[::1]:8765/"


def test_migrates_legacy_token_file_instead_of_minting(
    tmp_path: Path, monkeypatch: MonkeyPatch, capsys: CaptureFixture[str]
) -> None:
    home = _isolate_home(monkeypatch, tmp_path)
    legacy_dir = home / ".config" / "localflow"
    legacy_dir.mkdir(parents=True)
    legacy_token = legacy_dir / "token"
    legacy_token.write_text("legacy-token-with-at-least-thirty-two-chars\n", encoding="utf-8")

    settings = Settings.from_env()

    migrated = home / ".config" / "vocaphone" / "token"
    assert settings.token == "legacy-token-with-at-least-thirty-two-chars"
    assert migrated.read_text(encoding="utf-8").strip() == settings.token
    assert "migrated Local Flow bootstrap token" in capsys.readouterr().err


def test_migrates_legacy_token_env_instead_of_minting(
    tmp_path: Path, monkeypatch: MonkeyPatch, capsys: CaptureFixture[str]
) -> None:
    _isolate_home(monkeypatch, tmp_path)
    monkeypatch.setenv("LOCALFLOW_TOKEN", "env-legacy-token-with-at-least-thirty-two")

    settings = Settings.from_env()

    assert settings.token == "env-legacy-token-with-at-least-thirty-two"
    err = capsys.readouterr().err
    assert "LOCALFLOW_* environment variables are obsolete" in err
    assert "migrated Local Flow bootstrap token" in err


def test_migrates_legacy_config_file(
    tmp_path: Path, monkeypatch: MonkeyPatch, capsys: CaptureFixture[str]
) -> None:
    home = _isolate_home(monkeypatch, tmp_path)
    monkeypatch.setenv("VOCAPHONE_TOKEN", "test-" + ("x" * 48))
    legacy_config = home / ".config" / "localflow" / "config.json"
    legacy_config.parent.mkdir(parents=True)
    legacy_config.write_text('{"engine":"sherpa-onnx"}', encoding="utf-8")

    settings = Settings.from_env()

    assert settings.config_path.read_text(encoding="utf-8") == '{"engine":"sherpa-onnx"}'
    assert "migrated Local Flow config" in capsys.readouterr().err


def test_warns_about_unmigrated_legacy_data_dir(
    tmp_path: Path, monkeypatch: MonkeyPatch, capsys: CaptureFixture[str]
) -> None:
    home = _isolate_home(monkeypatch, tmp_path)
    monkeypatch.setenv("VOCAPHONE_TOKEN", "test-" + ("x" * 48))
    legacy_data = home / ".local" / "share" / "localflow"
    (legacy_data / "models").mkdir(parents=True)
    (legacy_data / "models" / "marker.txt").write_text("keep", encoding="utf-8")

    Settings.from_env()

    err = capsys.readouterr().err
    assert "Local Flow data still exists" in err
    assert str(legacy_data) in err


def test_refuses_to_mint_when_legacy_data_exists_without_token(
    tmp_path: Path, monkeypatch: MonkeyPatch
) -> None:
    home = _isolate_home(monkeypatch, tmp_path)
    legacy_data = home / ".local" / "share" / "localflow"
    legacy_data.mkdir(parents=True)
    (legacy_data / "sessions.sqlite3").write_text("x", encoding="utf-8")

    with pytest.raises(RuntimeError, match="no vocaphone bootstrap token"):
        Settings.from_env()


def test_fresh_install_still_mints_a_token(
    tmp_path: Path, monkeypatch: MonkeyPatch
) -> None:
    home = _isolate_home(monkeypatch, tmp_path)

    settings = Settings.from_env()

    token_file = home / ".config" / "vocaphone" / "token"
    assert len(settings.token) >= 32
    assert token_file.read_text(encoding="utf-8").strip() == settings.token
