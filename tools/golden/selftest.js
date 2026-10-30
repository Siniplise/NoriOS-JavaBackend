#!/usr/bin/env node
/**
 * record_proxy.js 的自检（不碰 Nori，不碰 4173）。
 *
 * 做法：起一个假上游（HTTP + WS 回声，端口 4198）→ 起录制代理（4185 → 4198）→
 * 当作客户端依次走一遍 HTTP、WS 文本、WS 二进制 → 停录制 → 检查产物。
 *
 * 为什么要有它：WS 代理的握手头、二进制落盘、方向标记这些，写错时**不会报错**，
 * 只会安静地录不到东西。等真拿浏览器录半小时才发现，代价太大。
 *
 * 用法：node selftest.js
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { WebSocketServer, WebSocket } = require('ws');

const UP_PORT = 4198;
const PROXY_PORT = 4185;
const OUT = path.resolve(__dirname, '..', '..', 'golden', '_selftest');

function assert(cond, msg) {
  if (!cond) {
    console.error(`  ✗ ${msg}`);
    process.exitCode = 1;
    throw new Error(msg);
  }
  console.log(`  ✓ ${msg}`);
}

function waitFor(emitter, event) {
  return new Promise((resolve) => emitter.once(event, resolve));
}

let stage = 'init';
// 硬超时：自检卡住时至少能指出卡在哪一步（录不到东西又没报错，是最难查的情况）
const watchdog = setTimeout(() => {
  console.error(`\n自检硬超时（15s），卡在: ${stage}`);
  process.exit(2);
}, 15000);

async function main() {
  fs.rmSync(OUT, { recursive: true, force: true });

  // ── 假上游 ──
  const upServer = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ on: true, path: req.url }));
  });
  const upWss = new WebSocketServer({
    server: upServer,
    // 和 Nori 的上游一样：从客户端给的列表里选第一个（arcade.v1）
    handleProtocols: (protocols) => protocols.values().next().value,
  });
  upWss.on('connection', (ws) => {
    ws.on('message', (data, isBinary) => ws.send(data, { binary: isBinary }));
  });
  await new Promise((r) => upServer.listen(UP_PORT, '127.0.0.1', r));
  stage = 'upstream-listening'; console.log(`假上游已起: ${UP_PORT}`);

  // ── 录制代理 ──
  const proxy = spawn(process.execPath, [
    path.join(__dirname, 'record_proxy.js'),
    '--port', String(PROXY_PORT),
    '--upstream-port', String(UP_PORT),
    '--out', OUT,
  ], { stdio: ['ignore', 'pipe', 'pipe'] });

  proxy.stderr.on('data', (d) => process.stderr.write(`[proxy] ${d}`));
  await new Promise((resolve) => {
    proxy.stdout.on('data', (d) => {
      if (d.toString().includes('golden 录制中')) resolve();
    });
  });
  stage = 'proxy-listening'; console.log(`录制代理已起: ${PROXY_PORT} → ${UP_PORT}`);

  // ── HTTP ──
  const body = await new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:${PROXY_PORT}/nori-lifestat?since=0`, (res) => {
      let s = '';
      res.on('data', (c) => (s += c));
      res.on('end', () => resolve(s));
    }).on('error', reject);
  });

  stage = 'http-done';
  // ── WS：文本 + 二进制 ──
  // 带子协议，模拟 Nori 客户端真实的 `arcade.v1, ticket.<token>`：
  // 代理不把它透传上去，上游就会回「Server sent a subprotocol but none was requested」然后断开
  const requested = ['arcade.v1', 'ticket.eyJ1IjoiZ3Vlc3QifQ.sig'];
  const client = new WebSocket(`ws://127.0.0.1:${PROXY_PORT}/api/arcade/web/v1`, requested);
  const echoed = [];
  client.on('message', (data, isBinary) => echoed.push({ data, isBinary }));

  stage = 'ws-open-wait'; await waitFor(client, 'open');
  stage = 'ws-open';
  client.send('hello {not json}');                 // 故意不是 JSON：验证不会去 parse
  client.send(Buffer.from([0, 1, 2, 250, 251, 252]), { binary: true }); // 非 UTF-8 的二进制
  await new Promise((r) => setTimeout(r, 300));
  client.close();
  await waitFor(client, 'close');
  await new Promise((r) => setTimeout(r, 200));

  stage = 'ws-done';
  // ── 停录制，等它落 manifest ──
  // 用控制面接口而不是 kill：Windows 上 SIGTERM 会把进程直接杀掉，manifest 写不出来
  await new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:${PROXY_PORT}/__golden/stop`, (res) => {
      res.resume();
      res.on('end', resolve);
    }).on('error', reject);
  });
  await waitFor(proxy, 'exit');

  // ── 检查产物 ──
  console.log('\n检查产物：');
  const httpLines = fs.readFileSync(path.join(OUT, 'http.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  const wsLines = fs.readFileSync(path.join(OUT, 'ws.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  const manifest = JSON.parse(fs.readFileSync(path.join(OUT, 'manifest.json'), 'utf8'));

  assert(body.includes('"on":true'), 'HTTP 透传成功（响应体能原样回到客户端）');

  const httpRec = httpLines.find((r) => r.kind === 'http' && r.path.startsWith('/nori-lifestat'));
  assert(!!httpRec, 'HTTP 记录已写入');
  assert(httpRec.resStatus === 200, 'HTTP 状态码被记录');
  assert(httpRec.resText && httpRec.resText.includes('"on":true'), '文本响应体被内联记录');
  assert(typeof httpRec.tookMs === 'number', '记录了耗时 tookMs');

  assert(wsLines.some((r) => r.kind === 'ws-open'), 'WS 打开被记录');
  const wsOpen = wsLines.find((r) => r.kind === 'ws-open');
  assert(Array.isArray(wsOpen.protocols) && wsOpen.protocols.includes('arcade.v1'), '子协议列表被记录');
  const upOpen = wsLines.find((r) => r.kind === 'ws-upstream-open');
  assert(!!upOpen && upOpen.protocol === 'arcade.v1', '上游选中的子协议被透传（arcade.v1）');
  assert(client.protocol === 'arcade.v1', '客户端最终协商到 arcade.v1，没被代理掐断');
  const c2sText = wsLines.find((r) => r.kind === 'ws-msg' && r.dir === 'c2s' && !r.binary);
  assert(!!c2sText, 'WS 上行文本被记录');
  assert(c2sText.text === 'hello {not json}', '文本按原文保存（没有 JSON 重序列化）');
  assert(typeof c2sText.sha256 === 'string' && c2sText.sha256.length === 64, '文本附了原始字节 sha256');

  const c2sBin = wsLines.find((r) => r.kind === 'ws-msg' && r.dir === 'c2s' && r.binary);
  assert(!!c2sBin, 'WS 上行二进制被记录');
  assert(c2sBin.len === 6, '二进制长度正确');
  const blobPath = path.join(OUT, 'blobs', c2sBin.blob);
  assert(fs.existsSync(blobPath), '二进制落到 blobs/');
  const blob = fs.readFileSync(blobPath);
  assert(blob.equals(Buffer.from([0, 1, 2, 250, 251, 252])), '二进制字节完全一致（可含非 UTF-8）');

  assert(wsLines.filter((r) => r.kind === 'ws-msg' && r.dir === 's2c').length >= 2, '下行消息被记录');
  assert(echoed.length >= 2, '往返正常（回显回到了客户端）');

  assert(manifest.stats.httpByPath['/nori-lifestat'] === 1, 'manifest 统计了 HTTP 端点分布');
  assert(manifest.stats.wsBinaryMsgs >= 2, 'manifest 统计了二进制消息数');

  console.log(`\n全部通过。产物在 ${OUT}`);
  upServer.close();
  upWss.close();
  // 显式退出：自检脚本不需要守着事件循环，残留句柄只会让进程挂着（之前的看门狗就是被这个触发的）
  clearTimeout(watchdog);
  setTimeout(() => process.exit(0), 100);
}

main().catch((e) => {
  console.error('\n自检失败:', e.message);
  process.exit(1);
});
