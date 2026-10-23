# Читалка вслух

Android-читалка электронных книг, которая читает текст вслух и подсвечивает озвучиваемое предложение.
Работает полностью офлайн: нейросетевые голоса (Ирина, Денис, Руслан) встроены в приложение.

- Форматы: EPUB, FB2, FB2.ZIP, PDF с текстом, TXT, HTML, Markdown.
- Быстрое чтение по одному слову, режим «только текст», работа при выключенном экране.
- Награды за чтение: медали за чтение без перерыва, ежедневный росток, литературные звания.
- Интерфейс на русском и английском, Android 8.0+.

## Скачать

Последняя версия — в [релизах](../../releases?q=chitalka): `arm64-v8a` для современных телефонов,
`armeabi-v7a` для старых 32-битных.

## Устройство ветки

| Путь | Что там |
|---|---|
| `android-reader/` | Приложение (Kotlin, Gradle). Подробное описание — [android-reader/README.md](android-reader/README.md) |
| `.github/workflows/android-reader.yml` | Сборка APK на каждый push в `android-reader/` |
| `.github/workflows/chitalka-release.yml` | Релиз: на push в эту ветку или тег `chitalka-v*` публикует APK в Releases |
| `index.html`, `server.js`, `Dockerfile` | Остались от [Vibrate](../../tree/vibrate-tg), к читалке не относятся |

## Сборка

```sh
cd android-reader
./gradlew :app:assembleDebug
```

Модели голосов скачиваются при сборке задачей `fetchTtsAssets`.

Это ветка продукта из репозитория [random_stuff](../../tree/main), где каждый продукт живёт в своей ветке.
