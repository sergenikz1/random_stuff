# Limit Pulse

Android-приложение и виджеты главного экрана, которые показывают остаток лимитов подписок
**Codex (ChatGPT)** и **Claude Pro/Max**: проценты для 5-часового и недельного окна, отсчёт до сброса
и напоминание за час до сброса. Без рекламы, аналитики и своего сервера, Android 8.0+.

- Вход через браузер (OAuth + PKCE) в OpenAI и в Claude, токены шифруются ключом из Android Keystore.
- Отдельные виджеты «Limit Pulse» (Codex) и «Limit Pulse · Claude».
- Неофициальный клиент: форматы ответов OpenAI и Anthropic могут измениться.

## Скачать

Последняя опубликованная версия — в [релизах](../../releases?q=limit-pulse).
В коде ветки уже есть следующая версия с разделом Claude (см. [android/RELEASE_NOTES.md](android/RELEASE_NOTES.md)),
её сборка лежит в артефактах запуска workflow «Android Limit Pulse».

## Устройство ветки

| Путь | Что там |
|---|---|
| `android/` | Приложение (Java, Gradle) и тесты. Подробное описание, приватность и сборка — [android/README.md](android/README.md) |
| `android/release-signing-certificate.pem` | Сертификат подписи релизных APK — по нему проверяется, что APK подлинный |
| `.github/workflows/android.yml` | Тесты и сборка APK на каждый push в `android/` |
| `index.html`, `server.js`, `Dockerfile` | Остались от [Vibrate](../../tree/vibrate-tg), к приложению не относятся |

## Сборка

Нужны JDK 17+ и Android SDK 35:

```sh
cd android
./gradlew test assembleRelease
```

Это ветка продукта из репозитория [random_stuff](../../tree/main), где каждый продукт живёт в своей ветке.
