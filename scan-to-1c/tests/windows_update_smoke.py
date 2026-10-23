"""Проверка автообновления на настоящем exe (запускается в CI на Windows после сборки).

Запускает собранную программу, подменяет её exe, пока она работает, и запускает
обновлённую — ровно то, что делает автообновление после скачивания.

    python tests/windows_update_smoke.py dist/Scan2-1C.exe
"""

import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scan_to_1c import updater


def running_count(name: str) -> int:
    out = subprocess.run(["tasklist", "/FI", f"IMAGENAME eq {name}", "/FO", "CSV", "/NH"],
                         capture_output=True, text=True, check=False).stdout
    return out.count(f'"{name}"')


def main(built: Path) -> None:
    app_dir = Path(tempfile.mkdtemp(prefix="scan2-1c-update-"))
    exe = app_dir / "Scan2-1C.exe"
    shutil.copy2(built, exe)

    updater.self_check(exe)
    print("self-check ok")

    running = subprocess.Popen([str(exe)], env=updater.child_env())
    time.sleep(10)
    assert running.poll() is None, "программа не запустилась"

    new_exe = app_dir / "Scan2-1C.exe.new"
    shutil.copy2(built, new_exe)
    updater.swap(exe, new_exe)  # работающий exe уезжает в .old
    assert exe.exists() and (app_dir / "Scan2-1C.exe.old").exists()
    print("swap while running ok")

    before = running_count("Scan2-1C.exe")
    updater.restart(exe)
    time.sleep(10)
    assert running_count("Scan2-1C.exe") > before, "обновлённая программа не запустилась"
    print("restart ok")

    subprocess.run(["taskkill", "/F", "/IM", "Scan2-1C.exe", "/T"], capture_output=True, check=False)
    subprocess.run(["taskkill", "/F", "/IM", "Scan2-1C.exe.old", "/T"], capture_output=True, check=False)
    time.sleep(3)
    updater.cleanup_old(exe)
    leftovers = sorted(p.name for p in app_dir.iterdir())
    assert leftovers == ["Scan2-1C.exe"], leftovers
    print("cleanup ok")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
