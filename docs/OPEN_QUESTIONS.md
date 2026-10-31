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
