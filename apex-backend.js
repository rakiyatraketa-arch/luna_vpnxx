/**
 * Apex VPN — backend аккаунтов и платежей (AyfoPay).
 *
 * Зачем: платёжный ключ AyfoPay — СЕРВЕРНЫЙ секрет (им же проверяется подпись
 * webhook). В мобильное приложение его класть нельзя — извлекается из APK.
 * Поэтому приложение зовёт ЭТОТ сервер, а сервер уже ходит в AyfoPay.
 *
 * Аккаунты: вход по логину (нику) + паролю. Один аккаунт работает на нескольких
 * устройствах (массив токенов). Пароли хранятся только в виде pbkdf2-хэша с солью.
 *
 * Хранит (JSON-файлы в DATA_DIR, атомарная запись, переживают перезапуск):
 *   - аккаунты: логин, userId, хэш пароля, баланс (₽), срок подписки (subUntil, ms), токены;
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

// Лимиты пополнения баланса (рубли). Любая сумма в этом диапазоне.
const MIN_TOPUP = 50;
const MAX_TOPUP = 50000;

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

// accounts: { [loginKey]: { login, userId, salt, hash, subUntil, balance, tokens:[], hwids:[], createdAt } }
let accounts = loadJson('accounts.json', {});
// orders: { [ayfoPaymentId]: { type:'topup', orderId, loginKey, login, userId, amount, method, status, createdAt, paidAt } }
let orders = loadJson('orders.json', {});

function persistAccounts() { saveJson('accounts.json', accounts); }
function persistOrders() { saveJson('orders.json', orders); }

// ===================== Аккаунты / авторизация =====================

function newUserId() {
    // короткий читаемый id, например APX-9F3A2B7C
    return 'APX-' + crypto.randomBytes(4).toString('hex').toUpperCase();
}

function normLogin(s) { return String(s || '').trim(); }
function loginKey(s) { return normLogin(s).toLowerCase(); }
// Логин: латиница/цифры/подчёркивание, 3–32 символа
function isValidLogin(s) { return /^[A-Za-z0-9_]{3,32}$/.test(s); }

function hashPassword(password, salt) {
    return crypto.pbkdf2Sync(String(password), salt, 120000, 32, 'sha256').toString('hex');
}
function verifyPassword(acct, password) {
    if (!acct || !acct.salt || !acct.hash) return false;
    const h = hashPassword(password, acct.salt);
    const a = Buffer.from(h, 'hex');
    const b = Buffer.from(acct.hash, 'hex');
    if (a.length !== b.length) return false;
    return crypto.timingSafeEqual(a, b);
}
function newToken() { return crypto.randomBytes(32).toString('hex'); }

function accountByToken(token) {
    if (!token) return null;
    for (const k of Object.keys(accounts)) {
        const a = accounts[k];
        if (Array.isArray(a.tokens) && a.tokens.indexOf(token) !== -1) return a;
    }
    return null;
}
// Токен берём из тела запроса или заголовка Authorization
function tokenFromReq(data, req) {
    if (data && data.token) return String(data.token);
    const h = req.headers['authorization'] || '';
    return h ? String(h).replace(/^Bearer\s+/i, '').trim() : '';
}

function publicProfile(a) {
    const now = Date.now();
    const active = a.subUntil > now;
    const daysLeft = active ? Math.ceil((a.subUntil - now) / 86400000) : 0;
    return {
        userId: a.userId,
        login: a.login,
        subUntil: a.subUntil,
        active,
        daysLeft,
        balance: a.balance || 0,
        plans: PLANS,
    };
}

function extendSubscription(a, days) {
    const now = Date.now();
    const base = a.subUntil > now ? a.subUntil : now; // продлеваем, не теряя остаток
    a.subUntil = base + days * 86400000;
    persistAccounts();
}

function rememberHwid(acct, hwidRaw) {
    const hwid = String(hwidRaw || '').trim().toUpperCase().replace(/[^A-F0-9]/g, '').slice(0, 64);
    if (!hwid || hwid.length < 8) return;
    if (!Array.isArray(acct.hwids)) acct.hwids = [];
    if (acct.hwids.indexOf(hwid) === -1) { acct.hwids.push(hwid); acct.hwids = acct.hwids.slice(-10); }
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
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
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
async function readJson(req) {
    const body = await readBody(req);
    try { return JSON.parse(body || '{}'); } catch (_) { return {}; }
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

        // Регистрация
        if (req.method === 'POST' && route === '/api/register') {
            const data = await readJson(req);
            const login = normLogin(data.login);
            const password = String(data.password || '');
            if (!isValidLogin(login)) return sendJson(res, 400, { error: 'invalid_login' });
            if (password.length < 6) return sendJson(res, 400, { error: 'weak_password' });
            const key = loginKey(login);
            if (accounts[key]) return sendJson(res, 409, { error: 'login_taken' });
            const salt = crypto.randomBytes(16).toString('hex');
            const token = newToken();
            const acct = {
                login,
                userId: newUserId(),
                salt,
                hash: hashPassword(password, salt),
                subUntil: 0,
                balance: 0,
                tokens: [token],
                hwids: [],
                createdAt: Date.now(),
            };
            rememberHwid(acct, data.hwid);
            accounts[key] = acct;
            persistAccounts();
            console.log(`[apex] REGISTER login=${login} user=${acct.userId}`);
            return sendJson(res, 200, { token, ...publicProfile(acct) });
        }

        // Вход
        if (req.method === 'POST' && route === '/api/login') {
            const data = await readJson(req);
            const key = loginKey(data.login);
            const acct = accounts[key];
            if (!acct || !verifyPassword(acct, String(data.password || ''))) {
                return sendJson(res, 401, { error: 'bad_credentials' });
            }
            const token = newToken();
            if (!Array.isArray(acct.tokens)) acct.tokens = [];
            acct.tokens.push(token);
            if (acct.tokens.length > 10) acct.tokens = acct.tokens.slice(-10); // не более 10 устройств
            rememberHwid(acct, data.hwid);
            persistAccounts();
            console.log(`[apex] LOGIN login=${acct.login} user=${acct.userId}`);
            return sendJson(res, 200, { token, ...publicProfile(acct) });
        }

        // Выход (удаляем токен текущего устройства)
        if (req.method === 'POST' && route === '/api/logout') {
            const data = await readJson(req);
            const token = tokenFromReq(data, req);
            const acct = accountByToken(token);
            if (acct) {
                acct.tokens = (acct.tokens || []).filter((t) => t !== token);
                persistAccounts();
            }
            return sendJson(res, 200, { ok: true });
        }

        // Профиль (по токену)
        if (req.method === 'POST' && route === '/api/profile') {
            const data = await readJson(req);
            const acct = accountByToken(tokenFromReq(data, req));
            if (!acct) return sendJson(res, 401, { error: 'unauthorized' });
            rememberHwid(acct, data.hwid);
            return sendJson(res, 200, publicProfile(acct));
        }

        // Пополнение баланса на любую сумму через AyfoPay
        if (req.method === 'POST' && route === '/api/topup') {
            const data = await readJson(req);
            const acct = accountByToken(tokenFromReq(data, req));
            if (!acct) return sendJson(res, 401, { error: 'unauthorized' });
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
                    description: `Apex VPN пополнение баланса (${acct.login})`,
                });
                if (!pay || !pay.id || !pay.url) {
                    return sendJson(res, 502, { error: 'bad AyfoPay response' });
                }
                orders[pay.id] = {
                    type: 'topup',
                    orderId,
                    loginKey: loginKey(acct.login),
                    login: acct.login,
                    userId: acct.userId,
                    amount,
                    method,
                    status: 'pending',
                    createdAt: Date.now(),
                    paidAt: 0,
                };
                persistOrders();
                console.log(`[apex] topup created ayfoId=${pay.id} login=${acct.login} ${amount}r ${method}`);
                return sendJson(res, 200, { id: pay.id, url: pay.url, amount: pay.amount, method });
            } catch (e) {
                console.error('[apex] topup error:', e.message);
                return sendJson(res, 502, { error: 'payment_failed', detail: e.message });
            }
        }

        // Покупка тарифа списанием с баланса (без AyfoPay)
        if (req.method === 'POST' && route === '/api/buy') {
            const data = await readJson(req);
            const acct = accountByToken(tokenFromReq(data, req));
            if (!acct) return sendJson(res, 401, { error: 'unauthorized' });
            const plan = planById(data.planId);
            if (!plan) return sendJson(res, 400, { error: 'invalid plan' });
            if ((acct.balance || 0) < plan.price) {
                return sendJson(res, 402, { error: 'insufficient_funds', balance: acct.balance || 0, price: plan.price });
            }
            acct.balance = (acct.balance || 0) - plan.price;
            extendSubscription(acct, plan.days); // вызывает persistAccounts()
            console.log(`[apex] BUY login=${acct.login} plan=${plan.id} -${plan.price}r balance=${acct.balance} -> subUntil=${new Date(acct.subUntil).toISOString()}`);
            return sendJson(res, 200, { ok: true, ...publicProfile(acct) });
        }

        // Webhook от AyfoPay (ограничить по IP на уровне firewall/nginx)
        if (req.method === 'POST' && route === '/api/ayfopay/webhook') {
            const data = await readJson(req);
            const id = data.id;
            const signature = data.signature;
            if (!verifyWebhookSignature(id, signature)) {
                console.warn('[apex] webhook BAD signature id=' + id);
                return sendJson(res, 403, { error: 'bad signature' });
            }
            const order = orders[id];
            if (!order) {
                console.warn('[apex] webhook unknown order id=' + id);
                return sendJson(res, 200, { ok: true, note: 'unknown order' });
            }
            if (order.status !== 'paid') {
                order.status = 'paid';
                order.paidAt = Date.now();
                persistOrders();
                const acct = accounts[order.loginKey];
                if (acct && order.type === 'topup') {
                    acct.balance = (acct.balance || 0) + order.amount;
                    persistAccounts();
                    console.log(`[apex] PAID topup id=${id} login=${order.login} +${order.amount}r -> balance=${acct.balance}`);
                } else {
                    console.warn(`[apex] webhook paid but account/type mismatch id=${id} login=${order.login}`);
                }
            }
            return sendJson(res, 200, { ok: true });
        }

        // health
        if (req.method === 'GET' && route === '/api/health') {
            return sendJson(res, 200, { ok: true, ayfopay: !!API_KEY, accounts: Object.keys(accounts).length, auth: true });
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
    console.log(' Аккаунтов:      ' + Object.keys(accounts).length);
    if (process.env.PUBLIC_BASE) {
        console.log(' Webhook URL:    ' + process.env.PUBLIC_BASE.replace(/\/$/, '') + '/api/ayfopay/webhook');
    }
    console.log('======================================================');
});

process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
