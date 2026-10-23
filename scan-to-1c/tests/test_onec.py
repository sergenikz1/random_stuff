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

    def __init__(self, counterparties=None, documents=None):
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
        if entity == "Document_ПоступлениеТоваровУслуг":
            if method == "POST":
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
