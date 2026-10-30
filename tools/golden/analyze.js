#!/usr/bin/env node
/**
 * golden 轨迹分析器：把录下来的 http.jsonl / ws.jsonl 变成「能拿去写 Java 的结论」。
 *
 * 输出四块：
 *   A 契约覆盖 —— HANDOFF §2.2 的 16 个端点，哪些录到了、哪些没触发
 *   B WS 消息分布 —— 按 type / channel 聚合，看清游戏与后台到底在传什么
 *   C 二进制帧 —— 按 §2.4 的 frameFor 结构解析，验证 blockId/chunkId 连续性
 *   D 语音链路 —— /nori-tts 走的是整段还是 stream=1、耗时与体积；文本→首帧延迟（9s 兜底的标定依据）
 *
 * 用法：node analyze.js [轨迹目录]        # 默认取 golden/ 下最新的一个
 */

'use strict';

const fs = require('fs');
const path = require('path');

// ── HANDOFF §2.2 / §2.3 的契约清单 ──────────────────────────────────────────
const CONTRACT_HTTP = [
  ['GET', '/nori-line', '台词队列（破坏性）'],
  ['POST', '/nori-spoken', '播放回报'],
  ['POST', '/nori-tts', 'TTS（?stream=1 分块 PCM）'],
  ['POST', '/nori-touch', '摸头'],
  ['GET', '/nori-lifestat', '生命周期快照'],
  ['GET', '/nori-hubstat', '子系统计数'],
  ['GET', '/nori-splicestat', '子系统计数'],
  ['GET', '/nori-n3stat', '子系统计数'],
  ['GET', '/nori-qstat', '子系统计数'],
  ['GET', '/nori-obsstat', '子系统计数'],
  ['GET', '/nori-llmstat', '子系统计数'],
  ['GET', '/nori-chip', '芯片文本'],
  ['POST', '/nori-world-gen', '世界生成'],
  ['GET', '/nori-debug', '诊断包'],
  ['POST', '/v1/chat/completions', 'LLM 出口（OpenAI 格式）'],
  ['GET', '/v1/models', 'LLM 模型列表'],
];

// ── 工具 ────────────────────────────────────────────────────────────────────
const readJsonl = (file) => {
  if (!fs.existsSync(file)) return [];
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .filter((l) => l.trim())
    .map((l) => {
      try { return JSON.parse(l); } catch { return null; }
    })
    .filter(Boolean);
};

const pct = (arr, p) => {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
};

const fmtMs = (v) => (v === null || v === undefined ? '-' : `${Math.round(v)}ms`);

/** §2.4 frameFor：u8,u8,u16LE flags,u32LE seq,u32LE blockId,u32LE chunkId,[16]op,[16]msg,PCM */
function parseFrame(buf) {
  if (buf.length < 48) return { error: `帧太短: ${buf.length}B` };
  const flags = buf.readUInt16LE(2);
  return {
    flags,
    isComplete: (flags & 1) === 1,
    seq: buf.readUInt32LE(4),
    blockId: buf.readUInt32LE(8),
    chunkId: buf.readUInt32LE(12),
    pcmBytes: buf.length - 48,
    op: buf.subarray(16, 32).toString('hex'),
    msg: buf.subarray(32, 48).toString('hex'),
  };
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
function pickDir() {
  const arg = process.argv[2];
  if (arg) return path.resolve(arg);
  const base = path.resolve(__dirname, '..', '..', 'golden');
  const dirs = fs
    .readdirSync(base, { withFileTypes: true })
    .filter((d) => d.isDirectory() && !d.name.startsWith('_'))
    .map((d) => ({ name: d.name, mtime: fs.statSync(path.join(base, d.name)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  if (!dirs.length) throw new Error('golden/ 下没有轨迹目录');
  return path.join(base, dirs[0].name);
}

const dir = pickDir();
const http = readJsonl(path.join(dir, 'http.jsonl'));
const ws = readJsonl(path.join(dir, 'ws.jsonl'));
const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
const blobPath = (name) => path.join(dir, 'blobs', name);

console.log(`轨迹: ${dir}`);
console.log(`时长: ${(manifest.durationMs / 1000).toFixed(1)}s\n`);

// ── A 契约覆盖 ──────────────────────────────────────────────────────────────
console.log('══ A. 契约覆盖（HANDOFF §2.2）══');
const httpCalls = http.filter((r) => r.kind === 'http' && typeof r.resStatus === 'number');
const failedCalls = http.filter((r) => r.kind === 'http' && typeof r.resStatus !== 'number');
const byPath = new Map();
for (const r of httpCalls) {
  const p = r.path.split('?')[0];
  if (!byPath.has(p)) byPath.set(p, []);
  byPath.get(p).push(r);
}

const covered = [];
const missing = [];
for (const [method, p, desc] of CONTRACT_HTTP) {
  const hits = byPath.get(p) || [];
  const matched = hits.filter((h) => h.method === method);
  if (matched.length) {
    covered.push(p);
    const statuses = [...new Set(matched.map((h) => h.resStatus))].join(',');
    const bad = matched.filter((h) => h.resStatus >= 400);
    const flag = bad.length === matched.length ? '⚠ 全失败' : bad.length ? `⚠ ${bad.length} 次失败` : '';
    console.log(`  ✓ ${p.padEnd(24)} ${String(matched.length).padStart(4)} 次  status=${statuses} ${flag}  ${desc}`);
    if (bad.length) {
      const why = (bad[0].resText || '').split('\n')[0].slice(0, 90);
      console.log(`      失败原因: ${why}`);
    }
  } else {
    missing.push(p);
    console.log(`  ✗ ${p.padEnd(24)} ${hits.length ? '只有 ' + hits.map((h) => h.method).join(',') : '未触发'}  ${desc}`);
  }
}
const extra = [...byPath.keys()].filter(
  (p) => !CONTRACT_HTTP.some(([, cp]) => cp === p) && (p.startsWith('/nori') || p.startsWith('/v1') || p.startsWith('/api')),
);
if (extra.length) {
  console.log('  其它业务路径：');
  for (const p of extra) console.log(`    · ${p.padEnd(24)} ${byPath.get(p).length} 次`);
}
console.log(`  覆盖 ${covered.length}/${CONTRACT_HTTP.length}\n`);

// ── B WS 消息分布 ───────────────────────────────────────────────────────────
console.log('══ B. WS 消息分布 ══');
const msgs = ws.filter((r) => r.kind === 'ws-msg');
const tally = (keyFn) => {
  const m = new Map();
  for (const r of msgs) {
    const k = keyFn(r);
    m.set(k, (m.get(k) || 0) + 1);
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
};

console.log(`  总计 ${msgs.length} 条（上行 ${msgs.filter((m) => m.dir === 'c2s').length} / 下行 ${msgs.filter((m) => m.dir === 's2c').length}）`);
console.log('  按 type：');
for (const [k, v] of tally((r) => {
  if (r.binary) return 'binary';
  try { return JSON.parse(r.text).type || '(no type)'; } catch { return '(非 JSON)'; }
}).slice(0, 12)) console.log(`    ${String(v).padStart(4)}  ${k}`);

console.log('  下行按 channel：');
for (const [k, v] of tally((r) => {
  if (r.binary || r.dir !== 's2c') return null;
  try {
    const o = JSON.parse(r.text);
    return o.channel || o.type;
  } catch { return null; }
}).filter(([k]) => k && k !== 'null').slice(0, 10)) console.log(`    ${String(v).padStart(4)}  ${k}`);
console.log('');

// ── C 二进制帧 ──────────────────────────────────────────────────────────────
console.log('══ C. 二进制音频帧（§2.4 frameFor）══');
const binaries = msgs.filter((m) => m.binary);
if (!binaries.length) {
  console.log('  没有二进制帧——这一轮音频没走 WS media 通道\n');
} else {
  const frames = [];
  for (const b of binaries) {
    const file = b.blob && blobPath(b.blob);
    if (!file || !fs.existsSync(file)) continue;
    frames.push({ ts: b.ts, dir: b.dir, conn: b.conn, ...parseFrame(fs.readFileSync(file)) });
  }
  // blockId 是「每个连接内」的句子序号，两个通道各自从 0 开始，必须带上 conn 才能判连续性
  const blocks = new Map();
  for (const f of frames) {
    const key = `${f.conn}/${f.blockId}`;
    if (!blocks.has(key)) blocks.set(key, []);
    blocks.get(key).push(f);
  }
  console.log(`  ${frames.length} 帧 / ${blocks.size} 个 (连接,blockId) 组合 / PCM 合计 ${(frames.reduce((a, f) => a + f.pcmBytes, 0) / 1024).toFixed(0)} KB`);
  for (const [blockId, fs2] of [...blocks.entries()]) {
    const chunkIds = fs2.map((f) => f.chunkId);
    const contiguous = chunkIds.every((c, i) => i === 0 || c === chunkIds[i - 1] + 1);
    const complete = fs2.some((f) => f.isComplete);
    console.log(`    block ${String(blockId).padStart(3)}: ${fs2.length} 块, chunk ${chunkIds[0]}..${chunkIds[chunkIds.length - 1]}` +
      `${contiguous ? ' 连续' : ' ⚠ 不连续'}, ${complete ? '有 complete 标记' : '⚠ 无 complete'}, ` +
      `${(fs2.reduce((a, f) => a + f.pcmBytes, 0) / 1024).toFixed(0)} KB`);
  }
  console.log('');
}

// ── D 语音链路与延迟 ────────────────────────────────────────────────────────
console.log('══ D. 语音链路与延迟 ══');
const tts = (byPath.get('/nori-tts') || []).map((r) => ({
  stream: r.path.includes('stream=1'),
  status: r.resStatus,
  ct: (r.resHeaders && r.resHeaders['content-type']) || '',
  tookMs: r.tookMs,
  len: r.resLen || 0,
  ts: r.ts,
}));
if (!tts.length) {
  console.log('  /nori-tts 未触发');
} else {
  const streamed = tts.filter((t) => t.stream);
  console.log(`  /nori-tts ${tts.length} 次（stream=1: ${streamed.length} / 整段: ${tts.length - streamed.length}）`);
  for (const t of tts.slice(0, 6)) {
    console.log(`    ${t.stream ? 'stream' : '整段  '} ${String(t.len).padStart(7)}B  ${String(t.tookMs).padStart(6)}ms  ${t.ct}`);
  }
  console.log(`  整段耗时: p50=${fmtMs(pct(tts.map((t) => t.tookMs), 50))} p90=${fmtMs(pct(tts.map((t) => t.tookMs), 90))}`);
  console.log(`  首字节≈总时长（整段模式的特征）: ${tts.filter((t) => !t.stream && t.tookMs > 1000).length} 次超过 1s`);
}

const spoken = byPath.get('/nori-spoken') || [];
if (spoken.length) {
  console.log(`  /nori-spoken ${spoken.length} 次，间隔：${spoken.slice(1).map((s, i) => s.ts - spoken[i].ts).map((v) => Math.round(v)).join(', ')} ms`);
}

const line = (byPath.get('/nori-line') || []).sort((a, b) => a.ts - b.ts);
if (line.length > 2) {
  const gaps = line.slice(1).map((r, i) => r.ts - line[i].ts);
  console.log(`  /nori-line 轮询 ${line.length} 次，间隔 p50=${fmtMs(pct(gaps, 50))} p90=${fmtMs(pct(gaps, 90))}`);
  const nonEmpty = line.filter((r) => r.resText && !/"lines"\s*:\s*\[\s*\]/.test(r.resText));
  console.log(`    其中非空返回 ${nonEmpty.length} 次`);
}

// 文本 → 首帧：用「最近一条 s2c 文本」到「紧随其后的第一个二进制帧」的差值近似
if (binaries.length) {
  const lat = [];
  for (const b of binaries) {
    const prevText = [...msgs].filter((m) => !m.binary && m.dir === 's2c' && m.ts <= b.ts).pop();
    if (prevText && b.ts - prevText.ts < 30_000) lat.push(b.ts - prevText.ts);
  }
  if (lat.length) {
    console.log(`  文本→首帧延迟（近似）: n=${lat.length} p50=${fmtMs(pct(lat, 50))} p90=${fmtMs(pct(lat, 90))} max=${fmtMs(Math.max(...lat))}`);
  }
}

// ── 汇总落盘 ────────────────────────────────────────────────────────────────
const summary = {
  dir,
  durationSec: Math.round(manifest.durationMs / 1000),
  coverage: { covered, missing, total: CONTRACT_HTTP.length },
  ws: {
    total: msgs.length,
    c2s: msgs.filter((m) => m.dir === 'c2s').length,
    s2c: msgs.filter((m) => m.dir === 's2c').length,
    binary: binaries.length,
  },
  httpPaths: Object.fromEntries([...byPath.entries()].map(([k, v]) => [k, v.length])),
  failedCalls: failedCalls.map((r) => ({ path: r.path, error: r.error })),
};
fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify(summary, null, 2), { flush: true });
console.log(`\n汇总已写入 ${path.join(dir, 'summary.json')}`);
