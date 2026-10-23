from pathlib import Path

import pytest
from PIL import Image, ImageDraw, ImageFont
from samples import INVOICE

from scan_to_1c.extract import extract
from scan_to_1c.loader import LoaderError, configure_tesseract, load_document, tesseract_available

configure_tesseract()
needs_ocr = pytest.mark.skipif(not tesseract_available(), reason="Tesseract не установлен")

FONT_CANDIDATES = ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf"]


def render_scan(text: str) -> Image.Image:
    font_path = next((f for f in FONT_CANDIDATES if Path(f).exists()), None)
    if not font_path:
        pytest.skip("Нет шрифта с кириллицей")
    font = ImageFont.truetype(font_path, 30)
    image = Image.new("L", (2480, 3508), 255)  # A4, 300 dpi
    draw = ImageDraw.Draw(image)
    for i, line in enumerate(text.splitlines()):
        draw.text((120, 150 + i * 48), line, font=font, fill=0)
    return image


def check_invoice(data):
    assert data.number == "154"
    assert data.date == "2025-03-12"
    assert data.seller.inn == "7701234560"
    assert data.buyer.inn == "5001007329"
    assert data.total == 4500.0


@needs_ocr
@pytest.mark.parametrize("suffix", [".png", ".jpg", ".tiff", ".pdf"])
def test_ocr_scan_formats(tmp_path, suffix):
    image = render_scan(INVOICE)
    path = tmp_path / f"scan{suffix}"
    if suffix == ".pdf":
        image.convert("RGB").save(path, resolution=300.0)
    else:
        image.save(path, quality=90) if suffix == ".jpg" else image.save(path)
    doc = load_document(path)
    assert doc.used_ocr
    check_invoice(extract(doc.text))


@needs_ocr
def test_rotated_scan(tmp_path):
    path = tmp_path / "rotated.png"
    render_scan(INVOICE).rotate(90, expand=True, fillcolor=255).save(path)
    check_invoice(extract(load_document(path).text))


def test_unsupported_format(tmp_path):
    path = tmp_path / "doc.docx"
    path.write_text("x")
    with pytest.raises(LoaderError):
        load_document(path)
