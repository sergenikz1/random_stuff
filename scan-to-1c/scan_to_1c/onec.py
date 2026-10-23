"""Выгрузка в 1С:Предприятие 8.3 через стандартный интерфейс OData (REST).

Что создаётся и какими реквизитами — задаётся шаблоном в настройках (см. DEFAULT_MAPPING),
поэтому подходит для разных конфигураций: Бухгалтерия, УТ, КА, ERP.
"""

from __future__ import annotations

import base64
import copy
import re
from dataclasses import dataclass
from urllib.parse import quote

import requests

from .extract import ExtractedData

EMPTY_REF = "00000000-0000-0000-0000-000000000000"

# Шаблон по умолчанию — «Поступление (акты, накладные, УПД)» в 1С:Бухгалтерии 3.0.
# Строка вида "{поле}" целиком заменяется значением с сохранением типа (число, дата),
# внутри более длинной строки — подставляется текстом.
DEFAULT_MAPPING = {
    "organization": {
        "entity": "Catalog_Организации",
        "inn_field": "ИНН",
        "default_key": "",
    },
    "counterparty": {
        "entity": "Catalog_Контрагенты",
        "inn_field": "ИНН",
        "kpp_field": "КПП",
        "create_if_missing": True,
        "fields": {
            "Description": "{seller_name}",
            "НаименованиеПолное": "{seller_name}",
            "ИНН": "{seller_inn}",
            "КПП": "{seller_kpp}",
            "ЮридическоеФизическоеЛицо": "{seller_legal_form}",
        },
    },
    "document": {
        "entity": "Document_ПоступлениеТоваровУслуг",
        "fields": {
            "Date": "{date}T00:00:00",
            "Организация_Key": "{organization_key}",
            "Контрагент_Key": "{counterparty_key}",
            "НомерВходящегоДокумента": "{number}",
            "ДатаВходящегоДокумента": "{date}T00:00:00",
            "СуммаДокумента": "{total}",
            "Комментарий": "Загружено из скана {file_name}",
        },
        "duplicate_check": ["НомерВходящегоДокумента", "Контрагент_Key"],
    },
}


class OneCError(Exception):
    pass


@dataclass
class UploadResult:
    ref_key: str
    number: str
    created: bool  # False — такой документ уже был в базе
    counterparty_created: bool = False


def build_context(data: ExtractedData, file_name: str = "") -> dict:
    seller = data.seller
    return {
        "doc_type": data.doc_type,
        "number": data.number,
        "date": data.date,
        "total": data.total if data.total is not None else 0,
        "vat": data.vat if data.vat is not None else 0,
        "vat_rate": data.vat_rate,
        "vat_rate_1c": vat_rate_enum(data.vat_rate),
        "seller_name": seller.name or f"Контрагент ИНН {seller.inn}",
        "seller_inn": seller.inn,
        "seller_kpp": seller.kpp,
        "seller_legal_form": "ФизическоеЛицо" if len(seller.inn) == 12 else "ЮридическоеЛицо",
        "buyer_name": data.buyer.name,
        "buyer_inn": data.buyer.inn,
        "buyer_kpp": data.buyer.kpp,
        "bik": data.bik,
        "account": data.account,
        "file_name": file_name,
    }


def vat_rate_enum(rate: str) -> str:
    """Значение перечисления СтавкиНДС в типовых конфигурациях."""
    return {"20%": "НДС20", "18%": "НДС18", "10%": "НДС10", "0%": "НДС0", "Без НДС": "БезНДС"}.get(rate, "")


_PLACEHOLDER = re.compile(r"\{(\w+)\}")


def render_template(template, context: dict):
    """Рекурсивно подставляет значения из context в шаблон (dict / list / str)."""
    if isinstance(template, dict):
        return {k: render_template(v, context) for k, v in template.items()}
    if isinstance(template, list):
        return [render_template(v, context) for v in template]
    if isinstance(template, str):
        whole = _PLACEHOLDER.fullmatch(template)
        if whole and whole.group(1) in context:
            return context[whole.group(1)]
        return _PLACEHOLDER.sub(lambda m: str(context.get(m.group(1), m.group(0))), template)
    return template


def _odata_string(value) -> str:
    if isinstance(value, (int, float)):
        return str(value)
    value = str(value)
    if re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", value):
        return f"guid'{value}'"
    return "'" + value.replace("'", "''") + "'"


class OneCClient:
    def __init__(self, base_url: str, user: str = "", password: str = "", timeout: int = 30,
                 verify_ssl: bool = True, session: requests.Session | None = None):
        base_url = base_url.strip().rstrip("/")
        if not base_url:
            raise OneCError("Не указан адрес публикации базы 1С")
        if not base_url.endswith("/odata/standard.odata"):
            base_url += "/odata/standard.odata"
        self.base_url = base_url
        self.timeout = timeout
        self.session = session or requests.Session()
        self.session.verify = verify_ssl
        self.session.headers.update({"Accept": "application/json", "Content-Type": "application/json"})
        if user:
            # requests кодирует Basic-авторизацию в latin-1, а имена пользователей 1С обычно кириллицей.
            token = base64.b64encode(f"{user}:{password}".encode()).decode("ascii")
            self.session.headers["Authorization"] = f"Basic {token}"

    # --- низкоуровневые запросы ---

    def _url(self, entity: str) -> str:
        return f"{self.base_url}/{quote(entity, safe='()_,=$')}"

    def _request(self, method: str, entity: str, params: dict | None = None, json=None) -> dict:
        params = {"$format": "json", **(params or {})}
        try:
            response = self.session.request(method, self._url(entity), params=params, json=json,
                                            timeout=self.timeout)
        except requests.RequestException as exc:
            raise OneCError(f"Нет связи с 1С: {exc}") from exc
        if response.status_code == 401:
            raise OneCError("1С отклонила логин или пароль (401)")
        if response.status_code == 404:
            raise OneCError(f"Объект {entity} не найден. Включите его в состав OData "
                            "(«Настройка стандартного интерфейса OData») или проверьте адрес.")
        if response.status_code >= 400:
            raise OneCError(f"1С вернула ошибку {response.status_code}: {_error_text(response)}")
        try:
            return response.json() if response.content else {}
        except ValueError as exc:
            raise OneCError("1С вернула не JSON — проверьте адрес публикации") from exc

    def find(self, entity: str, filters: dict, select: str = "Ref_Key") -> list[dict]:
        expr = " and ".join(f"{field} eq {_odata_string(value)}" for field, value in filters.items())
        params = {"$filter": expr, "$top": "5"}
        if select:
            params["$select"] = select
        return self._request("GET", entity, params=params).get("value", [])

    def create(self, entity: str, payload: dict) -> dict:
        return self._request("POST", entity, json=payload)

    def check_connection(self, mapping: dict | None = None) -> str:
        mapping = mapping or DEFAULT_MAPPING
        entities = [mapping["organization"]["entity"], mapping["counterparty"]["entity"],
                    mapping["document"]["entity"]]
        for entity in entities:
            self._request("GET", entity, params={"$top": "1", "$select": "Ref_Key"})
        return "Подключение работает, доступны: " + ", ".join(entities)

    # --- бизнес-логика ---

    def find_organization(self, mapping: dict, inn: str) -> str:
        org = mapping["organization"]
        if inn:
            rows = self.find(org["entity"], {org["inn_field"]: inn})
            if rows:
                return rows[0]["Ref_Key"]
        if org.get("default_key"):
            return org["default_key"]
        rows = self._request("GET", org["entity"],
                             params={"$top": "2", "$select": "Ref_Key", "$filter": "DeletionMark eq false"}
                             ).get("value", [])
        if len(rows) == 1:
            return rows[0]["Ref_Key"]
        raise OneCError(f"Не удалось определить организацию (ИНН покупателя «{inn or 'не распознан'}»). "
                        "Укажите организацию по умолчанию в настройках.")

    def ensure_counterparty(self, mapping: dict, context: dict) -> tuple[str, bool]:
        cp = mapping["counterparty"]
        inn = context["seller_inn"]
        if not inn:
            raise OneCError("Не распознан ИНН контрагента — заполните его вручную")
        filters = {cp["inn_field"]: inn}
        rows = self.find(cp["entity"], filters)
        if len(rows) > 1 and context.get("seller_kpp") and cp.get("kpp_field"):
            rows = self.find(cp["entity"], {**filters, cp["kpp_field"]: context["seller_kpp"]}) or rows
        if rows:
            return rows[0]["Ref_Key"], False
        if not cp.get("create_if_missing", True):
            raise OneCError(f"Контрагент с ИНН {inn} не найден в 1С")
        payload = _drop_empty(render_template(cp["fields"], context))
        created = self.create(cp["entity"], payload)
        return created["Ref_Key"], True

    def upload(self, data: ExtractedData, file_name: str = "", mapping: dict | None = None) -> UploadResult:
        mapping = mapping or DEFAULT_MAPPING
        if not data.date:
            raise OneCError("Не заполнена дата документа")
        context = build_context(data, file_name)
        context["organization_key"] = self.find_organization(mapping, data.buyer.inn)
        context["counterparty_key"], cp_created = self.ensure_counterparty(mapping, context)

        doc = mapping["document"]
        payload = _drop_empty(render_template(doc["fields"], context))

        check = {f: payload[f] for f in doc.get("duplicate_check", []) if payload.get(f) not in (None, "")}
        if check and len(check) == len(doc.get("duplicate_check", [])):
            existing = self.find(doc["entity"], check, select="Ref_Key,Number")
            if existing:
                return UploadResult(existing[0]["Ref_Key"], existing[0].get("Number", ""), False, cp_created)

        created = self.create(doc["entity"], payload)
        return UploadResult(created.get("Ref_Key", ""), created.get("Number", ""), True, cp_created)


def _drop_empty(payload: dict) -> dict:
    """Пустые строки не отправляем: 1С не примет "" в ссылочный реквизит."""
    return {k: v for k, v in payload.items() if v not in ("", None, EMPTY_REF)}


def _error_text(response: requests.Response) -> str:
    try:
        body = response.json()
        message = body.get("odata.error", {}).get("message", {})
        if isinstance(message, dict):
            return message.get("value", "") or response.text[:300]
        return str(message) or response.text[:300]
    except ValueError:
        return response.text[:300]


def merged_mapping(custom: dict | None) -> dict:
    """Пользовательский шаблон поверх шаблона по умолчанию (по разделам)."""
    result = copy.deepcopy(DEFAULT_MAPPING)
    for section, value in (custom or {}).items():
        if isinstance(value, dict) and isinstance(result.get(section), dict):
            result[section].update(value)
        else:
            result[section] = value
    return result
