"""Извлечение реквизитов первичных документов (счёт, УПД, счёт-фактура, накладная, акт) из текста."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from datetime import date

MONTHS = {
    "январ": 1, "феврал": 2, "март": 3, "апрел": 4, "ма": 5, "июн": 6,
    "июл": 7, "август": 8, "сентябр": 9, "октябр": 10, "ноябр": 11, "декабр": 12,
}

DOC_TYPES = [
    # (тип, шаблон) — порядок важен: УПД содержит слова «счет-фактура».
    ("УПД", r"универсальн\w*\s+передаточн\w*\s+документ|\bУПД\b"),
    ("Счет-фактура", r"сч[её]т[\s-]*фактур"),
    ("Товарная накладная", r"товарн\w*\s+накладн|ТОРГ[\s-]*12"),
    ("Акт", r"\bакт\b[^\n]{0,60}(?:выполненн|оказанн|сдачи|при[её]мк)|\bакт\s*№"),
    ("Платежное поручение", r"платежн\w*\s+поручени"),
    ("Счет на оплату", r"сч[её]т\s+на\s+оплату|\bсч[её]т\s*№"),
]

SELLER_WORDS = r"поставщик|продав[её]ц|исполнитель|получатель|грузоотправитель|подрядчик|арендодатель"
BUYER_WORDS = r"покупател|заказчик|плательщик|грузополучатель|арендатор"

NUMBER_RE = r"\d{1,3}(?:[   ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"
DATE_NUM_RE = r"(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})"
DATE_TEXT_RE = r"[«\"']?(\d{1,2})[»\"']?\s+([а-яё]+)[^\d\n]{0,3}?\s*(\d{4})"


@dataclass
class Party:
    name: str = ""
    inn: str = ""
    kpp: str = ""


@dataclass
class ExtractedData:
    doc_type: str = ""
    number: str = ""
    date: str = ""  # ISO yyyy-mm-dd
    seller: Party = field(default_factory=Party)
    buyer: Party = field(default_factory=Party)
    total: float | None = None
    vat: float | None = None
    vat_rate: str = ""  # "20%", "10%", "0%", "Без НДС"
    bik: str = ""
    account: str = ""  # расчётный счёт продавца
    corr_account: str = ""
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> ExtractedData:
        data = dict(data)
        seller = Party(**data.pop("seller", {}) or {})
        buyer = Party(**data.pop("buyer", {}) or {})
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(seller=seller, buyer=buyer, **known)


# Латинские буквы, которые OCR путает с кириллицей.
HOMOGLYPHS = str.maketrans("aceopxykmtbhABCEHKMOPTXY", "асеорхукмтьнАВСЕНКМОРТХУ")
_LATIN = re.compile(r"[A-Za-z]")
_CYRILLIC = re.compile(r"[А-Яа-яЁё]")


def _fix_homoglyphs(match: re.Match) -> str:
    word = match.group(0)
    mixed = _LATIN.search(word) and _CYRILLIC.search(word)
    all_lookalike = len(word) >= 3 and word.islower() and not word.translate(HOMOGLYPHS).isascii()
    lookalike_only = all(c in "aceopxykmtbh" for c in word)
    if mixed or (all_lookalike and lookalike_only):
        return word.translate(HOMOGLYPHS)
    return word


def normalize_text(text: str) -> str:
    """Убирает типичные артефакты OCR, мешающие регулярным выражениям."""
    text = text.replace("\r", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"[A-Za-zА-Яа-яЁё]+", _fix_homoglyphs, text)
    text = re.sub(r"\b(?:WHH|UHH|MHH|NHH|ИHH|ИНH|ИHН|MНН)\b", "ИНН", text)
    text = re.sub(r"(?<!\w)[0OО]{3}(?=\s*[«\"“'])", "ООО", text)
    text = re.sub(r"(?i)сч[её]тна(?=\s*оплату)", "Счет на", text)
    # «М» или «Ne»/«N°» вместо «№» — частая ошибка распознавания.
    text = re.sub(r"(?<=\s)(?:N[eо°o]|No|N)(?=\s*\d)", "№", text)
    return text


def parse_amount(raw: str) -> float | None:
    raw = re.sub(r"[   ]", "", raw).replace(",", ".")
    try:
        return round(float(raw), 2)
    except ValueError:
        return None


def parse_date(raw: str) -> str:
    raw = raw.strip().lower()
    m = re.search(DATE_NUM_RE, raw)
    if m:
        day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
    else:
        m = re.search(DATE_TEXT_RE, raw)
        if not m:
            return ""
        day, year = int(m.group(1)), int(m.group(3))
        word = m.group(2)
        # «март» стоит в словаре раньше «ма», поэтому «марта» не спутается с «мая».
        month = next((n for stem, n in MONTHS.items() if word.startswith(stem)), 0)
    if year < 100:
        year += 2000
    try:
        return date(year, month, day).isoformat()
    except ValueError:
        return ""


def inn_is_valid(inn: str) -> bool:
    if not inn.isdigit() or len(inn) not in (10, 12):
        return False
    digits = [int(c) for c in inn]

    def check(coeffs: list[int]) -> int:
        return sum(c * d for c, d in zip(coeffs, digits)) % 11 % 10

    if len(inn) == 10:
        return check([2, 4, 10, 3, 5, 9, 4, 6, 8]) == digits[9]
    return (check([7, 2, 4, 10, 3, 5, 9, 4, 6, 8]) == digits[10]
            and check([3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8]) == digits[11])


def detect_doc_type(text: str) -> str:
    for name, pattern in DOC_TYPES:
        if re.search(pattern, text, re.IGNORECASE):
            return name
    return ""


def find_number_and_date(text: str) -> tuple[str, str]:
    date_any = rf"(?:{DATE_NUM_RE}|{DATE_TEXT_RE})"
    title = (r"(?:сч[её]т(?:[\s-]*фактура)?(?:\s+на\s+оплату)?|накладная|акт[а-я]*|УПД|документ|"
             r"поручение|торг[\s-]*12)")
    pattern = rf"{title}[^\n№]{{0,40}}№\s*([A-Za-zА-Яа-я0-9][\w\-/.]*)\s*(?:от|от:|,)?\s*({date_any}[^\n]{{0,6}})?"
    for m in re.finditer(pattern, text, re.IGNORECASE):
        number = m.group(1).rstrip(".")
        if number.lower() in {"п", "пп", "п/п"}:
            continue  # «№ п/п» — заголовок колонки таблицы
        return number, parse_date(m.group(2) or "")
    # Номер не найден — ищем хотя бы дату документа.
    m = re.search(rf"\bот\s+({date_any})", text, re.IGNORECASE)
    return "", parse_date(m.group(1)) if m else ""


def _role_before(text: str, pos: int) -> str:
    """Определяет, к кому относится ИНН, по ближайшему слову-роли перед ним."""
    window = text[max(0, pos - 250):pos].lower()
    best_role, best_pos = "", -1
    for role, words in (("seller", SELLER_WORDS), ("buyer", BUYER_WORDS)):
        for m in re.finditer(words, window):
            if m.start() > best_pos:
                best_role, best_pos = role, m.start()
    return best_role


ORG_FORMS = r"(?:ООО|ОАО|ЗАО|ПАО|АО|НАО|ИП|ФГУП|МУП|ГУП|НКО|АНО|Общество\s+с\s+ограниченной\s+ответственностью|Индивидуальный\s+предприниматель)"
ORG_NAME_RE = re.compile(
    rf"{ORG_FORMS}\s*[«\"“']?[^,\n«»\"“”(]{{2,80}}?[»\"”']?(?=\s*(?:,|\n|\(|ИНН|$))",
    re.IGNORECASE,
)


def _name_near(text: str, start: int, end: int) -> str:
    window = text[start:end]
    m = ORG_NAME_RE.search(window)
    if not m:
        return ""
    name = m.group(0).strip(" ,.:;")
    name = re.sub(r"\s+", " ", name)
    name = name.replace("“", "«").replace("”", "»")
    name = re.sub(r'"([^"]+)"', r"«\1»", name)
    if "«" in name and "»" not in name:
        name += "»"
    return name


def _line_bounds(text: str, pos: int) -> tuple[int, int]:
    start = text.rfind("\n", 0, pos) + 1
    end = text.find("\n", pos)
    return start, len(text) if end == -1 else end


def _name_for_inn(text: str, start: int, end: int) -> str:
    """Наименование рядом с ИНН: в той же строке, затем строкой выше и ниже.

    Соседняя строка с ролью («Покупатель: …») относится к другой стороне — её пропускаем.
    """
    line_start, line_end = _line_bounds(text, start)
    candidates = [(line_start, start), (end, line_end)]
    neighbours = []
    if line_start > 0:
        neighbours.append(_line_bounds(text, line_start - 1))
    if line_end < len(text):
        neighbours.append(_line_bounds(text, line_end + 1))
    roles = re.compile(rf"{SELLER_WORDS}|{BUYER_WORDS}", re.IGNORECASE)
    candidates += [(a, b) for a, b in neighbours if not roles.search(text[a:b])]
    for a, b in candidates:
        name = _name_near(text, a, b)
        if name:
            return name
    return ""


def find_parties(text: str, result: ExtractedData) -> None:
    pattern = re.compile(
        r"ИНН\s*(?:/\s*КПП)?\s*(?:продавца|покупателя|получателя|плательщика|поставщика|исполнителя|заказчика)?"
        r"\s*[:№]?\s*(\d{12}|\d{10})(?!\d)(?:\s*(?:/|\\|,?\s*КПП\s*:?)\s*(\d{9})(?!\d))?",
        re.IGNORECASE)
    found: list[tuple[str, Party]] = []
    for m in pattern.finditer(text):
        inn, kpp = m.group(1), m.group(2) or ""
        name = _name_for_inn(text, m.start(), m.end())
        known = next((party for _, party in found if party.inn == inn), None)
        if known:
            # Повтор того же ИНН (в банковском блоке, шапке, подписях) — дополним пробелы.
            known.kpp = known.kpp or kpp
            known.name = known.name or name
            continue
        found.append((_role_before(text, m.end()), Party(name=name, inn=inn, kpp=kpp)))

    for role, party in found:
        if role == "seller" and not result.seller.inn:
            result.seller = party
        elif role == "buyer" and not result.buyer.inn:
            result.buyer = party
    # Роли не подписаны — по порядку: сначала продавец, затем покупатель.
    rest = [p for _, p in found if p.inn not in (result.seller.inn, result.buyer.inn)]
    if not result.seller.inn and rest:
        result.seller = rest.pop(0)
    if not result.buyer.inn and rest:
        result.buyer = rest.pop(0)

    # Наименование не рядом с ИНН (как в УПД) — берём из строки «Продавец: …» / «Покупатель: …».
    for party, words in ((result.seller, SELLER_WORDS), (result.buyer, BUYER_WORDS)):
        if party.name:
            continue
        for m in re.finditer(rf"(?:{words})\w*\s*:?\s*([^\n]+)", text, re.IGNORECASE):
            party.name = _name_near(m.group(1), 0, len(m.group(1)))
            if party.name:
                break


def find_amounts(text: str, result: ExtractedData) -> None:
    total_patterns = [
        rf"(?:всего|итого)\s+к\s+оплате\s*(?:\(?[а-я.\s]*\)?)?\s*:?\s*({NUMBER_RE})",
        rf"(?:всего|итого)\s+с\s+(?:уч[её]том\s+)?ндс\s*:?\s*({NUMBER_RE})",
        rf"сумма\s+к\s+оплате\s*:?\s*({NUMBER_RE})",
        rf"всего\s+(?:оказано\s+услуг|наименований)[^\n]*?на\s+сумму\s*({NUMBER_RE})",
        rf"(?:всего|итого)\s*(?:по\s+(?:документу|накладной|сч[её]ту))?\s*:?\s*({NUMBER_RE})(?![\d.,])(?!\s*%)",
    ]
    for pattern in total_patterns:
        values = [parse_amount(m.group(1)) for m in re.finditer(pattern, text, re.IGNORECASE)]
        values = [v for v in values if v]
        if values:
            result.total = max(values)
            break

    if re.search(r"без\s+(?:налога\s+)?\(?ндс\)?|ндс\s+не\s+облагается", text, re.IGNORECASE):
        result.vat_rate = "Без НДС"
        result.vat = 0.0
    else:
        rate = re.search(r"ндс\s*\(?\s*(\d{1,2})\s*%", text, re.IGNORECASE)
        if rate:
            result.vat_rate = f"{int(rate.group(1))}%"
        vat_values = [
            parse_amount(m.group(1))
            for m in re.finditer(
                rf"(?:(?:в\s+том\s+числе\s+|в\s+т\.\s*ч\.\s*|сумма\s+|итого\s+)?ндс|сумма\s+налога)"
                rf"\s*(?:\(?\s*\d{{1,2}}\s*%\s*\)?)?\s*:?\s*({NUMBER_RE})(?![\d.,])(?!\s*%)",
                text, re.IGNORECASE)
        ]
        vat_values = [v for v in vat_values if v and (result.total is None or v < result.total)]
        if vat_values:
            result.vat = max(vat_values)


def find_bank(text: str, result: ExtractedData) -> None:
    m = re.search(r"БИК\s*:?\s*(0\d{8})", text, re.IGNORECASE)
    if m:
        result.bik = m.group(1)
    m = re.search(r"(?:р/с|р\.\s*с\.|расч[её]тный\s+сч[её]т|сч\.\s*№)\s*:?\s*(?!301)(\d{20})", text, re.IGNORECASE)
    if not m:
        m = re.search(r"\b(40[5-8]\d{17})\b", text)
    if m:
        result.account = m.group(1)
    m = re.search(r"(?:к/с|к\.\s*с\.|корр?\.?\s*сч[её]т)\s*:?\s*(301\d{17})", text, re.IGNORECASE)
    if not m:
        m = re.search(r"\b(301\d{17})\b", text)
    if m:
        result.corr_account = m.group(1)


def extract(text: str) -> ExtractedData:
    text = normalize_text(text)
    result = ExtractedData()
    result.doc_type = detect_doc_type(text)
    result.number, result.date = find_number_and_date(text)
    find_parties(text, result)
    find_amounts(text, result)
    find_bank(text, result)
    result.warnings = validate(result)
    return result


def validate(result: ExtractedData) -> list[str]:
    """Что в реквизитах стоит проверить глазами — одинаково для разбора шаблонами и ИИ."""
    warnings = []
    for party in (result.seller, result.buyer):
        if party.inn and not inn_is_valid(party.inn):
            warnings.append(f"ИНН {party.inn} не прошёл проверку контрольной суммы — проверьте распознавание")
    if result.total and result.vat and result.vat_rate.endswith("%"):
        rate = int(result.vat_rate[:-1])
        expected = round(result.total * rate / (100 + rate), 2)
        if abs(expected - result.vat) > 1:
            warnings.append(
                f"НДС {result.vat:.2f} не сходится со ставкой {result.vat_rate} от суммы {result.total:.2f}")
    if not result.doc_type:
        warnings.append("Не удалось определить тип документа")
    if not result.number:
        warnings.append("Не найден номер документа")
    if not result.date:
        warnings.append("Не найдена дата документа")
    if not result.seller.inn:
        warnings.append("Не найден ИНН контрагента")
    if result.total is None:
        warnings.append("Не найдена сумма документа")
    return warnings
