import json

from samples import UPD

from scan_to_1c.exporter import export_to_folder
from scan_to_1c.extract import extract
from scan_to_1c.settings import Settings


def test_export_writes_json_and_scan_copy(tmp_path):
    scan = tmp_path / "in" / "Скан.JPG"
    scan.parent.mkdir()
    scan.write_bytes(b"jpeg")
    out = tmp_path / "out"
    first = export_to_folder(extract(UPD), scan, out, "text")
    second = export_to_folder(extract(UPD), scan, out, "text")
    assert first != second
    payload = json.loads(first.read_text(encoding="utf-8"))
    assert payload["number"] == "УТ-2041"
    assert payload["seller"]["inn"] == "7707083893"
    assert (out / payload["scan_file"]).read_bytes() == b"jpeg"


def test_settings_roundtrip(tmp_path):
    path = tmp_path / "settings.json"
    Settings(user="Администратор", password="секрет", mapping={"x": 1}).save(path)
    assert "секрет" not in path.read_text(encoding="utf-8")
    loaded = Settings.load(path)
    assert (loaded.user, loaded.password, loaded.mapping) == ("Администратор", "секрет", {"x": 1})


def test_export_document_of_several_photos(tmp_path):
    pages = []
    for n in (1, 2):
        page = tmp_path / f"photo_{n}.jpg"
        page.write_bytes(f"page{n}".encode())
        pages.append(page)
    target = export_to_folder(extract(UPD), pages, tmp_path / "out")
    payload = json.loads(target.read_text(encoding="utf-8"))
    assert payload["scan_file"] == payload["scan_files"][0]
    assert len(payload["scan_files"]) == 2
    assert [(tmp_path / "out" / name).read_bytes() for name in payload["scan_files"]] == [b"page1", b"page2"]
