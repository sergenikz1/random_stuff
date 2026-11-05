from samples import INVOICE_TWO_ITEMS, TORG12_FRAMES
from test_loader import needs_ocr, render_scan

from scan_to_1c import pipeline


@needs_ocr
def test_pdf_with_two_documents_is_split(tmp_path):
    path = tmp_path / "накладная и счёт-фактура.pdf"
    pages = [render_scan(TORG12_FRAMES).convert("RGB"), render_scan(INVOICE_TWO_ITEMS).convert("RGB")]
    pages[0].save(path, save_all=True, append_images=pages[1:], resolution=300.0)

    parts = pipeline.recognize([path])
    assert [p.page_numbers for p in parts] == [[0], [1]]
    torg, invoice = (p.data for p in parts)
    assert (torg.doc_type, torg.number, torg.date) == ("Товарная накладная", "1482304542", "2026-10-17")
    assert (invoice.doc_type, invoice.number, invoice.total) == ("Счет-фактура", "1056357861", 307480.0)
    assert len(invoice.items) == 2

    # Повторное распознавание одной части читает только её страницы.
    again = pipeline.recognize([path], page_numbers=[1])
    assert [p.page_numbers for p in again] == [[1]]
    assert again[0].data.number == "1056357861"


def test_photos_of_one_document_are_not_split(tmp_path, monkeypatch):
    from scan_to_1c.loader import LoadedDocument

    texts = {"p1.jpg": TORG12_FRAMES, "p2.jpg": INVOICE_TWO_ITEMS}

    def fake_load(path, **_kwargs):
        return LoadedDocument(path=path, pages=[texts[path.name]], used_ocr=True)

    monkeypatch.setattr(pipeline, "load_document", fake_load)
    parts = pipeline.recognize([tmp_path / "p1.jpg", tmp_path / "p2.jpg"])
    assert len(parts) == 1 and parts[0].page_numbers is None

