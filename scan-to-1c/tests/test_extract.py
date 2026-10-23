from samples import ACT, INVOICE, UPD

from scan_to_1c.extract import extract, inn_is_valid, parse_amount, parse_date


def test_parse_date_formats():
    assert parse_date("12.03.2025") == "2025-03-12"
    assert parse_date("31.01.25") == "2025-01-31"
    assert parse_date("«5» мая 2024 г.") == "2024-05-05"
    assert parse_date("12 марта 2025") == "2025-03-12"
    assert parse_date("32.01.2025") == ""


def test_parse_amount():
    assert parse_amount("118 800,00") == 118800.0
    assert parse_amount("1 000.5") == 1000.5


def test_inn_checksum():
    assert inn_is_valid("7707083893")
    assert inn_is_valid("500100732259")
    assert not inn_is_valid("7707083894")


def test_invoice():
    d = extract(INVOICE)
    assert d.doc_type == "Счет на оплату"
    assert (d.number, d.date) == ("154", "2025-03-12")
    assert (d.seller.inn, d.seller.kpp) == ("7701234560", "770101001")
    assert d.seller.name == "ООО «Ромашка»"
    assert (d.buyer.inn, d.buyer.name) == ("5001007329", "АО «Василёк»")
    assert d.total == 4500.0
    assert (d.vat, d.vat_rate) == (750.0, "20%")
    assert (d.bik, d.account, d.corr_account) == ("044525225", "40702810938000012345", "30101810400000000225")
    assert d.warnings == []


def test_upd():
    d = extract(UPD)
    assert d.doc_type == "УПД"
    assert (d.number, d.date) == ("УТ-2041", "2025-02-05")
    assert (d.seller.inn, d.seller.kpp, d.seller.name) == ("7707083893", "780101001", "ООО «Техснаб»")
    assert (d.buyer.inn, d.buyer.kpp) == ("7736050003", "773601001")
    assert d.total == 118800.0
    assert (d.vat, d.vat_rate) == (19800.0, "20%")


def test_act_without_vat():
    d = extract(ACT)
    assert d.doc_type == "Акт"
    assert (d.number, d.date) == ("17", "2025-01-31")
    assert d.seller.inn == "500100732259"
    assert d.seller.name.startswith("ИП Иванов")
    assert d.buyer.inn == "7736050003"
    assert d.total == 25000.0
    assert (d.vat_rate, d.vat) == ("Без НДС", 0.0)


def test_ocr_noise_number_sign():
    d = extract("Счет на оплату Ne 77 от 01.02.2025\nПоставщик: ООО Альфа ИНН 7707083893")
    assert (d.number, d.date) == ("77", "2025-02-01")


def test_empty_text_gives_warnings():
    d = extract("")
    assert d.warnings
