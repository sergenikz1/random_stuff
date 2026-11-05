"""Распознавание файла целиком: текст страниц → деление на документы → реквизиты (шаблоны и ИИ)."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import ai
from .extract import ExtractedData, extract, split_documents
from .loader import MAX_IMAGE_PAGES, load_document

# Во сколько раз крупнее перечитывать страницы, на которых не нашлись ИНН или номер:
# мелкий шрифт шапки ТОРГ-12 на скане 300 dpi Tesseract читает только после увеличения.
HI_RES_SCALE = 1.7


@dataclass
class Part:
    """Один документ из файла (или из нескольких фото одного документа)."""

    page_numbers: list[int] | None  # страницы файла; None — весь файл
    text: str
    data: ExtractedData
    message: str = ""


def _needs_detail(data: ExtractedData) -> bool:
    return any("ИНН" in w or "номер" in w for w in data.warnings)


def recognize(paths: list[Path], client: ai.AIClient | None = None, keep_images: bool = False,
              page_numbers: list[int] | None = None, progress=None, status=None) -> list[Part]:
    """Распознаёт файл (или фото одного документа) и возвращает документы, которые в нём нашлись.

    PDF, где подряд лежат разные документы (ТОРГ-12 и счёт-фактура), делится на части. page_numbers —
    распознать только эти страницы (повторное распознавание одной части).
    """
    status = status or (lambda _text: None)
    pages: list[str] = []
    images = []
    used_ocr = False
    for path in paths:
        doc = load_document(path, progress=progress, keep_images=keep_images,
                            pages=page_numbers if len(paths) == 1 else None)
        pages += doc.pages
        images += doc.images + [None] * (len(doc.pages) - len(doc.images))  # индекс картинки = страница
        used_ocr = used_ocr or doc.used_ocr

    # Номера страниц в pages — локальные; в файле им соответствуют page_numbers (если заданы).
    file_page = page_numbers if page_numbers is not None and len(paths) == 1 else list(range(len(pages)))
    if page_numbers is not None or len(paths) > 1:
        groups = [list(range(len(pages)))]
    else:
        groups = split_documents(pages) or [[]]

    parts = []
    for group in groups:
        text = "\n\n".join(pages[n] for n in group)
        rules = extract(text)
        if used_ocr and len(paths) == 1 and group and _needs_detail(rules):
            status("перечитываю мелкий шрифт крупнее…")
            detailed = load_document(paths[0], pages=[file_page[n] for n in group], scale=HI_RES_SCALE)
            better = extract(detailed.text)
            if len(better.warnings) < len(rules.warnings):
                text, rules = detailed.text, better
        part_images = [images[n] for n in group if n < len(images) and images[n] is not None]
        if client:
            status("распознаёт ИИ…")
        data, message = ai.recognize(client, text, part_images[:MAX_IMAGE_PAGES], rules)
        numbers = [file_page[n] for n in group] if len(groups) > 1 or page_numbers is not None else None
        parts.append(Part(page_numbers=numbers, text=text, data=data, message=message))
    return parts
