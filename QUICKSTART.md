# 🚀 БЫСТРЫЙ СТАРТ - Запуск сборки на GitHub

## ⚡ Шаг 1: Создать репозиторий на GitHub

1. Открыть: https://github.com/new
2. Название: `luna-vpn`
3. Описание: `Luna VPN - Fast VLESS+Reality VPN with TUN mode`
4. Выбрать **Public** или **Private**
5. Нажать **Create repository**

---

## ⚡ Шаг 2: Запушить код (КОПИПАСТА)

```bash
cd C:\Users\slayer\Downloads\vpss

# ЗАМЕНИ YOUR_USERNAME на свой GitHub username!
git remote add origin https://github.com/YOUR_USERNAME/luna-vpn.git

# Запушить код
git push -u origin master
```

**При запросе авторизации:**
- Username: твой GitHub username
- Password: Personal Access Token (НЕ пароль!)

### Как получить Personal Access Token:
1. https://github.com/settings/tokens
2. **Generate new token (classic)**
3. Выбрать: `repo` (полный доступ)
4. Скопировать токен и использовать как пароль

---

## ⚡ Шаг 3: Проверить сборку

После пуша:

1. Открыть: `https://github.com/YOUR_USERNAME/luna-vpn/actions`
2. Увидишь запущенную сборку **Build Luna VPN APK**
3. Дождаться завершения (~5-10 минут)
4. Скачать APK из **Artifacts**

---

## 📥 Скачать собранный APK

После завершения сборки:

1. Открыть последний запуск в Actions
2. Прокрутить вниз до **Artifacts**
3. Скачать:
   - `luna-vpn-debug.apk` - для тестирования
   - `luna-vpn-release.apk` - финальная версия

---

## 🔄 Автоматическая сборка

Сборка запускается автоматически при:
- ✅ Push в `master`, `main`, `develop`
- ✅ Создании Pull Request
- ✅ Ручном запуске через Actions UI

---

## 🐛 Если что-то не работает

### Ошибка: "Authentication failed"
```bash
# Используй Personal Access Token вместо пароля
# Получить: https://github.com/settings/tokens
```

### Ошибка: "remote origin already exists"
```bash
git remote remove origin
git remote add origin https://github.com/YOUR_USERNAME/luna-vpn.git
```

### Сборка падает на GitHub
- Открой Actions → выбери запуск → посмотри логи
- Обычно проблема в submodules

---

## ✅ Готово!

После выполнения:
- ✅ Код на GitHub
- ✅ Автосборка работает
- ✅ APK доступен в Artifacts

**Время сборки:** 5-10 минут  
**Размер APK:** ~15-20 MB

---

## 📱 Установка APK

```bash
# Через ADB
adb install luna-vpn-debug.apk

# Или скопировать на телефон и установить вручную
```

---

**Нужна помощь?** Смотри полную инструкцию в `GITHUB_SETUP.md`
