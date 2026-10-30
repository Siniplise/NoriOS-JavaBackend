#!/usr/bin/env node
/**
 * golden 录制代理（HANDOFF §8 第 1 步）。
 *
 * 位置：浏览器 → 本代理(默认 4185) → 旧 Node 反代(4173) → 游戏本体(4174)
 *
 * 它只做两件事：**原样转发**、**原样记录**。
 *   · HTTP：方法/路径/请求头/请求体 → 状态码/响应头/响应体，双向都录。
 *   · WS  ：消息级录制（文本存原文 + 原始字节的 sha256；二进制落 blobs/*.bin），
 *           并记每次消息的 seq / ts(相对起录的毫秒) / gapMs（同连接内与上一条的间隔）。
 *
 * 为什么不做 JSON 重序列化：HANDOFF §2.3 说过前端对字段顺序与未知字段敏感，
 * 这里存的是**原始字节的摘要 + 原文**，回放时按原文写回，不做 parse/stringify。
 *
 * 用法：
 *   node record_proxy.js                        # 监听 4185，产物写 golden/<时间戳>/
 *   node record_proxy.js --port 4185 --upstream-port 4173 --out golden/raw
 *   浏览器打开 http://127.0.0.1:4185 （不是 4173！否则流量不经过这里）
 *   Ctrl+C 结束，会打印统计并落 manifest.json
 *
 * 注意：`nori-prompt-inject.js` 里 NORI_LLM_BASE 写死了 http://127.0.0.1:4173/v1，
 * 所以「浏览器 → LLM 出口」这条默认不经过本代理。要录 /v1 就在游戏里的「AI 模型与提示词」
 * 面板把 baseUrl 改成 http://127.0.0.1:4185/v1（只改 localStorage，不动前端文件）。
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

// ── 参数 ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const LISTEN_PORT = Number(arg('port', 4185));
const UPSTREAM_HOST = arg('upstream-host', '127.0.0.1');
const UPSTREAM_PORT = Number(arg('upstream-port', 4173));
const STAMP = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-');
// 默认落在仓库根的 golden/ 下，不跟着 cwd 漂（在 tools/golden 里直接跑也不会套两层）
const OUT_DIR = path.resolve(arg('out', path.join(__dirname, '..', '..', 'golden', STAMP)));
const BLOB_DIR = path.join(OUT_DIR, 'blobs');

fs.mkdirSync(BLOB_DIR, { recursive: true });

// ── 记录骨架 ────────────────────────────────────────────────────────────────
const T0 = Date.now();
const now = () => Date.now() - T0;

const httpLog = fs.createWriteStream(path.join(OUT_DIR, 'http.jsonl'), { flags: 'a' });
const wsLog = fs.createWriteStream(path.join(OUT_DIR, 'ws.jsonl'), { flags: 'a' });

let seq = 0;
const nextSeq = () => ++seq;

const stats = {
  httpByPath: {},
  wsByPath: {},
  httpBytes: 0,
  wsTextMsgs: 0,
  wsBinaryMsgs: 0,
  wsBinaryBytes: 0,
  errors: 0,
};

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

/** 写一行 JSONL。文本字段里的换行由 JSON.stringify 转义，天然一行一条。 */
function emit(stream, record) {
  stream.write(JSON.stringify(record) + '\n');
}

/** 二进制落盘，JSONL 里只留引用——避免把 base64 塞进单行 JSON 撑成几十 MB。 */
function writeBlob(id, side, buf) {
  const name = `${String(id).padStart(6, '0')}-${side}.bin`;
  fs.writeFileSync(path.join(BLOB_DIR, name), buf, { flush: true });
  return { blob: name, len: buf.length, sha256: sha256(buf) };
}

/** 这些头属于逐跳（hop-by-hop），转发时必须摘掉，否则上游连接行为会被污染。 */
const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

/** 凭据类头一律脱敏——golden 可能被拿去提交，key 绝不能落进去。 */
const SECRET_HEADERS = new Set(['authorization', 'cookie', 'set-cookie', 'x-api-key', 'api-key']);

function sanitizeHeaders(headers) {
  const out = {};
  for (const [k, v] of Object.entries(headers || {})) {
    out[k] = SECRET_HEADERS.has(k.toLowerCase()) ? '<redacted>' : v;
  }
  return out;
}

/** 这些头由 ws 库自己生成，转发会撞车（尤其 sec-websocket-key：上游会按我们的 key 算 accept，握手直接失败）。 */
const WS_HANDSHAKE_HEADERS = new Set([
  'sec-websocket-key', 'sec-websocket-version', 'sec-websocket-extensions',
  'sec-websocket-protocol', 'sec-websocket-accept',
]);

/**
 * 转发用的头：摘掉逐跳头与 WS 握手头，并把 Host / Origin 还原成上游认识的身份。
 *
 * 为什么必须还原 Origin：页面现在跑在代理端口上，浏览器/游戏内嵌页发的 Origin 是代理端口；
 * 上游如果做同源校验，会把「外来」请求挡掉。伪装成上游自己的地址，行为才和原来一致。
 */
function forwardHeaders(headers) {
  const out = {};
  const upstreamOrigin = `http://${UPSTREAM_HOST}:${UPSTREAM_PORT}`;
  for (const [k, v] of Object.entries(headers || {})) {
    const key = k.toLowerCase();
    if (HOP_BY_HOP.has(key) || WS_HANDSHAKE_HEADERS.has(key)) continue;
    if (key === 'host') {
      out[k] = `${UPSTREAM_HOST}:${UPSTREAM_PORT}`;
      continue;
    }
    if (key === 'origin' && String(v).includes(`:${LISTEN_PORT}`)) {
      out[k] = upstreamOrigin;
      continue;
    }
    out[k] = v;
  }
  return out;
}

/**
 * 客户端的子协议列表。
 *
 * Nori 的客户端会发 `Sec-WebSocket-Protocol: arcade.v1, ticket.<token>`，上游按这个选一个回。
 * 如果代理不把列表带上去，ws 会在上游返回协议时报
 * 「Server sent a subprotocol but none was requested」然后断开——表现就是游戏里连不上、
 * 无限重连。旧 Node 反代（nori_proxy.js:3377）也是把 protocols 透传的。
 */
function wsProtocols(headers) {
  const raw = headers['sec-websocket-protocol'];
  if (!raw) return [];
  return String(raw).split(',').map((s) => s.trim()).filter(Boolean);
}

/** 响应体是文本就内联（方便人看），二进制就落 blob。 */
function looksTextual(contentType) {
  if (!contentType) return false;
  const ct = contentType.toLowerCase();
  return ct.includes('json') || ct.startsWith('text/') || ct.includes('javascript')
    || ct.includes('xml') || ct.includes('x-www-form-urlencoded');
}

// ── HTTP 代理 ───────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  // 控制面：Windows 上 SIGTERM 不会触发 Node 的钩子（被直接杀掉，manifest 就丢了），
  // 所以留一个显式的优雅停止入口。这两个路径不转发给上游。
  if (req.url === '/__golden/stop') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, out: OUT_DIR }));
    setTimeout(shutdown, 50);
    return;
  }
  if (req.url === '/__golden/stats') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, elapsedMs: now(), stats }, null, 2));
    return;
  }

  const id = nextSeq();
  const started = now();

  const reqChunks = [];
  for await (const chunk of req) reqChunks.push(chunk);
  const reqBody = Buffer.concat(reqChunks);

  const upstream = http.request(
    {
      host: UPSTREAM_HOST,
      port: UPSTREAM_PORT,
      method: req.method,
      path: req.url,
      headers: forwardHeaders(req.headers),
    },
    (upRes) => {
      res.writeHead(upRes.statusCode, forwardHeaders(upRes.headers));
      const resChunks = [];
      upRes.on('data', (c) => {
        resChunks.push(c);
        res.write(c);
      });
      upRes.on('end', () => {
        res.end();
        const resBody = Buffer.concat(resChunks);
        recordHttp(id, started, req, reqBody, upRes, resBody);
      });
    },
  );

  upstream.on('error', (err) => {
    stats.errors++;
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`proxy error: ${err.message}`);
    emit(httpLog, {
      seq: id, ts: started, kind: 'http', error: String(err && err.message),
      method: req.method, path: req.url,
    });
  });

  upstream.end(reqBody);
});

function recordHttp(id, started, req, reqBody, upRes, resBody) {
  const ct = upRes.headers['content-type'] || '';
  const pathKey = req.url.split('?')[0];
  stats.httpByPath[pathKey] = (stats.httpByPath[pathKey] || 0) + 1;
  stats.httpBytes += resBody.length;

  const record = {
    seq: id,
    ts: started,
    kind: 'http',
    method: req.method,
    path: req.url,
    reqHeaders: sanitizeHeaders(req.headers),
    resStatus: upRes.statusCode,
    resHeaders: sanitizeHeaders(upRes.headers),
    tookMs: now() - started,
  };

  if (reqBody.length) {
    record.reqLen = reqBody.length;
    record.reqSha256 = sha256(reqBody);
    if (looksTextual(req.headers['content-type'])) record.reqText = reqBody.toString('utf8');
    else Object.assign(record, { reqBody: writeBlob(id, 'req', reqBody) });
  }

  if (resBody.length) {
    record.resLen = resBody.length;
    record.resSha256 = sha256(resBody);
    if (looksTextual(ct)) record.resText = resBody.toString('utf8');
    else Object.assign(record, { resBody: writeBlob(id, 'res', resBody) });
  }

  emit(httpLog, record);
}

// ── WebSocket 代理 ──────────────────────────────────────────────────────────
// handleProtocols 必须显式给：客户端会带 `arcade.v1`，服务端若不选，握手结果和上游不一致，
// 游戏侧会当成连接失败。这里选客户端列表里的第一个，行为与旧 Node 反代（nori_proxy.js）一致。
const wss = new WebSocketServer({
  noServer: true,
  handleProtocols: (protocols) => {
    const first = protocols.values().next().value;
    return first === false ? false : first;
  },
});

server.on('upgrade', (req, socket, head) => {
  wss.handleUpgrade(req, socket, head, (client) => proxyWs(client, req));
});

function proxyWs(client, req) {
  const wsPath = req.url;
  const connId = nextSeq();
  const perConn = { lastC2s: null, lastS2c: null };
  const protocols = wsProtocols(req.headers);

  stats.wsByPath[wsPath] = (stats.wsByPath[wsPath] || 0) + 1;
  emit(wsLog, {
    seq: connId, ts: now(), kind: 'ws-open', path: wsPath,
    protocols,
    headers: sanitizeHeaders(req.headers),
  });
  console.log(`[ws] open ${wsPath} protocols=${protocols.join(',') || '-'}`);

  const upstream = new WebSocket(
    `ws://${UPSTREAM_HOST}:${UPSTREAM_PORT}${wsPath}`,
    protocols.length ? protocols : undefined,
    {
      headers: forwardHeaders(req.headers),
      // 和旧 Node 反代保持一致：不协商 permessage-deflate，避免压缩层改变帧边界
      perMessageDeflate: false,
    },
  );

  const pending = [];

  client.on('message', (data, isBinary) => {
    recordWs('c2s', wsPath, connId, data, isBinary, perConn);
    if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary: isBinary });
    else pending.push([data, isBinary]);
  });

  upstream.on('open', () => {
    emit(wsLog, {
      seq: nextSeq(), ts: now(), kind: 'ws-upstream-open', path: wsPath,
      protocol: upstream.protocol || '',
    });
    for (const [data, isBinary] of pending.splice(0)) upstream.send(data, { binary: isBinary });
  });

  upstream.on('message', (data, isBinary) => {
    recordWs('s2c', wsPath, connId, data, isBinary, perConn);
    if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary });
  });

  const closeBoth = (side, code, reason) => {
    emit(wsLog, {
      seq: nextSeq(), ts: now(), kind: `ws-close-${side}`, path: wsPath,
      code, reason: reason ? reason.toString() : '',
    });
    try { client.close(code, reason); } catch { /* 已经关了 */ }
    try { upstream.close(code, reason); } catch { /* 已经关了 */ }
  };

  client.on('close', (code, reason) => closeBoth('client', code, reason));
  upstream.on('close', (code, reason) => closeBoth('upstream', code, reason));

  const onErr = (side) => (err) => {
    stats.errors++;
    // 同时打到控制台：不然只能翻 jsonl 才发现「游戏里连不上」的真实原因
    console.error(`[ws] error ${side} ${wsPath}: ${err && err.message}`);
    emit(wsLog, { seq: nextSeq(), ts: now(), kind: `ws-error-${side}`, path: wsPath, error: String(err && err.message) });
    try { client.terminate(); } catch { /* ignore */ }
    try { upstream.terminate(); } catch { /* ignore */ }
  };
  client.on('error', onErr('client'));
  upstream.on('error', onErr('upstream'));
}

function recordWs(direction, wsPath, connId, data, isBinary, perConn) {
  const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const lastKey = direction === 'c2s' ? 'lastC2s' : 'lastS2c';
  const gapMs = perConn[lastKey] === null ? null : now() - perConn[lastKey];
  perConn[lastKey] = now();

  const record = {
    seq: nextSeq(),
    ts: now(),
    kind: 'ws-msg',
    conn: connId,
    path: wsPath,
    dir: direction,
    binary: isBinary,
    len: buf.length,
    gapMs,
  };

  if (isBinary) {
    stats.wsBinaryMsgs++;
    stats.wsBinaryBytes += buf.length;
    Object.assign(record, writeBlob(record.seq, direction, buf));
  } else {
    stats.wsTextMsgs++;
    record.text = buf.toString('utf8');
    record.sha256 = sha256(buf);
  }

  emit(wsLog, record);
}

// ── 启动与收尾 ──────────────────────────────────────────────────────────────
server.listen(LISTEN_PORT, '127.0.0.1', () => {
  console.log(`golden 录制中: http://127.0.0.1:${LISTEN_PORT}  →  ${UPSTREAM_HOST}:${UPSTREAM_PORT}`);
  console.log(`产物目录: ${OUT_DIR}`);
  console.log('浏览器请打开 4185 那个地址；结束按 Ctrl+C。');
});

let closing = false;
function shutdown() {
  if (closing) return;
  closing = true;

  const manifest = {
    startedAt: new Date(Date.now() - now()).toISOString(),
    endedAt: new Date().toISOString(),
    durationMs: now(),
    listenPort: LISTEN_PORT,
    upstream: `http://${UPSTREAM_HOST}:${UPSTREAM_PORT}`,
    stats,
    files: {
      http: 'http.jsonl',
      ws: 'ws.jsonl',
      blobs: 'blobs/',
    },
  };
  fs.writeFileSync(path.join(OUT_DIR, 'manifest.json'), JSON.stringify(manifest, null, 2), { flush: true });

  console.log('\n=== 录制结束 ===');
  console.log(`时长 ${(manifest.durationMs / 1000).toFixed(1)}s，HTTP ${Object.values(stats.httpByPath).reduce((a, b) => a + b, 0)} 条，` +
    `WS 文本 ${stats.wsTextMsgs} 条 / 二进制 ${stats.wsBinaryMsgs} 条（${(stats.wsBinaryBytes / 1024).toFixed(0)} KB）`);
  console.log('HTTP 端点分布：');
  for (const [k, v] of Object.entries(stats.httpByPath).sort((a, b) => b[1] - a[1])) {
    console.log(`  ${String(v).padStart(5)}  ${k}`);
  }
  if (stats.errors) console.log(`错误 ${stats.errors} 次（见 jsonl 里的 error 字段）`);
  console.log(`产物：${OUT_DIR}`);

  httpLog.end();
  wsLog.end();
  setTimeout(() => process.exit(0), 200);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
