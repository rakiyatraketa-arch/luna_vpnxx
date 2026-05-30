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
const { spawn, spawnSync } = require('child_process');

// ===================== Конфигурация =====================

const PORT = 8080;            // веб-интерфейс
const SOCKS_PORT = 1080;      // SOCKS5 для приложений
const HTTP_PROXY_PORT = 1081; // HTTP-прокси (используется и для проверки IP)
const API_PORT = 10085;       // gRPC API xray для статистики

// Основной (рабочий) линк. Все локации по умолчанию указывают на него,
// чтобы подключение всегда работало. Впиши сюда свои реальные линки для
// других стран — и дропдаун станет по-настоящему мультисерверным.
const DEFAULT_LINK = 'vless://dda41cb1-c9e9-4fb0-8ef8-5cf051d55003@finlandbox.space:443?security=reality&encryption=none&pbk=PXtzIrCwLrvgGHBRqZBB-mPOUvlwWiPbuGWsoloxDjc&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.max.ru&sid=74#dasd';

const SERVERS = {
    'Германия (Быстрый)': DEFAULT_LINK,
    'Нидерланды':         DEFAULT_LINK, // ← замени на свой линк для NL
    'США (Нью-Йорк)':     DEFAULT_LINK, // ← замени на свой линк для US
    'Сингапур':           DEFAULT_LINK, // ← замени на свой линк для SG
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
    if (xrayProc) {
        try { xrayProc.kill(); } catch (_) {}
        xrayProc = null;
    }
}

function startXray(country) {
    return new Promise((resolve, reject) => {
        const bin = findXray();
        if (!bin) {
            return reject(new Error('Бинарник xray не найден. Положи xray.exe рядом с server.js или задай XRAY_PATH.'));
        }
        const link = SERVERS[country] || DEFAULT_LINK;
        let cfg;
        try {
            cfg = buildConfig(parseVless(link));
        } catch (e) {
            return reject(new Error('Не удалось разобрать VLESS-ссылку: ' + e.message));
        }
        fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2));

        const proc = spawn(bin, ['run', '-c', CONFIG_PATH]);
        xrayProc = proc;
        currentCountry = country;

        let settled = false;
        const finishOk = () => { if (!settled) { settled = true; resolve(); } };
        const finishErr = (e) => { if (!settled) { settled = true; reject(e); } };

        const onData = (d) => {
            const s = d.toString();
            process.stdout.write('[xray] ' + s);
            if (/started/i.test(s)) finishOk();
            if (/failed to start|panic|config error/i.test(s)) finishErr(new Error(s.trim().split('\n').pop()));
        };
        proc.stdout.on('data', onData);
        proc.stderr.on('data', onData);
        proc.on('error', finishErr);
        proc.on('exit', (code) => {
            if (xrayProc === proc) xrayProc = null;
            finishErr(new Error('xray завершился с кодом ' + code));
        });

        // запасной путь: если явного "started" нет — считаем запущенным через 1.8с
        setTimeout(finishOk, 1800);
    });
}

// ===================== Статистика (xray api) =====================

function queryStats() {
    const bin = findXray();
    if (!bin || !xrayProc) return { up: 0, down: 0 };
    const r = spawnSync(bin, ['api', 'statsquery', '--server=127.0.0.1:' + API_PORT], { encoding: 'utf8' });
    if (r.status !== 0 || !r.stdout) return { up: 0, down: 0 };
    try {
        const data = JSON.parse(r.stdout);
        let up = 0, down = 0;
        for (const s of (data.stat || [])) {
            if (s.name === 'outbound>>>proxy>>>traffic>>>uplink') up = Number(s.value || 0);
            if (s.name === 'outbound>>>proxy>>>traffic>>>downlink') down = Number(s.value || 0);
        }
        return { up, down };
    } catch (_) {
        return { up: 0, down: 0 };
    }
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
        socket.on('data', (d) => { buf += d.toString(); });
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
    return new Promise((resolve) => {
        let b = '';
        req.on('data', (c) => { b += c; });
        req.on('end', () => resolve(b));
    });
}

const server = http.createServer(async (req, res) => {
    try {
        if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
            const html = fs.readFileSync(path.join(__dirname, 'index.html'));
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(html);
        }

        if (req.method === 'GET' && req.url === '/api/health') {
            return sendJson(res, 200, { xray: !!findXray(), running: !!xrayProc, country: currentCountry });
        }

        if (req.method === 'POST' && req.url === '/api/connect') {
            const body = await readBody(req);
            let country = null;
            try { country = JSON.parse(body || '{}').country; } catch (_) {}
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
            return sendJson(res, 200, queryStats());
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
    console.log('======================================================');
});

// Корректно гасим xray при выходе
function shutdown() { stopXray(); process.exit(0); }
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('exit', stopXray);
