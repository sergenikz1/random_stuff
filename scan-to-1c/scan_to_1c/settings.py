"""Настройки программы: %APPDATA%\\Scan2-1C\\settings.json. Пароль шифруется средствами Windows (DPAPI)."""

from __future__ import annotations

import base64
import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path


def settings_dir() -> Path:
    root = os.environ.get("APPDATA") or os.path.join(Path.home(), ".config")
    return Path(root) / "Scan2-1C"


@dataclass
class Settings:
    mode: str = "odata"  # "odata" — сразу в базу, "folder" — файлы в папку обмена
    base_url: str = "http://localhost/accounting"
    user: str = ""
    password: str = ""
    verify_ssl: bool = True
    exchange_dir: str = ""
    mapping: dict = field(default_factory=dict)  # переопределения шаблона выгрузки
    auto_update: bool = True  # при запуске скачивать и ставить новую версию с GitHub
    ai_enabled: bool = False  # распознавать реквизиты ИИ-моделью через OmniRoute
    ai_base_url: str = "http://localhost:20128/v1"
    ai_api_key: str = ""
    ai_model: str = "auto"
    ai_send_images: bool = True  # отдавать модели картинки страниц, а не только текст OCR

    @classmethod
    def load(cls, path: Path | None = None) -> Settings:
        path = path or settings_dir() / "settings.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return cls()
        data["password"] = _unprotect(data.pop("password_protected", ""))
        data["ai_api_key"] = _unprotect(data.pop("ai_api_key_protected", ""))
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(**known)

    def save(self, path: Path | None = None) -> None:
        path = path or settings_dir() / "settings.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        data = asdict(self)
        data["password_protected"] = _protect(data.pop("password"))
        data["ai_api_key_protected"] = _protect(data.pop("ai_api_key"))
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _protect(secret: str) -> str:
    if not secret:
        return ""
    raw = secret.encode("utf-8")
    if os.name == "nt":
        try:
            return "dpapi:" + base64.b64encode(_dpapi(raw, encrypt=True)).decode("ascii")
        except OSError:
            pass
    return "plain:" + base64.b64encode(raw).decode("ascii")


def _unprotect(value: str) -> str:
    if not value or ":" not in value:
        return ""
    kind, payload = value.split(":", 1)
    try:
        raw = base64.b64decode(payload)
        if kind == "dpapi":
            raw = _dpapi(raw, encrypt=False)
        return raw.decode("utf-8")
    except (OSError, ValueError):
        return ""


def _dpapi(data: bytes, encrypt: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    buffer = ctypes.create_string_buffer(data, len(data))
    blob_in = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    blob_out = Blob()
    func = crypt32.CryptProtectData if encrypt else crypt32.CryptUnprotectData
    if not func(ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)):
        raise OSError("DPAPI не смог обработать пароль")
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        kernel32.LocalFree(blob_out.pbData)
