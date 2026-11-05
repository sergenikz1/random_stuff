from samples import ACT, INVOICE, INVOICE_PHOTO, TORG12_PHOTO, UPD

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


def test_amounts_with_any_thousands_separator():
    assert parse_amount("87.340,00") == 87340.0
    assert parse_amount("21,835.00") == 21835.0
    assert parse_amount("87340.") == 87340.0


def test_torg12_photo():
    d = extract(TORG12_PHOTO)
    assert d.doc_type == "Товарная накладная"
    assert (d.number, d.date) == ("1482791257", "2026-10-22")  # не дата заказа из «Основание»
    assert (d.seller.inn, d.seller.name) == ("7705123452", "ООО «Альфа-Мед»")
    assert (d.buyer.inn, d.buyer.name) == ("5027123457", "ООО «Бета-Трейд»")
    assert d.total == 87340.0
    assert (d.vat_rate, d.vat) == ("Без НДС", 0.0)
    assert d.basis == "Заказ № 13-FY27/LLC/6261084513 от 14.10.2026"
    assert d.shipper.name == "ООО «Альфа-Мед»" and d.shipper.inn == "7705123452"
    assert d.consignee.name == "ООО «Бета-Трейд»"
    assert d.warnings == []


def test_invoice_photo_without_inn_labels():
    d = extract(INVOICE_PHOTO)
    assert d.doc_type == "Счет-фактура"
    assert (d.number, d.date) == ("1056358372", "2026-10-22")
    assert (d.seller.name, d.seller.inn, d.seller.kpp) == ("ООО «Альфа-Мед»", "7705123452", "770501001")
    assert d.buyer.name == "ООО «Бета-Трейд»"
    assert d.total == 87340.0
    assert d.vat_rate == "Без НДС"
    assert d.basis == "13-FY27/LLC/6261084513"
    assert d.shipment_doc == "№ 1482791257 от 22.10.2026"


def test_items_sum_is_checked():
    from scan_to_1c.extract import Item

    d = extract(TORG12_PHOTO)
    d.items = [Item(name="Датчик", quantity=4, amount=80000, total=80000)]
    from scan_to_1c.extract import validate

    assert any("Сумма строк товаров 80000.00" in w for w in validate(d))


def test_invoice_items_and_total_from_lines():
    from samples import INVOICE_TWO_ITEMS

    d = extract(INVOICE_TWO_ITEMS)
    assert (d.number, d.date) == ("1056357861", "2026-10-17")  # «17.10. 2026» — пробел внутри даты
    assert d.total == 307480.0  # «Всего к оплате» не прочиталось — итог по сумме строк
    assert [(i.code, i.unit, i.quantity, i.price, i.total) for i in d.items] == [
        ("5093600", "кор", 5.0, 41900.0, 209500.0), ("5093500", "кор", 2.0, 48990.0, 97980.0)]
    first = d.items[0]
    assert first.name == "Изделия медицинские для анестезиологии и вентиляции легких. Контуры дыхательные"
    assert (first.vat_rate, first.country, first.gtd) == ("Без НДС", "Италия", "10511010/101026/5177319")
    assert d.warnings == []


def test_simple_invoice_table_lines():
    d = extract(INVOICE)
    assert [(i.name, i.quantity, i.unit, i.total) for i in d.items] == [
        ("Бумага офисная А4", 10.0, "пач", 3500.0), ("Доставка", 1.0, "усл", 1000.0)]


def test_torg12_frames_and_form_dates():
    from samples import TORG12_FRAMES, TORG12_PAGE2

    d = extract(TORG12_FRAMES + "\n\n" + TORG12_PAGE2)
    # Не дата транспортной накладной (16.10) и не «25.12.98» из реквизитов формы.
    assert (d.number, d.date) == ("1482304542", "2026-10-17")
    assert (d.seller.inn, d.buyer.inn) == ("7705123452", "5027123457")
    assert d.total == 307480.0
    assert d.items == []  # строки из перемешанной таблицы ТОРГ-12 не сходятся с итогом — не берём
    assert d.warnings == []


def test_split_documents_in_one_file():
    from samples import INVOICE_TWO_ITEMS, TORG12_FRAMES, TORG12_PAGE2

    from scan_to_1c.extract import split_documents

    assert split_documents([TORG12_FRAMES, TORG12_PAGE2, INVOICE_TWO_ITEMS]) == [[0, 1], [2]]
    assert split_documents([UPD, "Всего к оплате 118 800,00", INVOICE_PHOTO]) == [[0, 1, 2]]  # УПД ~ счёт-фактура
    assert split_documents(["подписи", INVOICE]) == [[0, 1]]
    assert split_documents([]) == []
