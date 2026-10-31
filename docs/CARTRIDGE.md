# 卡带（Cartridge）设计

> 状态：设计 v1（2026-10-31）。**刻意不照搬上游**：上游把 cartridge 当纯协议对象，
> 我们把它当「叙事上下文」——只读它的状态，用它驱动台词、记忆与闸门，不去实现游戏规则。

## 1. 先说清楚：卡带不是我们实现的

游戏规则（下棋、猜词、翻牌、你画我猜）在 **4174 的 exe** 里。我们这一层只做三件事：

| 做什么 | 不做什么 |
|---|---|
| 记录"当前在玩什么"（`cart`） | 不实现棋盘/牌局规则 |
| 卸载时收尾（软清空 + 条件通报） | 不校验走子合法性 |
| 把"他在玩 X"翻译成叙事（台词/记忆） | 不复制 `runtimes` 状态（那是客户端与服务端的状态机） |

这条边界是从实测里长出来的：golden 三轮里 **103 个音频帧全是代理自己组装的**，
而上游一条媒体帧都没发——说明真正的玩法逻辑根本不在我们这侧。

## 2. 数据形状（只读，来自上游协议）

```
world
 └ mountedCartridges[]
      └ cartridgeId  chat | chess | codenames | cakeduel | pictionary
           └ runtimes[]  { visibilityFenceId, visibleVersion, headVersion, state }
```

- `headVersion`：已提交版本；`visibleVersion`：玩家可见版本；
- 写操作走 `dispatch`（带 `expectedHeadVersion` 乐观锁 + `requestId`）；
- 提交后广播 `runtime_transition{version, patches[], events[]}`；
- 玩家看见要 `advance_visibility_fence`。

**我们只读 `phase / cartridgeId`，不碰 version/fence。**
理由：一旦我们也维护 version，就会和 exe 的版本打架，而排障时没人能说清谁对——
这是"看起来更完备、实际更容易坏"的典型。

## 3. 状态机（我们这侧的）

只有三个状态，够用且不会错：

```
idle ──cartridge_mounted──► mounted(cart) ──cartridge_unmounted──► ended(cart)
  ▲                                                                    │
  └──────────────── world_joined / web_world_reset_ack ────────────────┘
```

- **`cartridge_unmounted` 是换局的唯一锚点**：`onGameEnd` 是"这局下完了"，她恰好在那时说收尾台词，
  挂它会吞掉那句（这条在旧栈里被明确记录过，我们沿用了结论）；
- 重复的 unmount 帧**只处理一次**（`seq` 去重），否则会重复通报人格；
- `world_joined` / `web_world_reset_ack` 把状态拉回 `idle`（`ended=false`）。

## 4. 换局时到底做什么

语义：**当前句说完，其余丢弃**（半句被吞比多说一句糟得多）。

1. `speech.softReset()`：清 `pending`、保留 `current`；
2. **条件通报**：只有 `dropped > 0` 才 `POST /game-note`（只记不答，不触发 LLM）。
   每退出一次就写一条"被截断"，记忆会被噪音填满——这是"条件"两个字的全部意义；
3. 一次性台词闸门由 `boot.completed` 单独重置（`resetStory`），**不挂在 unmount 上**：
   卸载只是离开游戏，不等于开了新一局。

## 5. 游戏 → 台词的映射落在哪

⚠️ **本节原结论已被实测推翻，以下是更正后的版本。**

**错误结论（作废）**：`gesture.*` 是前端 QfrDock 在"点开游戏"时 `emitFact` 发的。
**错在哪**：`dispatch{cmd:"startGame"}` 的 `runtime_transition.patches` 里**只有 `/gameState` 和 `/settings`，没有 `/facts`**；
全 bundle 里 `gesture.*` 只被**读**（`Ox` 监听 + `countTrue` 计数），**零处写入**。

**正确链路**：

```
游戏卡带 runtime_transition（上游 4174）
   │
   ├─ GameSession.handleServer() → checkEvents() → flatEvent() 抹平两种封装
   │     ├─ chess     : {type:"game_start"} / {type:"capture"}   ← 平铺
   │     └─ cakeduel  : {type:"engine", event:{type:"game_started"}} ← 嵌套
   │
   ├─ 判据命中 → fireGesture(id, why)
   │     └─ 门控 hasFact('qfr.installed')  ← 没装 QFR-9000 一律跳过
   │
   └─ injectFact(id) ──► event{channel:"manifold.dev.jump.request", payload:{facts:[id]}}
                          └─► manifold.web（ARG）卡带落库
                                └─► 客户端读 facts ─► toast「交互模块负载超限 n/4」
```

**四枚手势的判据**（源：`NoriOSNew/nori_game.js`，用例：`_t_gesture.js` 14 条）：

| fact | 判据 |
|---|---|
| （全部） | 前置 `hasFact('qfr.installed')` |
| `gesture.chess` | 她的**车**吃我的**后** |
| `gesture.codenames` | 她**连续 3 次**踩浆果 |
| `gesture.pictionary` | 她猜的词**精确等于**「水母」 |
| `gesture.cakeduel` | 狼爵士 `wolfy` 被弃进阻挡堆（`wolfy_taunt`/`game_ended` 兜底） |
| `arg.gestures_complete` | 四枚集齐后 1.2s 补发 |

**我们该怎么做**：
- `gesture_*` beat 是**剧情节点**，**不是 repeatable**；判据在 Java 侧复刻，台词表仍是唯一真源；
- **"每玩一局该说一句"用另一个锚点**：`dispatch{cmd:"startGame"}` 的 `runtime_transition.events[]`
  （chess 平铺 `game_start`，cakeduel 嵌套 `engine.game_started`，两种都要认）；
- **必须去重**：同一条 transition 会按连接数被广播 N 份（实测 3 份），按
  `(worldId, cartridgeId, version)` 只处理一次，否则一句话播三遍。

## 5.5 她怎么"会玩"——`/game-turn` 契约（**实测**，2026-10-31）

`25503/game-turn` 是**人格服务里的游戏大脑**：把局面交给它，它回"她要做哪一步 + 顺口说句什么"。
这就是"先用 25503"的另一半含义——她不只会说台词，还会替你下棋、出牌、猜词。

### 请求（POST JSON）

```json
{"game":"chess","observation":"<多行自然语言局面>","directive":"<玩家口头指令，没有就空串>",
 "legal":[{"id":"e2e4","desc":"e4 (e2→e4)"}],"maxActions":1,"clueMode":false,
 "image":"data:image/png;base64,..."}
```

| 字段 | 真实形状 | 备注 |
|---|---|---|
| `observation` | **多行字符串** | 给 LLM 读的自然语言：局面 + 最近几步 + 「你的合法走法」清单 |
| `legal` | **数组** `[{id, desc}]` | `id` 是机器句柄（象棋是 `e2e4`，寻宝是 `cell:3`），`desc` 给人看 |
| `maxActions` | 数字 | 象棋 1；森林寻宝猜牌 **9**；寻宝给词 1 |
| `clueMode` | bool | 只有森林寻宝「给提示词」时为 true |
| `image` | PNG data URI | 只有你画我猜会带 |

### 回复（实测两份）

```json
{"ok":true,"actions":["e2e4"],"clue":null,
 "line":"嘿嘿，Nori 先走一步啦。\n你可要认真点哦。",
 "lines":["嘿嘿，Nori 先走一步啦。","你可要认真点哦。"],
 "emotion":"happy","obeyed":true,"reason":"开局先占中心","ms":1129}
```

- **`actions` 是 id 字符串数组**，不是对象数组。
- **`lines` 才是台词真身**（逐句）；`line` 只是用 `\n` 拼起来的整段。
  上屏/合成要按 `lines` 逐条走（每句一个 blockId）。
- `reason` 是她"为什么这么做"，**只进日志，不上屏**。
- `clue` 只在 `clueMode` 时有值，形如 `{word:"海洋", count:3}`。
- **`ok=false` 必须当失败**（不是"没有动作"），照空动作走会把局面卡死。
- 空动作是**合法回复**：实测第一版我发错形状时她回
  `{"ok":true,"actions":[],"reason":"没有合法动作，只能等局面恢复"}` + 一句"棋盘卡住了"。

### 踩过的坑

第一版按"结构化 observation + 对象化 legal + 对象数组 actions"设计，她**完全没看懂**，
回了"没有合法动作"。**契约是被真实响应纠正的，不是从代码里推出来的**——
`nori_game.js` 里的 `actionsFromBrain(d)` 才是真相：`actions` 就是 id 列表。

### 超时与降级

超时 **8s**（对齐 JS 的 `HOLD_MS`）。超时不是灾难：调用方应退回本地兜底策略
（JS 侧 `pickFallback` / `pickChessFallback`），把这一回合走完。
**绝不能阻塞 `SessionLoop` 等待**——那是每会话唯一线程，卡 8 秒会把台词上屏和音频推帧一起冻住。
所以 `GameBrain` 接口直接返回 `Mono`，拿到结果后由调用方回到 loop 里应用。



## 6. 与上游的差异（明确"不照搬"的三处）

| 上游做法 | 我们的做法 | 理由 |
|---|---|---|
| cartridge 是纯协议对象，只维护 version/fence | 只读 `cartridgeId`，另建"ended"叙事状态 | 复制 version 会与 exe 打架，排障时无法界定责任 |
| 卸载只回 `cartridge_unmounted` | 额外做软清空 + 条件通报 | 否则退出游戏后她还在念上一局的台词 |
| 游戏内逻辑全在 exe/上游 | **在 Java 侧重写 `GameSession`**（判据 + AI 决策） | 上游是闭源 exe，我们只能按 `nori_game.js` 的行为对齐；且 `BRAIN_URL` 指向我们自己的 25503 |

## 7. 待定（不要当成已实现）

- **Q22** ✅ 已结案：见上文第 5 节（`gesture.*` 由我们注入，非客户端发）。
- **Q23**：`runtimes[].state` 已实测出结构（chess/cakeduel/manifold.web/chat 各不同），
  但**游戏卡带的 state 里没有 facts**，所以判据不能靠它 —— 只能用 `runtime_transition`。
- **Q24**：多开窗口时 `cartridgeId` 会互相覆盖（单会话假设）。真要多会话，得先拆 `SessionRuntime`。
- **Q25**：`boot.completed` 与 `cartridge_unmounted` 的先后顺序在"退出后立刻开新游戏"时未验证。
- **Q26**：`nori_game.js`（2230 行）**尚未移植** —— 当前最大缺口，涉及四游戏 AI、`/game-turn` 大脑调用、
  你画我猜的 PNG 编码。分三层来：事件归一+判据 → 大脑客户端 → 四个 AI 循环。
- **Q27**：同一条 transition 被广播 N 次，必须按 `(worldId, cartridgeId, version)` 去重。
- **Q28**：`/nori-line`（我们）与 `chat.agentMessage`（上游 LLM）是**两条平行说话通道**，
  实测 `/nori-line` 全程空、`agentMessage` 有内容 —— 两者都不能砍，但要避免同句重复。

## 8. 当前代码落点

| 关注点 | 位置 |
|---|---|
| 状态推导（只读帧） | `lifecycle.LifecycleMachine#observeFrame` |
| 换局落地（软清空 + 通报） | `app.SessionRuntime#observeFrame` |
| 帧旁路观察（不改帧） | `gateway.ArcadeWebSocketProxy`（文本帧转发前只读读一眼） |
| 台词映射 | `script.ScriptEngine`（`gesture_*` beat）+ `nori_script_lines.json` |
| 存档里的痕迹 | `save.SaveGame.Session#cartridge / ended` |
