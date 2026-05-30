# 🚀 Настройка GitHub и запуск сборки

## Шаг 1: Создать репозиторий на GitHub

1. Открыть https://github.com/new
2. Название: `luna-vpn` (или любое другое)
3. Описание: `Luna VPN - Fast VLESS+Reality VPN with TUN mode`
4. Выбрать: **Public** или **Private**
5. **НЕ** добавлять README, .gitignore, license (уже есть)
6. Нажать **Create repository**

---

## Шаг 2: Подключить локальный репозиторий

```bash
cd C:\Users\slayer\Downloads\vpss

# Добавить remote (замени YOUR_USERNAME на свой GitHub username)
git remote add origin https://github.com/YOUR_USERNAME/luna-vpn.git

# Или через SSH (если настроен SSH ключ)
git remote add origin git@github.com:YOUR_USERNAME/luna-vpn.git

# Проверить remote
git remote -v
```

---

## Шаг 3: Запушить код на GitHub

```bash
# Создать ветку main (если нужно)
git branch -M main

# Запушить код
git push -u origin main
```

При первом пуше GitHub может попросить авторизацию:
- **HTTPS**: введи username и Personal Access Token
- **SSH**: используй SSH ключ

### Как создать Personal Access Token:
1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token (classic)
3. Выбрать scopes: `repo` (полный доступ к репозиториям)
4. Скопировать токен и использовать вместо пароля

---

## Шаг 4: Запустить GitHub Actions

После пуша:

### Автоматический запуск:
GitHub Actions запустится автоматически при пуше в `main`/`master`

### Ручной запуск:
1. Открыть репозиторий на GitHub
2. Перейти в **Actions**
3. Выбрать workflow **Build Luna VPN APK**
4. Нажать **Run workflow** → **Run workflow**

---

## Шаг 5: Скачать собранный APK

После завершения сборки (5-10 минут):

1. Открыть **Actions** → выбрать последний запуск
2. Прокрутить вниз до **Artifacts**
3. Скачать:
   - `luna-vpn-debug` - Debug версия (для тестирования)
   - `luna-vpn-release` - Release версия (неподписанная)

---

## 📱 Установка APK

### На телефон:
```bash
# Через ADB
adb install luna-vpn-debug.apk

# Или скопировать на телефон и установить вручную
# (нужно разрешить установку из неизвестных источников)
```

### На эмулятор:
```bash
adb -e install luna-vpn-debug.apk
```

---

## 🔧 Настройка автосборки

Workflow уже настроен и будет запускаться:
- ✅ При каждом push в `main`/`master`/`develop`
- ✅ При создании Pull Request
- ✅ Вручную через GitHub Actions UI

### Что делает workflow:
1. Клонирует репозиторий (с submodules)
2. Устанавливает JDK 17
3. Собирает Debug APK
4. Собирает Release APK (если возможно)
5. Загружает APK как artifacts (хранятся 30 дней)

---

## 🎯 Быстрый старт (копипаста)

```bash
# 1. Перейти в папку проекта
cd C:\Users\slayer\Downloads\vpss

# 2. Добавить remote (ЗАМЕНИ YOUR_USERNAME!)
git remote add origin https://github.com/YOUR_USERNAME/luna-vpn.git

# 3. Запушить код
git branch -M main
git push -u origin main

# 4. Открыть GitHub Actions
# https://github.com/YOUR_USERNAME/luna-vpn/actions

# 5. Дождаться сборки и скачать APK из Artifacts
```

---

## 🐛 Troubleshooting

### Ошибка: "remote origin already exists"
```bash
git remote remove origin
git remote add origin https://github.com/YOUR_USERNAME/luna-vpn.git
```

### Ошибка: "failed to push some refs"
```bash
git pull origin main --rebase
git push -u origin main
```

### Ошибка: "Authentication failed"
- Используй Personal Access Token вместо пароля
- Или настрой SSH ключ

### Сборка падает на GitHub Actions
- Проверь логи в Actions → выбери запуск → посмотри ошибки
- Обычно проблема в submodules или зависимостях

---

## 📊 Статус сборки

После настройки можно добавить badge в README:

```markdown
![Build Status](https://github.com/YOUR_USERNAME/luna-vpn/workflows/Build%20Luna%20VPN%20APK/badge.svg)
```

---

## ✅ Готово!

После выполнения всех шагов:
- ✅ Код на GitHub
- ✅ Автосборка настроена
- ✅ APK собирается автоматически
- ✅ Можно скачать из Artifacts

**Время сборки:** ~5-10 минут  
**Размер APK:** ~15-20 MB (debug), ~10-15 MB (release)

---

**Нужна помощь?** Пиши в Issues репозитория!
