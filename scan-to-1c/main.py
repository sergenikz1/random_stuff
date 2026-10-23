"""Точка входа: без аргументов-ключей открывает окно, с --extract работает из командной строки.

    Scan2-1C.exe [сканы...]                          — окно, переданные файлы сразу распознаются
    Scan2-1C.exe --extract скан.pdf ... --out r.json — только распознать и сохранить реквизиты в JSON
"""

import argparse
import json
import sys


def run_extract(argv: list[str]) -> int:
    from scan_to_1c.extract import extract
    from scan_to_1c.loader import configure_tesseract, load_document

    parser = argparse.ArgumentParser(prog="Scan2-1C")
    parser.add_argument("--extract", nargs="+", required=True, metavar="ФАЙЛ")
    parser.add_argument("--out", help="куда записать JSON (по умолчанию — в консоль)")
    args = parser.parse_args(argv)

    configure_tesseract()
    results, failed = [], 0
    for path in args.extract:
        try:
            doc = load_document(path)
            results.append({"file": path, **extract(doc.text).to_dict()})
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


if __name__ == "__main__":
    if "--extract" in sys.argv[1:]:
        sys.exit(run_extract(sys.argv[1:]))
    from scan_to_1c.gui import main

    main()
