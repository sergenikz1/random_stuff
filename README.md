# FlowVoice

Аналог Wispr Flow: голосовой ввод, который печатает надиктованный текст туда, где стоит курсор.
Понимает русский и английский, в режиме «Авто» — оба языка в одной фразе.

| Платформа | Что это |
|---|---|
| **Windows** | Приложение в трее: `Ctrl + Shift + Space` → говорите → текст вставляется в активное окно |
| **Android** | Приложение и голосовая клавиатура: кнопка микрофона вставляет текст в любое поле ввода |

Распознавание — через любой сервис с OpenAI-совместимым API (OpenAI Whisper и другие пресеты),
на Android есть бесплатный офлайн-движок «На устройстве».

## Скачать

Установщик и portable-версия для Windows и APK для Android лежат в предрелизе
[`flowvoice-latest`](../../releases/tag/flowvoice-latest); каждая сборка обновляет его.
Сборки не подписаны, поэтому Windows SmartScreen и Android покажут предупреждение при установке.

## Устройство ветки

| Путь | Что там |
|---|---|
| `flowvoice/desktop/` | Приложение для Windows |
| `flowvoice/android/` | Приложение и клавиатура для Android |
| `flowvoice/README.md` | [Подробное описание](flowvoice/README.md): настройки, движки распознавания, сборка |
| `.github/workflows/flowvoice.yml` | Сборка exe и APK на каждый push в `flowvoice/` и публикация в `flowvoice-latest` |
| `index.html`, `server.js`, `Dockerfile` | Остались от [Vibrate](../../tree/vibrate-tg), к FlowVoice не относятся |

Это ветка продукта из репозитория [random_stuff](../../tree/main), где каждый продукт живёт в своей ветке.
