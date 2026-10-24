"""Точка входа: без аргументов-ключей открывает окно, с --extract работает из командной строки.

    Scan2-1C.exe [сканы...]                          — окно, переданные файлы сразу распознаются
    Scan2-1C.exe --extract скан.pdf ... --out r.json — только распознать и сохранить реквизиты в JSON
                 [--ai]                              — с ИИ через OmniRoute по сохранённым настройкам
    Scan2-1C.exe --self-check                        — проверить, что сборка рабочая (код 0); нужно автообновлению
"""

import argparse
import json
import sys


def run_extract(argv: list[str]) -> int:
    from scan_to_1c import ai
    from scan_to_1c.extract import extract
    from scan_to_1c.loader import configure_tesseract, load_document
    from scan_to_1c.settings import Settings

    parser = argparse.ArgumentParser(prog="Scan2-1C")
    parser.add_argument("--extract", nargs="+", required=True, metavar="ФАЙЛ")
    parser.add_argument("--out", help="куда записать JSON (по умолчанию — в консоль)")
    parser.add_argument("--ai", action="store_true", help="распознавать ИИ через OmniRoute (адрес и ключ из настроек)")
    args = parser.parse_args(argv)

    configure_tesseract()
    settings = Settings.load()
    client = ai.AIClient(ai.config_from_settings(settings)) if args.ai else None
    results, failed = [], 0
    for path in args.extract:
        try:
            doc = load_document(path, keep_images=bool(client and settings.ai_send_images))
            data, note = ai.recognize(client, doc.text, doc.images, extract(doc.text))
            results.append({"file": path, **data.to_dict(), **({"note": note} if note else {})})
        except Exception as exc:  # noqa: BLE001
            failed += 1
            results.append({"file": path, "error": str(exc)})
    output = json.dumps(results, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(output)
    elif sys.stdout:  # в оконной сборке консоли нет
        sys.stdout.write(output + "\n")
    return 1 if failed else 0


def run_self_check() -> int:
    """Новая версия проверяется перед установкой: модули грузятся и встроенный OCR отвечает."""
    import tkinter  # noqa: F401 — без Tk окно не откроется

    from scan_to_1c import gui  # noqa: F401
    from scan_to_1c.loader import configure_tesseract, tesseract_available

    configure_tesseract()
    return 0 if tesseract_available() else 2


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-check"]:
        sys.exit(run_self_check())
    if "--extract" in sys.argv[1:]:
        sys.exit(run_extract(sys.argv[1:]))
    from scan_to_1c.gui import main

    main()
