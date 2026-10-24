import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from PIL import Image
from samples import INVOICE

from scan_to_1c import ai
from scan_to_1c.ai import AIClient, AIConfig, AIError
from scan_to_1c.extract import extract

ANSWER = {
    "doc_type": "Счет на оплату", "number": "154", "date": "2025-03-12",
    "seller": {"name": "ООО «Ромашка»", "inn": "7701234560", "kpp": "770101001"},
    "buyer": {"name": "АО «Василёк»", "inn": "5001007329", "kpp": "500101001"},
    "total": 4500, "vat": "750,00", "vat_rate": "20", "bik": "044525225",
    "account": "40702810938000012345", "corr_account": "30101810400000000225",
}


class FakeOmniRoute(BaseHTTPRequestHandler):
    """Минимальный OpenAI-совместимый сервер: отвечает как OmniRoute и запоминает запросы."""

    requests: list = []
    reply = json.dumps(ANSWER, ensure_ascii=False)
    status = 200

    def do_GET(self):
        self._send(200, {"object": "list", "data": [{"id": "auto"}, {"id": "openai/gpt-5.4"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeOmniRoute.requests.append({"path": self.path, "auth": self.headers.get("Authorization"), "body": body})
        if FakeOmniRoute.status != 200:
            self._send(FakeOmniRoute.status, {"error": {"message": "no route for model"}})
            return
        content = "Вот реквизиты:\n```json\n" + FakeOmniRoute.reply + "\n```"
        self._send(200, {"choices": [{"message": {"role": "assistant", "content": content}}]})

    def _send(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


@pytest.fixture
def omniroute():
    FakeOmniRoute.requests = []
    FakeOmniRoute.reply = json.dumps(ANSWER, ensure_ascii=False)
    FakeOmniRoute.status = 200
    server = HTTPServer(("127.0.0.1", 0), FakeOmniRoute)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()


def test_extract_over_http_with_images(omniroute):
    client = AIClient(AIConfig(base_url=omniroute, api_key="sk-test", model="auto"))
    page = Image.new("L", (2480, 3508), 255)
    data = client.extract("Счет на оплату № 154", [page])

    sent = FakeOmniRoute.requests[0]
    assert sent["path"] == "/v1/chat/completions"
    assert sent["auth"] == "Bearer sk-test"
    assert sent["body"]["model"] == "auto/best-vision"  # с картинками «auto» уходит в маршрут vision
    user = sent["body"]["messages"][1]["content"]
    assert user[0]["type"] == "text" and "Счет на оплату № 154" in user[0]["text"]
    assert user[1]["image_url"]["url"].startswith("data:image/jpeg;base64,")

    assert (data.doc_type, data.number, data.date) == ("Счет на оплату", "154", "2025-03-12")
    assert (data.seller.inn, data.seller.kpp, data.seller.name) == ("7701234560", "770101001", "ООО «Ромашка»")
    assert (data.total, data.vat, data.vat_rate) == (4500.0, 750.0, "20%")
    assert data.account == "40702810938000012345"


def test_text_only_when_images_disabled(omniroute):
    client = AIClient(AIConfig(base_url=omniroute + "/v1/", send_images=False))
    client.extract("текст", [Image.new("L", (100, 100), 255)])
    sent = FakeOmniRoute.requests[0]
    assert sent["auth"] is None
    assert sent["body"]["model"] == "auto"
    assert isinstance(sent["body"]["messages"][1]["content"], str)


def test_check_lists_models(omniroute):
    assert "моделей доступно: 2" in AIClient(AIConfig(base_url=omniroute)).check()


def test_route_error_is_readable(omniroute):
    FakeOmniRoute.status = 503
    with pytest.raises(AIError, match="503: no route for model — проверьте, что в панели OmniRoute"):
        AIClient(AIConfig(base_url=omniroute)).extract("текст")


def test_wrong_key(omniroute):
    FakeOmniRoute.status = 401
    with pytest.raises(AIError, match="отклонил ключ"):
        AIClient(AIConfig(base_url=omniroute)).extract("текст")


def test_not_running():
    with pytest.raises(AIError, match="не отвечает"):
        AIClient(AIConfig(base_url="http://127.0.0.1:9", timeout=3)).extract("текст")


def test_garbage_answer_falls_back_to_rules(omniroute):
    FakeOmniRoute.reply = "извините, не могу"
    rules = extract(INVOICE)
    data, note = ai.recognize(AIClient(AIConfig(base_url=omniroute)), INVOICE, [], rules)
    assert note == ""
    assert data.number == "154"
    assert data.warnings[0].startswith("ИИ не сработал")


def test_recognize_merges_and_reports_model(omniroute):
    rules = extract(INVOICE)
    data, note = ai.recognize(AIClient(AIConfig(base_url=omniroute, model="openai/gpt-5.4")), INVOICE, [], rules)
    assert note == "Распознано ИИ (openai/gpt-5.4)"
    assert data.warnings == []
    assert data.buyer.name == "АО «Василёк»"


def test_merge_keeps_valid_inn_from_rules():
    rules = extract(INVOICE)
    broken = dict(ANSWER, seller={"name": "ООО «Ромашка»", "inn": "7701234561", "kpp": ""}, number="",
                  total=None)
    data = ai.merge(ai.to_extracted(broken), rules)
    assert data.seller.inn == "7701234560"  # у ИИ ошибка в цифре, у шаблонов ИНН проходит проверку
    assert data.seller.kpp == "770101001"
    assert data.number == "154"  # ИИ не нашёл — берём из шаблонов
    assert data.total == 4500.0


def test_to_extracted_normalizes_sloppy_answer():
    data = ai.to_extracted({"doc_type": "упд", "date": "05.02.2025", "seller": {"inn": "7707 083 893"},
                            "total": "118 800,00", "vat_rate": "без ндс", "account": "123"})
    assert data.doc_type == "УПД"
    assert data.date == "2025-02-05"
    assert data.seller.inn == "7707083893"
    assert data.total == 118800.0
    assert data.vat_rate == "Без НДС"
    assert data.account == ""  # не 20 цифр — не принимаем


def test_parse_json_errors():
    with pytest.raises(AIError, match="не JSON"):
        ai.parse_json("нет данных")
    with pytest.raises(AIError, match="битый JSON"):
        ai.parse_json("{number: 1,}")


def test_stream_false_is_requested(omniroute):
    AIClient(AIConfig(base_url=omniroute)).extract("текст")
    assert FakeOmniRoute.requests[0]["body"]["stream"] is False


def test_sse_answer_is_collected():
    # Так отвечал настоящий OmniRoute 3.8 на запрос без "stream": кусками text/event-stream.
    answer = json.dumps(ANSWER, ensure_ascii=False)
    half = len(answer) // 2
    raw = "\n\n".join([
        'data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}',
        "data: " + json.dumps({"choices": [{"delta": {"content": answer[:half]}}]}, ensure_ascii=False),
        "data: " + json.dumps({"choices": [{"delta": {"content": answer[half:]}}]}, ensure_ascii=False),
        "data: [DONE]"])
    content = ai._collect_stream(raw)["choices"][0]["message"]["content"]
    assert ai.to_extracted(ai.parse_json(content)).number == "154"


def test_empty_sse_answer_is_an_error():
    class Response:
        status_code = 200
        headers = {"Content-Type": "text/event-stream"}
        text = 'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\ndata: [DONE]\n'

    class Session:
        def request(self, *args, **kwargs):
            return Response()

    with pytest.raises(AIError, match="пустой ответ"):
        AIClient(AIConfig(base_url="http://omniroute"), session=Session()).extract("текст")
