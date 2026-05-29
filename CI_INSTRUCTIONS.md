# Luna VPN — автосборка APK через GitHub Actions

## Что настроено

✅ **Полностью автоматическая сборка APK** при каждом push в `master`  
✅ **Скачивание libv2ray.aar** из AndroidLibXrayLite (последний релиз)  
✅ **Сборка libhevtun** (tun2socks) через NDK с кешированием  
✅ **Загрузка APK** как артефакт (доступен 30 дней)  
✅ **Автоматический релиз** при создании git-тега  

## Как получить APK

### Вариант 1: Скачать из Actions (после каждого push)

1. Открой https://github.com/rakiyatraketa-arch/luna-vpn/actions
2. Кликни на последний успешный запуск (зелёная галочка).
3. Внизу страницы — **Artifacts** → `luna-vpn-release` → скачай ZIP.
4. Распакуй → внутри `app-release.apk`.

### Вариант 2: Создать релиз (для постоянной ссылки)

```bash
cd C:\Users\slayer\Downloads\vpss\v2rayNG

# Создай тег (например, v1.0.0):
git tag v1.0.0
git push luna v1.0.0
```

GitHub Actions автоматически:
- Соберёт APK.
- Создаст релиз на https://github.com/rakiyatraketa-arch/luna-vpn/releases
- Прикрепит APK к релизу (постоянная ссылка).

## Как изменить код и пересобрать

```bash
cd C:\Users\slayer\Downloads\vpss\v2rayNG

# Внеси изменения (например, в LunaActivity.kt или index.html)

git add -A
git commit -m "Описание изменений"
git push luna master
```

GitHub Actions автоматически соберёт новый APK (проверь через несколько минут на странице Actions).

## Добавить свои серверы

Открой `V2rayNG/app/src/main/java/com/v2ray/ang/ui/LunaActivity.kt`, строка ~30:

```kotlin
private val SERVERS = linkedMapOf(
    "Германия (Быстрый)" to "vless://твой-линк-DE",
    "Нидерланды" to "vless://твой-линк-NL",
    "США (Нью-Йорк)" to "vless://твой-линк-US",
    "Сингапур" to "vless://твой-линк-SG",
)
```

Закоммить → push → GitHub Actions соберёт APK с новыми серверами.

## Статус сборки

[![Build Luna VPN APK](https://github.com/rakiyatraketa-arch/luna-vpn/actions/workflows/build-luna.yml/badge.svg)](https://github.com/rakiyatraketa-arch/luna-vpn/actions/workflows/build-luna.yml)

Кликни на бейдж выше, чтобы увидеть текущий статус сборки.

## Структура workflow

`.github/workflows/build-luna.yml`:

1. **Checkout** — клонирует код с submodules (AndroidLibXrayLite, hev-socks5-tunnel).
2. **Setup JDK 17** — устанавливает Java для Gradle.
3. **Install NDK** — устанавливает Android NDK 26.1 для сборки нативных библиотек.
4. **Build libhevtun** — собирает tun2socks (с кешированием, чтобы не пересобирать каждый раз).
5. **Download libv2ray.aar** — скачивает готовое ядро xray из AndroidLibXrayLite.
6. **Build APK** — `./gradlew assembleRelease`.
7. **Upload Artifact** — загружает APK как артефакт (доступен 30 дней).
8. **Create Release** (только при git-теге) — создаёт релиз с APK.

## Время сборки

Первая сборка: **~8-12 минут** (компиляция libhevtun).  
Последующие: **~4-6 минут** (libhevtun берётся из кеша).

## Если сборка упала

1. Открой https://github.com/rakiyatraketa-arch/luna-vpn/actions
2. Кликни на упавший запуск (красный крестик).
3. Посмотри логи — обычно ошибка в шаге "Build APK" или "Download libv2ray.aar".

Частые причины:
- **libv2ray.aar не найден** — AndroidLibXrayLite выпустил новый релиз без `.aar`. Проверь https://github.com/2dust/AndroidLibXrayLite/releases
- **NDK ошибка** — версия NDK изменилась. Обнови `ndk;26.1.10909125` в workflow на актуальную.
- **Gradle ошибка** — зависимости изменились. Проверь `V2rayNG/app/build.gradle.kts`.

## Локальная сборка (если нужна)

Если хочешь собрать APK локально (без GitHub Actions):

1. Открой `V2rayNG` в Android Studio.
2. Скачай `libv2ray.aar` вручную из https://github.com/2dust/AndroidLibXrayLite/releases → положи в `V2rayNG/app/libs/`.
3. Установи NDK через SDK Manager.
4. Собери libhevtun: `bash compile-hevtun.sh` (нужен `NDK_HOME`).
5. **Build → Build APK**.

Подробнее в `README_LUNA.md`.

---

**Готово.** Теперь каждый push автоматически собирает APK. Просто меняй код и пуш — APK появится в Actions через несколько минут. 🚀
