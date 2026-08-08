from __future__ import annotations

import json
from pathlib import Path

from app.models.base import TranscriptionOptions
from app.models.handy import HandyEngine


def _write_selected_model(settings_file: Path, model: str) -> None:
    settings_file.write_text(
        json.dumps({"settings": {"selected_model": model}}),
        encoding="utf-8",
    )


def _write_downloaded_model(cache: Path, model: str) -> Path:
    owner, repository, filename = model.split("/")
    model_path = cache / f"models--{owner}--{repository}" / "snapshots" / "revision" / filename
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_bytes(b"model")
    return model_path


async def test_handy_adapter_uses_downloaded_model_and_parses_json(
    tmp_path: Path,
) -> None:
    model = "owner/repository/model.gguf"
    binary = tmp_path / "handy"
    binary.write_text(
        "#!/bin/sh\nprintf '%s\\n' '{\"text\":\"private local result\"}'\n",
        encoding="utf-8",
    )
    binary.chmod(0o700)
    model_path = (
        tmp_path / "cache" / "models--owner--repository" / "snapshots" / "revision" / "model.gguf"
    )
    model_path.parent.mkdir(parents=True)
    model_path.write_bytes(b"model")
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    engine = HandyEngine(binary, model, huggingface_cache=tmp_path / "cache")
    health = await engine.health()
    transcript = await engine.transcribe(
        audio,
        TranscriptionOptions(language="auto", style="raw"),
    )

    assert health.ready is True
    assert health.name == f"handy:{model}"
    assert transcript == "private local result"


async def test_handy_health_is_false_when_model_is_not_downloaded(
    tmp_path: Path,
) -> None:
    binary = tmp_path / "handy"
    binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    binary.chmod(0o700)
    engine = HandyEngine(
        binary,
        "owner/repository/missing.gguf",
        huggingface_cache=tmp_path / "cache",
    )
    assert (await engine.health()).ready is False


async def test_handy_reports_an_unavailable_model_selected_in_the_app(tmp_path: Path) -> None:
    selected = "owner/repository/missing.gguf"
    settings_file = tmp_path / "settings_store.json"
    _write_selected_model(settings_file, selected)
    binary = tmp_path / "handy"
    binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    binary.chmod(0o700)

    engine = HandyEngine(
        binary,
        settings_file=settings_file,
        huggingface_cache=tmp_path / "cache",
    )

    health = await engine.health()
    assert health.ready is False
    assert health.name == f"handy:{selected}"


async def test_handy_retries_empty_primary_result_with_downloaded_fallback(
    tmp_path: Path,
) -> None:
    primary = "owner/primary/primary.gguf"
    fallback = "owner/fallback/fallback.gguf"
    binary = tmp_path / "handy"
    binary.write_text(
        "#!/bin/sh\n"
        'case "$*" in\n'
        "  *primary.gguf*) printf '%s\\n' '{\"text\":\"\"}' ;;\n"
        "  *) printf '%s\\n' '{\"text\":\"fallback result\"}' ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    binary.chmod(0o700)
    for model in (primary, fallback):
        owner, repository, filename = model.split("/")
        model_path = (
            tmp_path
            / "cache"
            / f"models--{owner}--{repository}"
            / "snapshots"
            / "revision"
            / filename
        )
        model_path.parent.mkdir(parents=True)
        model_path.write_bytes(b"model")
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")

    engine = HandyEngine(
        binary,
        primary,
        fallback_model=fallback,
        huggingface_cache=tmp_path / "cache",
    )

    transcript = await engine.transcribe(
        audio,
        TranscriptionOptions(language="auto", style="raw"),
    )

    assert transcript == "fallback result"


async def test_handy_follows_the_model_selected_in_the_app_without_restart(
    tmp_path: Path,
) -> None:
    first = "owner/first/first.gguf"
    second = "owner/second/second.gguf"
    settings_file = tmp_path / "settings_store.json"
    binary = tmp_path / "handy"
    binary.write_text(
        '#!/bin/sh\nprintf \'%s\\n\' "$*" > "$0.args"\nprintf \'%s\\n\' \'{"text":"result"}\'\n',
        encoding="utf-8",
    )
    binary.chmod(0o700)
    for model in (first, second):
        _write_downloaded_model(tmp_path / "cache", model)
    _write_selected_model(settings_file, first)
    engine = HandyEngine(
        binary,
        settings_file=settings_file,
        huggingface_cache=tmp_path / "cache",
    )

    assert (await engine.health()).name == f"handy:{first}"

    _write_selected_model(settings_file, second)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"audio")
    assert (await engine.health()).name == f"handy:{second}"
    assert (
        await engine.transcribe(audio, TranscriptionOptions(language="auto", style="raw"))
        == "result"
    )
    arguments = (tmp_path / "handy.args").read_text(encoding="utf-8")
    assert f"--model {second}" in arguments


async def test_handy_explicit_model_override_does_not_follow_the_app(
    tmp_path: Path,
) -> None:
    configured = "owner/configured/configured.gguf"
    selected = "owner/selected/selected.gguf"
    settings_file = tmp_path / "settings_store.json"
    binary = tmp_path / "handy"
    binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    binary.chmod(0o700)
    for model in (configured, selected):
        _write_downloaded_model(tmp_path / "cache", model)
    _write_selected_model(settings_file, selected)

    engine = HandyEngine(
        binary,
        configured,
        settings_file=settings_file,
        huggingface_cache=tmp_path / "cache",
    )

    assert (await engine.health()).name == f"handy:{configured}"
