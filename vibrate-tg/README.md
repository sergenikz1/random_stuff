# Vibrate

Telegram Mini App: пока приложение открыто, телефон коротко вибрирует раз в секунду,
а на экране пульсирует индикатор. Кнопка «Стоп/Старт» ставит на паузу.
Экран не гаснет, пока приложение открыто (Screen Wake Lock).

- В Telegram вибрация идёт через `Telegram.WebApp.HapticFeedback`, в обычном браузере — через Vibration API.
- Вся логика — одна страница `index.html` без зависимостей.

## Файлы

| Путь | Что там |
|---|---|
| `index.html` | Само мини-приложение: вёрстка, стили и скрипт |
| `server.js` | Node.js-сервер без зависимостей, отдаёт страницу на порту 3000 |
| `Dockerfile` | Образ `node:22-alpine` с сервером |

## Запуск

```sh
cd vibrate-tg
node server.js                 # http://localhost:3000
# или в Docker
docker build -t vibrate . && docker run -p 3000:3000 vibrate
```

Чтобы открыть приложение в Telegram, опубликуйте страницу по HTTPS и укажите её адрес
у своего бота в [@BotFather](https://t.me/BotFather) (Bot Settings → Menu Button или Configure Mini App).
