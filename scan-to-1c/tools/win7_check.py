"""Проверка, что exe и DLL сборки не требуют функций Windows 8+ (иначе на Windows 7 ошибка запуска).

Смотрит таблицы импорта (обычные и отложенные) всех .exe/.dll/.pyd в папке и ищет:
- библиотеки, которых в Windows 7 нет (api-ms-win-core-path-l1-1-0.dll, shcore.dll, combase.dll …);
- функции, появившиеся в Windows 8 и новее (GetSystemTimePreciseAsFileTime, CreateFile2 …);
- зависимость от Universal CRT (api-ms-win-crt-*), которой на Windows 7 без обновлений нет, —
  её закрывают DLL UCRT, положенные рядом с программой.

    python tools/win7_check.py ПАПКА [--ucrt-bundled]

Код возврата 1, если найдено несовместимое.
"""

import sys
from pathlib import Path

import pefile

# Наборы API и библиотеки, которых нет в Windows 7 SP1.
MISSING_DLLS = {
    "api-ms-win-core-path-l1-1-0.dll", "api-ms-win-core-synch-l1-2-0.dll", "api-ms-win-core-file-l1-2-0.dll",
    "api-ms-win-core-file-l1-2-1.dll", "api-ms-win-core-file-l1-2-2.dll", "api-ms-win-core-file-l2-1-0.dll",
    "api-ms-win-core-memory-l1-1-1.dll", "api-ms-win-core-memory-l1-1-2.dll", "api-ms-win-core-sysinfo-l1-2-0.dll",
    "api-ms-win-core-sysinfo-l1-2-1.dll", "api-ms-win-core-winrt-l1-1-0.dll",
    "api-ms-win-core-winrt-string-l1-1-0.dll", "api-ms-win-core-wow64-l1-1-1.dll",
    "api-ms-win-core-libraryloader-l1-2-0.dll", "api-ms-win-core-processthreads-l1-1-2.dll",
    "api-ms-win-core-handle-l1-1-0.dll", "api-ms-win-core-heap-l2-1-0.dll",
    "shcore.dll", "combase.dll", "d3d11_1.dll", "dxgi1_2.dll", "mfplat.dll", "bcryptprimitives.dll",
    "api-ms-win-security-base-l1-2-0.dll", "api-ms-win-core-errorhandling-l1-1-1.dll",
}
# Функции Windows 8+ в библиотеках, которые в Windows 7 есть.
MISSING_FUNCTIONS = {
    "GetSystemTimePreciseAsFileTime", "CreateFile2", "CopyFile2", "GetOverlappedResultEx", "WaitOnAddress",
    "WakeByAddressSingle", "WakeByAddressAll", "PathCchCombineEx", "PathCchCanonicalizeEx", "PathCchAppendEx",
    "PathAllocCombine", "PathAllocCanonicalize", "SetThreadDescription", "GetThreadDescription",
    "GetCurrentPackageFullName", "GetCurrentPackageId", "GetCurrentPackageFamilyName",
    "GetProcessMitigationPolicy", "SetProcessMitigationPolicy", "PrefetchVirtualMemory", "DiscardVirtualMemory",
    "OfferVirtualMemory", "ReclaimVirtualMemory", "GetFirmwareType", "InitializeSynchronizationBarrier",
    "EnterSynchronizationBarrier", "DeleteSynchronizationBarrier", "CreateFileMappingFromApp",
    "MapViewOfFileFromApp", "VirtualAllocFromApp", "SetCoalescableTimer", "GetDpiForMonitor",
    "SetProcessDpiAwareness", "GetDpiForWindow", "SetProcessDpiAwarenessContext", "GetDpiForSystem",
    "AdjustWindowRectExForDpi", "GetSystemMetricsForDpi", "RoInitialize", "WindowsCreateString",
    "GetPackagePathByFullName",
    "QueryUnbiasedInterruptTimePrecise", "VirtualAlloc2", "MapViewOfFile3", "GetProcessInformation",
    "SetProcessInformation", "GetThreadInformation", "SetThreadInformation",
}
# Системные библиотеки Windows 7 SP1. DLL, которой нет ни здесь, ни в сборке, на чистой Windows 7
# не найдётся — как VCRUNTIME140.dll у программы, собранной без неё.
WIN7_SYSTEM_DLLS = {
    "kernel32.dll", "user32.dll", "gdi32.dll", "advapi32.dll", "shell32.dll", "ole32.dll", "oleaut32.dll",
    "comctl32.dll", "comdlg32.dll", "ws2_32.dll", "wsock32.dll", "msvcrt.dll", "ntdll.dll", "crypt32.dll",
    "secur32.dll", "shlwapi.dll", "version.dll", "winmm.dll", "iphlpapi.dll", "rpcrt4.dll", "bcrypt.dll",
    "uxtheme.dll", "imm32.dll", "dwmapi.dll", "setupapi.dll", "wintrust.dll", "psapi.dll", "userenv.dll",
    "netapi32.dll", "mpr.dll", "dnsapi.dll", "cfgmgr32.dll", "powrprof.dll", "propsys.dll", "ncrypt.dll",
    "credui.dll", "oleacc.dll", "gdiplus.dll", "msimg32.dll", "opengl32.dll", "glu32.dll", "wininet.dll",
    "urlmon.dll", "odbc32.dll", "normaliz.dll", "dbghelp.dll", "winspool.drv", "comctl32", "winhttp.dll",
    "usp10.dll", "mswsock.dll", "wtsapi32.dll", "msacm32.dll", "avrt.dll", "dxgi.dll", "d3d9.dll", "d3d11.dll",
    "dwrite.dll", "d2d1.dll", "windowscodecs.dll", "hid.dll", "winusb.dll", "msi.dll", "apphelp.dll",
    "wldap32.dll", "kernelbase.dll", "sspicli.dll", "cryptbase.dll", "cryptui.dll", "imagehlp.dll", "shfolder.dll",
    "winscard.dll", "rasapi32.dll", "tapi32.dll", "msvfw32.dll", "avicap32.dll", "avifil32.dll", "ksuser.dll",
}
# Есть в Windows 7 только с обновлением KB2533623 (2011 г.) — предупреждаем, но не считаем ошибкой.
NEEDS_KB2533623 = {"SetDefaultDllDirectories", "AddDllDirectory", "RemoveDllDirectory"}


def imports(path: Path):
    pe = pefile.PE(str(path), fast_load=True)
    pe.parse_data_directories(directories=[
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"]])
    for attr, delayed in (("DIRECTORY_ENTRY_IMPORT", False), ("DIRECTORY_ENTRY_DELAY_IMPORT", True)):
        for entry in getattr(pe, attr, []):
            dll = entry.dll.decode(errors="replace").lower()
            names = [imp.name.decode(errors="replace") for imp in entry.imports if imp.name]
            yield dll, names, delayed


def check(folder: Path, ucrt_bundled: bool) -> int:
    problems, notes = [], []
    files = [p for p in folder.rglob("*") if p.suffix.lower() in (".exe", ".dll", ".pyd") and p.is_file()]
    present = {p.name.lower() for p in files}
    for path in sorted(files):
        try:
            entries = list(imports(path))
        except pefile.PEFormatError:
            continue
        rel = path.relative_to(folder)
        for dll, names, delayed in entries:
            kind = " (отложенный импорт)" if delayed else ""
            if dll in MISSING_DLLS and dll not in present:
                problems.append(f"{rel}: требует {dll}{kind} — нет в Windows 7")
            if dll.startswith("api-ms-win-crt-") and not ucrt_bundled and dll not in present:
                notes.append(f"{rel}: нужен Universal CRT ({dll})")
            elif (dll not in present and dll not in WIN7_SYSTEM_DLLS and dll not in MISSING_DLLS
                  and not dll.startswith(("api-ms-win-core-", "api-ms-win-crt-", "ext-ms-")) and not delayed):
                problems.append(f"{rel}: требует {dll} — нет ни в сборке, ни в Windows 7")
            for name in names:
                if name in MISSING_FUNCTIONS and not delayed:
                    problems.append(f"{rel}: {dll}!{name} — функция Windows 8+")
                elif name in NEEDS_KB2533623:
                    notes.append(f"{rel}: {dll}!{name} — в Windows 7 нужен KB2533623")
    for line in sorted(set(notes)):
        print("примечание:", line)
    for line in problems:
        print("НЕСОВМЕСТИМО:", line)
    print(f"Проверено файлов: {len(files)}, несовместимостей: {len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # консоль Windows — cp1252, а вывод русский
    sys.exit(check(Path(sys.argv[1]), "--ucrt-bundled" in sys.argv[2:]))
