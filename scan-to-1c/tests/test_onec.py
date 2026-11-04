import base64
import json
from urllib.parse import unquote

import pytest
from samples import INVOICE

from scan_to_1c.extract import extract
from scan_to_1c.onec import OneCClient, OneCError, merged_mapping, render_template

ORG_KEY = "11111111-1111-1111-1111-111111111111"
CP_KEY = "22222222-2222-2222-2222-222222222222"
DOC_KEY = "33333333-3333-3333-3333-333333333333"
NOM_KEY = "44444444-4444-4444-4444-444444444444"


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body
        self.content = json.dumps(body).encode()
        self.text = self.content.decode()

    def json(self):
        return self._body


class FakeSession:
    """Имитирует OData 1С: справочники и документы в памяти."""

    def __init__(self, counterparties=None, documents=None, reject_items=False):
        self.nomenclature = []
        self.reject_items = reject_items
        self.headers = {}
        self.verify = True
        self.calls = []
        self.counterparties = counterparties or []
        self.documents = documents or []

    def request(self, method, url, params=None, json=None, timeout=None):
        entity = unquote(url.rsplit("/", 1)[1])
        self.calls.append((method, entity, params, json))
        flt = (params or {}).get("$filter", "")
        if entity == "Catalog_Организации":
            return FakeResponse(200, {"value": [{"Ref_Key": ORG_KEY}] if "5001007329" in flt else []})
        if entity == "Catalog_Контрагенты":
            if method == "POST":
                self.counterparties.append(json)
                return FakeResponse(201, {"Ref_Key": CP_KEY, **json})
            rows = [{"Ref_Key": CP_KEY} for c in self.counterparties if c["ИНН"] in flt]
            return FakeResponse(200, {"value": rows})
        if entity == "Catalog_Номенклатура":
            if method == "POST":
                self.nomenclature.append(json)
                return FakeResponse(201, {"Ref_Key": NOM_KEY, **json})
            rows = [{"Ref_Key": NOM_KEY} for n in self.nomenclature
                    if f"'{n.get('Артикул')}'" in flt or f"'{n['Description']}'" in flt]
            return FakeResponse(200, {"value": rows})
        if entity == "Document_ПоступлениеТоваровУслуг":
            if method == "POST":
                if self.reject_items and "Товары" in json:
                    return FakeResponse(400, {"odata.error": {"message": {"value": "Неизвестное поле Товары"}}})
                self.documents.append(json)
                return FakeResponse(201, {"Ref_Key": DOC_KEY, "Number": "0000-000001", **json})
            rows = [{"Ref_Key": DOC_KEY, "Number": "0000-000001"}
                    for d in self.documents if f"'{d['НомерВходящегоДокумента']}'" in flt]
            return FakeResponse(200, {"value": rows})
        return FakeResponse(404, {})


def test_render_template_keeps_types():
    ctx = {"total": 10.5, "number": "7"}
    assert render_template({"a": "{total}", "b": ["№ {number}"], "c": "{unknown}"}, ctx) == \
        {"a": 10.5, "b": ["№ 7"], "c": "{unknown}"}


def test_upload_creates_counterparty_and_document():
    session = FakeSession()
    client = OneCClient("http://srv/base/", "Администратор", "пароль", session=session)
    assert client.base_url == "http://srv/base/odata/standard.odata"
    token = session.headers["Authorization"].split()[1]
    assert base64.b64decode(token).decode("utf-8") == "Администратор:пароль"

    result = client.upload(extract(INVOICE), "scan.pdf")
    assert result.created and result.counterparty_created
    assert session.counterparties[0]["ИНН"] == "7701234560"
    assert session.counterparties[0]["Description"] == "ООО «Ромашка»"
    doc = session.documents[0]
    assert doc["Организация_Key"] == ORG_KEY
    assert doc["Контрагент_Key"] == CP_KEY
    assert doc["НомерВходящегоДокумента"] == "154"
    assert doc["Date"] == "2025-03-12T00:00:00"
    assert doc["СуммаДокумента"] == 4500.0

    # Повторная загрузка того же скана не создаёт дубль.
    again = client.upload(extract(INVOICE), "scan.pdf")
    assert not again.created and not again.counterparty_created
    assert len(session.documents) == 1


def test_upload_uses_default_organization_and_custom_document():
    session = FakeSession()
    client = OneCClient("http://srv/base", session=session)
    data = extract(INVOICE)
    data.buyer.inn = ""
    mapping = merged_mapping({"organization": {"default_key": ORG_KEY}})
    client.upload(data, "scan.pdf", mapping)
    assert session.documents[0]["Организация_Key"] == ORG_KEY


def test_missing_entity_gives_readable_error():
    client = OneCClient("http://srv/base", session=FakeSession())
    with pytest.raises(OneCError, match="состав OData"):
        client.check_connection(merged_mapping({"document": {"entity": "Document_Нет"}}))


def invoice_with_items():
    from scan_to_1c.extract import Item

    data = extract(INVOICE)
    data.basis = "Заказ № 7 от 01.03.2025"
    data.items = [Item(name="Бумага офисная А4", code="A4-500", unit="пач", quantity=10, price=350,
                       amount=3500, vat_rate="20%", vat=583.33, total=3500),
                  Item(name="Доставка", quantity=1, price=1000, amount=1000, total=1000, gtd="")]
    return data


def test_upload_with_items_creates_nomenclature_and_table():
    session = FakeSession()
    result = OneCClient("http://srv/base", session=session).upload(invoice_with_items(), "scan.jpg")
    assert result.created and result.note == "строк товаров: 2"
    assert [n["Description"] for n in session.nomenclature] == ["Бумага офисная А4", "Доставка"]
    assert session.nomenclature[0]["Артикул"] == "A4-500"
    doc = session.documents[0]
    assert doc["ВидОперации"] == "Товары" and doc["СуммаВключаетНДС"] is True
    assert "основание: Заказ № 7 от 01.03.2025" in doc["Комментарий"]
    first = doc["Товары"][0]
    assert first == {"LineNumber": "1", "Номенклатура_Key": NOM_KEY, "Количество": 10.0, "Цена": 350.0,
                     "Сумма": 3500, "СтавкаНДС": "НДС20", "СуммаНДС": 583.33}


def test_upload_falls_back_to_header_when_1c_rejects_items():
    session = FakeSession(reject_items=True)
    result = OneCClient("http://srv/base", session=session).upload(invoice_with_items(), "scan.jpg")
    assert result.created
    assert "строки товаров не загружены" in result.note and "Неизвестное поле Товары" in result.note
    doc = session.documents[0]
    assert "Товары" not in doc and "ВидОперации" not in doc
    assert doc["СуммаДокумента"] == 4500.0
