#!/usr/bin/env node
/**
 * WS 握手诊断：定位「4183 连不上 4174，但 4173（nori_proxy）连得上」的差异。
 *
 * 对同一个上游、同一个路径，逐个叠加变量，看哪一项会让握手失败：
 *   ① 纯净（对齐 nori_proxy）：无 Origin、关 permessage-deflate、单子协议
 *   ② 加 Origin（我们现在会透传浏览器的 Origin）
 *   ③ 开 permessage-deflate
 *   ④ Origin + deflate 都开（≈ 我们现状）
 *
 * 只读诊断：不改任何上游状态，连上就立刻关。
 *
 * 用法：node tools/diag_ws.js [url]
 */
'use strict';

const path = require('path');
const WS = require('C:/Users/sinip/WorkBuddy/NoriOSNew/node_modules/ws');

const URL_ = process.argv[2] || 'ws://127.0.0.1:4174/api/arcade/web/v1';
const ORIGIN = 'http://127.0.0.1:4183';

function attempt(label, opts, protocols) {
    return new Promise((resolve) => {
        const t0 = Date.now();
        let settled = false;
        const done = (ok, detail) => {
            if (settled) return;
            settled = true;
            const ms = Date.now() - t0;
            console.log(`${ok ? '✓ 成功' : '✗ 失败'}  [${String(ms).padStart(5)}ms]  ${label}${detail ? '  — ' + detail : ''}`);
            try { ws.terminate(); } catch (e) { /* ignore */ }
            resolve(ok);
        };

        let ws;
        try {
            ws = new WS(URL_, protocols === undefined ? ['arcade.v1'] : protocols, opts);
        } catch (e) {
            console.log(`✗ 构造失败  ${label}  — ${e.message}`);
            return resolve(false);
        }

        const timer = setTimeout(() => done(false, '超时 8s'), 8000);
        ws.on('open', () => {
            clearTimeout(timer);
            done(true, `proto=${ws.protocol || '(none)'}`);
        });
        ws.on('unexpected-response', (req, res) => {
            clearTimeout(timer);
            let body = '';
            res.on('data', (c) => { if (body.length < 400) body += c.toString(); });
            res.on('end', () => {
                const hdrs = JSON.stringify(res.headers);
                done(false, `HTTP ${res.statusCode} body=${JSON.stringify(body)} hdrs=${hdrs}`);
            });
            res.on('error', () => done(false, `HTTP ${res.statusCode}`));
        });
        ws.on('error', (e) => {
            clearTimeout(timer);
            done(false, e.message);
        });
    });
}

(async function main() {
    console.log(`上游: ${URL_}\n`);
    console.log('--- 逐项定位 ---');

    await attempt('① 纯净（对齐 nori_proxy）', { perMessageDeflate: false });

    await attempt('② 加 Origin（浏览器 Origin 透传）', {
        perMessageDeflate: false,
        headers: { origin: ORIGIN },
    });

    await attempt('③ 开 permessage-deflate', { perMessageDeflate: true });

    await attempt('④ Origin + deflate（≈ 当前实现）', {
        perMessageDeflate: true,
        headers: { origin: ORIGIN },
    });

    console.log('\n--- 子协议矩阵（重点：ticket 是不是准入门槛）---');
    await attempt('⑤ 只有 arcade.v1', { perMessageDeflate: false }, ['arcade.v1']);

    await attempt('⑥ arcade.v1 + ticket.dummy（假 ticket）', { perMessageDeflate: false },
        ['arcade.v1', 'ticket.dummy-not-valid']);

    await attempt('⑦ ticket.dummy 在前', { perMessageDeflate: false },
        ['ticket.dummy-not-valid', 'arcade.v1']);

    await attempt('⑧ 只要 ticket.dummy', { perMessageDeflate: false }, ['ticket.dummy-not-valid']);

    process.exit(0);
})();
