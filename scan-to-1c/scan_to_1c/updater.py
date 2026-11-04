"""Автообновление: новая версия берётся из релизов GitHub и подменяет exe.

Порядок: найти самый новый релиз scan2onec-v*, скачать exe рядом с текущим,
сверить SHA-256 из GitHub, убедиться, что он запускается (--self-check),
затем переименовать работающий exe в .old и поставить новый на его место.
Windows разрешает переименовать запущенный exe, поэтому вспомогательный
скрипт не нужен; .old удаляется при следующем запуске.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import requests

from . import __version__

REPO = "sergenikz1/random_stuff"
TAG_PREFIX = "scan2onec-v"
RELEASES_URL = f"https://api.github.com/repos/{REPO}/releases"
ASSET_RE = re.compile(r"^Scan2-1C.*\.exe$", re.IGNORECASE)
SELF_CHECK_TIMEOUT = 120


class UpdateError(Exception):
    pass


@dataclass
class Update:
    version: str
    url: str
    size: int
    sha256: str  # пусто, если GitHub не прислал контрольную сумму
    notes: str = ""


def parse_version(text: str) -> tuple[int, ...]:
    match = re.fullmatch(r"v?(\d+(?:\.\d+)*)", text.strip())
    if not match:
        return ()
    return tuple(int(part) for part in match.group(1).split("."))


def is_newer(candidate: str, current: str) -> bool:
    new, old = parse_version(candidate), parse_version(current)
    if not new or not old:
        return False
    width = max(len(new), len(old))
    return new + (0,) * (width - len(new)) > old + (0,) * (width - len(old))


def current_exe() -> Path | None:
    """Путь к exe, если программа запущена из сборки PyInstaller; иначе обновлять нечего."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve()
    return None


def find_update(current: str = __version__, session: requests.Session | None = None,
                timeout: int = 15) -> Update | None:
    """Самый новый опубликованный релиз программы, если он новее текущей версии."""
    http = session or requests.Session()
    try:
        response = http.get(RELEASES_URL, params={"per_page": 50}, timeout=timeout,
                            headers={"Accept": "application/vnd.github+json",
                                     "User-Agent": f"Scan2-1C/{current}"})
        response.raise_for_status()
        releases = response.json()
    except (requests.RequestException, ValueError) as exc:
        raise UpdateError(f"Не удалось проверить обновления: {exc}") from exc

    best: Update | None = None
    for release in releases:
        tag = release.get("tag_name", "")
        if release.get("draft") or release.get("prerelease") or not tag.startswith(TAG_PREFIX):
            continue
        version = tag[len(TAG_PREFIX):]
        if not is_newer(version, best.version if best else current):
            continue
        asset = next((a for a in release.get("assets", []) if ASSET_RE.match(a.get("name", ""))), None)
        if not asset:
            continue
        digest = asset.get("digest") or ""
        best = Update(version=version, url=asset["browser_download_url"], size=int(asset.get("size", 0)),
                      sha256=digest.split(":", 1)[1] if digest.startswith("sha256:") else "",
                      notes=release.get("body") or "")
    return best


def download(update: Update, target: Path, session: requests.Session | None = None,
             progress=None, timeout: int = 60) -> Path:
    """Скачивает exe в target и проверяет размер, SHA-256 и заголовок Windows-программы."""
    http = session or requests.Session()
    partial = target.with_name(target.name + ".part")
    digest = hashlib.sha256()
    received = 0
    try:
        with http.get(update.url, stream=True, timeout=timeout) as response:
            response.raise_for_status()
            with open(partial, "wb") as out:
                for chunk in response.iter_content(chunk_size=1 << 20):
                    out.write(chunk)
                    digest.update(chunk)
                    received += len(chunk)
                    if progress:
                        progress(received, update.size)
    except PermissionError:
        partial.unlink(missing_ok=True)
        raise  # нет прав на папку программы — install() объяснит, что делать
    except (requests.RequestException, OSError) as exc:
        partial.unlink(missing_ok=True)
        raise UpdateError(f"Не удалось скачать обновление: {exc}") from exc

    problem = ""
    if update.size and received != update.size:
        problem = f"размер {received} вместо {update.size}"
    elif update.sha256 and digest.hexdigest() != update.sha256.lower():
        problem = "не совпала контрольная сумма SHA-256"
    else:
        with open(partial, "rb") as fh:
            if fh.read(2) != b"MZ":
                problem = "это не программа Windows"
    if problem:
        partial.unlink(missing_ok=True)
        raise UpdateError(f"Скачанное обновление повреждено: {problem}")
    os.replace(partial, target)
    return target


def child_env() -> dict:
    """Окружение для запуска другого exe: без него PyInstaller переиспользует временную папку текущего."""
    env = dict(os.environ)
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    return env


def _creation_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0)


def self_check(exe: Path) -> None:
    """Новый exe должен запуститься и ответить, прежде чем заменит рабочий."""
    try:
        result = subprocess.run([str(exe), "--self-check"], env=child_env(), timeout=SELF_CHECK_TIMEOUT,
                                creationflags=_creation_flags(), capture_output=True, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise UpdateError(f"Новая версия не запускается: {exc}") from exc
    if result.returncode != 0:
        raise UpdateError(f"Новая версия не прошла проверку (код {result.returncode})")


def swap(exe: Path, new_exe: Path) -> None:
    """Ставит new_exe на место exe; работающий exe уезжает в .old."""
    old = exe.with_name(exe.name + ".old")
    old.unlink(missing_ok=True)
    os.replace(exe, old)
    try:
        os.replace(new_exe, exe)
    except OSError:
        os.replace(old, exe)  # вернуть как было, чтобы программа не пропала
        raise


def cleanup_old(exe: Path | None = None) -> None:
    """Удаляет остатки прошлого обновления (.old, недокачанный .part)."""
    exe = exe or current_exe()
    if not exe:
        return
    for leftover in (exe.with_name(exe.name + ".old"), exe.with_name(exe.name + ".new"),
                     exe.with_name(exe.name + ".new.part")):
        try:
            leftover.unlink(missing_ok=True)
        except OSError:
            pass  # .old ещё занят — удалим в следующий раз


def install(update: Update, exe: Path, session: requests.Session | None = None, progress=None,
            check=self_check) -> None:
    """Скачивает и ставит обновление. После этого следующий запуск exe — уже новая версия."""
    new_exe = exe.with_name(exe.name + ".new")
    try:
        download(update, new_exe, session=session, progress=progress)
        check(new_exe)
        swap(exe, new_exe)
    except PermissionError as exc:
        new_exe.unlink(missing_ok=True)
        raise UpdateError(f"Нет прав на запись в папку программы ({exe.parent}). "
                          "Перенесите программу в свою папку, например на Рабочий стол.") from exc
    except Exception:
        new_exe.unlink(missing_ok=True)
        raise


def restart(exe: Path) -> None:
    """Запускает обновлённую программу отдельным процессом; текущий процесс должен завершиться сам."""
    flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    subprocess.Popen([str(exe)], env=child_env(), creationflags=flags, close_fds=True)
