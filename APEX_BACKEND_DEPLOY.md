# Apex VPN — бэкенд профиля и платежей (AyfoPay)

Платёжный ключ AyfoPay — **серверный секрет**, в приложение его класть нельзя.
Поэтому приложение обращается к этому бэкенду, а бэкенд уже ходит в AyfoPay.

## 1. Запуск на сервере

Нужен Node.js 18+. Зависимостей нет.

```bash
# на сервере, рядом с apex-backend.js
export AYFOPAY_API_KEY="<НОВЫЙ_ключ_AyfoPay>"   # старый, что был в чате — перевыпусти!
export PORT=8090
export PUBLIC_BASE="https://api.ВАШ-ДОМЕН.tld"
node apex-backend.js
```

Лучше под systemd / pm2, чтобы перезапускался. Данные (профили, заказы)
лежат в `apex-data/` (в git не коммитятся).

## 2. HTTPS + домен

Бэкенд слушает HTTP на `:8090`. Поставь перед ним TLS-прокси (nginx/caddy):

```nginx
server {
  server_name api.ВАШ-ДОМЕН.tld;
  location / { proxy_pass http://127.0.0.1:8090; proxy_set_header Host $host; }
  # TLS через certbot/letsencrypt
}
```

## 3. Webhook в AyfoPay

Напиши в поддержку AyfoPay, чтобы установили `webhook_url`:

```
https://api.ВАШ-ДОМЕН.tld/api/ayfopay/webhook
```

Разреши приём только с их IP `109.120.177.0` (firewall/nginx `allow`).
Бэкенд сам проверяет подпись `sha256(id + ключ)` — подделать нельзя.

## 4. Указать адрес бэкенда в приложении

В `v2rayNG/V2rayNG/app/src/main/assets/luna/index.html` найди строку:

```js
const API_BASE = 'https://api.apexvpn.example';
```

и впиши свой реальный домен (это НЕ секрет — просто адрес API). Пересобери APK.

## Эндпоинты

| Метод | Путь | Назначение |
|------|------|-----------|
| GET  | `/api/plans` | список тарифов |
| POST | `/api/profile` `{hwid}` | профиль по HWID (создаётся при первом заходе): `userId`, `subUntil`, `daysLeft`, `active`, `plans` |
| POST | `/api/pay` `{hwid, planId, method}` | создаёт платёж в AyfoPay, возвращает `{url}` |
| POST | `/api/ayfopay/webhook` | приём подтверждения оплаты (продлевает подписку) |
| GET  | `/api/health` | проверка |

## Тарифы

Заданы в `apex-backend.js` (массив `PLANS`): 1 мес 199₽, 3 мес 400₽, 6 мес 750₽, 1 год 1400₽.
Меняй цены/сроки там — приложение подтянет их из `/api/plans`.

## Безопасность

- Ключ только в `AYFOPAY_API_KEY` (env), никогда в репозитории/APK.
- HWID привязывается к профилю на сервере (один HWID = один профиль).
- Подписка хранится на сервере (`subUntil`) — на устройстве её подделать нельзя.
- Присланный в чат ключ считай скомпрометированным — **перевыпусти в AyfoPay**.
