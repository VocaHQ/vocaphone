#!/usr/bin/env python3
"""Download one pinned WhisperKit model, and its tokenizer, for the model tests.

The files and digests come from the pins the app itself verifies against
(`ios/VocaPhoneApp/Models/local_model_pins.json`). A test therefore runs the
exact bytes a phone would, and changing a pin changes what the tests download.
Each file is checked by SHA-256. A file already present with the right digest
is not downloaded again, so a CI cache of the output directory is safe to reuse.

    tools/model-e2e/fetch_whisper.py openai_whisper-small_216MB ios/build/model-e2e

Writes `<out>/<model id>/...` and `<out>/Tokenizers/<tokenizer name>/...`, the
same layout `LocalModelManager` uses on the phone, and prints the model
directory.
"""

from __future__ import annotations

import hashlib
import json
import sys
import urllib.request
from pathlib import Path

PINS = Path(__file__).resolve().parents[2] / "ios/VocaPhoneApp/Models/local_model_pins.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# Every pinned file is on Hugging Face. Anything else -- another host, or a
# `file:` URL, which urllib would happily read -- is refused before it is opened.
ALLOWED_PREFIX = "https://huggingface.co/"


def fetch(url: str, destination: Path, size: int, expected: str) -> None:
    if not url.startswith(ALLOWED_PREFIX):
        sys.exit(f"Refusing to download from {url}: pinned models come only from {ALLOWED_PREFIX}")
    if destination.exists() and destination.stat().st_size == size and sha256(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    # The prefix check above pins the scheme and host; the digest check below
    # rejects anything that is not the pinned file.
    with urllib.request.urlopen(url) as response, partial.open("wb") as handle:  # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        while block := response.read(1 << 20):
            handle.write(block)
    actual = sha256(partial)
    if actual != expected:
        partial.unlink()
        sys.exit(f"{destination}: SHA-256 {actual} does not match the pin {expected}")
    partial.replace(destination)


def tokenizer_for(model_id: str, tokenizers: dict) -> str:
    # `openai_whisper-small_216MB` pins against `openai/whisper-small`.
    candidate = model_id.replace("_", "/", 1)
    matches = [name for name in tokenizers if candidate.startswith(name)]
    if not matches:
        sys.exit(f"No pinned tokenizer for {model_id}")
    return max(matches, key=len)


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    model_id, out = sys.argv[1], Path(sys.argv[2])
    pins = json.loads(PINS.read_text())
    model = pins["models"].get(model_id)
    if model is None:
        sys.exit(f"{model_id} is not pinned in {PINS.name}: {', '.join(pins['models'])}")

    repository, revision = pins["repository"], pins["revision"]
    for file in model["files"]:
        fetch(
            f"https://huggingface.co/{repository}/resolve/{revision}/{model_id}/{file['path']}",
            out / model_id / file["path"],
            file["size"],
            file["sha256"],
        )

    tokenizer = tokenizer_for(model_id, pins["tokenizers"])
    pinned = pins["tokenizers"][tokenizer]
    for file in pinned["files"]:
        fetch(
            f"https://huggingface.co/{tokenizer}/resolve/{pinned['revision']}/{file['path']}",
            # As `LocalModelManager.tokenizerFolderName` names it on the phone.
            out / "Tokenizers" / tokenizer.replace("/", "_") / file["path"],
            file["size"],
            file["sha256"],
        )
    print(out / model_id)


if __name__ == "__main__":
    main()
