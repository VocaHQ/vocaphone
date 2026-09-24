#!/usr/bin/env python3
"""Download one pinned whisper.cpp model for the Android model tests.

The id, size and SHA-256 come from the Android catalog itself
(`LocalModelCatalog.kt`), so the test decodes the bytes a phone would, and a
changed pin changes what the test downloads. A file already present with the
right digest is not fetched again, so a CI cache of the output is safe to reuse.

    tools/model-e2e/fetch_ggml.py base-q8_0 android/build/model-e2e

Prints the path of the model file.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from fetch_whisper import fetch

CATALOG = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/com/vocahq/vocaphone/local/LocalModelCatalog.kt"
)


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    model_id, out = sys.argv[1], Path(sys.argv[2])
    source = CATALOG.read_text()
    repository = re.search(r'WHISPER_REPOSITORY = "([^"]+)"', source).group(1)
    revision = re.search(r'WHISPER_REVISION = "([^"]+)"', source).group(1)
    pinned = re.search(
        rf'model\(\s*"{re.escape(model_id)}",\s*"[^"]*",\s*([\d_]+)L,\s*"([0-9a-f]{{64}})"',
        source,
    )
    if pinned is None:
        sys.exit(f"{model_id} is not a pinned whisper.cpp model in {CATALOG.name}")
    size, digest = int(pinned.group(1).replace("_", "")), pinned.group(2)
    name = f"ggml-{model_id}.bin"
    fetch(
        f"https://huggingface.co/{repository}/resolve/{revision}/{name}",
        out / name,
        size,
        digest,
    )
    print(out / name)


if __name__ == "__main__":
    main()
