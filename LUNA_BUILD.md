# Luna VPN — Build & Run Guide

Этот форк v2rayNG переименован в **Luna VPN** и переделан под неоновую тему.
Главный экран — `LunaActivity` (анимированная Луна, большая кнопка, статистика).
Старый список серверов доступен через меню (☰ → Manage servers).

## Что было изменено

Все добавления локализованы — ядро xray-core, VpnService, парсер VLESS+REALITY не тронуты.

| Файл | Что |
|---|---|
| `V2rayNG/app/src/main/res/values/colors.xml` | + неоновая палитра (`luna_*`) |
| `V2rayNG/app/src/main/res/values/strings.xml` | `app_name` → "Luna VPN", + строки `luna_*` |
| `V2rayNG/app/src/main/res/drawable/luna_*.xml` | drawable: кнопка, иконки, фон карточки |
| `V2rayNG/app/src/main/res/layout/activity_luna.xml` | новый layout главного экрана |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/luna/MoonView.kt` | анимированный custom view "Луна" |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ui/luna/LunaActivity.kt` | новая launcher Activity |
| `V2rayNG/app/src/main/AndroidManifest.xml` | LunaActivity → launcher, MainActivity → exported для quick-tile |
| `V2rayNG/app/build.gradle.kts` | `applicationId` → `com.lunavpn.app`, имена APK → `LunaVPN_*` |
| `V2rayNG/app/src/main/java/com/v2ray/ang/util/Utils.kt` | `isXray()` → всегда `true` (для VLESS+REALITY) |
| `V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt` | имя канала уведомлений → "Luna VPN..." |

## Лицензия (важно)

v2rayNG — **GPL-3.0**. Этот форк наследует ту же лицензию. Если распространяете
`.apk` третьим лицам — обязаны открыть исходники (включая ваши изменения).
Для личного использования ограничений нет.

---

## Способ 1: Сборка через GitHub Actions (рекомендуется)

Самый простой путь к подписанному APK на Windows — не собирать локально, а
загрузить форк на GitHub и собрать через готовый workflow.

1. Создайте пустой репозиторий на GitHub (например `lunavpn-android`).
2. В папке проекта:
   ```bash
   cd C:/Users/slayer/Downloads/lunavpn
   git remote remove origin
   git remote add origin https://github.com/ВАШ_ЛОГИН/lunavpn-android.git
   git add -A
   git commit -m "Luna VPN: neon UI fork of v2rayNG"
   git push -u origin master
   ```
3. В Settings → Secrets and variables → Actions добавьте 4 секрета:
   - `APP_KEYSTORE_BASE64` — base64 от keystore (см. ниже)
   - `APP_KEYSTORE_PASSWORD`
   - `APP_KEYSTORE_ALIAS`
   - `APP_KEY_PASSWORD`

   Сгенерировать keystore + base64:
   ```bash
   keytool -genkey -v -keystore luna.jks -keyalg RSA -keysize 2048 \
     -validity 10000 -alias luna -storepass <ВАШ_ПАРОЛЬ> -keypass <ВАШ_ПАРОЛЬ> \
     -dname "CN=Luna,O=Personal,C=US"
   base64 -w0 luna.jks > luna.jks.b64
   ```
   Содержимое `luna.jks.b64` → в `APP_KEYSTORE_BASE64`.

4. В Actions запустите workflow **Build APK** вручную (workflow_dispatch).
5. Готовый APK будет в artifacts билда. Скачать → установить на телефон.

---

## Способ 2: Локальная сборка (Windows)

### Требования

| Инструмент | Версия | Где взять |
|---|---|---|
| **JDK** | 21 (Temurin) | https://adoptium.net/temurin/releases/?version=21 |
| **Android SDK** | platforms;android-36.1, build-tools;36.1.0 | через Android Studio: SDK Manager |
| **Android NDK** | 28.2.13676358 | через Android Studio: SDK Manager → NDK (Side by side) |
| **Android Studio** | свежая стабильная | https://developer.android.com/studio |

### Шаг 1: получить `libv2ray.aar`

xray-core уже скомпилирован — есть в релизах:

```bash
mkdir -p C:/Users/slayer/Downloads/lunavpn/V2rayNG/app/libs
# Перейти на https://github.com/2dust/AndroidLibXrayLite/releases
# Скачать самый свежий libv2ray.aar и положить в путь выше
```

### Шаг 2: собрать `libhevtun` (TUN → SOCKS5)

Это C-проект. Через bash в Git Bash / MSYS:

```bash
cd C:/Users/slayer/Downloads/lunavpn
git submodule update --init --recursive
# Установите переменную NDK_HOME, например:
export NDK_HOME="/c/Users/slayer/AppData/Local/Android/Sdk/ndk/28.2.13676358"
bash compile-hevtun.sh
cp -r libs V2rayNG/app/
```

Если `ndk-build` падает — на Windows вместо него работает `ndk-build.cmd`.
В этом случае проще запустить сборку через WSL Ubuntu (`wsl --install`),
там скрипт работает «из коробки».

### Шаг 3: собрать APK

```bash
cd C:/Users/slayer/Downloads/lunavpn/V2rayNG
echo "sdk.dir=C:\\Users\\slayer\\AppData\\Local\\Android\\Sdk" > local.properties
./gradlew assemblePlaystoreDebug
```

Готовый APK: `V2rayNG/app/build/outputs/apk/playstore/debug/LunaVPN_2.2.1_universal.apk`

Установка:
```bash
adb install -r V2rayNG/app/build/outputs/apk/playstore/debug/LunaVPN_2.2.1_universal.apk
```

Либо просто перенести `.apk` на телефон и открыть — установится из неизвестных источников.

---

## Первый запуск

1. Откройте Luna VPN.
2. Скопируйте свою VLESS-ссылку (`vless://uuid@host:443?...&type=tcp#name`) в буфер.
3. На главном экране нажмите ☰ → **Paste vless:// link** (либо при первом
   запуске сразу появится диалог).
4. Сервер добавится. Имя появится в шапке.
5. Большая кнопка → CONNECT. Первый раз Android спросит разрешение на VPN.
6. Луна засветится циан, статус "PROTECTED", IP сменится на адрес сервера.

## Если нужно ввести данные вручную

Меню ☰ → **Enter manually** → откроется `SubSettingActivity`. Оттуда
"Add" → выберите тип VLESS → введите host/port/UUID/publicKey/shortId.

---

## Что НЕ работает «из коробки»

- **Подпись release-APK локально**: подписать debug-APK gradle делает сам
  (`debug.keystore` из `~/.android/`). Release требует своего keystore — см.
  Способ 1 (через CI) или настройте `signingConfigs.release` в `app/build.gradle.kts`.
- **Иконка приложения**: пока стандартная (v2rayNG). Чтобы переделать —
  замените `app/src/main/res/mipmap-*/ic_launcher*.png` или сгенерируйте
  через Android Studio → Image Asset.
- **Splash screen**: не реализован — приложение открывается сразу на Луну.

## Знайте свои ограничения

Луна-экран использует `View + Canvas + ValueAnimator` (не Compose) — анимация
плавная, но при большом количестве колец >60fps возможна нагрузка. Если будете
расширять — переходить на Compose Canvas будет проще.
