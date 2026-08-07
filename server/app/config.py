from __future__ import annotations

import os
import secrets
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_HANDY_FALLBACK_MODEL = "handy-computer/whisper-base-gguf/whisper-base-Q8_0.gguf"
WILDCARD_BIND_HOSTS = frozenset({"0.0.0.0", "::"})
LEGACY_TOKEN_FILE = Path("~/.config/localflow/token")
LEGACY_CONFIG_FILE = Path("~/.config/localflow/config.json")
LEGACY_DATA_DIR = Path("~/.local/share/localflow")
MIGRATION_DOCS = (
    "https://github.com/VocaHQ/vocaphone/blob/main/docs/deployment.md"
    "#migrating-from-the-local-flow-working-name-v030"
)


def format_host_port(host: str, port: int) -> str:
    """Format a listener address without pretending it is a browsable URL."""
    display_host = f"[{host}]" if ":" in host else host
    return f"{display_host}:{port}"


def local_webui_url(host: str, port: int) -> str:
    """Return the loopback URL that opens a listener from the same host."""
    if host == "0.0.0.0":
        host = "127.0.0.1"
    elif host == "::":
        host = "::1"
    return f"http://{format_host_port(host, port)}/"


@dataclass(frozen=True, slots=True)
class Settings:
    token: str
    data_dir: Path
    whisper_binary: Path
    whisper_model: Path
    engine: str = "auto"
    handy_binary: Path = Path("/Applications/Handy.app/Contents/MacOS/handy")
    handy_model: str | None = None
    handy_fallback_model: str | None = DEFAULT_HANDY_FALLBACK_MODEL
    vocamac_app: Path = Path("/Applications/VocaMac.app")
    vocamac_model: str | None = None
    whisperkit_binary: str = "whisperkit-cli"
    models_dir: Path | None = None
    config_path: Path = Path("~/.config/vocaphone/config.json")
    bind_host: str = "0.0.0.0"
    port: int = 8765
    maximum_upload_bytes: int = 25 * 1024 * 1024
    maximum_duration_seconds: int = 120
    retention_hours: int = 24
    delete_successful_audio: bool = True
    maximum_concurrent_transcriptions: int = 1
    debug: bool = False

    def resolved_models_dir(self) -> Path:
        return self.models_dir if self.models_dir is not None else self.data_dir / "models"

    @classmethod
    def from_env(cls) -> Settings:
        _warn_ignored_legacy_env()
        token_file = Path(
            os.environ.get(
                "VOCAPHONE_TOKEN_FILE",
                "~/.config/vocaphone/token",
            )
        ).expanduser()
        token = os.environ.get("VOCAPHONE_TOKEN", "").strip()
        if not token and token_file.is_file():
            token = token_file.read_text(encoding="utf-8").strip()
        if not token:
            token = _resolve_or_migrate_token(token_file)
        if len(token) < 32:
            raise RuntimeError(
                "Set VOCAPHONE_TOKEN to at least 32 characters or create "
                "~/.config/vocaphone/token with mode 600."
            )
        data_dir = Path(
            os.environ.get("VOCAPHONE_DATA_DIR", "~/.local/share/vocaphone")
        ).expanduser()
        if "VOCAPHONE_DATA_DIR" not in os.environ:
            _warn_unmigrated_legacy_data(data_dir)
        config_path = Path(
            os.environ.get(
                "VOCAPHONE_CONFIG_FILE",
                "~/.config/vocaphone/config.json",
            )
        ).expanduser()
        if "VOCAPHONE_CONFIG_FILE" not in os.environ:
            _migrate_legacy_config_file(config_path)
        return cls(
            token=token,
            data_dir=data_dir,
            whisper_binary=Path(
                os.environ.get("VOCAPHONE_WHISPER_BINARY", "/opt/homebrew/bin/whisper-cli")
            ).expanduser(),
            whisper_model=Path(
                os.environ.get(
                    "VOCAPHONE_WHISPER_MODEL",
                    "~/.local/share/whisper.cpp/models/ggml-base.en.bin",
                )
            ).expanduser(),
            engine=os.environ.get("VOCAPHONE_ENGINE", "auto").lower(),
            handy_binary=Path(
                os.environ.get(
                    "VOCAPHONE_HANDY_BINARY",
                    "/Applications/Handy.app/Contents/MacOS/handy",
                )
            ).expanduser(),
            handy_model=os.environ.get("VOCAPHONE_HANDY_MODEL") or None,
            handy_fallback_model=os.environ.get(
                "VOCAPHONE_HANDY_FALLBACK_MODEL",
                DEFAULT_HANDY_FALLBACK_MODEL,
            )
            or None,
            vocamac_app=Path(
                os.environ.get("VOCAPHONE_VOCAMAC_APP", "/Applications/VocaMac.app")
            ).expanduser(),
            vocamac_model=os.environ.get("VOCAPHONE_VOCAMAC_MODEL") or None,
            whisperkit_binary=os.environ.get("VOCAPHONE_WHISPERKIT_BINARY", "whisperkit-cli"),
            models_dir=Path(
                os.environ.get("VOCAPHONE_MODELS_DIR", str(data_dir / "models"))
            ).expanduser(),
            config_path=config_path,
            bind_host=os.environ.get("VOCAPHONE_BIND_HOST", "0.0.0.0"),
            port=int(os.environ.get("VOCAPHONE_PORT", "8765")),
            retention_hours=int(os.environ.get("VOCAPHONE_RETENTION_HOURS", "24")),
            delete_successful_audio=os.environ.get(
                "VOCAPHONE_DELETE_SUCCESSFUL_AUDIO", "true"
            ).lower()
            in {"1", "true", "yes"},
            debug=os.environ.get("VOCAPHONE_DEBUG", "false").lower() in {"1", "true", "yes"},
        )


def _legacy_env_names() -> list[str]:
    return sorted(name for name in os.environ if name.startswith("LOCALFLOW_"))


def _warn_ignored_legacy_env() -> None:
    leftover = _legacy_env_names()
    if not leftover:
        return
    print(
        "vocaphone: LOCALFLOW_* environment variables are obsolete "
        f"({', '.join(leftover)}). Rename them to VOCAPHONE_*. "
        "LOCALFLOW_TOKEN / LOCALFLOW_TOKEN_FILE are read only for a "
        f"one-time bootstrap migration. See {MIGRATION_DOCS}",
        file=sys.stderr,
    )


def _legacy_token_candidates() -> list[tuple[str, str]]:
    """Return (source label, token) pairs from the pre-rename Local Flow layout."""
    candidates: list[tuple[str, str]] = []
    env_token = os.environ.get("LOCALFLOW_TOKEN", "").strip()
    if env_token:
        candidates.append(("LOCALFLOW_TOKEN", env_token))
    legacy_file = Path(os.environ.get("LOCALFLOW_TOKEN_FILE", str(LEGACY_TOKEN_FILE))).expanduser()
    if legacy_file.is_file():
        file_token = legacy_file.read_text(encoding="utf-8").strip()
        if file_token:
            candidates.append((str(legacy_file), file_token))
    return candidates


def _write_token_file(token_file: Path, token: str) -> None:
    token_file.parent.mkdir(parents=True, exist_ok=True)
    token_file.parent.chmod(0o700)
    descriptor = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(token + "\n")


def _resolve_or_migrate_token(token_file: Path) -> str:
    """Prefer a one-time Local Flow token migration over minting a new secret."""
    legacy = _legacy_token_candidates()
    if legacy:
        source, token = legacy[0]
        try:
            _write_token_file(token_file, token)
        except FileExistsError:
            # Another process created the destination between our checks.
            existing = token_file.read_text(encoding="utf-8").strip()
            if existing:
                return existing
            raise
        except OSError as exc:
            raise RuntimeError(
                "Found a Local Flow bootstrap token at "
                f"{source} but could not write {token_file}: {exc}. "
                f"Copy it manually, then restart. See {MIGRATION_DOCS}"
            ) from exc
        print(
            "vocaphone: migrated Local Flow bootstrap token from "
            f"{source} to {token_file}. Paired phones keep working. "
            f"See {MIGRATION_DOCS}",
            file=sys.stderr,
        )
        return token

    legacy_data = LEGACY_DATA_DIR.expanduser()
    if legacy_data.is_dir() and any(legacy_data.iterdir()):
        raise RuntimeError(
            "Found Local Flow data at "
            f"{legacy_data} but no vocaphone bootstrap token. "
            "Copy ~/.config/localflow/token to ~/.config/vocaphone/token "
            f"(or set VOCAPHONE_TOKEN) before starting the gateway so paired "
            f"phones are not locked out. See {MIGRATION_DOCS}"
        )
    return _generate_token(token_file)


def _migrate_legacy_config_file(config_path: Path) -> None:
    if config_path.is_file():
        return
    legacy = LEGACY_CONFIG_FILE.expanduser()
    if not legacy.is_file():
        return
    try:
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.parent.chmod(0o700)
        shutil.copy2(legacy, config_path)
        config_path.chmod(0o600)
    except OSError as exc:
        print(
            f"vocaphone: could not migrate {legacy} to {config_path}: {exc}. "
            f"Copy it manually if you need the previous WebUI settings. "
            f"See {MIGRATION_DOCS}",
            file=sys.stderr,
        )
        return
    print(
        f"vocaphone: migrated Local Flow config from {legacy} to {config_path}. "
        f"See {MIGRATION_DOCS}",
        file=sys.stderr,
    )


def _warn_unmigrated_legacy_data(data_dir: Path) -> None:
    if data_dir.exists():
        return
    legacy = LEGACY_DATA_DIR.expanduser()
    if not legacy.is_dir() or not any(legacy.iterdir()):
        return
    print(
        f"vocaphone: Local Flow data still exists at {legacy}. "
        f"Move or copy it to {data_dir} (and rename any "
        ".localflow-model.json markers to .vocaphone-model.json) to keep "
        f"downloaded models and session history. See {MIGRATION_DOCS}",
        file=sys.stderr,
    )


def _generate_token(token_file: Path) -> str:
    """First-run friendly default: create a private token automatically."""
    token = secrets.token_urlsafe(48)
    try:
        _write_token_file(token_file, token)
    except OSError:
        return token
    return token
