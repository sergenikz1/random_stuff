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
    ("Счет-фактура", r"сч[её]т\w{0,3}[\s-]*фактур"),
    ("Товарная накладная", r"товарн\w*\s+накладн|ТОРГ[\s-]*12"),
    ("Акт", r"\bакт\b[^\n]{0,60}(?:выполненн|оказанн|сдачи|при[её]мк)|\bакт\s*№"),
    ("Платежное поручение", r"платежн\w*\s+поручени"),
    ("Счет на оплату", r"сч[её]т\s+на\s+оплату|\bсч[её]т\s*№"),
]

# Корни с учётом частых ошибок OCR: «Поставшик», «Плателышик».
SELLER_WORDS = r"постав[шщ]ик|продав[её]ц|исполнитель|получатель|грузоотправитель|подрядчик|арендодатель"
BUYER_WORDS = r"покупател|заказчик|плател\w{0,3}[шщ]ик|грузополучатель|арендатор"

NUMBER_RE = r"\d{1,3}(?:[   ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"
# Денежная сумма: всегда с копейками. Разделители тысяч — пробел, точка или запятая («87.340,00», «21,835.00»),
# поэтому количество вроде «4,000» и масса «2.120,000» за сумму не принимаются.
MONEY_RE = r"(?<![\d.,])(?:\d{1,3}(?:[ \u00a0\u202f.,]\d{3})+|\d+)[.,]\d{2}(?![\d])"
GTD_RE = r"\b\d{8}/\d{6}/\d{7}(?:/\d{1,3})?\b"
DATE_NUM_RE = r"(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})"
DATE_TEXT_RE = r"[«\"']?(\d{1,2})[»\"']?\s+([а-яё]+)[^\d\n]{0,3}?\s*(\d{4})"


@dataclass
class Party:
    name: str = ""
    inn: str = ""
    kpp: str = ""


@dataclass
class Item:
    """Строка товаров (работ, услуг)."""

    name: str = ""
    code: str = ""  # артикул или код товара
    unit: str = ""
    quantity: float | None = None
    price: float | None = None
    amount: float | None = None  # стоимость без НДС
    vat_rate: str = ""
    vat: float | None = None
    total: float | None = None  # стоимость с НДС
    country: str = ""
    gtd: str = ""  # регистрационный номер декларации на товары


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
    basis: str = ""  # основание: договор, заказ
    shipment_doc: str = ""  # документ об отгрузке, например «№ 1482791257 от 22.10.2026»
    shipper: Party = field(default_factory=Party)  # грузоотправитель
    consignee: Party = field(default_factory=Party)  # грузополучатель
    items: list[Item] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> ExtractedData:
        data = dict(data)
        parties = {role: Party(**(data.pop(role, None) or {})) for role in ("seller", "buyer", "shipper", "consignee")}
        items = [Item(**{k: v for k, v in item.items() if k in Item.__dataclass_fields__})
                 for item in data.pop("items", None) or []]
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
        return cls(items=items, **parties, **known)


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
    text = re.sub(r"\b(\d{1,2})\.\s?(\d{1,2})\.\s(\d{4})\b", r"\1.\2.\3", text)  # «17.10. 2026»
    text = re.sub(r"\$(?=\d)", "8", text)  # «$7340,00» — так OCR читает восьмёрку
    text = re.sub(r"\b[HН][AА][CС]\b", "НДС", text)  # «Без HAC»
    # «М» или «Ne»/«N°» вместо «№» — частая ошибка распознавания.
    text = re.sub(r"(?<=\s)(?:N[eо°o]|No|N)(?=\s*\d)", "№", text)
    return text


def parse_amount(raw: str) -> float | None:
    """«4 500,00», «87.340,00», «21,835.00», «118800» → число. Последний разделитель — дробная часть."""
    raw = re.sub(r"[ \u00a0\u202f]", "", raw.strip())
    match = re.fullmatch(r"(\d[\d.,]*?)(?:[.,](\d{1,2}))?", raw)
    if not match:
        return None
    whole = re.sub(r"[.,]", "", match.group(1))
    return round(float(f"{whole}.{match.group(2) or 0}"), 2)


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


DATE_ANY = rf"(?:{DATE_NUM_RE}|{DATE_TEXT_RE})"
# Строки, даты и номера из которых к самому документу не относятся.
FOREIGN_LINE = re.compile(r"о[сc][нв]ование|утвержден|постановлени|госкомстат|росси\w*\s+от|доверенност|годен\s+до|"
                          r"документ\w*\s+об\s+отгрузке|дата\s+и\s+время\s+печати|исправлени", re.IGNORECASE)


def _own_text(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not FOREIGN_LINE.search(line))


def find_number_and_date(text: str) -> tuple[str, str]:
    full_text, text = text, _own_text(text)
    date_any = DATE_ANY
    title = (r"(?:сч[её]т(?:[\s-]*фактура)?(?:\s+на\s+оплату)?|накладная|акт[а-я]*|УПД|документ|"
             r"поручение|торг[\s-]*12)")
    pattern = rf"{title}[^\n№]{{0,40}}№\s*([A-Za-zА-Яа-я0-9][\w\-/.]*)\s*(?:от|от:|,)?\s*({date_any}[^\n]{{0,6}})?"
    for m in re.finditer(pattern, text, re.IGNORECASE):
        number = m.group(1).rstrip(".")
        if number.lower() in {"п", "пп", "п/п"}:
            continue  # «№ п/п» — заголовок колонки таблицы
        return number, parse_date(m.group(2) or "") or _main_date(text)
    # ТОРГ-12: номер и дата стоят в рамке под заголовками «Номер документа» и «Дата составления».
    # OCR читает рамку по-разному: «Номер документа | Дата составления» или «Номер Дата / документа составления».
    # Номер и дата стоят рядом, в одной строке, сразу под подписями — дальше идут рамки других документов
    # (номер транспортной накладной), их не берём.
    m = re.search(rf"составлени\w*[^\d]{{0,40}}?(\d{{4,}})[ \t]+({DATE_NUM_RE})", text, re.IGNORECASE)
    if m:
        return m.group(1), parse_date(m.group(2))
    # Колонтитул учётной системы: «НАКЛАДНАЯ # 1482791257 (Дата и время печати …)».
    m = re.search(r"накладная\s*[#№]\s*(\d{3,})", full_text, re.IGNORECASE)
    return (m.group(1) if m else ""), _main_date(text)


def _main_date(text: str) -> str:
    """Дата документа без явной подписи: «Дата составления …», иначе самая частая дата в документе."""
    m = re.search(rf"составлени\w*[^\d]{{0,40}}?(?:\d{{4,}}[ \t]+)?({DATE_NUM_RE})", text, re.IGNORECASE)
    if m:
        return parse_date(m.group(1))
    # Даты из будущего — это не дата документа: «25.12.98» из реквизитов формы читается как 2098 год.
    latest = date.today().replace(year=date.today().year + 1).isoformat()
    dates = [parse_date(m.group(0)) for m in re.finditer(DATE_NUM_RE, text)]
    dates = [d for d in dates if d and d <= latest]
    if not dates:
        m = re.search(rf"\bот\s+({DATE_ANY})", text, re.IGNORECASE)
        return parse_date(m.group(1)) if m else ""
    return max(dates, key=lambda d: (dates.count(d), -dates.index(d)))


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
# Форма собственности — отдельным словом: иначе «ИП» находится внутри «муниципальный».
# Название в кавычках заканчивается закрывающей кавычкой (после неё бывает мусор OCR),
# без кавычек — запятой, концом строки, скобкой или «ИНН».
ORG_NAME_RE = re.compile(
    rf"(?<![А-Яа-яЁёA-Za-z]){ORG_FORMS}(?![А-Яа-яЁёA-Za-z])\s*"
    rf"(?:[«\"“'][^,\n«»\"“”(]{{2,80}}?[»\"”']|[^,\n«»\"“”(]{{2,80}}?(?=\s*[.;]?\s*(?:,|\n|\(|ИНН|$)))",
    re.IGNORECASE,
)


def _name_near(text: str, start: int, end: int) -> str:
    window = text[start:end]
    m = ORG_NAME_RE.search(window)
    if not m:
        return ""
    name = m.group(0).strip(" ,.:;")
    name = re.sub(r"\s+", " ", name)
    # Мусор OCR вместо названия: цифры (реквизиты банка) или нет ни одного нормального слова.
    if len(re.findall(r"\d", name)) > 2 or not re.search(r"[А-Яа-яЁёA-Za-z]{3,}", name[3:]):
        return ""
    # Кавычки любые («ООО "СМ-Альянс”») — приводим к ёлочкам: ООО «СМ-Альянс».
    form = re.match(ORG_FORMS, name, re.IGNORECASE)
    body = name[form.end():].strip() if form else name
    if re.search(r"[«»\"“”']", body):
        body = "«" + re.sub(r"[«»\"“”']", "", body).strip() + "»"
    return f"{form.group(0)} {body}".strip() if form else body


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
    above, taken = line_start, 0
    while above > 0 and taken < 4:  # в УПД название стоит на несколько строк выше ИНН, через адрес
        above, _end = _line_bounds(text, above - 1)
        if not text[above:_end].strip():
            continue
        taken += 1
        neighbours.append((above, _end))
        if taken == 1 and line_end < len(text):
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
    matches = list(pattern.finditer(text))
    labeled = {m.group(1) for m in matches}
    # ИНН/КПП без подписи «ИНН» (в УПД OCR часто теряет подписи): «7703655356 / 770301001».
    matches += [m for m in re.finditer(r"(?<!\d)(\d{10})\s*/\s*(\d{9})(?!\d)", text)
                if m.group(1) not in labeled and inn_is_valid(m.group(1))]
    matches.sort(key=lambda m: m.start())
    found: list[tuple[str, Party]] = []
    for m in matches:
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


TOTAL_KEYWORDS = [
    r"к\s+оплате",
    r"всего\s+по\s+(?:накладной|документу|сч[её]ту)|всего\s+отпущено|с\s+(?:уч[её]том\s+)?ндс|на\s+сумму",
    r"итого|всего",
]


def _money(text: str) -> list[float]:
    values = [parse_amount(m.group(0)) for m in re.finditer(MONEY_RE, text)]
    return [v for v in values if v]


def find_amounts(text: str, result: ExtractedData) -> None:
    lines = text.splitlines()
    for keywords in TOTAL_KEYWORDS:
        values = []
        for index, line in enumerate(lines):
            m = re.search(keywords, line, re.IGNORECASE)
            if m:
                # Сумма бывает на той же строке после подписи или перенесена на следующую.
                values += _money(line[m.end():]) or _money(" ".join(lines[index + 1:index + 2]))
        if values:
            result.total = max(values)
            break
    if result.total is None:
        # Подпись «Итого» не распозналась — итог обычно повторяется в документе несколько раз.
        values = _money(text)
        repeated = [v for v in set(values) if values.count(v) >= 2]
        if repeated:
            result.total = max(repeated)

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
                rf"\s*(?:\(?\s*\d{{1,2}}\s*%\s*\)?)?\s*:?\s*({MONEY_RE})",
                text, re.IGNORECASE)
        ]
        vat_values = [v for v in vat_values if v and (result.total is None or v < result.total)]
        if vat_values:
            result.vat = max(vat_values)


QTY_RE = r"\d+(?:[.,]\d{1,3})?"
# Строка счёта-фактуры / УПД: [№] код [код ОКЕИ] ед. кол-во цена стоимость … (наименование — строками ниже).
INVOICE_LINE_RE = re.compile(
    rf"^\s*(?:[^\w\s]{{1,2}}\s+|[а-яa-z]\s+)?(?:(\d{{1,3}})\s+)?([\w\-/.]{{3,}})\s+(?:\d{{3,4}}\s+)?"
    rf"([А-Яа-яЁё.]{{1,8}})\s+({QTY_RE})\s+({MONEY_RE})\s+({MONEY_RE})", re.IGNORECASE)
# Строка счёта на оплату или акта: № наименование кол-во ед. цена сумма.
SIMPLE_LINE_RE = re.compile(
    rf"^\s*(\d{{1,3}})\s+(.+?)\s+({QTY_RE})\s+([А-Яа-яЁё.]{{1,8}})\s+({MONEY_RE})\s+({MONEY_RE})\s*$")
NAME_NOISE = re.compile(r"\b(?:акциза|без|ндс)\b", re.IGNORECASE)
PACKAGING = re.compile(r"\d\s*\w{1,5}\s*=\s*\d|годен\s+до|^\s*\(", re.IGNORECASE)


def _qty(raw: str) -> float | None:
    try:
        return float(raw.replace(",", "."))
    except ValueError:
        return None


def find_items(text: str, result: ExtractedData) -> None:
    """Строки товаров, если таблица прочиталась построчно (счёт-фактура, УПД, счёт на оплату, акт).

    ТОРГ-12 OCR обычно разбивает по колонкам — её строки извлекает ИИ.
    """
    lines = text.splitlines()
    items: list[Item] = []
    for index, line in enumerate(lines):
        m = INVOICE_LINE_RE.match(line)
        if m:
            moneys = [parse_amount(x.group(0)) for x in re.finditer(MONEY_RE, line)]
            rate = "Без НДС" if re.search(r"без\s+ндс", line, re.IGNORECASE) else ""
            pct = re.search(r"\b(\d{1,2})\s*%", line)
            if pct:
                rate = f"{int(pct.group(1))}%"
            country = re.search(r"\b\d{3}\s+([А-ЯЁ][А-ЯЁ -]{2,})", line)
            gtd = re.search(GTD_RE, line)
            # Наименование — следующие строки до упаковки («1 кор = 10 ШТ», «Годен до») или новой строки таблицы.
            name_parts = []
            for follow in lines[index + 1:index + 5]:
                if not follow.strip() or INVOICE_LINE_RE.match(follow) or PACKAGING.search(follow) \
                        or re.match(r"\s*(всего|итого)", follow, re.IGNORECASE):
                    break
                name_parts.append(NAME_NOISE.sub("", follow).strip(" ,"))
            items.append(Item(
                name=re.sub(r"\s+", " ", " ".join(name_parts)).strip() or m.group(2), code=m.group(2),
                unit=m.group(3).strip("."), quantity=_qty(m.group(4)), price=parse_amount(m.group(5)),
                amount=parse_amount(m.group(6)), vat_rate=rate, vat=0.0 if rate == "Без НДС" else None,
                total=moneys[-1] if len(moneys) > 2 else parse_amount(m.group(6)),
                country=country.group(1).strip().capitalize() if country else "",
                gtd=gtd.group(0) if gtd else ""))
            continue
        m = SIMPLE_LINE_RE.match(line)
        if m and not re.match(r"\s*\d", m.group(2)):
            items.append(Item(name=m.group(2).strip(), unit=m.group(4).strip("."), quantity=_qty(m.group(3)),
                              price=parse_amount(m.group(5)), amount=parse_amount(m.group(6)),
                              total=parse_amount(m.group(6))))
    totals = [i.total for i in items]
    if not items or not all(totals):
        return
    line_sum = round(sum(totals), 2)
    if result.total is None or abs(result.total - line_sum) <= 1:
        result.items = items
        result.total = line_sum if result.total is None else result.total
    elif len(items) > 1 and result.total in totals:
        # Итог прочитался неверно — вместо него взялась сумма одной строки; верный итог — сумма строк.
        result.items, result.total = items, line_sum
    # Иначе строки собраны из таблицы, которую OCR перемешал (как в ТОРГ-12), — им не доверяем.


def find_extras(text: str, result: ExtractedData) -> None:
    """Основание, документ об отгрузке, грузоотправитель и грузополучатель."""
    m = re.search(r"\bо[сc][нв]ование\b(?:\s*(?:передачи|сдачи|получения)[^:\n]*)?\s*:?\s*([^\n]{3,160})",
                  text, re.IGNORECASE)
    if m:
        basis = re.sub(r"\s*\(.*?(?:наличии|договор|доверенн).*?\)\s*", " ", m.group(1))
        result.basis = re.sub(r"\s+", " ", basis).strip(" ,.;:|_—-")
    # «Документ об отгрузке № п/п 1 № 1482791257 от 22.10.2026»: нужен номер, за которым идёт «от дата».
    m = (re.search(rf"документ\w*\s+об\s+отгрузке[^\n]*?№\s*([\w\-/]+)\s*от\s*({DATE_NUM_RE})", text, re.IGNORECASE)
         or re.search(r"документ\w*\s+об\s+отгрузке[^\n№]*№\s*(?:п/п\s*)?(\d[\w\-/]*)()", text, re.IGNORECASE))
    if m:
        date_text = parse_date(m.group(2) or "")
        result.shipment_doc = f"№ {m.group(1)}" + (f" от {date_text[8:10]}.{date_text[5:7]}.{date_text[:4]}"
                                                     if date_text else "")
    for role, word in (("shipper", r"грузоотправител"), ("consignee", r"грузополучател")):
        for m in re.finditer(rf"{word}\w*\s*(?:и\s+его\s+адрес)?\s*:?\s*([^\n]+)", text, re.IGNORECASE):
            line = m.group(1)
            name = _name_near(line, 0, len(line))
            inn = re.search(r"ИНН\s*:?\s*(\d{12}|\d{10})(?!\d)", line, re.IGNORECASE)
            if name or inn:
                setattr(result, role, Party(name=name, inn=inn.group(1) if inn else ""))
                break

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
    find_extras(text, result)
    find_items(text, result)
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
    line_totals = [i.total if i.total is not None else i.amount for i in result.items]
    if result.total and line_totals and all(v is not None for v in line_totals) \
            and abs(sum(line_totals) - result.total) > 1:
        warnings.append(f"Сумма строк товаров {sum(line_totals):.2f} не сходится с итогом {result.total:.2f}")
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


# Типы, которые бывают одним документом на нескольких листах с разными заголовками.
_DOC_FAMILY = {"УПД": "счет-фактура", "Счет-фактура": "счет-фактура"}


def split_documents(pages: list[str]) -> list[list[int]]:
    """Делит страницы файла на документы: например, PDF «ТОРГ-12 на двух листах + счёт-фактура» → [[0, 1], [2]].

    Новый документ начинается со страницы другого типа; страница без явного типа (продолжение таблицы,
    подписи) относится к предыдущему документу.
    """
    groups: list[list[int]] = []
    family = ""
    for index, page in enumerate(pages):
        doc_type = detect_doc_type(normalize_text(page))
        page_family = _DOC_FAMILY.get(doc_type, doc_type)
        if not groups or (page_family and family and page_family != family):
            groups.append([index])
            family = page_family
        else:
            groups[-1].append(index)
            family = family or page_family
    return groups
