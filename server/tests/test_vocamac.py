from __future__ import annotations

import json
import plistlib
from pathlib import Path

import pytest

from app.errors import EngineUnavailableError
from app.models.base import EngineTranscription, TranscriptionOptions
from app.models.vocamac import REQUIRED_COMPONENTS, VocaMacEngine

# Records the arguments of the last call so the one-shot CLI path stays inspectable.
FAKE_CLI = """#!/bin/sh
printf '%s\\n' "$@" > "$0.args"
case "$1" in
  serve) exit 1 ;;
esac
printf '%s\\n' 'private local result'
"""


def _write_model(models_dir: Path, variant: str, *, weight_bytes: int = 16) -> Path:
    """Create a complete VocaMac Core ML model folder."""
    directory = models_dir / variant
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "config.json").write_text("{}", encoding="utf-8")
    for component in REQUIRED_COMPONENTS:
        weights = directory / component / "weights"
        weights.mkdir(parents=True)
        (weights / "weight.bin").write_bytes(b"w" * weight_bytes)
        (directory / component / "metadata.json").write_text("[]", encoding="utf-8")
        (directory / component / "coremldata.bin").write_bytes(b"model")
    return directory


def _write_partial_model(models_dir: Path, variant: str) -> Path:
    """Create the folder an interrupted VocaMac download leaves behind."""
    directory = models_dir / variant
    for component in REQUIRED_COMPONENTS:
        (directory / component / "weights").mkdir(parents=True)
    return directory


def _write_preferences(tmp_path: Path, selected: str) -> None:
    with (tmp_path / "com.vocamac.app.plist").open("wb") as handle:
        plistlib.dump({"vocamac.selectedModelSize": selected}, handle)


def _build_engine(tmp_path: Path, *, model: str | None = None) -> tuple[VocaMacEngine, Path]:
    app_path = tmp_path / "VocaMac.app"
    app_path.mkdir(exist_ok=True)
    binary = tmp_path / "whisperkit-cli"
    binary.write_text(FAKE_CLI, encoding="utf-8")
    binary.chmod(0o700)
    models_dir = tmp_path / "support" / "models" / "models" / "argmaxinc" / "whisperkit-coreml"
    models_dir.mkdir(parents=True, exist_ok=True)
    engine = VocaMacEngine(
        str(binary),
        model,
        app_path=app_path,
        support_dir=tmp_path / "support",
        preferences_file=tmp_path / "com.vocamac.app.plist",
    )
    return engine, models_dir


def _build_headless_engine(
    tmp_path: Path,
    models: list[dict[str, object]],
    *,
    model: str | None = None,
    transcription: dict[str, object] | None = None,
    failure: dict[str, str] | None = None,
) -> tuple[VocaMacEngine, Path]:
    app_path = tmp_path / "VocaMac.app"
    executable = app_path / "Contents" / "MacOS" / "VocaMac"
    executable.parent.mkdir(parents=True)
    model_payload = json.dumps({"models": models}, separators=(",", ":"))
    transcription_payload = json.dumps(
        transcription
        or {
            "text": "private local result",
            "model": "parakeet-tdt-0.6b-v2",
            "engine": "parakeet",
            "detected_language": "en",
            "duration_seconds": 0.125,
            "audio_length_seconds": 1.0,
        },
        separators=(",", ":"),
    )
    failure_payload = json.dumps(failure, separators=(",", ":")) if failure else ""
    executable.write_text(
        "#!/bin/sh\n"
        "# --transcribe-file capability marker\n"
        'printf \'%s\\n\' "$@" > "$0.args"\n'
        'case "$1" in\n'
        f"  --list-models) printf '%s\\n' '{model_payload}' ;;\n"
        + (
            f"  --transcribe-file) printf '%s\\n' '{failure_payload}' >&2; exit 4 ;;\n"
            if failure
            else f"  --transcribe-file) printf '%s\\n' '{transcription_payload}' ;;\n"
        )
        + "  *) exit 2 ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    executable.chmod(0o700)
    engine = VocaMacEngine(
        str(tmp_path / "missing-whisperkit-cli"),
        model,
        app_path=app_path,
        support_dir=tmp_path / "support",
        preferences_file=tmp_path / "com.vocamac.app.plist",
    )
    return engine, executable


def _headless_model(
    model_id: str,
    *,
    selected: bool,
    downloaded: bool = True,
    supported: bool = True,
) -> dict[str, object]:
    return {
        "id": model_id,
        "name": model_id,
        "engine": "parakeet" if model_id.startswith("parakeet") else "whisperkit",
        "selected": selected,
        "downloaded": downloaded,
        "supported": supported,
        "system_managed": False,
    }


async def test_vocamac_headless_cli_uses_the_apps_selected_parakeet_model(
    tmp_path: Path,
) -> None:
    engine, executable = _build_headless_engine(
        tmp_path,
        [
            _headless_model("small", selected=False),
            _headless_model("parakeet-tdt-0.6b-v2", selected=True),
        ],
    )
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    assert engine.is_available() is True
    assert (await engine.health()).name == "vocamac:parakeet-tdt-0.6b-v2"
    result = await engine.transcribe(audio, TranscriptionOptions("en", "raw"))

    assert result.text == "private local result"
    assert result.inference_ms == 125
    arguments = Path(f"{executable}.args").read_text(encoding="utf-8").splitlines()
    assert arguments[:3] == ["--transcribe-file", str(audio), "--json"]
    assert arguments[arguments.index("--language") + 1] == "en"
    assert "--model" not in arguments


async def test_vocamac_headless_cli_honours_an_explicit_model_override(
    tmp_path: Path,
) -> None:
    engine, executable = _build_headless_engine(
        tmp_path,
        [
            _headless_model("small", selected=False),
            _headless_model("parakeet-tdt-0.6b-v2", selected=True),
        ],
        model="openai_whisper-small",
    )
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    assert engine.is_available() is True
    assert (await engine.health()).name == "vocamac:small"
    await engine.transcribe(audio, TranscriptionOptions("auto", "raw"))

    arguments = Path(f"{executable}.args").read_text(encoding="utf-8").splitlines()
    assert arguments[arguments.index("--model") + 1] == "small"
    assert arguments[arguments.index("--language") + 1] == "auto"


async def test_vocamac_headless_cli_reports_an_unavailable_selected_model(
    tmp_path: Path,
) -> None:
    engine, _ = _build_headless_engine(
        tmp_path,
        [_headless_model("parakeet-tdt-0.6b-v2", selected=True, downloaded=False)],
    )

    health = await engine.health()

    assert engine.is_available() is False
    assert health.ready is False
    assert health.name == "vocamac:parakeet-tdt-0.6b-v2"


async def test_vocamac_headless_cli_surfaces_model_failures_as_unavailable(
    tmp_path: Path,
) -> None:
    engine, _ = _build_headless_engine(
        tmp_path,
        [_headless_model("parakeet-tdt-0.6b-v2", selected=True)],
        failure={
            "error": "model_not_downloaded",
            "message": "Model is not downloaded: parakeet-tdt-0.6b-v2",
        },
    )
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    with pytest.raises(EngineUnavailableError, match="Model is not downloaded"):
        await engine.transcribe(audio, TranscriptionOptions("auto", "raw"))


async def test_vocamac_runs_the_model_selected_in_the_app(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_model(models_dir, "openai_whisper-tiny", weight_bytes=512)
    selected = _write_model(models_dir, "openai_whisper-small")
    _write_preferences(tmp_path, "small")
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    health = await engine.health()
    transcript = await engine.transcribe(audio, TranscriptionOptions("auto", "raw"))
    arguments = (tmp_path / "whisperkit-cli.args").read_text(encoding="utf-8").splitlines()

    assert health.ready is True
    assert health.name == "vocamac:openai_whisper-small"
    assert isinstance(transcript, EngineTranscription)
    assert transcript.text == "private local result"
    assert arguments[arguments.index("--model-path") + 1] == str(selected)
    # VocaMac already downloaded the matching tokenizer, so reuse it.
    assert arguments[arguments.index("--download-tokenizer-path") + 1] == str(
        tmp_path / "support" / "models"
    )


async def test_vocamac_skips_an_interrupted_download(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_partial_model(models_dir, "openai_whisper-tiny")
    _write_model(models_dir, "openai_whisper-small")
    _write_preferences(tmp_path, "tiny")

    health = await engine.health()

    assert health.ready is True
    assert health.name == "vocamac:openai_whisper-small"


async def test_vocamac_does_not_replace_a_selected_parakeet_model_with_whisper(
    tmp_path: Path,
) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_model(models_dir, "openai_whisper-small")
    _write_preferences(tmp_path, "parakeet-tdt-0.6b-v2")

    health = await engine.health()

    assert engine.is_available() is False
    assert health.ready is False
    assert health.name == "vocamac:parakeet-tdt-0.6b-v2"

    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")
    with pytest.raises(EngineUnavailableError, match="not a WhisperKit model"):
        await engine.transcribe(audio, TranscriptionOptions("auto", "raw"))


async def test_vocamac_does_not_replace_an_unknown_future_model_with_whisper(
    tmp_path: Path,
) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_model(models_dir, "openai_whisper-small")
    _write_preferences(tmp_path, "future-vocamac-engine-model")

    health = await engine.health()

    assert engine.is_available() is False
    assert health.ready is False
    assert health.name == "vocamac:future-vocamac-engine-model"


async def test_vocamac_prefers_the_largest_model_without_a_selection(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_model(models_dir, "openai_whisper-tiny", weight_bytes=16)
    largest = _write_model(models_dir, "openai_whisper-small", weight_bytes=512)

    assert (await engine.health()).name == f"vocamac:{largest.name}"


async def test_vocamac_honours_an_explicit_model_override(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path, model="tiny")
    pinned = _write_model(models_dir, "openai_whisper-tiny")
    _write_model(models_dir, "openai_whisper-small", weight_bytes=512)
    _write_preferences(tmp_path, "small")

    assert (await engine.health()).name == f"vocamac:{pinned.name}"


async def test_vocamac_never_substitutes_a_configured_model(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path, model="large-v3")
    _write_model(models_dir, "openai_whisper-small")

    health = await engine.health()

    assert engine.is_available() is False
    assert health.ready is False
    assert health.name == "vocamac:openai_whisper-large-v3"


async def test_vocamac_is_unavailable_without_the_app_or_a_model(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path)

    assert engine.is_available() is False
    assert (await engine.health()).name == "vocamac:no-model-selected"

    _write_model(models_dir, "openai_whisper-small")
    assert engine.is_available() is True

    engine.app_path.rmdir()
    assert engine.is_available() is False
    assert (await engine.health()).ready is False


async def test_vocamac_is_unavailable_without_whisperkit_cli(tmp_path: Path) -> None:
    engine, models_dir = _build_engine(tmp_path)
    _write_model(models_dir, "openai_whisper-small")
    Path(engine.whisperkit_binary).unlink()

    assert engine.is_available() is False
    assert (await engine.health()).ready is False
