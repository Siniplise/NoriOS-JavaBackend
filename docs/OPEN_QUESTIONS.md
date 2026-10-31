# 疑点清单（Open Questions）

> 规则：**这里只放"还没被证据钉死"的判断**。每条都要有位置、现象、初步判断、以及"怎么才能定论"。
> 一旦有实测/文档定论，就移到 `ARCHITECTURE.md` 或项目记忆里，并从本表删除，避免它变成传说。
>
> 最后更新：2026-10-31 03:0x ｜ 悬而未决：13 条

| # | 位置 | 一句话 | 严重度 |
|---|---|---|---|
| Q1 | tts / speech | 整段 TTS 耗时 1.2–3.7s，但 WS 首帧 275ms，对不上 | 中 |
| Q2 | script / lifecycle | beat 状态并发写可能丢 `done` | **高** |
| Q3 | speech | `/nori-line` 破坏性队列在多线程下可能丢行/重取 | **高** |
| Q4 | speech | 9s 兜底与 `/nori-spoken` 回报的竞态 | **高** |
| Q5 | tts / gateway | 并发合成时 `seq` 与发送顺序可能不一致 | **高** |
| Q6 | gateway | 二进制帧零拷贝的 buffer 寿命（use-after-free） | **高** |
| Q7 | gateway/media | `open_media` 的 grant 校验被忽略 | 中 |
| Q8 | lifecycle | `pong.now` 的数值语义未验证 | 低 |
| Q9 | lifecycle | `world_joined.facts` 不能本地补，否则冷开场不播 | **高** |
| Q10 | gateway | `outboundFix` 改写策略若不照抄，reveal 与配音错位 | 中 |
| Q11 | contract | `/nori-world-gen` 实测 GET，文档写 POST | 低 |
| Q12 | gateway | `nori.ai.config` 是我们自研消息，不在上游协议里 | 中 |
| Q13 | app/config | 环境变量 `SERVER_*` 会污染 Boot 属性 | 中 |
| Q14 | gateway/media | 新连接顶掉旧音频出口，多开窗口会互相踢 | 中 |
| Q15 | gateway/tts | 若上游也发媒体帧，会与我们生成的 seq 冲突 | 中 |
| Q16 | script | 台词表路径相对 cwd，换个工作目录就静默空表 | 中 |
| Q17 | chat | `/v1/models` 未实现（会 404，旧栈是转发人格服务） | 低 |
| Q18 | chat | `stream:true` 命中时回的是整段 JSON，客户端行为未验 | 中 |
| Q19 | persona | 记忆写入是 fire-and-forget，停机时可能丢在途请求 | 低 |
| Q20 | save | 存档里的 `bundle` 现在是 `unknown`（没做启动探测） | 低 |
| Q21 | save | ~~存档只存不读~~ → 已补 `restoreFrom`（结案） | — |
| Q22 | cartridge | `gesture.*` 只在点开游戏时发一次，重开同一游戏是否重发未验 | 中 |
| Q23 | cartridge | `runtimes.state` 字段语义未知，未使用 | 低 |
| Q24 | cartridge | 多开窗口时 cartridgeId 会互相覆盖（单会话假设） | 低 |
| Q25 | cartridge | `boot.completed` 与 `cartridge_unmounted` 的先后顺序未验 | 中 |

---

## Q1 · 整段 TTS 耗时与首帧延迟对不上

- **位置**：`tts` 客户端 ↔ `speech` 帧推送
- **现象**：golden 第二轮 `/nori-tts` 四次耗时 1.22/1.27/2.35/3.70s（整段 WAV@48k）；同一时段的 WS 二进制帧，
  「最近一条 s2c 文本 → 首个二进制帧」只有 275ms（p90 442ms）。
- **初步判断**：这两组帧**不是**那四次 TTS 的产物。可能是：① 前端对同一句有本地缓存/重放；
  ② 帧来自另一条链路（游戏音效/manifold）；③ 录制时 `/nori-tts` 与 `streamSpeech` 不是一一对应。
- **怎么定论**：一次「只聊天、不开任何游戏」的定向录制，把 `/nori-tts` 次数与 block 数对账；
  或在 `SpeechFramer` 上加 first-frame 时间戳，与 `/nori-tts` 的 `tookMs` 直接相减。

## Q2 · beat 状态并发写

- **位置**：`script`（beat 完成标记）+ `lifecycle`（换局 `resetStory`）
- **现象**：旧栈是 Node 单事件循环，`done[id]=ts` 与 `resetStory()` 天然串行。Java 侧若帧转发线程与
  HTTP 线程各自写状态，会丢标记或把刚清空的表又写回一条。
- **初步判断**：这是**架构级**风险，不是某行代码的 bug。换局与 beat 写入必须落在同一个串行上下文。
- **处理**：所有状态变更投递到 `runtime.SessionLoop`（每会话单线程）；状态对象只在该线程内可变，
  对外只暴露不可变快照。

## Q3 · 破坏性队列的并发读取

- **位置**：`speech` 的 `/nori-line`
- **现象**：实测客户端 **706ms 轮询**（693/754 次），语义是「取走即清空」。若两个请求并发进入，
  典型的错误实现会「同一行返回两次」或「取走后写回时覆盖新行」。
- **初步判断**：读操作必须是**原子出队**（drain），不能「读 → 处理 → 清空」三步分离。
- **处理**：在 `SessionLoop` 内做 `drain()`，返回不可变列表；HTTP 线程只投递任务并等结果。

## Q4 · 9s 兜底与播放回报的竞态

- **位置**：`speech` 队列推进
- **现象**：`/nori-spoken` 可能晚于 9s 兜底计时器到达。若两者都推进队列，会「跳一句」或「同一句播两遍」。
- **初步判断**：需要 `epoch`（或 generation）号：兜底推进时递增 epoch，迟到的回报用旧 epoch 直接丢弃。
- **处理**：`SpeechQueue` 内置 epoch；推进动作幂等（用 `(qid, epoch)` 判定是否已消费）。

## Q5 · 并发合成时 seq 与发送顺序

- **位置**：`tts`（合成）↔ `gateway`（发送）
- **现象**：旧栈单队列串行，`seq` 递增与 `client.send()` 是同一线程，天然有序。
  Java 侧若并发合成（`NORI_TTS_PARALLEL=3`），两个线程各自 `seq++` 再各自 send，
  **发送顺序可能与 seq 顺序相反** —— 客户端按 seq 组装会拼错音频。
- **初步判断**：合成可并发，**发送必须串行**。
- **处理**：`SpeechFramer` 的 `seq` 用 `AtomicInteger` 分配（保证唯一且单调），
  但 `send` 一律回到 `SessionLoop` 执行，按分配顺序入队。

## Q6 · 二进制帧零拷贝的 buffer 寿命

- **位置**：`gateway` 的 WS 转发
- **现象**：Netty 的 `ByteBuf` 在 handler 返回后可能被回收。若把 upstream 的 buffer 直接交给
  另一个连接异步写而不 `retain()`，会出现「偶尔静音/花屏」这类不可复现故障。
- **初步判断**：零拷贝的前提是**引用计数管对**，不是「不复制」。
- **处理**：转发路径统一 `retain()` + 写出失败时 `release()`；用 `EmbeddedChannel` 写单测覆盖。

## Q7 · `open_media` 的 grant 校验

- **位置**：`gateway` media 通道
- **现象**：上游要求 media 连接建连后先发 `{"type":"open_media","grant":"<mediaGrant>"}`（[U]）。
  grant 来自 `world_joined.session.mediaGrant`。我们若照收不校验，可能出现「一个 grant 多处复用」。
- **初步判断**：本地单用户场景危害有限，但**校验缺失会让协议行为与上游分叉**。
- **处理**：world_joined 时缓存 grant（绑 sessionId），open_media 时比对，不匹配直接关闭并记 WARN。

## Q8 · `pong.now` 的数值语义

- **位置**：`lifecycle` 心跳
- **现象**：文档只说 `now` 是「可选整数」，没说单位（ms/s）和基准。
- **初步判断**：客户端只做存活判断，不校验单位；但既然 `.strict()`，字段名错就丢包。
- **处理**：先**原样回上游的 `now`**（如果有），没有就省略该字段，不自造。

## Q9 · `world_joined.facts` 不能本地补

- **位置**：`gateway` / `lifecycle`
- **现象**：`Nori.Web.exe` 原版把 41 个 fact 烘进常量池（含 `boot.completed`），导致冷开场永不触发；
  我们用 `noai` 版把它阉掉。若 Java 侧组装 `world_joined` 时"好心"补 facts，
  冷开场哨兵会重新出现 → **冷开场再次不播**，而且极难查（表现为"直接落桌面"）。
- **初步判断**：facts 必须**透传 exe 的值**（实测 facts=0）。
- **处理**：`world_joined` 只做透传；若必须改，先写一条 CRTL 日志说明改了哪些 fact。

## Q10 · `outboundFix` 的改写策略

- **位置**：`gateway` → `speech`
- **现象**：旧栈对 `ingestBlock` 的 agent 文本做清洗/分句/reveal，分句口径必须与官方 reveal 一致（`bubblesFor`）。
  Java 侧若自己改一套分句，配音与气泡会**错位一个块**，且只在长句上暴露。
- **初步判断**：先**原样透传**，不改写；等影子对比确认必须改写时，再逐条移植。
- **处理**：`gateway` 第一版不做任何文本改写，只记录「本可改写」的样本供后续对比。

## Q11 · `/nori-world-gen` 的方法

- **位置**：`contract` / `gateway`
- **现象**：golden 实测 **GET**（2 次 200），HANDOFF §2.2 写 POST；`/nori-debug` 实测 **POST**（204），文档写 GET。
- **初步判断**：文档表记反了，或新旧版本都支持两种方法。
- **处理**：两种方法都注册，日志里记录实际用法；以实测为准写入契约。

## Q12 · `nori.ai.config` 不在上游协议里

- **位置**：`gateway` 白名单
- **现象**：golden 里出现 `c2s nori.ai.config` / `s2c nori.ai.config.result`（786.8s），
  但 [U] 的 10 条客户端消息里没有它 —— 是我们自研 shim 加的。
- **初步判断**：如果我们按"未知类型丢弃"处理，AI 设置面板会失灵。
- **处理**：`ArcadeProtocol` 增加一个"已知的本地扩展"集合，白名单放行、不参与上游语义。

## Q13 · `SERVER_*` 环境变量污染 Boot 属性

- **位置**：`app` / `config`
- **现象**：本机存在 `SERVER__PORT=8942`（宿主注入），Spring 松弛绑定把它认成 `server.port`，
  优先级高于默认属性 → 「配置写 4183 实际起 8942」。已用命令行参数压住。
- **初步判断**：同类污染还可能出现在 `SERVER_SERVLET_*`、`SPRING_*` 上。
- **处理**：启动时打印**生效端口**与来源；配置层加一条"检测到 `SERVER_*` 与配置冲突则 WARN"。

---

## 已结案（从上面移下来，留档）

| 原疑点 | 结论 | 依据 |
|---|---|---|
| 反代响应体丢失 / 请求不返回 | 两个独立原因：① 响应侧删了 `Content-Length` 又没给 chunked → 消息无边 界，Spring 吞掉 body；② `exchangeToMono` + `ServerResponse.body(fromDataBuffers)` 互相等（要 15s 超时才断）。改用 `WebFilter` + `response.writeWith(flux)` 后 5 条集成测试全过 | `HttpProxyTest`、`HttpReverseProxy` 类注释 |
| record 带 `byte[]` 的相等性 | record 默认对数组做引用比较，两段内容相同的 PCM 判不等 → 去重/对拍会静默出错。`ArcadeFrame` 已显式覆写 `equals/hashCode` | `ArcadeFrameTest.roundTrip` |
| Boot 4 没有 `WebClient.Builder` bean | 自动配置被拆成独立 starter，当前依赖组合下不存在 → 启动即 `UnsatisfiedDependency`。改为 `WebClient.builder().build()` | 启动测试 |
| log4j `PatternLayout.Builder.setPattern` | Boot 4 管的 log4j 2.25.5 只有 `withPattern`（已弃用但可用） | 编译错误 |
| `Mono.fromFuture(null)` | 会变成**空完成**而不是发出 null；`petGate()` 用 null 表示放行，导致 200 无 body。用 `Optional` 承载 | `NoriEndpointsTest` |
| reactor-netty `headers()` 回调 | 给的是 **Netty** 的 `HttpHeaders`，不是 Spring 的；多值头要 `set(name, values)` | 编译错误 |
| Spring 7 无 `HandshakeHandler` | 服务端不再"回选"子协议；浏览器容忍，Netty 客户端不容忍（JDK 客户端可以） | `ArcadeWebSocketProxyTest` |

## Q14 · 媒体出口只允许一个，新连接会顶掉旧连接

- **位置**：`gateway.MediaHub#register`
- **现象**：同一时刻只保留最近注册的 media 连接，旧连接被"顶掉"（不主动关闭，只是不再推帧）。
- **初步判断**：单用户单窗口场景下这是**对的**——两路同时推帧会让客户端收到交错的 seq，
  听起来像卡带，其实两路混在一起。但如果前端因为重连短暂开了两条，后开的会把能用的那条顶掉。
- **怎么定论**：录一次「刷新页面」的完整会话，看 media 连接数与帧是否连贯。

## Q15 · 上游若也发媒体帧，seq 会冲突

- **位置**：`gateway.ArcadeWebSocketProxy`（转发上游帧）↔ `SessionRuntime#speak`（生成帧）
- **现象**：我们生成的帧 seq 由 `SpeechFramer` 从 0 起；上游若也推二进制帧，它带的是**上游自己的 seq**，
  两路混进同一个媒体连接，客户端按 seq 排序就会乱。
- **初步判断**：golden 三轮里上游一个二进制帧都没发过（103 帧全是我们代理组装的），
  所以当前实现是对的；但这是**实测的空白区**，不能当成契约保证。
- **怎么定论**：在 media 通道上统计"上游 s2c 二进制帧"的数量；若长期为 0，就在代码里显式丢弃并记一次 WARN。

## Q16 · 台词表路径依赖工作目录

- **位置**：`app.SessionConfig#scriptTable`，配置项 `script.linesFile`
- **现象**：默认值是相对路径 `nori_script_lines.json`。从项目根跑能找到，从别处跑（IDEA 的工作目录设置不对）
  就变成空表——**只打一条 WARN**，表现是"她不主动说话"。
- **初步判断**：不阻断启动是对的（台词是内容，改坏了不该起不来），但"静默变空表"太难查。
- **怎么定论/怎么改**：启动时把**解析后的绝对路径**打出来（已做）；后续考虑在 `/nori-lifestat` 里暴露
  `script.beats` 数量，让"空表"一眼可见。

## Q17 · `/v1/models` 还没实现

- **位置**：`gateway.HttpReverseProxy#isOwnEndpoint` 把 `/v1/*` 全划成自有，但只有
  `/v1/chat/completions` 有控制器 ⇒ `/v1/models` 现在会 404。
- **初步判断**：旧栈把它转发给 25503；前端设置面板可能用它列模型。
- **怎么定论**：录一次「打开 AI 设置面板」的会话，看有没有发 `/v1/models`；有就补转发。

## Q18 · `stream:true` 命中固定台词时的客户端行为

- **位置**：`chat.NoriChatController#writeFixedReply`
- **现象**：命中时不管 `stream` 取值，都返回**整段 JSON**（与旧栈 `sendFixed` 一致）。
- **初步判断**：旧栈就是这么干的，且线上可用 ⇒ 客户端应能处理。但如果客户端只按 SSE 解析，
  这条会静默失败（表现为"她没回复"）。
- **怎么定论**：抓一次 `stream:true` 且命中的真实请求，看客户端是否上屏。
  若不行，就按 SSE 格式逐块吐（每条 `data: {...}\n\n`，末尾 `data: [DONE]`）。

## Q19 · 记忆写入是旁路，停机时会丢在途请求

- **位置**：`app.SessionRuntime#onFact` 末尾的 `persona.writeScriptLine(...).subscribe()`
- **现象**：写入是 fire-and-forget（不 join）。JVM 立刻退出时，在途的那一次可能没发出去。
- **初步判断**：这是刻意的取舍——记忆写入卡住不能拖住台词上屏。代价是极端情况下少一张卡，
  而少一张卡比"摸头没反应"轻得多。
- **怎么定论/怎么改**：优雅停机时（`@PreDestroy`）flush 一次在途写入；先观察是否真的丢过。

## Q20 · 存档的 `bundle` 字段暂时是 `unknown`

- **位置**：`save.SaveController`
- **现象**：前端打包产物指纹（`NormalApp-*.js` 的 md5 前 10 位）没在启动时探测，所以写 `unknown`。
- **初步判断**：不做启动探测是**刻意的**——不能让"能起服务"依赖上游可用。
  但也别把上游 URL 塞进这个字段（第一版我就这么写错了，语义混了比空着糟）。
- **怎么定论/怎么改**：首次成功代理到上游时，异步读一次 `/asset-manifest.json` 或 `NormalApp-*.js` 算 md5，
  缓存起来；存档时再取。

## Q21 · 有存档，还没有读档

- **位置**：`save` 模块
- **现象**：`POST /api/admin/saves/{slot}` 能存，`GET` 能读回结构，但**没有任何路径把存档恢复进会话**。
- **初步判断**：恢复要动三块（lifecycle 的 facts/phase、script 的闸门、speech 的队列），
  顺序错了会互相覆盖。这属于"半步做完"，必须显式记下来，别让人以为已经闭环。
- **怎么定论/怎么改**：`SessionRuntime.restoreFrom(SaveGame)`，在会话线程里按
  `script → lifecycle → speech` 的顺序恢复，并立刻落盘一次。

## 卡带相关（详见 docs/CARTRIDGE.md §7）

### Q22 · `gesture.*` 是否只在"点开游戏"时发一次 —— ✅ 已结案（原假设**被推翻**）

- **位置**：前端 `NormalApp` 的 QfrDock（`public/assets/NormalApp-*.js`）
- **原假设（错）**：前端点开游戏 → `emitFact('gesture.<id>')` → WS 上行 → 我们 `observeFact`。
- **实测证据**（录制 `golden/20261031-035029`，Chrome 驱动真机）：
  1. `dispatch{cmd:{type:"startGame"}}` 返回的 `runtime_transition.patches` **只有 `/gameState` 和 `/settings`**，
     **没有 `/facts`** —— 客户端在开一局时根本不写任何 fact。
  2. 全 bundle 里 `gesture.*` 只出现 2 处，**全是只读**：
     - `NormalApp`：`Wtt` 常量 + `Ox(oT, …)` 监听 → 弹 toast
     - `QfrDock`：`gesturesDone: {kind:"countTrue", of:[…4], cap:4, labels:{…}}` 计数器
     **零处写入。**
  3. `cakeduel` 的 `cartridge_mounted.runtimes[0].state` 里**没有 `facts` 字段** —— 游戏卡带不持有 fact。
  4. toast 文案（i18n `os.gestureWarning`）：**「交互模块负载超限」「已复现 {{done}}/{{total}}」**，
     sfx `arg-gesture-warning-toast`，labels 为「国际象棋/森林寻宝/你画我猜/蛋糕对决」。
- **真相**：`gesture.*` 是**我们自己后端注入**的（`NoriOSNew/nori_game.js` → `fireGesture` → `injectFact`），
  注入走 `event{channel:"manifold.dev.jump.request", payload:{facts:[id]}}`，
  由 **manifold.web（ARG）卡带**落库，客户端只负责读出来计数。
- **触发条件（已在 `_t_gesture.js` 固化，共 14 条用例）**：
  | fact | 判据 |
  |---|---|
  | 全部 | **前置门控** `hasFact('qfr.installed')`（玩家先装 QFR-9000），否则一律跳过 |
  | `gesture.chess` | `capture` 事件里**她的车吃我的后**（`by=她`、`from.piece='r'`、`captured.piece='q'`） |
  | `gesture.codenames` | 她**连续 3 次**踩到浆果（`guess{by:'agent',result:'bystander'}` ×3） |
  | `gesture.pictionary` | 她猜出的词**精确等于「水母」**（"水母灯"/"水母伞"都不算） |
  | `gesture.cakeduel` | 狼爵士（`wolfy`）被弃进阻挡堆；另有 `wolfy_taunt` / `game_ended` 兜底 |
  | `arg.gestures_complete` | 四枚集齐后 **1.2s** 再补一发 |
- **对我们代码的影响（重要）**：
  1. `gesture_*` **不能**挂在 `cartridge_mounted` 上（那是"打开游戏"，与判据无关）。
  2. `gesture_*` **不能**做成 repeatable —— 它靠 `hasFact` + `BEAT_TRIES`(≤12 次 / 8s 节流) 约束，语义是**剧情节点**。
  3. **"每玩一局该说一句"要用另一个锚点**：`dispatch{cmd:"startGame"}` 的
     `runtime_transition.events[]` —— chess 是**平铺** `{type:"game_start"}`，
     cakeduel 是**嵌套** `{type:"engine", event:{type:"game_started"}}`。**两种封装并存，必须都认。**
  4. 真正产出 `gesture.*` 的 `NoriOSNew/nori_game.js`（2230 行）**尚未移植** —— 见 Q26。

### Q23 · `runtimes.state` 未使用 —— 已实测出结构（仍不接管）

- **位置**：`world_joined.mountedCartridges[].runtimes[].state`
- **实测结构**（`golden/20261031-035029`）：
  - `chess`：`{settings:{playerSide,difficulty,locale}, gameState:{fen,pgn,turn,phase,status,fullMoveNumber,…}, drawOffer…}`
  - `cakeduel`：`{config:null, game:null, settings:{difficulty,roundsToWin,seed}, tutorial:null, lastError:null}`
  - `manifold.web`：`{facts:{…}, operations:{…}}` ← **唯一带 facts 的卡带**
  - `chat`：`{operations:{…}}`
- **结论**：**游戏卡带不持有 `facts`**，`gesture.*` 只可能落在 manifold.web 的 facts 里。
  我们仍然**不接管** `state`（判据靠 `runtime_transition`，见 Q22），只把它当调试快照。

### Q24 · 多开窗口互相覆盖

- **位置**：`app.SessionConfig`（单会话单例）+ `gateway.MediaHub`（单出口）
- **现象**：第二个窗口会把第一个的 cart / 音频出口顶掉。
- **初步判断**：本地单用户单窗口的既定假设；真要多会话，先从 `SessionRuntime` 拆起。

### Q25 · `boot.completed` 与 `cartridge_unmounted` 的先后

- **位置**：`script.resetStory`（挂 `boot.completed`）↔ `SessionRuntime#observeFrame`（挂 unmount）
- **现象**：退出后立刻开新游戏时，两者可能交错：软清空把新局刚入队的台词也清掉。
- **怎么定论**：录一次"退出 → 立刻开新游戏"，看新的台词有没有被丢掉。

### Q26 · `nori_game.js`（2230 行）尚未移植 —— **最大缺口**

- **位置**：`NoriOSNew/nori_game.js` → Java 侧**没有对应包**
- **现象**：现有 `lifecycle`/`script` 只处理"换局 + 台词"，**完全没有游戏内逻辑**。
  实测 `cakeduel` 开局后上游产出 `chat.agentMessage`「唔...开局啦，Nori 先把牌捏紧一点。」——
  这句**不是 `/nori-line`**（全程 `{"lines":[]}`），而是 **chat 卡带的 `runtime_transition`**。
- **它到底做什么**（`GameSession` 类骨架）：
  | 职责 | 方法 |
  |---|---|
  | 四游戏 AI 决策 | `kickChess()` / `cakeTick()` / `picKick()` / `sdTick()`+`sdGuess()` |
  | 大脑调用 | `BRAIN_URL = http://127.0.0.1:25503/game-turn`（**这就是 25503 的真正用途**） |
  | 台词 | `say()` → `onLine` → `/nori-line`；`onCommentary` 旁白 |
  | 剧情判定 | `checkEvents()` → `fireGesture()` → `injectFact()` |
  | 你画我猜取图 | `strokeAscii()` / `strokePng()`（自己手写 PNG 编码器 `encodePngRgb`） |
  | 事件归一 | `flatEvent(e)` —— 把 chess 的平铺 `{type:"game_start"}` 与 cakeduel 的嵌套 `{type:"engine",event:{…}}` 抹平 |
- **初步判断**：必须移植，但**分层来**：先 `flatEvent` + `checkEvents` + `fireGesture`（剧情判定，纯函数、可离线测），
  再 `BRAIN_URL` 客户端，最后四个游戏的 AI 循环。
- **怎么定论**：`_t_gesture.js` 的 14 条用例可以直接当 Java 单测的验收标准。

### Q27 · 同一条 transition 被广播 N 次（连接数）

- **位置**：`gateway/ArcadeWebSocketProxy` 的上行解析
- **现象**：实测同一时刻有 4 条 WS 连接（`conn 133/744/1374/1379`）。
  一条 `runtime_transition` / `cartridge_mounted` / `cartridge_unmounted` / `visibility_fence_advanced`
  会被上游**复制 N 份**（每连接一份），只有 `*_ack` 发回发起方。
- **风险**：若按消息逐条驱动 `checkEvents`/`fireGesture`/`say`，**会重复 N 次**（同一句话播 3 遍）。
- **初步判断**：必须按 `(worldId, cartridgeId, version)` 去重（同一 version 的 transition 只处理一次）；
  `cartridge_unmounted` 无 version，按 `(worldId, cartridgeId, 时间窗)` 去重。
- **怎么定论**：录制里同一 transition 出现 3 次已复现，加去重后再跑一遍确认只处理 1 次。

### Q28 · 两条平行的"说话"通道

- **位置**：`speech/NoriLineController`（`/nori-line` 轮询）↔ 上游 `chat` 卡带的 `agentMessage`
- **现象**：
  1. `/nori-line` —— 客户端约 706ms（实测 270~350ms 双组交错）**轮询**，全程 `{"lines":[]}`；
  2. `chat.runtime_transition{type:"agent_message", messageId:"msg_7", content:"唔...开局啦…"}`
     —— **上游 LLM 直接产出**，带真实游戏上下文。
- **初步判断**：`agentMessage` 是**上游的能力**，反代只需**透传**（绝不改写）；
  `/nori-line` 是**我们自己**（25503）的通道，用于"我们编排的台词"（`ScriptEngine`）。
  **两者互不替代，也不能互相去重** —— 但要避免同一句同时从两条通道出现（见 Q22 第 3 条：锚点要错开）。
- **怎么定论**：抓一次完整对局，统计两条通道各自的产出条数与时间点是否重叠。

### Q29 · `noai` 版**有** arcade —— 原判断是错的，作废

> ⚠️ **2026-10-31 更正**：以下原结论已被推翻，保留仅为记录教训。

- **原结论（错）**：`Nori.Web.noai.exe` 只是静态服务器、没有 `/api/arcade/web/v1`，
  所以整条链不通。
- **错在哪**：
  1. 用 `curl` 带 `Upgrade` 头去探 WS 端点，**探不出真相**——普通 GET 会落到 HTTP 路由返回 404，
     而真正的握手走的是 upgrade 分支。`/health`、`/arcade/web/v1` 返回 `index.html`
     也只是 SPA 兜底，不代表没有 WS。
  2. 当时**真正的故障是 Q31（我们没回选子协议）**，我却把日志里的
     `AbortedException` 归因给了上游。而 `nori_proxy.js` 那条链同时也在失败，
     是因为我给它的请求同样没有合法 ticket，它对上游失败就 `socket hang up`。
- **事实**：`noai` 是 `Nori.Web.clean.exe` 的改名版——**同一个 build**，
  只是把 `DEFAULT_UNLOCKED_FACTS` 那 41 个常量（`boot.completed` / `session.ready` / `arg.*` / `qfr.*`）
  挖成了 `zzfact00..zzfact40`，好让 ColdOpen 能触发。**arcade 服务完整存在**，
  `proxy.log` 里 `FACT + zzfact00.......` `GAME mount cid=manifold.web` `dispatch_ack` 都是它产的。
- **正确的判别方式**：
  - 看 `NoriOSNew/proxy.log` 有没有 `WS up /api/arcade/web/v1 proto=arcade.v1` 和后续 `FACT +`；
  - 或者直接用浏览器打开 4173，能走 ColdOpen 就说明上游健康。
  - **不要**用 curl 探 WS 端点下结论。
- **另一个坑（用户当场纠正）**：页面上显示的**「连接中断」「四个连接失败」是 ARG 的演出内容**，
  不是故障。本项目是「侵入现实解密」——客户端会**故意**模拟断线/报错来推进叙事。
- **判别原则（用户定，2026-10-31）**：
  > **真故障会留下硬证据（HTTP 404/403/502、白屏、`ERR_CONNECTION_REFUSED`），
  > ARG 叙事只留下精心设计的文案。**

  | 现象 | 倾向 | 怎么定 |
  |---|---|---|
  | 只有风格化文案（「连接中断」「四个连接失败」），布局/美术正常 | **叙事** | 等演完；看后台日志有没有正常帧流 |
  | 带 **HTTP 状态码**的失败、白屏、连接被拒 | **真故障** | 按码查：404=路由/上游缺端点，403=准入(ticket/Origin)，502=上游不可达 |
  | Network 面板有红色请求 + 状态码 | **真故障** | **以状态码为准，不信页面文案** |

  排查顺序固定：**先看 Network 面板的状态码，再看页面文案。**
- **启动方式**：`NoriOSNew/nori_start_all.bat`（用户指定以此脚本为准），
  它启动 `Nori.Web.noai.exe` 到 4174 + `nori_proxy.js` 到 4173。


### Q30 · 主动注入通道 —— 原结论被推翻，改为 `Flux.merge`（待真机确认）

- **位置**：`gateway/ArcadeWebSocketProxy`
- **原结论（错）**：`WebsocketOutbound` 只接受一个出站序列，`Flux.merge` 与二次
  `sendObject().subscribe()` 都会 `AbortedException`。
- **错在哪**：那两次实验是在 **Q31（子协议未回选）** 期间做的。当时**纯透传也一样报**
  `AbortedException` —— 也就是说这个错误跟"怎么拼出站流"根本无关，是浏览器握手即断。
  **教训：依赖本身是坏的时候，对照实验的结论要打问号。**
- **现在的写法**：
  ```java
  Flux<WebSocketFrame> fromBrowser = browser.receive().<WebSocketFrame>handle(...);
  Flux<WebSocketFrame> toUpstream = injected == null
          ? fromBrowser : Flux.merge(fromBrowser, injected.asFlux());
  Mono<Void> up = outbound.sendObject(toUpstream).then();
  ```
  注入侧是一个 `Sinks.many().unicast()`，`tryEmitNext` 进同一条出站流；
  `doFinally` 里 `unregister` + `tryEmitComplete`。
- **仍然成立的那半条**：**确实只能有一个出站序列**。所以必须 merge，不能另开订阅。
- **待确认**：真机上注入一帧后，透传是否仍然正常、帧序有没有被打乱。
  验证方法：装 `qfr.installed` 后打一局象棋，用"她的车吃我的后"，看
  `game: 手势注入 gesture.chess` 是否出现、且同时 `dispatch_ack` 还正常回流。

### Q31 · Spring WebFlux 不回选子协议 ⇒ 浏览器直接断开（已结案）

- **位置**：`gateway/SubProtocolHandshakeService` + `GatewayWebSocketConfig`
- **现象**：从 4183 打开前端，永远停在「正在连接 FUTURUM」；后端日志是
  `AbortedException: Connection has been closed BEFORE send operation`
  ——**看着像上游连不上，实际上游没参与**。
- **判据（3 秒定位）**：
  ```
  node tools/diag_ws.js ws://127.0.0.1:4183/api/arcade/web/v1
  → Server sent no subprotocol
  ```
- **根因**：Spring WebFlux 的 `HandshakeWebSocketService` 不提供选协议钩子，
  默认**不回** `Sec-WebSocket-Protocol` 响应头。浏览器请求了子协议、服务端一个都不选时，
  按 RFC 6455 会**直接判握手失败并关闭连接**。浏览器一断，我们那条 `sendObject`
  就在发送前发现连接没了 —— 于是错误指向了毫不相干的上游。
- **为什么旧栈没这问题**：`nori_proxy.js` 用 `wss.handleUpgrade`，会自动回选。
- **修法**：继承 `HandshakeWebSocketService`，覆盖 `handleRequest`：
  优先回 `arcade.v1`（不在第一个也照样选它），否则退回客户端列表第一个；
  客户端没请求就不发该头。然后在 `GatewayWebSocketConfig` 里
  `new WebSocketHandlerAdapter(new SubProtocolHandshakeService())`。
- **验证**：IDEA 启动后日志出现 `media: 已注册音频出口` 与
  `lifecycle: 离开游戏 cart=chess ⇒ ended`，说明 WS 与帧流都通了。


### Q32 · 帧流驱动：我们是"事后驱动"，不是"扣动作"

- **位置**：`game/driver/GameDriverService` + `ChessDriver` + `GameHeads`
- **与 JS 的根本差别**：
  - `nori_game.js` **扣住**玩家的那一步（先不发上游），等算出她的应对再一起发
    ⇒ 所以它需要 `simCakeduelAfterPlayerAction` 这类"相位推测"。
  - 我们的代理是**纯透传**：玩家的动作早就发给上游了，等上游把新局面广播回来，
    才看到"轮到她" ⇒ 我们**不需要推测相位**（上游已经告诉我们了）。
- **代价**：她的这一步比 JS 版晚一个往返。对回合制游戏可接受。
- **为什么刻意不扣动作**：扣住意味着要接管帧序与 ack，一旦出错就是"玩家点了没反应"——
  那比"她慢半拍"严重得多。
- **两个防呆（都是正确性要求，不是优化）**：
  1. **同一 version 只问一次**：上游按连接数广播多份（Q27），我们也会重连——不去重她会连走三步。
  2. **没有 headVersion 就不发**：`expectedHeadVersion` 不对时上游**静默丢弃**（不报错、不回 ack），
     "她为什么不动"会变成查不出原因的谜。`GameHeads` 从 `runtime_transition.version`
     和 `dispatch_ack.headVersion` 两处记账，且**只升不降**（防广播乱序把 head 拉回去）。
- **dispatch 报文以实测为准**：`{type, actor:"agent", cartridgeId, requestId, expectedHeadVersion, cmd}`
  —— **没有 worldId**（不要多加）。
- **开关**：默认**关闭**，`-Dnori.game.driver=on` 打开。理由：这条链路会替她往上游发命令，
  判断错就是"我们污染了对局"。默认关掉，让它在真机上被显式打开、被观察。
- **报文形状两者不同（容易漏）**：玩家发的 dispatch **不带** `worldId` / `visibilityFenceId`；
  **agent 发的要带**（依据 `nori_game.js#sendAgent`）。我们两个来源各写各的。
- **当前覆盖**：
  | 卡带 | 触发点 | 状态 |
  |---|---|---|
  | `chess` | 下行 transition 里 `/gameState` 的 `turn` | ✅ 已接 |
  | `pictionary` | **上行** `submitStrokeBatch`（玩家画一笔） | ✅ 已接 |
  | `cakeduel` / `codenames` | 下行 transition | ⬜ 待接 |
- **为什么你画我猜要单独走上行**：笔画是玩家的**上行命令**，上游不会为它广播局面，
  等 transition 她永远不会动。所以要给 `browser.receive()` 加旁路观察
  （`SessionRuntime.observeUpstreamFrame`），攒进 `PictionaryBoard` 后**直接**触发一次看图。
  去抖 700ms（玩家还在画时看图，猜出来必然是半成品）。
- **⚠️ 真机未验证**：单测覆盖了开关/去重/报文形状/非法动作，但"打一局象棋看她的子会不会动"
  还没做过。第一次开的时候重点看两件事：
  1. 日志有没有 `game: 轮到她 cart=chess ... head=N`；
  2. 有没有 `game: 已 发出 chess → {type=move...}`，且页面上盘面真的变了。
