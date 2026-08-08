from __future__ import annotations

import asyncio
import json
import os
import plistlib
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.errors import EngineUnavailableError, TranscriptionProcessError
from app.models.base import EngineHealth, EngineTranscription, TranscriptionOptions
from app.models.whisperkit import WhisperKitEngine

DEFAULT_VOCAMAC_APP = Path("/Applications/VocaMac.app")
DEFAULT_SUPPORT_DIR = Path("~/Library/Application Support/VocaMac")
DEFAULT_PREFERENCES_FILE = Path("~/Library/Preferences/com.vocamac.app.plist")
SELECTED_MODEL_KEY = "vocamac.selectedModelSize"
MODEL_REPOSITORY = "argmaxinc/whisperkit-coreml"

# VocaMac stores a `ModelSize` raw value in its preferences but names the model
# folder after the WhisperKit variant. This is `ModelManager.whisperKitModelName`
# from the VocaMac source.
MODEL_VARIANTS = {
    "tiny": "openai_whisper-tiny",
    "base": "openai_whisper-base",
    "small": "openai_whisper-small",
    "medium": "openai_whisper-medium",
    "large-v3": "openai_whisper-large-v3",
    "large-v3_turbo": "openai_whisper-large-v3_turbo",
    "large-v3-v20240930": "openai_whisper-large-v3-v20240930",
    "large-v3-v20240930_turbo": "openai_whisper-large-v3-v20240930_turbo",
    "large-v3-v20240930_626MB": "openai_whisper-large-v3-v20240930_626MB",
    "large-v3-v20240930_turbo_632MB": "openai_whisper-large-v3-v20240930_turbo_632MB",
    "distil-large-v3_594MB": "distil-whisper_distil-large-v3_594MB",
    "distil-large-v3_turbo_600MB": "distil-whisper_distil-large-v3_turbo_600MB",
}

REQUIRED_COMPONENTS = (
    "MelSpectrogram.mlmodelc",
    "AudioEncoder.mlmodelc",
    "TextDecoder.mlmodelc",
)
_MODEL_DEFINITIONS = ("model.mil", "model.mlmodel", "coremldata.bin")
_VOCAMAC_EXECUTABLE = Path("Contents/MacOS/VocaMac")
_HEADLESS_MARKER = b"--transcribe-file"
_HEADLESS_TIMEOUT_SECONDS = 300


@dataclass(frozen=True, slots=True)
class _HeadlessModel:
    id: str
    downloaded: bool
    supported: bool


class VocaMacEngine:
    """Adapter for VocaMac's selected local transcription model.

    Current VocaMac builds expose a headless file-transcription command backed
    by the same multi-engine router as the app. Older builds are kept working by
    the original WhisperKit-folder adapter, without ever probing an old app with
    command-line arguments that would launch its GUI.
    """

    def __init__(
        self,
        whisperkit_binary: str,
        model: str | None = None,
        *,
        app_path: Path | None = None,
        support_dir: Path | None = None,
        preferences_file: Path | None = None,
    ) -> None:
        self.whisperkit_binary = whisperkit_binary
        self.model = model
        self.app_path = app_path or DEFAULT_VOCAMAC_APP
        self.download_base = (support_dir or DEFAULT_SUPPORT_DIR.expanduser()) / "models"
        self.models_dir = self.download_base / "models" / MODEL_REPOSITORY
        self.preferences_file = preferences_file or DEFAULT_PREFERENCES_FILE.expanduser()
        self._delegate: WhisperKitEngine | None = None
        self._delegate_path: Path | None = None
        self._headless_signature: tuple[int, int] | None = None
        self._headless_capable = False

    def is_available(self) -> bool:
        """Cheap synchronous check used while resolving the `auto` engine."""
        if self._headless_supported():
            model = self._headless_model()
            return model is not None and model.downloaded and model.supported
        return (
            self.app_path.exists()
            and self._resolved_binary() is not None
            and bool(self._usable_models())
        )

    async def health(self) -> EngineHealth:
        if self._headless_supported():
            model = await asyncio.to_thread(self._headless_model)
            wanted = model.id if model is not None else self._wanted_model_name()
            return EngineHealth(
                ready=model is not None and model.downloaded and model.supported,
                name=f"vocamac:{wanted}",
            )
        return await self._legacy_health()

    async def _legacy_health(self) -> EngineHealth:
        models = self._usable_models()
        if not models:
            selected, variant = self._selection()
            wanted = variant or selected
            return EngineHealth(ready=False, name=f"vocamac:{wanted or 'no-model-selected'}")
        delegate = await self._delegate_for(models[0]).health()
        return EngineHealth(
            ready=self.app_path.exists() and delegate.ready,
            name=f"vocamac:{models[0].name}",
        )

    async def warmup(self) -> int:
        # The VocaMac CLI is deliberately one-shot. A future persistent CLI
        # mode can expose real warmup; reporting zero avoids loading a model
        # merely for a readiness probe and then immediately exiting.
        if self._headless_supported():
            return 0
        models = self._usable_models()
        if not models or not (await self.health()).ready:
            return 0
        return await self._delegate_for(models[0]).warmup()

    async def transcribe(
        self, audio_path: Path, options: TranscriptionOptions
    ) -> EngineTranscription:
        if self._headless_supported():
            return await self._transcribe_headless(audio_path, options)
        return await self._transcribe_legacy(audio_path, options)

    async def _transcribe_headless(
        self, audio_path: Path, options: TranscriptionOptions
    ) -> EngineTranscription:
        executable = self._app_executable()
        if executable is None:
            raise EngineUnavailableError("VocaMac's headless executable is unavailable.")

        arguments = [
            str(executable),
            "--transcribe-file",
            str(audio_path),
            "--json",
        ]
        configured_model = self._configured_model_id()
        if configured_model:
            arguments.extend(["--model", configured_model])
        # Pass `auto` explicitly too. Omitting the argument asks the VocaMac CLI
        # to inherit its GUI preference, which may differ from this session.
        arguments.extend(["--language", options.language])

        started = time.monotonic()
        process = await asyncio.create_subprocess_exec(
            *arguments,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=_headless_environment(),
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=_HEADLESS_TIMEOUT_SECONDS
            )
        except TimeoutError as error:
            process.kill()
            await process.wait()
            raise TranscriptionProcessError("VocaMac transcription timed out.") from error

        if process.returncode != 0:
            _raise_headless_failure(stderr)

        try:
            payload: dict[str, Any] = json.loads(stdout)
            transcript = payload["text"]
        except (json.JSONDecodeError, KeyError, TypeError) as error:
            raise TranscriptionProcessError(
                "VocaMac returned an invalid transcription response."
            ) from error
        if not isinstance(transcript, str) or not transcript.strip():
            raise TranscriptionProcessError("VocaMac returned an empty transcript.")

        total_ms = _elapsed_ms(started)
        duration = payload.get("duration_seconds")
        inference_ms = (
            max(0, round(duration * 1000))
            if isinstance(duration, (int, float)) and not isinstance(duration, bool)
            else 0
        )
        return EngineTranscription(
            text=transcript.strip(),
            model_load_ms=max(0, total_ms - inference_ms),
            inference_ms=inference_ms,
        )

    async def _transcribe_legacy(
        self, audio_path: Path, options: TranscriptionOptions
    ) -> EngineTranscription:
        models = self._usable_models()
        if not models or not (await self.health()).ready:
            selected, variant = self._selection()
            if selected is not None and variant is None:
                raise EngineUnavailableError(
                    f"VocaMac selected '{selected}', which is not a WhisperKit model. "
                    "VocaMac does not expose its other engines for headless transcription; "
                    "select a Whisper model in VocaMac or select the matching native engine "
                    "and model in the vocaphone gateway."
                )
            raise EngineUnavailableError(
                "VocaMac, one of its downloaded models, or the WhisperKit CLI is "
                "unavailable. Install VocaMac, download a model in its Models tab, "
                "and install the CLI with `brew install whisperkit-cli`."
            )
        last_error: TranscriptionProcessError | None = None
        for model_path in models:
            try:
                return await self._delegate_for(model_path).transcribe(audio_path, options)
            except TranscriptionProcessError as error:
                last_error = error
        if last_error is not None:
            raise last_error
        raise EngineUnavailableError("No usable VocaMac transcription model is available.")

    def _headless_model(self) -> _HeadlessModel | None:
        executable = self._app_executable()
        if executable is None:
            return None
        try:
            process = subprocess.run(
                [str(executable), "--list-models", "--json"],
                capture_output=True,
                check=False,
                timeout=5,
                env=_headless_environment(),
            )
            if process.returncode != 0:
                return None
            payload: dict[str, Any] = json.loads(process.stdout)
            entries = payload["models"]
        except (OSError, subprocess.SubprocessError, json.JSONDecodeError, KeyError, TypeError):
            return None
        if not isinstance(entries, list):
            return None

        configured = self._configured_model_id()
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            model_id = entry.get("id")
            matches = model_id == configured if configured else entry.get("selected") is True
            if matches and isinstance(model_id, str):
                return _HeadlessModel(
                    id=model_id,
                    downloaded=entry.get("downloaded") is True,
                    supported=entry.get("supported") is True,
                )
        return None

    def _configured_model_id(self) -> str | None:
        if not self.model:
            return None
        for model_id, folder_name in MODEL_VARIANTS.items():
            if self.model in {model_id, folder_name}:
                return model_id
        return self.model

    def _wanted_model_name(self) -> str:
        configured = self._configured_model_id()
        if configured:
            return configured
        selected, variant = self._selection()
        return selected or variant or "no-model-selected"

    def _app_executable(self) -> Path | None:
        executable = self.app_path / _VOCAMAC_EXECUTABLE
        if executable.is_file() and os.access(executable, os.X_OK):
            return executable
        return None

    def _headless_supported(self) -> bool:
        executable = self._app_executable()
        if executable is None:
            self._headless_signature = None
            self._headless_capable = False
            return False
        try:
            metadata = executable.stat()
        except OSError:
            self._headless_signature = None
            self._headless_capable = False
            return False
        signature = (metadata.st_size, metadata.st_mtime_ns)
        if signature != self._headless_signature:
            self._headless_capable = _file_contains(executable, _HEADLESS_MARKER)
            self._headless_signature = signature
        return self._headless_capable

    def close(self) -> None:
        delegate = self._delegate
        self._delegate = None
        self._delegate_path = None
        if delegate is not None:
            delegate.close()

    def _delegate_for(self, model_path: Path) -> WhisperKitEngine:
        """Keep one WhisperKit service alive until VocaMac's selection changes."""
        if self._delegate is not None and self._delegate_path == model_path:
            return self._delegate
        self.close()
        self._delegate = WhisperKitEngine(
            self.whisperkit_binary,
            model_path,
            tokenizer_path=self.download_base,
        )
        self._delegate_path = model_path
        return self._delegate

    def _usable_models(self) -> list[Path]:
        """VocaMac's selected model first, then its other complete downloads."""
        selected, variant = self._selection()
        # Current VocaMac releases persist one preference across WhisperKit,
        # FluidAudio Parakeet, Apple Speech, and sherpa-onnx models. Only the
        # Whisper entries live in this directory or can be served by
        # whisperkit-cli. Treating a non-Whisper selection as "no selection"
        # silently ran another downloaded Whisper model instead.
        if selected is not None and variant is None:
            return []
        preferred = self.models_dir / variant if variant else None
        if preferred is not None and not _model_is_usable(preferred):
            preferred = None
        if self.model:
            # A configured model is a choice, not a hint: never substitute silently.
            return [preferred] if preferred is not None else []
        models = [
            directory
            for directory in self._downloaded_directories()
            if directory != preferred and _model_is_usable(directory)
        ]
        models.sort(key=_model_weight_bytes, reverse=True)
        if preferred is not None:
            models.insert(0, preferred)
        return models

    def _selection(self) -> tuple[str | None, str | None]:
        """Return the persisted selection and its WhisperKit folder, if any.

        An explicit environment override may already be a WhisperKit folder
        name. A VocaMac preference cannot: its value is a ModelSize raw value,
        so an unknown value belongs to another/new engine and must not fall
        through to a downloaded Whisper model.
        """
        if self.model:
            return self.model, MODEL_VARIANTS.get(self.model, self.model)
        try:
            with self.preferences_file.open("rb") as handle:
                payload = plistlib.load(handle)
            selected = payload[SELECTED_MODEL_KEY]
        except (OSError, plistlib.InvalidFileException, KeyError, TypeError):
            return None, None
        if not isinstance(selected, str):
            return None, None
        return selected, MODEL_VARIANTS.get(selected)

    def _downloaded_directories(self) -> list[Path]:
        try:
            return sorted(entry for entry in self.models_dir.iterdir() if entry.is_dir())
        except OSError:
            return []

    def _resolved_binary(self) -> str | None:
        candidate = Path(self.whisperkit_binary).expanduser()
        if candidate.is_file():
            return str(candidate)
        return shutil.which(self.whisperkit_binary)


def _model_is_usable(directory: Path) -> bool:
    """Reject partial downloads the way VocaMac's own asset check does.

    An interrupted download leaves the variant folder in place with some Core ML
    components empty, and Core ML then refuses to load the model at transcription
    time rather than at selection time.
    """
    return all(_component_is_usable(directory / name) for name in REQUIRED_COMPONENTS)


def _component_is_usable(component: Path) -> bool:
    return (
        (component / "metadata.json").is_file()
        and any((component / name).is_file() for name in _MODEL_DEFINITIONS)
        and (component / "weights" / "weight.bin").is_file()
    )


def _model_weight_bytes(directory: Path) -> int:
    """Rank complete models by weight size rather than walking gigabytes of files."""
    total = 0
    for name in REQUIRED_COMPONENTS:
        try:
            total += (directory / name / "weights" / "weight.bin").stat().st_size
        except OSError:
            return 0
    return total


def _file_contains(path: Path, marker: bytes) -> bool:
    """Side-effect-free capability probe for old VocaMac app binaries.

    Passing an unknown flag to an old VocaMac build launches its GUI and can
    trigger its single-instance cleanup, so capability detection must inspect
    the executable rather than execute it.
    """
    overlap = b""
    try:
        with path.open("rb") as handle:
            while chunk := handle.read(64 * 1024):
                combined = overlap + chunk
                if marker in combined:
                    return True
                overlap = combined[-max(0, len(marker) - 1) :]
    except OSError:
        return False
    return False


def _headless_environment() -> dict[str, str]:
    environment = os.environ.copy()
    # Development-signed VocaMac builds may contain LLVM coverage hooks. Never
    # let a gateway request drop default.profraw into its working directory.
    environment["LLVM_PROFILE_FILE"] = os.devnull
    return environment


def _raise_headless_failure(stderr: bytes) -> None:
    detail = stderr.decode("utf-8", errors="replace").strip()
    code = ""
    message = "VocaMac transcription failed."
    try:
        payload: dict[str, Any] = json.loads(detail)
        raw_code = payload.get("error")
        raw_message = payload.get("message")
        if isinstance(raw_code, str):
            code = raw_code
        if isinstance(raw_message, str) and raw_message:
            message = raw_message
    except (json.JSONDecodeError, TypeError):
        if detail:
            message = detail.splitlines()[-1][:300]

    if code in {"model_not_found", "model_not_downloaded", "model_unsupported"}:
        raise EngineUnavailableError(message)
    raise TranscriptionProcessError(message)


def _elapsed_ms(started: float) -> int:
    return max(0, round((time.monotonic() - started) * 1000))
