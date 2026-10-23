"""Выгрузка в папку обмена: JSON с реквизитами + копия скана. Забирается обработкой 1c/ЗагрузкаСканов.bsl."""

from __future__ import annotations

import json
import re
import shutil
from datetime import datetime
from pathlib import Path

from .extract import ExtractedData


def export_to_folder(data: ExtractedData, scan_path: Path, folder: Path, text: str = "") -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    stem = re.sub(r"[^\w\-]+", "_", f"{data.doc_type or 'Документ'}_{data.number or scan_path.stem}_{data.date}")
    stem = stem.strip("_")[:100]
    target = folder / f"{stem}.json"
    counter = 1
    while target.exists():
        counter += 1
        target = folder / f"{stem}_{counter}.json"

    scan_copy = target.with_suffix(scan_path.suffix.lower())
    shutil.copy2(scan_path, scan_copy)

    payload = {
        "format": "scan2onec/1",
        "created": datetime.now().isoformat(timespec="seconds"),
        "scan_file": scan_copy.name,
        "source_path": str(scan_path),
        **data.to_dict(),
        "text": text,
    }
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return target
