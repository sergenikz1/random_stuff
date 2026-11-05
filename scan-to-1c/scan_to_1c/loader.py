"""Чтение сканов: PDF и изображения → текст (через текстовый слой или OCR Tesseract)."""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageOps

try:  # HEIC с телефонов — необязательная зависимость
    from pillow_heif import register_heif_opener

    register_heif_opener()
    HEIC_SUPPORTED = True
except Exception:  # noqa: BLE001
    HEIC_SUPPORTED = False

import pytesseract

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif", ".webp"}
if HEIC_SUPPORTED:
    IMAGE_EXTENSIONS |= {".heic", ".heif"}
PDF_EXTENSIONS = {".pdf"}
SUPPORTED_EXTENSIONS = IMAGE_EXTENSIONS | PDF_EXTENSIONS

# Если в PDF меньше символов на страницу, считаем его сканом без текстового слоя.
MIN_TEXT_LAYER_CHARS = 40
PDF_RENDER_DPI = 300
OCR_LANG = "rus+eng"
TARGET_LONG_SIDE = 3000
PROBE_LONG_SIDE = 1600  # размер для быстрой пробы поворота
OSD_TRUSTED_CONFIDENCE = 5
# Сколько страниц сохранять картинками для ИИ: счёт или УПД почти всегда умещаются в 1–3 листа.
MAX_IMAGE_PAGES = 5


class LoaderError(Exception):
    pass


@dataclass
class LoadedDocument:
    path: Path
    pages: list[str] = field(default_factory=list)
    used_ocr: bool = False
    images: list[Image.Image] = field(default_factory=list)  # страницы для ИИ, если просили keep_images

    @property
    def text(self) -> str:
        return "\n\n".join(self.pages)


def configure_tesseract() -> None:
    """Находит tesseract: встроенный в exe, рядом с exe или установленный в системе."""
    candidates = []
    base = getattr(sys, "_MEIPASS", None)
    if base:
        candidates.append(Path(base) / "tesseract")
    candidates.append(Path(sys.executable).parent / "tesseract")
    candidates.append(Path(__file__).resolve().parent.parent / "tesseract")
    if os.name == "nt":
        for env in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
            root = os.environ.get(env)
            if root:
                candidates.append(Path(root) / "Tesseract-OCR")
                candidates.append(Path(root) / "Programs" / "Tesseract-OCR")

    exe_name = "tesseract.exe" if os.name == "nt" else "tesseract"
    for folder in candidates:
        exe = folder / exe_name
        if exe.is_file():
            pytesseract.pytesseract.tesseract_cmd = str(exe)
            tessdata = folder / "tessdata"
            if tessdata.is_dir():
                os.environ["TESSDATA_PREFIX"] = str(tessdata)
            return
    # Иначе остаётся «tesseract» из PATH.


def tesseract_available() -> bool:
    try:
        pytesseract.get_tesseract_version()
        return True
    except Exception:  # noqa: BLE001
        return False


def is_supported(path: Path) -> bool:
    return path.suffix.lower() in SUPPORTED_EXTENSIONS


def prepare_image(image: Image.Image, target: int = 0) -> Image.Image:
    """Поворот по EXIF, оттенки серого, растяжение контраста, увеличение мелких сканов и фото."""
    target = target or TARGET_LONG_SIDE
    image = ImageOps.exif_transpose(image)
    image = image.convert("L")
    image = ImageOps.autocontrast(image)  # без отсечки: она усиливает шум JPEG
    # Лист A4 при 250–300 dpi — около 3000 px по длинной стороне. Фото с телефона (часто 1280 px)
    # увеличиваем до этого размера: Tesseract плохо читает строки ниже ~20 px.
    if max(image.size) < target:
        factor = target / max(image.size)
        image = image.resize((int(image.width * factor), int(image.height * factor)), Image.LANCZOS)
    return image


def _readable_words(image: Image.Image) -> int:
    """Сколько русских слов Tesseract распознал уверенно — мера того, что страница повёрнута правильно."""
    scale = PROBE_LONG_SIDE / max(image.size)
    probe = image.resize((int(image.width * scale), int(image.height * scale)), Image.LANCZOS)
    data = pytesseract.image_to_data(probe, lang=OCR_LANG, config="--psm 3", output_type=pytesseract.Output.DICT)
    return sum(1 for word, conf in zip(data["text"], data["conf"])
               if float(conf) >= 70 and re.fullmatch(r"[А-Яа-яЁё]{3,}", word.strip()))


def _fix_rotation(image: Image.Image) -> Image.Image:
    """Разворачивает страницу, сфотографированную боком или вверх ногами.

    Определению ориентации Tesseract верим, только когда оно уверено: на фото таблиц оно путает
    90° и 270°. В остальных случаях пробуем повороты и берём тот, где читается больше слов.
    """
    candidates = {0, 90, 270}
    try:
        osd = pytesseract.image_to_osd(image, output_type=pytesseract.Output.DICT)
        angle = -int(osd.get("rotate", 0)) % 360  # PIL поворачивает против часовой стрелки
        if float(osd.get("orientation_conf", 0)) >= OSD_TRUSTED_CONFIDENCE:
            return image.rotate(angle, expand=True, fillcolor=255) if angle else image
        candidates.add(angle)
    except Exception:  # noqa: BLE001, S110 — мало текста для определения ориентации: пробуем все повороты
        pass
    scores = {angle: _readable_words(image.rotate(angle, expand=True, fillcolor=255) if angle else image)
              for angle in sorted(candidates)}
    best = max(scores, key=lambda a: (scores[a], a == 0))
    return image.rotate(best, expand=True, fillcolor=255) if best else image


def ocr_image(image: Image.Image) -> str:
    return _ocr_prepared(_fix_rotation(prepare_image(image)))


def _ocr_prepared(image: Image.Image) -> str:
    return pytesseract.image_to_string(image, lang=OCR_LANG, config="--psm 3")


def _image_pages(path: Path) -> list[Image.Image]:
    try:
        image = Image.open(path)
    except Exception as exc:
        raise LoaderError(f"Не удалось открыть изображение: {exc}") from exc
    pages = []
    for index in range(getattr(image, "n_frames", 1)):
        image.seek(index)
        pages.append(image.copy())
    return pages


def load_document(path: str | Path, progress=None, keep_images: bool = False, pages: list[int] | None = None,
                  scale: float = 1.0) -> LoadedDocument:
    """Возвращает текст документа постранично. progress(page, total) вызывается по ходу.

    keep_images=True дополнительно сохраняет первые страницы картинками — их смотрит ИИ.
    pages — номера страниц файла (с нуля), если нужны не все. scale > 1 — распознать крупнее:
    медленнее, но читается мелкий шрифт (например, шапка ТОРГ-12).
    """
    path = Path(path)
    suffix = path.suffix.lower()
    if suffix not in SUPPORTED_EXTENSIONS:
        raise LoaderError(f"Формат {suffix or 'без расширения'} не поддерживается")
    doc = LoadedDocument(path=path)

    if suffix in PDF_EXTENSIONS:
        import pypdfium2 as pdfium

        try:
            pdf = pdfium.PdfDocument(str(path))
        except Exception as exc:
            raise LoaderError(f"Не удалось открыть PDF: {exc}") from exc
        try:
            selected = [n for n in (pages if pages is not None else range(len(pdf))) if 0 <= n < len(pdf)]
            total = len(selected)
            for index, number in enumerate(selected):
                page = pdf[number]
                text = page.get_textpage().get_text_bounded() or ""
                need_image = keep_images and index < MAX_IMAGE_PAGES
                if len(text.strip()) < MIN_TEXT_LAYER_CHARS or need_image:
                    bitmap = page.render(scale=PDF_RENDER_DPI * scale / 72).to_pil()
                    image = _fix_rotation(prepare_image(bitmap, int(TARGET_LONG_SIDE * scale)))
                    if need_image:
                        doc.images.append(image)
                    if len(text.strip()) < MIN_TEXT_LAYER_CHARS:
                        text = _ocr_prepared(image)
                        doc.used_ocr = True
                doc.pages.append(text)
                if progress:
                    progress(index + 1, total)
        finally:
            pdf.close()
        return doc

    images = _image_pages(path)
    if pages is not None:
        images = [images[n] for n in pages if 0 <= n < len(images)]
    for index, image in enumerate(images):
        image = _fix_rotation(prepare_image(image, int(TARGET_LONG_SIDE * scale)))
        if keep_images and index < MAX_IMAGE_PAGES:
            doc.images.append(image)
        doc.pages.append(_ocr_prepared(image))
        if progress:
            progress(index + 1, len(images))
    doc.used_ocr = True
    return doc
