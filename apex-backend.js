/**
 * Apex VPN — backend профиля и платежей (AyfoPay).
 *
 * Зачем: платёжный ключ AyfoPay — СЕРВЕРНЫЙ секрет (им же проверяется подпись
 * webhook). В мобильное приложение его класть нельзя — извлекается из APK.
 * Поэтому приложение зовёт ЭТОТ сервер, а сервер уже ходит в AyfoPay.
 *
 * Хранит:
 *   - профиль пользователя, привязанный к HWID устройства (один HWID = один профиль);
 *   - баланс пользователя (рубли) — пополняется через AyfoPay;
 *   - срок подписки (subUntil, ms) — тарифы покупаются списанием с баланса;
 *   - заказы пополнения (по id платежа AyfoPay) для надёжной обработки webhook.
 *
 * Зависимостей нет — только встроенные модули Node.js.
 *
 * Запуск (на ТВОЁМ хостинге, за HTTPS-прокси nginx/caddy):
 *   AYFOPAY_API_KEY=<твой_ключ>  node apex-backend.js
 * Переменные окружения:
 *   AYFOPAY_API_KEY   (обязательно) — секретный ключ AyfoPay
 *   PORT              (по умолчанию 8090)
 *   HOST             (по умолчанию 0.0.0.0)
 *   DATA_DIR          (по умолчанию ./apex-data) — где хранить JSON
 *   PUBLIC_BASE       (необязательно) — твой публичный https-URL, для логов
 *
 * Webhook в поддержке AyfoPay указать как: https://<твой-домен>/api/ayfopay/webhook
 */

'use strict';

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ===================== Конфиг =====================

const PORT = parseInt(process.env.PORT || '8090', 10);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'apex-data');
const API_KEY = process.env.AYFOPAY_API_KEY || '';
const AYFOPAY_HOST = 'api.ayfopay.com';
const MAX_BODY = 32 * 1024;

if (!API_KEY) {
    console.warn('[apex] ВНИМАНИЕ: AYFOPAY_API_KEY не задан — оплата работать не будет.');
}

// Тарифы (цена в рублях, длительность в днях). Источник правды — здесь.
const PLANS = [
    { id: '1m', title: '1 месяц', months: 1, days: 30, price: 199 },
    { id: '3m', title: '3 месяца', months: 3, days: 90, price: 400 },
    { id: '6m', title: '6 месяцев', months: 6, days: 180, price: 750 },
    { id: '12m', title: '1 год', months: 12, days: 365, price: 1400 },
];
const planById = (id) => PLANS.find((p) => p.id === id) || null;

// Лимиты пополнения баланса (рубли). Любая сумма в этом диапазоне.
const MIN_TOPUP = 50;
const MAX_TOPUP = 50000;

// ===================== Хранилище (JSON-файлы) =====================

function ensureDir(d) {
    try { fs.mkdirSync(d, { recursive: true }); } catch (_) {}
}
ensureDir(DATA_DIR);

function loadJson(file, fallback) {
    try {
        return JSON.parse(fs.readFileSync(path.join(DATA_DIR, file), 'utf8'));
    } catch (_) {
        return fallback;
    }
}
function saveJson(file, obj) {
    const full = path.join(DATA_DIR, file);
    const tmp = full + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, full); // атомарная замена
}

// users: { [hwid]: { userId, hwid, subUntil, balance, createdAt } }
let users = loadJson('users.json', {});
// orders: { [ayfoPaymentId]: { type:'topup', userId, hwid, amount, method, status, createdAt, paidAt } }
// (type 'plan' — историческая покупка тарифа напрямую; новые покупки тарифа идут с баланса без AyfoPay)
let orders = loadJson('orders.json', {});

function persistUsers() { saveJson('users.json', users); }
function persistOrders() { saveJson('orders.json', orders); }

// ===================== Профиль / HWID =====================

function newUserId() {
    // короткий читаемый id, например APX-9F3A2B7C
    return 'APX-' + crypto.randomBytes(4).toString('hex').toUpperCase();
}

function normalizeHwid(hwid) {
    return String(hwid || '').trim().toUpperCase().replace(/[^A-F0-9]/g, '').slice(0, 64);
}

function getOrCreateUser(hwidRaw) {
    const hwid = normalizeHwid(hwidRaw);
    if (!hwid || hwid.length < 8) return null;
    if (!users[hwid]) {
        users[hwid] = {
            userId: newUserId(),
            hwid,
            subUntil: 0,
            balance: 0,
            createdAt: Date.now(),
        };
        persistUsers();
    }
    // Миграция старых профилей без поля balance
    if (typeof users[hwid].balance !== 'number') {
        users[hwid].balance = 0;
        persistUsers();
    }
    return users[hwid];
}

function publicProfile(u) {
    const now = Date.now();
    const active = u.subUntil > now;
    const daysLeft = active ? Math.ceil((u.subUntil - now) / 86400000) : 0;
    return {
        userId: u.userId,
        hwid: u.hwid,
        subUntil: u.subUntil,
        active,
        daysLeft,
        balance: u.balance || 0,
        plans: PLANS,
    };
}

function extendSubscription(u, days) {
    const now = Date.now();
    const base = u.subUntil > now ? u.subUntil : now; // продлеваем, не теряя остаток
    u.subUntil = base + days * 86400000;
    persistUsers();
}

// ===================== AyfoPay =====================

function createAyfoPayment({ amount, method, customerReference, description }) {
    return new Promise((resolve, reject) => {
        if (!API_KEY) return reject(new Error('AYFOPAY_API_KEY not configured'));
        const payload = JSON.stringify({
            amount,
            method, // 'sbp' | 'card'
            customer_reference: customerReference,
            description: description || undefined,
        });
        const req = https.request(
            {
                host: AYFOPAY_HOST,
                path: '/v1/payment',
                method: 'POST',
                headers: {
                    'Authorization': API_KEY,
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(payload),
                },
                timeout: 15000,
            },
            (res) => {
                let buf = '';
                res.on('data', (d) => { buf += d; if (buf.length > 256 * 1024) req.destroy(); });
                res.on('end', () => {
                    if (res.statusCode < 200 || res.statusCode >= 300) {
                        return reject(new Error('AyfoPay HTTP ' + res.statusCode + ': ' + buf.slice(0, 300)));
                    }
                    try { resolve(JSON.parse(buf)); }
                    catch (e) { reject(new Error('AyfoPay invalid JSON: ' + buf.slice(0, 200))); }
                });
            }
        );
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(new Error('AyfoPay timeout')); });
        req.write(payload);
        req.end();
    });
}

// Подпись webhook: sha256(id + token), token = API_KEY
function verifyWebhookSignature(id, signature) {
    if (!id || !signature || !API_KEY) return false;
    const expected = crypto.createHash('sha256').update(String(id) + API_KEY).digest('hex');
    const a = Buffer.from(expected, 'utf8');
    const b = Buffer.from(String(signature).toLowerCase(), 'utf8');
    if (a.length !== b.length) return false;
    return crypto.timingSafeEqual(a, b);
}

// ===================== HTTP =====================

function sendJson(res, code, obj) {
    const body = JSON.stringify(obj);
    res.writeHead(code, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    });
    res.end(body);
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        let b = '';
        let len = 0;
        req.on('data', (c) => {
            len += c.length;
            if (len > MAX_BODY) { req.destroy(); reject(new Error('body too large')); return; }
            b += c;
        });
        req.on('end', () => resolve(b));
        req.on('error', reject);
    });
}

const server = http.createServer(async (req, res) => {
    try {
        const url = new URL(req.url, 'http://localhost');
        const route = url.pathname;

        if (req.method === 'OPTIONS') return sendJson(res, 204, {});

        // Список тарифов
        if (req.method === 'GET' && route === '/api/plans') {
            return sendJson(res, 200, { plans: PLANS });
        }

        // Профиль по HWID (создаётся при первом обращении)
        if ((req.method === 'POST' || req.method === 'GET') && route === '/api/profile') {
            let hwid = url.searchParams.get('hwid');
            if (req.method === 'POST') {
                const body = await readBody(req);
                try { hwid = JSON.parse(body || '{}').hwid || hwid; } catch (_) {}
            }
            const u = getOrCreateUser(hwid);
            if (!u) return sendJson(res, 400, { error: 'invalid hwid' });
            return sendJson(res, 200, publicProfile(u));
        }

        // Пополнение баланса на любую сумму через AyfoPay
        if (req.method === 'POST' && route === '/api/topup') {
            const body = await readBody(req);
            let data = {};
            try { data = JSON.parse(body || '{}'); } catch (_) {}
            const u = getOrCreateUser(data.hwid);
            if (!u) return sendJson(res, 400, { error: 'invalid hwid' });
            const amount = Math.round(Number(data.amount));
            if (!Number.isFinite(amount) || amount < MIN_TOPUP || amount > MAX_TOPUP) {
                return sendJson(res, 400, { error: 'invalid amount', min: MIN_TOPUP, max: MAX_TOPUP });
            }
            const method = data.method === 'card' ? 'card' : 'sbp';
            const orderId = 'top_' + crypto.randomBytes(8).toString('hex');

            try {
                const pay = await createAyfoPayment({
                    amount,
                    method,
                    customerReference: orderId,
                    description: `Apex VPN пополнение баланса (${u.userId})`,
                });
                if (!pay || !pay.id || !pay.url) {
                    return sendJson(res, 502, { error: 'bad AyfoPay response' });
                }
                orders[pay.id] = {
                    type: 'topup',
                    orderId,
                    userId: u.userId,
                    hwid: u.hwid,
                    amount,
                    method,
                    status: 'pending',
                    createdAt: Date.now(),
                    paidAt: 0,
                };
                persistOrders();
                console.log(`[apex] topup created ayfoId=${pay.id} user=${u.userId} ${amount}r ${method}`);
                return sendJson(res, 200, { id: pay.id, url: pay.url, amount: pay.amount, method });
            } catch (e) {
                console.error('[apex] topup error:', e.message);
                return sendJson(res, 502, { error: 'payment_failed', detail: e.message });
            }
        }

        // Покупка тарифа списанием с баланса (без AyfoPay)
        if (req.method === 'POST' && route === '/api/buy') {
            const body = await readBody(req);
            let data = {};
            try { data = JSON.parse(body || '{}'); } catch (_) {}
            const u = getOrCreateUser(data.hwid);
            if (!u) return sendJson(res, 400, { error: 'invalid hwid' });
            const plan = planById(data.planId);
            if (!plan) return sendJson(res, 400, { error: 'invalid plan' });
            if ((u.balance || 0) < plan.price) {
                return sendJson(res, 402, { error: 'insufficient_funds', balance: u.balance || 0, price: plan.price });
            }
            // Списываем и продлеваем атомарно (в одном процессе — гонок нет)
            u.balance = (u.balance || 0) - plan.price;
            extendSubscription(u, plan.days); // вызывает persistUsers()
            console.log(`[apex] BUY user=${u.userId} plan=${plan.id} -${plan.price}r balance=${u.balance} -> subUntil=${new Date(u.subUntil).toISOString()}`);
            return sendJson(res, 200, { ok: true, ...publicProfile(u) });
        }

        // Webhook от AyfoPay (только с их IP — ограничить на уровне firewall/nginx: 109.120.177.0)
        if (req.method === 'POST' && route === '/api/ayfopay/webhook') {
            const body = await readBody(req);
            let data = {};
            try { data = JSON.parse(body || '{}'); } catch (_) {}
            const id = data.id;
            const signature = data.signature;
            if (!verifyWebhookSignature(id, signature)) {
                console.warn('[apex] webhook BAD signature id=' + id);
                return sendJson(res, 403, { error: 'bad signature' });
            }
            const order = orders[id];
            if (!order) {
                // подпись верна, но заказ не найден — отвечаем 200, чтобы не ретраили вечно
                console.warn('[apex] webhook unknown order id=' + id);
                return sendJson(res, 200, { ok: true, note: 'unknown order' });
            }
            if (order.status !== 'paid') {
                order.status = 'paid';
                order.paidAt = Date.now();
                persistOrders();
                const hwidUser = users[order.hwid];
                if (hwidUser) {
                    if (order.type === 'topup') {
                        // Пополнение баланса
                        hwidUser.balance = (hwidUser.balance || 0) + order.amount;
                        persistUsers();
                        console.log(`[apex] PAID topup id=${id} user=${order.userId} +${order.amount}r -> balance=${hwidUser.balance}`);
                    } else {
                        // Историческая прямая покупка тарифа (старые pending-заказы)
                        extendSubscription(hwidUser, order.days);
                        console.log(`[apex] PAID plan id=${id} user=${order.userId} +${order.days}d -> subUntil=${new Date(hwidUser.subUntil).toISOString()}`);
                    }
                }
            }
            return sendJson(res, 200, { ok: true });
        }

        // health
        if (req.method === 'GET' && route === '/api/health') {
            return sendJson(res, 200, { ok: true, ayfopay: !!API_KEY, users: Object.keys(users).length, balance: true });
        }

        sendJson(res, 404, { error: 'not found' });
    } catch (e) {
        sendJson(res, 500, { error: e.message });
    }
});

server.listen(PORT, HOST, () => {
    console.log('======================================================');
    console.log(' Apex VPN backend запущен');
    console.log(' Слушает:        ' + HOST + ':' + PORT);
    console.log(' AyfoPay ключ:   ' + (API_KEY ? 'задан ✓' : 'НЕ ЗАДАН — оплата выключена'));
    console.log(' Данные:         ' + DATA_DIR);
    if (process.env.PUBLIC_BASE) {
        console.log(' Webhook URL:    ' + process.env.PUBLIC_BASE.replace(/\/$/, '') + '/api/ayfopay/webhook');
    }
    console.log('======================================================');
});

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
