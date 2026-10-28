from __future__ import annotations

import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


ARCHIVE_DIRNAME = "archive_2026-10-25"


def archive_paths(paths: Iterable[Path], results_dir: Path, relative_to: Path) -> Path | None:
    existing = [path for path in paths if path.exists()]
    if not existing:
        return None

    archive_root = results_dir / ARCHIVE_DIRNAME
    batch_name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    batch_dir = archive_root / batch_name
    batch_dir.mkdir(parents=True, exist_ok=False)
    for path in existing:
        destination = batch_dir / path.relative_to(relative_to)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(path), str(destination))
    return batch_dir


def archive_existing_results(results_dir: Path) -> Path | None:
    existing = [
        path for path in results_dir.iterdir()
        if path.name != ARCHIVE_DIRNAME
    ]
    return archive_paths(existing, results_dir, results_dir)
