#!/usr/bin/env python3
"""Пересобирает модели: python3 build.py [knight] [shaman].

Без аргументов собирает все. Каждая модель попадает в свою папку:
<имя>.glb, <имя>.obj + <имя>.mtl, palette.png и viewer.html.
Нужен только стандартный Python 3.8+.
"""
import importlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from lowpoly import export_model  # noqa: E402

MODELS = ["knight", "shaman"]


def main():
    wanted = sys.argv[1:] or MODELS
    for name in wanted:
        if name not in MODELS:
            sys.exit(f"Нет такой модели: {name}. Есть: {', '.join(MODELS)}")
        model = importlib.import_module(name)
        tris, y0, y1 = export_model(model, os.path.join(HERE, name), os.path.join(HERE, "viewer_template.html"))
        print(f"{name}: {tris} треугольников, высота {y0:.2f}..{y1:.2f}")


if __name__ == "__main__":
    main()
