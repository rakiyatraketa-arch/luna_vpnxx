/**
 * Luna VPN — локальный бэкенд.
 * Поднимает РЕАЛЬНЫЙ VLESS+Reality через нативный бинарник xray (xray-core).
 * Браузер не умеет в сырые TCP-сокеты и Reality, поэтому туннель держит xray,
 * а эта программа лишь управляет им и отдаёт фронтенду статус/статистику/IP.
 *
 * Зависимостей нет — только встроенные модули Node.js.
 *
 * Что нужно:
 *   1. Положить бинарник xray рядом с этим файлом:
 *        Windows: xray.exe   |   macOS/Linux: xray
 *      (скачать: https://github.com/XTLS/Xray-core/releases)
 *      Либо задать путь через переменную окружения XRAY_PATH.
 *   2. node server.js
 *   3. Открыть http://127.0.0.1:8080
 *
 * Чтобы трафик приложений шёл через VPN — указать им SOCKS5 127.0.0.1:1080
 * (или HTTP-прокси 127.0.0.1:1081).
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const net = require('net');
const { spawn, spawnSync, execFile } = require('child_process');

// ===================== Конфигурация =====================

const PORT = 8080;            // веб-интерфейс
const SOCKS_PORT = 1080;      // SOCKS5 для приложений
const HTTP_PROXY_PORT = 1081; // HTTP-прокси (используется и для проверки IP)
const API_PORT = 10085;       // gRPC API xray для статистики

// Лимиты, защищающие от исчерпания памяти на localhost-эндпоинтах.
const MAX_BODY_BYTES = 64 * 1024;
const MAX_IP_RESP_BYTES = 256 * 1024;

// VLESS-линки берём из окружения, чтобы НЕ хранить реальные ключи/UUID в коде.
//   LUNA_VLESS_LINK         — основной линк (fallback для всех стран)
//   LUNA_LINK_DE/NL/US/SG   — линки конкретных стран (необязательно)
// ВНИМАНИЕ: значение ниже — лишь нерабочая заглушка. Реальный линк, который
// раньше был захардкожен здесь, СКОМПРОМЕТИРОВАН (лежал в исходниках) — на
// сервере его UUID и Reality-ключи нужно перевыпустить.
const PLACEHOLDER_LINK = 'vless://00000000-0000-0000-0000-000000000000@example.com:443?security=reality&encryption=none&type=tcp&sni=www.example.com#placeholder';
const DEFAULT_LINK = process.env.LUNA_VLESS_LINK || PLACEHOLDER_LINK;

const SERVERS = {
    'Германия (Быстрый)': process.env.LUNA_LINK_DE || DEFAULT_LINK,
    'Нидерланды':         process.env.LUNA_LINK_NL || DEFAULT_LINK,
    'США (Нью-Йорк)':     process.env.LUNA_LINK_US || DEFAULT_LINK,
    'Сингапур':           process.env.LUNA_LINK_SG || DEFAULT_LINK,
};

const CONFIG_PATH = path.join(__dirname, '.xray-config.json');

// ===================== Поиск бинарника xray =====================

let cachedXray = undefined;
function findXray() {
    if (cachedXray !== undefined) return cachedXray;
    if (process.env.XRAY_PATH && fs.existsSync(process.env.XRAY_PATH)) {
        cachedXray = process.env.XRAY_PATH;
        return cachedXray;
    }
    const names = process.platform === 'win32' ? ['xray.exe', 'xray'] : ['xray'];
    for (const n of names) {
        const local = path.join(__dirname, n);
        if (fs.existsSync(local)) { cachedXray = local; return cachedXray; }
    }
    const probe = spawnSync(process.platform === 'win32' ? 'where' : 'which', ['xray'], { encoding: 'utf8' });
    if (probe.status === 0 && probe.stdout.trim()) {
        cachedXray = probe.stdout.split(/\r?\n/)[0].trim();
        return cachedXray;
    }
    cachedXray = null;
    return cachedXray;
}

// ===================== Парсинг VLESS-ссылки =====================

function parseVless(raw) {
    const u = new URL(raw);
    const p = u.searchParams;
    return {
        uuid: decodeURIComponent(u.username),
        address: u.hostname,
        port: parseInt(u.port || '443', 10),
        type: p.get('type') || 'tcp',
        encryption: p.get('encryption') || 'none',
        security: p.get('security') || 'reality',
        pbk: p.get('pbk') || '',
        fp: p.get('fp') || 'chrome',
        sni: p.get('sni') || '',
        sid: p.get('sid') || '',
        spx: p.get('spx') || '/',
        flow: p.get('flow') || '',
    };
}

// ===================== Генерация конфига xray =====================

function buildConfig(p) {
    const stream = {
        network: p.type,
        security: p.security,
    };
    if (p.security === 'reality') {
        stream.realitySettings = {
            serverName: p.sni,
            fingerprint: p.fp || 'chrome',
            publicKey: p.pbk,
            shortId: p.sid || '',
            spiderX: p.spx || '/',
        };
    } else if (p.security === 'tls') {
        stream.tlsSettings = { serverName: p.sni, fingerprint: p.fp || 'chrome' };
    }

    return {
        log: { loglevel: 'warning' },
        stats: {},
        api: { tag: 'api', services: ['StatsService'] },
        policy: {
            system: {
                statsOutboundUplink: true,
                statsOutboundDownlink: true,
            },
        },
        inbounds: [
            { tag: 'socks', listen: '127.0.0.1', port: SOCKS_PORT, protocol: 'socks',
              settings: { udp: true, auth: 'noauth' } },
            { tag: 'http', listen: '127.0.0.1', port: HTTP_PROXY_PORT, protocol: 'http',
              settings: {} },
            { tag: 'api-in', listen: '127.0.0.1', port: API_PORT, protocol: 'dokodemo-door',
              settings: { address: '127.0.0.1' } },
        ],
        outbounds: [
            {
                tag: 'proxy',
                protocol: 'vless',
                settings: {
                    vnext: [{
                        address: p.address,
                        port: p.port,
                        users: [{ id: p.uuid, encryption: p.encryption || 'none', flow: p.flow || '' }],
                    }],
                },
                streamSettings: stream,
            },
            { tag: 'direct', protocol: 'freedom' },
            { tag: 'block', protocol: 'blackhole' },
        ],
        routing: {
            rules: [
                { type: 'field', inboundTag: ['api-in'], outboundTag: 'api' },
            ],
        },
    };
}

// ===================== Управление процессом xray =====================

let xrayProc = null;
let currentCountry = null;

function stopXray() {
    if (!xrayProc) return;
    const proc = xrayProc;
    xrayProc = null;
    // Снимаем слушатели, чтобы при пересоздании процесса не текли listeners и
    // 'exit' старого процесса не трактовался как ошибка нового.
    proc.removeAllListeners('exit');
    proc.removeAllListeners('error');
    try {
        proc.kill();
        // На Windows SIGTERM ненадёжен — добиваем дерево процессов принудительно,
        // иначе xray может держать порты 1080/1081/10085 и следующий старт упадёт.
        if (process.platform === 'win32' && proc.pid) {
            try { spawnSync('taskkill', ['/pid', String(proc.pid), '/T', '/F']); } catch (_) {}
        }
    } catch (_) {}
}

// Ждём, пока порт начнёт слушать (xray реально поднялся), или таймаут.
function waitForPort(port, host, timeoutMs) {
    return new Promise((resolve) => {
        const deadline = Date.now() + timeoutMs;
        const tryOnce = () => {
            const s = net.connect(port, host);
            s.once('connect', () => { s.destroy(); resolve(true); });
            s.once('error', () => {
                s.destroy();
                if (Date.now() >= deadline) resolve(false);
                else setTimeout(tryOnce, 200);
            });
        };
        tryOnce();
    });
}

async function startXray(country) {
    const bin = findXray();
    if (!bin) {
        throw new Error('Бинарник xray не найден. Положи xray.exe рядом с server.js или задай XRAY_PATH.');
    }
    const link = SERVERS[country] || DEFAULT_LINK;
    let cfg;
    try {
        cfg = buildConfig(parseVless(link));
    } catch (e) {
        throw new Error('Не удалось разобрать VLESS-ссылку: ' + e.message);
    }
    await fs.promises.writeFile(CONFIG_PATH, JSON.stringify(cfg, null, 2));

    const proc = spawn(bin, ['run', '-c', CONFIG_PATH]);
    xrayProc = proc;
    currentCountry = country;

    let exitedEarly = null;
    const onData = (d) => {
        const s = d.toString();
        process.stdout.write('[xray] ' + s);
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('error', (e) => { exitedEarly = e; });
    proc.on('exit', (code) => {
        if (xrayProc === proc) xrayProc = null;
        if (exitedEarly == null) exitedEarly = new Error('xray завершился с кодом ' + code);
    });

    // Подтверждаем запуск тем, что SOCKS-порт реально начал слушать.
    // Раньше успех возвращался по слепому таймауту 1.8с — даже если xray упал.
    const ready = await waitForPort(SOCKS_PORT, '127.0.0.1', 8000);
    if (exitedEarly) throw exitedEarly;
    if (!ready) {
        stopXray();
        throw new Error('xray не открыл SOCKS-порт за отведённое время');
    }
}

// ===================== Статистика (xray api) =====================

function queryStats() {
    return new Promise((resolve) => {
        const bin = findXray();
        if (!bin || !xrayProc) return resolve({ up: 0, down: 0 });
        execFile(
            bin,
            ['api', 'statsquery', '--server=127.0.0.1:' + API_PORT],
            { encoding: 'utf8', timeout: 5000 },
            (err, stdout) => {
                if (err || !stdout) return resolve({ up: 0, down: 0 });
                try {
                    const data = JSON.parse(stdout);
                    let up = 0, down = 0;
                    for (const s of (data.stat || [])) {
                        if (s.name === 'outbound>>>proxy>>>traffic>>>uplink') up = Number(s.value || 0);
                        if (s.name === 'outbound>>>proxy>>>traffic>>>downlink') down = Number(s.value || 0);
                    }
                    resolve({ up, down });
                } catch (_) {
                    resolve({ up: 0, down: 0 });
                }
            }
        );
    });
}

// ===================== Проверка IP через HTTP-прокси =====================
// Используем plain-HTTP endpoint и HTTP/1.0, чтобы не возиться с chunked-телом.

function checkIp() {
    return new Promise((resolve) => {
        if (!xrayProc) return resolve(null);
        const socket = net.connect(HTTP_PROXY_PORT, '127.0.0.1', () => {
            socket.write(
                'GET http://ip-api.com/json HTTP/1.0\r\n' +
                'Host: ip-api.com\r\n' +
                'User-Agent: luna-vpn\r\n' +
                'Connection: close\r\n\r\n'
            );
        });
        let buf = '';
        socket.on('data', (d) => {
            buf += d.toString();
            if (buf.length > MAX_IP_RESP_BYTES) { socket.destroy(); resolve(null); }
        });
        socket.on('end', () => {
            const m = buf.match(/\{[\s\S]*\}/);
            if (m) { try { return resolve(JSON.parse(m[0])); } catch (_) {} }
            resolve(null);
        });
        socket.on('error', () => resolve(null));
        socket.setTimeout(9000, () => { socket.destroy(); resolve(null); });
    });
}

// ===================== HTTP-сервер =====================

function sendJson(res, code, obj) {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        let b = '';
        let len = 0;
        req.on('data', (c) => {
            len += c.length;
            if (len > MAX_BODY_BYTES) {
                req.destroy();
                reject(new Error('request body too large'));
                return;
            }
            b += c;
        });
        req.on('end', () => resolve(b));
        req.on('error', reject);
    });
}

// Защита от DNS-rebinding и CSRF: управляющие эндпоинты доступны только
// с самого localhost. Проверяем Host и (если есть) Origin.
function isLocalRequest(req) {
    const allowedHosts = ['127.0.0.1:' + PORT, 'localhost:' + PORT, '[::1]:' + PORT];
    const host = (req.headers.host || '').toLowerCase();
    if (!allowedHosts.includes(host)) return false;

    const origin = req.headers.origin;
    if (origin) {
        try {
            const o = new URL(origin);
            const okHost = ['127.0.0.1', 'localhost', '::1', '[::1]'].includes(o.hostname);
            if (!okHost || o.port !== String(PORT)) return false;
        } catch (_) {
            return false;
        }
    }
    return true;
}

const server = http.createServer(async (req, res) => {
    try {
        if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
            const html = await fs.promises.readFile(path.join(__dirname, 'index.html'));
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(html);
        }

        // Все /api/* — только с локального origin
        if (req.url.startsWith('/api/') && !isLocalRequest(req)) {
            return sendJson(res, 403, { error: 'forbidden' });
        }

        if (req.method === 'GET' && req.url === '/api/health') {
            return sendJson(res, 200, { xray: !!findXray(), running: !!xrayProc, country: currentCountry });
        }

        if (req.method === 'POST' && req.url === '/api/connect') {
            const body = await readBody(req);
            let country = null;
            try { country = JSON.parse(body || '{}').country; } catch (_) {}
            // Берём только известные ключи, иначе DEFAULT_LINK (защита от prototype lookup)
            if (country != null && !Object.prototype.hasOwnProperty.call(SERVERS, country)) {
                country = null;
            }
            stopXray();
            try {
                await startXray(country);
                return sendJson(res, 200, { ok: true, country });
            } catch (e) {
                stopXray();
                return sendJson(res, 500, { ok: false, error: e.message });
            }
        }

        if (req.method === 'POST' && req.url === '/api/disconnect') {
            stopXray();
            currentCountry = null;
            return sendJson(res, 200, { ok: true });
        }

        if (req.method === 'GET' && req.url === '/api/stats') {
            return sendJson(res, 200, await queryStats());
        }

        if (req.method === 'GET' && req.url === '/api/ip') {
            const info = await checkIp();
            return sendJson(res, 200, info || { error: 'no-proxy' });
        }

        sendJson(res, 404, { error: 'not found' });
    } catch (e) {
        sendJson(res, 500, { error: e.message });
    }
});

server.listen(PORT, '127.0.0.1', () => {
    const bin = findXray();
    console.log('======================================================');
    console.log(' Luna VPN backend запущен:  http://127.0.0.1:' + PORT);
    console.log(' SOCKS5 прокси:             127.0.0.1:' + SOCKS_PORT);
    console.log(' HTTP прокси:               127.0.0.1:' + HTTP_PROXY_PORT);
    console.log(' xray бинарник:             ' + (bin || 'НЕ НАЙДЕН — положи xray.exe рядом или задай XRAY_PATH'));
    if (DEFAULT_LINK === PLACEHOLDER_LINK) {
        console.log('------------------------------------------------------');
        console.log(' ВНИМАНИЕ: задан линк-заглушка. Подключение НЕ заработает.');
        console.log(' Задай реальный линк в переменной окружения LUNA_VLESS_LINK');
        console.log(' (и при желании LUNA_LINK_DE/NL/US/SG для отдельных стран).');
    }
    console.log('======================================================');
});

// Корректно гасим xray при выходе
function shutdown() { stopXray(); process.exit(0); }
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('exit', stopXray);
