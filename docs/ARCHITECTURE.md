# Nori 后端架构（Java 重写版）

> 状态：设计基线 v1（2026-10-31）。来源按可信度标注：**[实测]** = golden 三轮录制/进程实测；
> **[H]** = `NoriOSNew/HANDOFF.md`；**[U]** = 上游 `Nori.Web-master/docs/VERIFIED_PROTOCOL.md`；
> **[C]** = 社区/自建材料（仅作旁证）。

## 1. 一页速览：4 个进程 → 1 个进程

现状是四个进程接力（[H] §0.2）：

```
浏览器 ──► 4173 nori_proxy.js (Node)  ──► 4174 Nori.Web.exe (PyInstaller, 静态资源 + 游戏)
                │  ├── /v1/*    ──► 25503 n_llm.js (Node, 人格/记忆/LLM)
                │  └── /nori-tts ──► 8880 server.py (Python, Inworld TTS)
                └── /nori-*   自有业务（台词/摸头/语音队列/观测）
```

Java 版把**4173 这一层**整体替换，其余三个进程先原样保留（[H] §7.2「不要一上手拆微服务」）：

```
浏览器 ──► 4183 nori-backend (Spring Boot 4 + WebFlux)  ──► 4174 exe
                │  ├── /v1/*    ──► 25503 persona（先透传，后续可内聚）
                │  └── /nori-tts ──► 8880 TTS
                └── /nori-*   自建业务
```

**为什么是模块化单体**：所有玩法状态（台词进度、语音队列、生命周期、卡带）都围绕**同一个会话**，
拆进程只会把「一次状态变更」变成跨网络事务。等边界稳定再谈拆。

## 2. 组件职责与边界

| 组件 | 归属 | 职责 | 不做什么 |
|---|---|---|---|
| **gateway** | 我们 | HTTP 反向代理、WS 双向透传、二进制帧零拷贝、连接生命周期 | 不解析业务 JSON（除必要的路由/生命周期钩子） |
| **lifecycle** | 我们 | `phase/cart/ended`、换局软清空、`petGate` 四类闸门 | 不生产台词 |
| **chat** | 我们 | `/v1/*` 拦截、固定回应、`json_object` 一律放行 | 不实现 LLM |
| **script** | 我们 | beat 表、fact→hook→fire、`resetStory`、轮换 | 不合成语音 |
| **speech** | 我们 | 单队列串行、分句、9s 兜底、`qid/epoch`、预取 | 不切音频帧 |
| **tts** | 我们 | `/tts` 与 `/tts.stream` 客户端、WAV→32k、切片组帧 | 不决定说什么 |
| **touch** | 我们 | 摸头回执、fact 注入、闸门联动 | 不做 L2D 命中判定（前端做） |
| **content** | 我们 | 芯片/邮件/信号/工件闸门 | 不生成内容文本（内容在数据里） |
| **proactive** | 我们 | N3 主动触发 + 节流 + 互斥 | 不抢台词队列 |
| **persona** | 25503（外部） | 记忆卡、召回、LLM 出口 | — |
| **contract** | 我们 | 前端契约常量/DTO/帧编解码 | 不含任何业务分支 |
| **config** | 我们 | 三层 JSON 配置 + 密钥引用 | 不解释语义 |
| **obs** | 我们 | 结构化日志、`*stat` 指标 | 不参与决策 |

依赖方向铁律：`contract ← 所有业务包`；`runtime` / `config` / `obs` 不依赖业务包；
业务包之间只能通过事件（`runtime.Mailbox`）通信，**不允许互相 import 实现类**。

## 3. 三条主链路

### A. HTTP 反代（`/`、静态资源、`/nori-*`）

`浏览器 → gateway → (4174 | 自有 handler)`。规则：

1. 请求头/体**原样转发**，只重写 `Host`（[实测]：代理若保留客户端 Host，上游行为会变）；
2. `/nori-*` 由业务包接管，不进上游；
3. `/v1/*` 先给 `chat` 拦截器，未命中才转发 25503；
4. 静态资源（`/assets/*`、字体、音频）必须**流式转发**，不缓冲——golden 里 465 个路径多数是这类，
   一旦全缓冲，首屏会明显变慢。

### B. WS 主通道 `/api/arcade/web/v1`（文本，原样透传）

子协议固定：`["arcade.v1", "ticket.<ticket>"]`（[U]，[实测] 缺了会被上游拒绝 → 无限重连）。

**铁律：不 JSON 重序列化**（[H] §2.3）。前端对字段顺序与未知字段敏感，
解析只允许发生在「只读判断」场景（读 `type` / `cartridge_unmounted` 这类），
转发的永远是**原始字节**。

`lifecycle` 在这里挂只读钩子：`noteLifeFrame(rawText)` 提取 `plan_end` / `card_reveal` /
`cartridge_unmounted`，维护 `phase / cart / seq`；其余一律不说、不改。

### C. WS 媒体通道 `/api/arcade/web/v1/media`（二进制 PCM）

建连后客户端先发 `{"type":"open_media","grant":"<mediaGrant>"}`（[U]）。
之后下行帧结构见 `contract.ArcadeFrame`（48B 头 + PCM16LE @32k，5760 采样 = 180ms/帧）。

**关键：这层的帧是我们（代理）自己生成的**，不是上游发的（[实测] + [H] §3.4）：

```
整段模式： POST 8880/tts → WAV@48k → decode → resample 48k→32k → 切 5760 采样 → 组帧
流式模式： POST 8880/tts.stream → 裸 PCM@32k（省掉 resample）→ 切 5760 → 组帧
```

## 4. 卡带（cartridge）状态机

### 4.1 服务端视角

卡带是「游戏内的应用」。同一时刻可以挂多张，每张有自己的运行时版本与可见性栅栏：

```
world
 └─ mountedCartridges[]
      └─ cartridgeId (chat | chess | codenames | cakeduel | pictionary | …)
           └─ runtimes[]  { visibilityFenceId, visibleVersion, headVersion, state }
```

不变式（[U]）：

- `headVersion`：已提交状态的最新版本；`visibleVersion`：**已对玩家可见**的版本；
- 写操作走 `dispatch`，必须带 `expectedHeadVersion`（乐观锁）与 `requestId`；
- 提交后服务端广播 `runtime_transition{ version, transition.patches[], transition.events[] }`；
- 玩家看到状态需要 `advance_visibility_fence` → `visibility_fence_advanced_ack`；
- **`dispatch_ack` 必须带 `success/committed/committedVersion/headVersion/result`**（[U]），
  缺字段客户端静默丢弃 → 表现为「点了没反应」。

### 4.2 挂载/卸载（换局的锚点）

```
mount_cartridge   → cartridge_mounted{ runtimes }        → 客户端 ack: transition=created|already_mounted
unmount_cartridge → cartridge_unmounted                  → （无 ack 也合法）
```

**「换局」挂在 `cartridge_unmounted`，不是 `onGameEnd`**（[H] §3.1，[实测] 已验证）：

- `onGameEnd` = 这一局下完了 —— 她恰好在这时说收尾台词，挂它会吞掉那句；
- `cartridge_unmounted` = 他离开了这个游戏 —— 换游戏时旧卡带必先 unmount，一次覆盖两种情况。

卸载时的动作（语义：**当前句说完，其余丢弃**）：

1. `speech.softReset()`：保留正在播的那一句，丢弃队列里剩余；
2. **有条件**通报人格：只有 `dropped > 0` 才 `POST 25503 /game-note`（只记不答，不触发 LLM）；
3. 一次性闸门：`boot.completed` ⇒ `resetStory()` 清 beat 进度（台词每局都说），`@mem:` 保留。

### 4.3 与生命周期的耦合

`phase` 由帧流推导（`plan_end` / `card_reveal`），`ended` 由 `cartridge_unmounted` 推导。
`petGate` 四类闸门（`coldopen` / `corrupt` / `memory` / `down`）读的是**同一份状态**，
所以 `lifecycle` 必须是**单实例、单线程写**——这正是 `runtime.SessionLoop` 的存在理由。

## 5. 与 MF-Dust 上游的关系

上游 `Nori.Web`（MIT，package `nori-os-local-compatibility` 2.0.0）是**本地兼容层**：
它实现 Convex/Arcade 协议的一半（前端 + Rust 侧），**不含**人格、记忆、TTS 编排。

融合边界（本项目的核心判断）：

| 维度 | 跟随上游 | 跟随我们 |
|---|---|---|
| Arcade 协议/帧格式/消息字段 | ✅ [U] 唯一权威 | — |
| 前端 bundle | ✅ 新版本为准 | — |
| `/nori-*` 注入端点 | — | ✅ 自研（上游没有） |
| 台词 beat 表 | — | ✅ `nori_script_lines.json`（14 beat） |
| 人格/记忆/召回 | — | ✅ 25503 与我们的种子卡 |
| 剧情内容 | 上游 pack 略新但更薄 | ✅ 我们的 pack + beat 表 |

**版本现状**（[实测]）：上游 bundle `NormalApp` md5 `75b988ffff`（10-11）；
我们发行版 `9f5df6c4c4`（09-27 构建）——**我们的旧**。
golden 三轮录制于旧 bundle，标注为 baseline-A；换新版后需补录 baseline-B 做对照。

上游新增的 4 个 shim（`ai-provider-switch` / `tts-settings` / `ui-settings` / `wallpaper-mode`）
只操作 `localStorage`，**不引入新端点**（[实测] grep 全文无 fetch）。

## 6. Java 包结构与当前进度

```
net.siniplise.noriosbackend
├── app         ✅ 启动、Spring 装配、端口来自配置
├── config      ✅ 三层 JSON（默认/本机/env）+ SecretRef + 校验
├── contract    ✅ NoriPaths / NoriPorts / ArcadeFrame / ArcadeProtocol
├── obs         ✅ 日志（四字符级别、%LVL4 插件）
├── runtime     ✅ SessionLoop（每会话串行）+ Mailbox（子线程通信）
├── admin       ✅ GET /api/admin/config（管理器的读写契约）
├── tts         ✅ WavDecoder / Resampler / SpeechFramer      ⬜ TtsClient、预取
├── gateway     ⬜ HTTP 反代 + WS 双向 + 二进制帧转发
├── lifecycle   ⬜ phase/cart/ended、软清空、petGate
├── chat        ⬜ /v1 拦截、固定回应、json_object 放行
├── script      ⬜ beat 表、fact→hook→fire、resetStory
├── speech      ⬜ 队列、9s 兜底、qid/epoch
├── touch       ⬜ 摸头回执 + fact 注入
├── content     ⬜ 芯片/邮件/信号/工件闸门
├── proactive   ⬜ N3 触发 + 节流
├── persona     ⬜ 25503 客户端（后续可内聚为 memory）
├── memory      ⬜ 三层记忆 + 召回融合
├── save        ⬜ 存档事件日志 + 快照
└── store       ⬜ SQLite(WAL)
```

## 7. 状态落库（SQLite/WAL 草案）

| 表 | 键 | 内容 | 重建代价 |
|---|---|---|---|
| `script_state` | `beat_id` | 完成时间、轮换指针、`@mem` 标记 | 剧情重置 |
| `speech_queue` | `qid` | 待播句、`epoch`、`block_id` | 丢几句台词 |
| `dedupe` | `content_hash` | 2.5s 内容去重窗口 | 重复播一句 |
| `session` | `session_id` | `phase/cart/facts/ended` | 冷启动重来 |
| `tts_cache` | `text+voice` | 已合成 WAV/PCM | 重新调 TTS（花钱） |

写纪律沿用旧栈：**先读旧 → 算新 → 写 tmp → 原子替换**（[H] §6），SQLite 用事务 + `PRAGMA integrity_check`。

## 8. 关键不变式（违反即线上事故）

1. WS 文本帧**不得** JSON 重序列化；二进制帧按 `frameOffset` 零拷贝。
2. 媒体帧 `blockId` = **回复内**句子序号，唯一键是 `(messageId, blockId)`（[实测]：只按 blockId 分组会误判重复）。
3. 每个 block **恰好一个** `complete=true`，且必须在末块；中间帧带 complete 会让 worklet 提前收尾。
4. `chunkId` 在**一次回复内**跨句递增，回复之间重置；`seq` 在**整条连接**上单调递增。
5. `pong` 必须带 `serverId`，时间字段名是 `now`（[U]：写 `timestamp` 客户端认不出）。
6. `/nori-line` 是**客户端 ~706ms 轮询**的破坏性队列（[实测] 693/754 次，p50 706ms），不是服务端推送。
7. `/nori-spoken` 丢了必须走 9s 兜底，否则队列永久卡死。
8. 换局只在 `cartridge_unmounted` 触发；当前句不打断。
9. API KEY 只从环境变量读，缺则显式抛；JSON 里出现疑似真 key 直接拒绝启动。
10. 台词的流式开关由**前端**决定（`nori.streamMode` / `__NORI_BEAT_STREAM`），后端两种都要支持，默认整段。

## 9. 迁移顺序（每步都可回滚）

1. **gateway 只读镜像**：`/nori-*` 与静态资源全部转发 4174，WS 原样透传 → 页面能开、能玩；
2. **lifecycle 钩子**：只读解析帧，`/nori-lifestat` 与旧栈逐字段对齐；
3. **tts + speech**：先整段 WAV，接 9s 兜底；与旧栈影子对比帧序列；
4. **script + touch**：beat 表迁移，`/nori-touch` 闸门对齐；
5. **chat 拦截**：`/v1/*` 固定回应 + `json_object` 放行；
6. **persona/memory 内聚**（可选，最后）：先把 25503 当外部服务跑通再谈替换。

验收基线（[H] §9）：同一浏览器、同一存档，新旧两栈录制的帧序列差异为 0，
`/nori-line` 返回一致，TTS 首帧延迟不劣于旧栈。

---

## 日志与可观测性（开发期原则：宁可吵死，不要安静）

> 2026-10-31 用户定：**"满屏 ERROR/FATAL 比一点动静没有更安心，至少知道哪有问题。"**

背景很实在：这一天为了"她完全不动"，排查了两轮——最后发现是一个
`ArrayNode.asString()` 异常被 `catch` 成 **debug**，而 root level 是 INFO，
于是日志里**一行报错都没有**。功能悄悄失效比崩溃难查一百倍。

### 一、级别怎么选（严格按语义，别随手用）

| 级别 | 什么时候用 | 例子 |
|---|---|---|
| **CRTL**（`log.error(Critical.CRITICAL, …)`） | **代码 bug**：重试也不会好。必须带堆栈 | 帧处理抛未预期异常、转换失败、驱动内部异常 |
| **ERROR** | 可恢复的失败，但功能受损 | 上游 502、写库失败（有重试/降级） |
| **WARN** | 降级但不影响主流程 | 旁路观察失败（笔画少记一段、生命周期判定漏一次） |
| **INFO** | 状态变化与关键节点 | 挂载、轮到她、发出指令 |
| **DEBUG** | 高频细节 | 每帧记账、攒笔画 |

### 二、开发期把包全开 DEBUG

`log4j2.xml`：

```xml
<Logger name="net.siniplise" level="DEBUG" additivity="false">
    <AppenderRef ref="CONSOLE"/>
</Logger>
<Root level="INFO">…</Root>
```

- 只管**我们自己的包**，第三方（reactor/netty）仍是 INFO，不会刷屏。
- production 删掉这个 logger 即可。

### 三、前端通信入口一律兜底

`obs/logging/FatalGuard` 统一兜底，任何 `Throwable` 都记成 **CRTL** 并明确写
"已隔离、本次数据丢弃、连接/会话继续"——避免读日志的人误以为进程要挂。

已覆盖的入口：

| 入口 | 兜底方式 |
|---|---|
| `SessionRuntime.observeFrame`（下行帧总入口） | `FatalGuard.call` 包整个处理体 |
| `SessionRuntime.observeUpstreamFrame`（上行帧） | 同上 + `exceptionally` |
| `ArcadeWebSocketProxy` 下行 `handle` | 观察走 `FatalGuard.run`；**转换失败记 CRTL 后丢帧**（不断连接） |
| `ArcadeWebSocketProxy` 上行 `handle` | 同上 |
| `GameDriverService.observe` 的调用点 | CRTL（驱动异常都是 bug） |

### 四、三条教训（都踩过）

1. **`catch` 里只打 debug ≈ 没打**。要么 ERROR/WARN，要么别 catch。
2. **异步入 future 的异常会被丢掉**。`observeFrame` 返回 `CompletableFuture`，
   光在调用处 try/catch 抓不到——必须 `.exceptionally`。
3. **"关掉时不打日志"是设计缺陷**。功能未启用和功能坏掉，在外部看起来一模一样。
   所以开关状态、是否收到第一帧、每次跳过判定的**原因**，都要有日志。

