"""Выгрузка в папку обмена: JSON с реквизитами + копии сканов. Забирается обработкой 1c/ЗагрузкаСканов.bsl."""

from __future__ import annotations

import json
import re
import shutil
from datetime import datetime
from pathlib import Path

from .extract import ExtractedData


def export_to_folder(data: ExtractedData, scan_paths: Path | list[Path], folder: Path, text: str = "") -> Path:
    scans = [scan_paths] if isinstance(scan_paths, Path) else list(scan_paths)
    folder.mkdir(parents=True, exist_ok=True)
    stem = re.sub(r"[^\w\-]+", "_", f"{data.doc_type or 'Документ'}_{data.number or scans[0].stem}_{data.date}")
    stem = stem.strip("_")[:100]
    target = folder / f"{stem}.json"
    counter = 1
    while target.exists():
        counter += 1
        target = folder / f"{stem}_{counter}.json"

    copies = []
    for page, scan in enumerate(scans, 1):
        suffix = f"_стр{page}" if len(scans) > 1 else ""
        copy = target.with_name(f"{target.stem}{suffix}{scan.suffix.lower()}")
        shutil.copy2(scan, copy)
        copies.append(copy.name)

    payload = {
        "format": "scan2onec/1",
        "created": datetime.now().isoformat(timespec="seconds"),
        "scan_file": copies[0],
        "scan_files": copies,
        "source_path": str(scans[0]),
        **data.to_dict(),
        "text": text,
    }
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return target
