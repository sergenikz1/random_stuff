# Сканы → 1С

Windows-программа `Scan2-1C.exe`: принимает сканы первичных документов, распознаёт текст,
извлекает реквизиты и загружает документы в 1С:Предприятие 8.3.

- Форматы: PDF, JPG, PNG, TIFF, BMP, GIF, WEBP, HEIC. OCR Tesseract с русским языком встроен в exe.
- Документы: счёт на оплату, УПД, счёт-фактура, ТОРГ-12, акт, платёжное поручение.
- Реквизиты: номер, дата, продавец и покупатель (название, ИНН, КПП), сумма, НДС, банковские реквизиты.
- Загрузка в 1С через OData (контрагент ищется по ИНН или создаётся, документ создаётся непроведённым)
  или в папку обмена с обработкой `scan2onec/1c/ЗагрузкаСканов.bsl`.

## Скачать

Последняя версия — в [релизах](../../releases?q=scan2onec).

## Устройство ветки

| Путь | Что там |
|---|---|
| `scan2onec/` | Программа на Python. Подробное описание, настройка 1С и шаблоны выгрузки — [scan2onec/README.md](scan2onec/README.md) |
| `.github/workflows/scan2onec.yml` | Тесты, сборка exe на Windows и публикация релиза `scan2onec-v<версия>` |
| `android/`, `android-reader/` и их workflow | Старые копии [Limit Pulse](../../tree/limit-pulse) и [Читалки вслух](../../tree/chitalka-vsluh), к программе не относятся; актуальный код — в их ветках |
| `index.html`, `server.js`, `Dockerfile` | Остались от [Vibrate](../../tree/vibrate-tg), к программе не относятся |

## Сборка и тесты

```sh
cd scan2onec
pip install -r requirements.txt pytest
python -m pytest tests   # для тестов OCR нужен Tesseract с языком rus
python main.py           # запустить окно
```

Exe собирает GitHub Actions: при push в эту ветку публикуется релиз с версией из `scan2onec/scan_to_1c/__init__.py`.

Это ветка продукта из репозитория [random_stuff](../../tree/main), где каждый продукт живёт в своей ветке.
