"""Распознавание реквизитов ИИ-моделью через OmniRoute (или любой OpenAI-совместимый шлюз).

Модели отдаются текст после OCR и, по желанию, картинки страниц. Ответ — JSON с реквизитами,
который проверяется теми же правилами, что и разбор шаблонами (контрольная сумма ИНН, НДС).
Если ИИ недоступен или ответил ерундой, программа остаётся с результатом шаблонов.
"""

from __future__ import annotations

import base64
import io
import json
import re
from dataclasses import dataclass

import requests
from PIL import Image

from .extract import ExtractedData, Item, Party, inn_is_valid, parse_amount, parse_date, validate

DEFAULT_BASE_URL = "http://localhost:20128/v1"
DEFAULT_MODEL = "auto"
# Маршрут OmniRoute к моделям, которые видят картинки; им заменяем «auto», когда отправляем страницы.
VISION_MODEL = "auto/best-vision"
MAX_TEXT_CHARS = 15000
IMAGE_MAX_SIDE = 1600
DOC_TYPES = ["УПД", "Счет-фактура", "Товарная накладная", "Акт", "Счет на оплату", "Платежное поручение"]

SYSTEM_PROMPT = """Ты помощник бухгалтера. Тебе дают скан российского первичного документа: картинки страниц
и/или текст, распознанный OCR (в нём бывают ошибки). Извлеки реквизиты и верни ТОЛЬКО JSON-объект без пояснений:

{
  "doc_type": "одно из: УПД, Счет-фактура, Товарная накладная, Акт, Счет на оплату, Платежное поручение, или пусто",
  "number": "номер документа как напечатан",
  "date": "дата документа в формате ГГГГ-ММ-ДД",
  "seller": {"name": "продавец / поставщик / исполнитель", "inn": "10 или 12 цифр", "kpp": "9 цифр или пусто"},
  "buyer": {"name": "покупатель / заказчик / плательщик", "inn": "", "kpp": ""},
  "total": число — итоговая сумма к оплате с НДС,
  "vat": число — сумма НДС, 0 если без НДС, null если не указана,
  "vat_rate": "20%, 10%, 0% или Без НДС",
  "bik": "БИК банка продавца", "account": "расчётный счёт продавца, 20 цифр", "corr_account": "корр. счёт, 20 цифр",
  "basis": "основание: договор или заказ с номером и датой, как напечатано",
  "shipment_doc": "документ об отгрузке, например «№ 1482791257 от 22.10.2026», или пусто",
  "shipper": {"name": "грузоотправитель", "inn": ""},
  "consignee": {"name": "грузополучатель", "inn": ""},
  "items": [
    {"name": "наименование товара, работы, услуги", "code": "артикул или код товара",
     "unit": "единица измерения", "quantity": число, "price": число — цена за единицу без НДС,
     "amount": число — стоимость без НДС, "vat_rate": "20%, 10%, 0% или Без НДС", "vat": число,
     "total": число — стоимость с НДС, "country": "страна происхождения", "gtd": "номер декларации на товары"}
  ]
}

Правила: не выдумывай — если реквизита нет, оставь пустую строку или null. Наименование организации пиши
с формой собственности и названием в кавычках-ёлочках, например ООО «Ромашка». В счёте на оплату получатель
платежа — это продавец. Числа без пробелов-разделителей, дробная часть через точку. В items перечисли все строки
таблицы товаров по порядку; строки «Итого» и «Всего» туда не включай. Документ может быть сфотографирован боком
или занимать несколько страниц — читай все."""


class AIError(Exception):
    pass


@dataclass
class AIConfig:
    base_url: str = DEFAULT_BASE_URL
    api_key: str = ""
    model: str = DEFAULT_MODEL
    send_images: bool = True
    timeout: int = 120


def image_to_data_url(image: Image.Image) -> str:
    image = image.convert("RGB") if image.mode not in ("RGB", "L") else image
    if max(image.size) > IMAGE_MAX_SIDE:
        image = image.copy()
        image.thumbnail((IMAGE_MAX_SIDE, IMAGE_MAX_SIDE), Image.LANCZOS)
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=85)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


def build_messages(text: str, images: list[Image.Image]) -> list[dict]:
    text = text.strip()
    if len(text) > MAX_TEXT_CHARS:
        text = text[:MAX_TEXT_CHARS] + "\n…(текст обрезан)"
    prompt = "Текст документа после OCR:\n\n" + (text or "(OCR не дал текста — смотри на картинки)")
    if not images:
        user = prompt
    else:
        user = [{"type": "text", "text": prompt}] + [
            {"type": "image_url", "image_url": {"url": image_to_data_url(img)}} for img in images]
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user}]


class AIClient:
    def __init__(self, config: AIConfig, session: requests.Session | None = None):
        base = config.base_url.strip().rstrip("/")
        if not base:
            raise AIError("Не указан адрес OmniRoute")
        if not base.endswith("/v1"):
            base += "/v1"
        self.base_url = base
        self.config = config
        self.session = session or requests.Session()
        self.headers = {"Content-Type": "application/json"}
        if config.api_key:
            self.headers["Authorization"] = f"Bearer {config.api_key}"

    def _post(self, path: str, payload: dict) -> dict:
        return self._request("POST", path, json=payload)

    def _request(self, method: str, path: str, **kwargs) -> dict:
        url = self.base_url + path
        try:
            response = self.session.request(method, url, headers=self.headers, timeout=self.config.timeout,
                                            **kwargs)
        except requests.ConnectionError as exc:
            raise AIError(f"OmniRoute не отвечает по адресу {self.base_url} — он запущен?") from exc
        except requests.Timeout as exc:
            raise AIError(f"OmniRoute не ответил за {self.config.timeout} с") from exc
        except requests.RequestException as exc:
            raise AIError(f"Ошибка связи с OmniRoute: {exc}") from exc
        if response.status_code in (401, 403):
            raise AIError("OmniRoute отклонил ключ — скопируйте его в панели OmniRoute: Dashboard → Endpoints")
        if response.status_code >= 400:
            hint = (" — проверьте, что в панели OmniRoute подключён провайдер с подходящей моделью"
                    if response.status_code in (502, 503) else "")
            raise AIError(f"OmniRoute вернул ошибку {response.status_code}: {_error_text(response)}{hint}")
        if "text/event-stream" in response.headers.get("Content-Type", ""):
            return _collect_stream(response.text)
        try:
            return response.json()
        except ValueError as exc:
            raise AIError("OmniRoute вернул не JSON — проверьте адрес (должен заканчиваться на /v1)") from exc

    def check(self) -> str:
        """Проверка подключения: список моделей, доступных по ключу."""
        models = self._request("GET", "/models").get("data", [])
        names = [m.get("id", "") for m in models if m.get("id")]
        shown = ", ".join(names[:8]) + ("…" if len(names) > 8 else "")
        return f"OmniRoute отвечает, моделей доступно: {len(names)}" + (f" ({shown})" if names else "")

    def model_for(self, with_images: bool) -> str:
        model = self.config.model or DEFAULT_MODEL
        return VISION_MODEL if with_images and model == DEFAULT_MODEL else model

    def extract(self, text: str, images: list[Image.Image] | None = None) -> ExtractedData:
        images = (images or []) if self.config.send_images else []
        payload = {"model": self.model_for(bool(images)), "temperature": 0, "stream": False,
                   "messages": build_messages(text, images)}
        body = self._post("/chat/completions", payload)
        try:
            content = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise AIError("Модель вернула пустой ответ") from exc
        if isinstance(content, list):  # некоторые провайдеры отдают ответ частями
            content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
        if not (content or "").strip():
            raise AIError("Модель вернула пустой ответ — попробуйте другую модель в настройках")
        return to_extracted(parse_json(content))


def _collect_stream(raw: str) -> dict:
    """OmniRoute иногда отвечает потоком SSE даже без stream=true — собираем его в обычный ответ."""
    parts = []
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        chunk = line[5:].strip()
        if not chunk or chunk == "[DONE]":
            continue
        try:
            event = json.loads(chunk)
        except ValueError:
            continue
        if "error" in event:
            error = event["error"]
            raise AIError(f"OmniRoute вернул ошибку: {error.get('message', error) if isinstance(error, dict) else error}")
        for choice in event.get("choices", []):
            parts.append((choice.get("delta") or choice.get("message") or {}).get("content") or "")
    return {"choices": [{"message": {"role": "assistant", "content": "".join(parts)}}]}


def parse_json(content: str) -> dict:
    """Достаёт JSON-объект из ответа модели, даже если он обёрнут в ```json или пояснения."""
    content = re.sub(r"^\s*```(?:json)?|```\s*$", "", content.strip(), flags=re.IGNORECASE)
    start, end = content.find("{"), content.rfind("}")
    if start == -1 or end <= start:
        raise AIError("Модель ответила не JSON: " + content[:200])
    try:
        data = json.loads(content[start:end + 1])
    except ValueError as exc:
        raise AIError(f"Модель вернула битый JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise AIError("Модель вернула не объект JSON")
    return data


def _digits(value) -> str:
    return re.sub(r"\D", "", str(value or ""))


def _text(value) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _amount(value) -> float | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)):
        return round(float(value), 2)
    return parse_amount(str(value))


def _date(value) -> str:
    value = _text(value)
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return parse_date(f"{value[8:10]}.{value[5:7]}.{value[:4]}")
    return parse_date(value) if value else ""


def _doc_type(value) -> str:
    value = _text(value).lower().replace("ё", "е")
    return next((t for t in DOC_TYPES if t.lower().replace("ё", "е") == value), "")


def _party(value) -> Party:
    value = value if isinstance(value, dict) else {}
    inn, kpp = _digits(value.get("inn")), _digits(value.get("kpp"))
    return Party(name=_text(value.get("name")),
                 inn=inn if len(inn) in (10, 12) else "",
                 kpp=kpp if len(kpp) == 9 else "")


def _vat_rate(value) -> str:
    value = _text(value)
    if value.lower().startswith("без"):
        return "Без НДС"
    if re.fullmatch(r"\d{1,2}\s*%?", value):
        return f"{int(re.sub(r'[^0-9]', '', value))}%"
    return ""


def _item(value) -> Item | None:
    if not isinstance(value, dict):
        return None
    gtd = _text(value.get("gtd"))
    item = Item(name=_text(value.get("name")), code=_text(value.get("code")), unit=_text(value.get("unit")),
                quantity=_amount(value.get("quantity")), price=_amount(value.get("price")),
                amount=_amount(value.get("amount")), vat_rate=_vat_rate(value.get("vat_rate")),
                vat=_amount(value.get("vat")), total=_amount(value.get("total")),
                country=_text(value.get("country")),
                gtd=gtd if re.fullmatch(r"\d{8}/\d{6}/\d{7}(?:/\d{1,3})?", gtd) else "")
    if not item.name or re.match(r"(итого|всего)\b", item.name, re.IGNORECASE):
        return None
    return item


def to_extracted(data: dict) -> ExtractedData:
    vat_rate = _vat_rate(data.get("vat_rate"))
    account, corr = _digits(data.get("account")), _digits(data.get("corr_account"))
    bik = _digits(data.get("bik"))
    return ExtractedData(
        doc_type=_doc_type(data.get("doc_type")), number=_text(data.get("number")), date=_date(data.get("date")),
        seller=_party(data.get("seller")), buyer=_party(data.get("buyer")),
        total=_amount(data.get("total")), vat=_amount(data.get("vat")), vat_rate=vat_rate,
        bik=bik if len(bik) == 9 else "", account=account if len(account) == 20 else "",
        corr_account=corr if len(corr) == 20 else "",
        basis=_text(data.get("basis")), shipment_doc=_text(data.get("shipment_doc")),
        shipper=_party(data.get("shipper")), consignee=_party(data.get("consignee")),
        items=[i for i in map(_item, data.get("items") or []) if i])


def merge(ai: ExtractedData, rules: ExtractedData) -> ExtractedData:
    """ИИ главный; пробелы заполняются результатом шаблонов. ИНН с битой контрольной суммой не принимаем,
    если шаблоны нашли корректный."""
    result = ExtractedData()
    for name in ("doc_type", "number", "date", "vat_rate", "bik", "account", "corr_account", "basis",
                 "shipment_doc"):
        setattr(result, name, getattr(ai, name) or getattr(rules, name))
    for role in ("shipper", "consignee"):
        a, r = getattr(ai, role), getattr(rules, role)
        setattr(result, role, Party(name=a.name or r.name, inn=a.inn or r.inn, kpp=a.kpp or r.kpp))
    result.items = ai.items or rules.items
    result.total = ai.total if ai.total is not None else rules.total
    result.vat = ai.vat if ai.vat is not None else rules.vat
    for role in ("seller", "buyer"):
        a, r = getattr(ai, role), getattr(rules, role)
        main, other = (a, r) if a.inn and (inn_is_valid(a.inn) or not inn_is_valid(r.inn)) else (r, a)
        kpp = main.kpp or (other.kpp if other.inn == main.inn else "")
        setattr(result, role, Party(name=a.name or r.name, inn=main.inn, kpp=kpp))
    result.warnings = validate(result)
    return result


def _error_text(response: requests.Response) -> str:
    try:
        error = response.json().get("error", {})
        if isinstance(error, dict):
            return error.get("message") or response.text[:300]
        return str(error) or response.text[:300]
    except (ValueError, AttributeError):
        return response.text[:300]


def config_from_settings(settings) -> AIConfig:
    return AIConfig(base_url=settings.ai_base_url, api_key=settings.ai_api_key,
                    model=settings.ai_model or DEFAULT_MODEL, send_images=settings.ai_send_images)


def recognize(client: AIClient | None, text: str, images, rules: ExtractedData) -> tuple[ExtractedData, str]:
    """Результат с ИИ, если он включён и ответил; иначе — шаблоны и пояснение, почему без ИИ."""
    if client is None:
        return rules, ""
    try:
        data = merge(client.extract(text, images), rules)
    except AIError as exc:
        rules.warnings.insert(0, f"ИИ не сработал ({exc}) — реквизиты найдены шаблонами")
        return rules, ""
    with_images = bool(images) and client.config.send_images
    return data, f"Распознано ИИ ({client.model_for(with_images)})"
