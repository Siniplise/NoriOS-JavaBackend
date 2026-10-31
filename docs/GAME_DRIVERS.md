# 四个游戏的驱动要点（实现速查）

> 这份是**实现者视角**的要点表：每个卡带"怎么判断轮到她、我们发什么、哪里容易错"。
> 契约细节看 `CARTRIDGE.md §5.5`（`/game-turn`），问题记录看 `OPEN_QUESTIONS.md` Q22/Q26/Q32。

## 总览

| 卡带 | 中文名 | 触发点 | 她做什么 | 动作形状 | 状态 |
|---|---|---|---|---|---|
| `chess` | 国际象棋 | 下行 `/gameState`.turn | 走一步 | `{type:"move", from, to, promotion?}` | ✅ |
| `codenames` | 森林寻宝 | 下行 `history` 回合记录 | 翻牌（可多张）+ 收手 | `submitGuess` / `endTurn` / `submitClue` | ✅ 猜牌侧 |
| `cakeduel` | 蛋糕对决 | 下行 `phase` + 行动者 | 宣称/格挡/过牌 | `{type:"play", action:{...}}` | ✅ |
| `pictionary` | 你画我猜 | **上行** `submitStrokeBatch` | 看图猜词 | `{type:"submitGuess", atMs, text}` | ✅ |

共同点：**事后驱动**——玩家动作照常透传，我们看到新局面才替她出手。
所以不需要像 JS 那样"扣住玩家动作 + 推测相位"。

---

## 0 · 节奏闸门（**最容易从 JS 版带过来的问题**）

大脑回复只要 1 秒左右。拿到就立刻发指令，会同时坏两件事：

1. **观感上的"抢跑"**：玩家还在看她上一句台词/看动画，她的下一手已经生效了。
2. **更严重的：指令太密会让前端丢掉过渡** —— `gameState` 推进了、界面却停在旧状态，
   表现成「卡在 Nori 正在想提示」。这个症状极难归因，因为日志里我们**确实发出去了**。

所以有三个闸门（`game/driver/Pacer`），数值全部来自 `nori_game.js` 的实测常量：

| 闸门 | 值 | 说明 |
|---|---|---|
| 最短思考时间 | chess **2600** / codenames 1600 / cakeduel 1400 / pictionary 1200 | 从"轮到她"算起的总时长，不是额外延迟（大脑本身要 1~1.5s） |
| 多步动作间隔 | **2200** | 森林寻宝一次产出"翻牌+收手"，连着发前端来不及播翻牌动画 |
| 质疑揭示静默 | **3600** | 前端 `CHALLENGE_PRE_REVEAL_PAUSE 1000 + REVEAL_HOLD 3000 ≈ 4000`，这期间还禁用交互（玩家点不了） |

**质疑静默是"等一会儿再来"而不是"跳过"**：跳过的后果是她一直不动，玩家会以为她卡了。

**延迟之后必须重新校验**：等 2.6 秒的这段时间里局面可能已经变了
（玩家抢先动了、上游推了别的 transition）。所以发之前要比一次 `version`，
过期就丢弃——否则会发出一个基于旧局面的动作。

**实现注意**：延迟用 `Mono.delay`，**不能用 `Thread.sleep`**——这个调用在 `SessionLoop`
线程上，睡一下就冻住整条会话（台词、音频都停）。

**单测**：`setPacingEnabled(false)` 关掉闸门（否则每个用例都要真等 2.6 秒）。
生产环境永远开着。

## 0.5 · 真实帧形状（**实测 2026-10-31，别再猜**）

这一段是用 golden 逐字段核对出来的。**猜错任何一处，现象都是"她完全不动、日志里一片安静"**，
而原因根本看不出来。四个坑全是这么来的。

### `runtime_transition`

```
msg 顶层：  type / worldId / cartridgeId / version / transition
transition：actor / cmd / patches / events        ← 这里**没有** version
```

| 字段 | 正确位置 |
|---|---|
| `version` | **msg 顶层** `msg.version`；`transition.version` 是 **null** |
| `gameState` | `transition.patches[].value`（`path` 为 `/gameState`） |

### `cartridge_mounted`

```
msg 顶层：  type / worldId / cartridgeId / transition / runtimes
runtimes[0]：visibilityFenceId / headVersion / visibleVersion / state
state：    settings / gameState / drawOffer / takebackRequest / tutorial / …
```

| 字段 | 正确位置 | 踩过的坑 |
|---|---|---|
| `playerSide` | `runtimes[0].state.settings.playerSide` | **不在** gameState 里，也**不在** msg 顶层 |
| `headVersion` | `runtimes[0].headVersion` | **没人读它** ⇒ head 一直是 null ⇒ 所有指令都发不出去 |
| `gameState` | `runtimes[0].state.gameState`，但**实测是空对象** `{}` | 挂载时没有局面，要等 `game_started` 的 patch |

### chess 的 `gameState`

```
{fen, pgn, turn, phase, status, fullMoveNumber, halfMoveClock,
 isCheck, isCheckmate, isStalemate, isDraw, isInsufficientMaterial,
 isThreefoldRepetition, winner, moveHistory, startFen, openingMatch}
```

- **没有 `settings`**（所以 playerSide 绝不能从这里找）
- **`moveHistory` 是数组**（元素可能是 `{san}` 或纯字符串）

### ★ 最容易炸的一条：`text()` 不能读数组

```java
// ❌ ArrayNode.asString() 直接抛 JsonNodeException
String recent = text(gameState, "moveHistory");

// ✅ 按数组读
JsonNode mh = gameState.get("moveHistory");
if (mh != null && mh.isArray()) { ... }
```

这个异常会**打断整条 `observe`**，而 `SessionRuntime` 原来把它吞成 `debug` ⇒
日志里什么都看不到。现在 `GameEvents.text()` 对数组/对象一律返回 null（防御），
并且驱动异常改成 `warn`（可见）。

### 另外两条兜底

- transition **不带** `/gameState` 时（纯事件帧），沿用上一次缓存的局面；
  否则所有判定会在"纯事件帧"上直接放弃。
- 挂载消息里**带了非空** `gameState` 时要立刻收下（chess 是空的，但别的卡带可能给）。

---

## 1 · 国际象棋（`chess`）

**怎么判"轮到她"**：`gameState.fen` 解析出来的 `turn` 与她的颜色一致。
她的颜色 = 玩家颜色（`settings.playerSide`）的**反面**。

**必须同时满足**（任一不满足就返回空）：
1. 有合法 `fen`；
2. `turn` 是她的；
3. `status` / `phase` 不是终局（`checkmate`/`ended`/`draw`/`resigned`…）；
4. 她**还有棋可走**（将死/逼和时不问，省一次推理）。

**要点**
- 走法生成靠自研引擎，正确性由 **perft** 证明（初始局面 4 层 197281、Kiwipete 3 层 97862）。
- `desc` 必须是 `SAN (from→to)`；大脑靠 SAN 理解棋，靠 `id` 回填动作。
- 合法走法只列前 80 条，但 `parseId` **认得全部**——截断不等于非法。
- 升变要带 `promotion` 字母（`q`/`r`/`b`/`n`）。

**易错点**
- 方向搞反（她执哪方 vs 现在轮到谁）——`ChessPosition` 的方法特意叫 `turnIsWhite()` 而不是 `sheIsWhite()`。
- 忘了剔除"自己王被将"的走法 → perft 立刻对不上。

---

---

## 1.5 · 非走子类请求（悔棋 / 和棋）—— 通道归我们，决策归 LLM

**事件**：`request_takeback`（求悔棋）、`draw_offer`（提和）。
**应答命令**：`respondTakeback{accept}` / `respondDraw{accept}`。

### 为什么必须问 LLM

答不答应是**人格决策**，不是规则决策——取决于她跟玩家什么关系、当前谁占优、她心情如何。
硬编码 `always accept` 就把这个角色毁了。

**关键在于：这不用改 25503。** `n_llm.js#gameTurn` 完全通用：

```js
const g = String(body.game || 'game').slice(0, 24);   // 任意字符串，不校验
```

`game` 只是个标签，`legal` 是自由列表。所以悔棋应答**就是一次普通的 `/game-turn`**，
只是 observation 换成"他点了求悔棋"、legal 换成 `[{accept, refuse}]`。

已实测（2026-10-31）她回：

```json
{"ok":true,"actions":["accept"],
 "lines":["唔...悔棋呀，那好吧。","不过下次Nori可不一定让着你哦。"],
 "emotion":"doubtful","reason":"他主动求悔棋，让一步也无妨"}
```

`reason` 是她自己的判断，不是我们写的 if/else。

### 三条实现约束

1. **与走子互斥**：处理了 meta 就 `return`，同一个 transition 不能既应答又走子（局面会被推两次）。
2. **只认明确 accept**：`actions` 里没有 `accept` 就一律按拒绝。拒绝不改变局面，是更安全的默认。
3. **嵌套事件也要认**：cakeduel 的事件会被 `engine` 包裹，用 `GameEvents.type()`（内部 flatten）。

### 已知的风险点（未验证）

- `request_takeback` 的**真实 payload 形状未知**。文档只说 exe 里有这个串（`@65157233`），
  全量日志里出现过 1 次。现在的实现**只依赖事件类型**、不读 payload，所以形状未知也不影响应答——
  但如果它带了"哪一步"之类的参数，我们没法回显给她。
- **`history_rewound` 之后要重算局面**：悔棋生效后上游会推进局面，我们的 `lastStates` 靠
  `transition.patches` 更新，所以会自动跟上；但如果上游**不广播**新 gameState，本地 fen 就会脱节。

## 2 · 森林寻宝（`codenames`）

**怎么判"轮到她猜牌"**：
- `history` 最后一条**未结束**（没有 `endedBy`）；
- 且给词方**不是她**（`clueGiver` 是玩家）。

**提示词从哪来**：`history[].clue`（上游权威记录），不自己另记一份——提示词是玩家发的上行命令，我们没拦。

**要点**
- `maxActions` 是 **9**（一次能连猜多张），但实测通常只猜 1~2 张。
- 动作 id：`cell:N`（翻第 N 格）/ `end`（收手）/ `clue`（顺带给词）。
- **猜完必须 `endTurn` 收手**：本地对手会在"仍是她的回合"时替她连猜，而且它**读得到答案**。
  所以只要猜了牌、她又没主动选 `end`，我们**自动补一个** `endTurn`。
- `clue` 但没给词 ⇒ **跳过该动作**（曾用「诺莉」兜底，那是假 AI 的占位词，一触发玩家就以为假 AI 又活了）。

**易错点**
- `bystanderMarks` 每格只有两个槽，会互相覆盖 ⇒ 判"连续踩浆果"要**逐次累加事件**，不能只看终态。
- 「她给提示词」这条路径依赖她能看到的 key 地图（★我的词 / X 怪兽），**字段未校准，暂不驱动**。

---

## 3 · 蛋糕对决（`cakeduel`）

**怎么判"轮到她"**：看 `phase` 推出的**行动者**是不是她。这是四个卡带里最容易写错的一处：

| 相位 | 行动者 | 说明 |
|---|---|---|
| `attack` | 攻击方 | |
| `block` | **被攻击方**（`1 - attackerIndex`） | 是他要格挡，不是攻击方 |
| `review` | 攻击方 | 回应对面的格挡 |
| `pick` | `pickPhaseEffects[0].player` | 两边都可能挑，**不能按攻击方算** |
| 其它 | -1 | 没人该动 |

**要点**
- 卡牌：`wolfy` **不能宣称**（ARG 剧情节点）；`defender` 只挡物理、`scientist` 只挡魔法。
- 格挡可选牌要**按伤害类型匹配**（`attackType`）。
- `pick` 相位**没有 pass**（必须挑一张）。
- 动作映射：`claim:i` → `play.action.{type:"claim", handIndices:[i], claim:<牌名>}`；
  `i` 是**手牌下标**，不是牌表下标（容易混）。
- 一次只取**第一个**合法 id（多给会连发两个动作搞乱相位）。

**易错点（JS 里改出来的两条，不要"简化"回去）**
- `block` 相位过牌 → 回 **attack**，不是 `review`。写错会让 actor 算错 ⇒ 我方漏补位 ⇒ 整局卡死。
- `challenge` → 直接 **bout_end**，不存在"挑战完接着 attack"。

### ⛔ special 卡：按计划**整块剔除**，不要逐条修

卡池里有一组特殊能力牌：`priest` / `angel` / `baacrates` / `quartermaster` / `oracle` /
`summoner` / `agent_u` / `pierrot`。它们不是"攻击 1 点"这种普通效果，而是改规则的
（召唤额外牌、预言对方手牌）。

**官方自己没开这套**（exe 里 sleep 掉了），所以实战中根本不会出现。
`91_roadmap.md` 已写明「⛔ special/promo 全套 已锁定不做」，`96` 写「special 整块剔除」。

**我们抄 JS 表时把三条 bug 一起抄来了**（`80_bug_registry.md`）：

| ID | 内容 |
|---|---|
| B-006 | 格挡条件写成 `atkType === 'all_types'`，应为 `meta.blockType === 'all_types'` |
| B-007 | `priest` / `angel` 应是 **blocker**、`baacrates` 应 **unclaimable** |
| B-008 | 漏 `claimBlacklist` 过滤（witch 的 `name_and_blacklist`） |

**后果**：这几张写成 `attack=0`（不是 `null`），而 `claimable()` 判的是 `attack != null`
⇒ **它们变成"可宣称的 0 伤牌"**。不是标签错，是真能被打出来。

**正确做法**：不实现它们（`meta()` 对它们返回不可宣称/不可格挡），而不是逐条修 ——
修了也没有收益（默认不开），而且会留下"修了一半"的状态。

---

## 4 · 你画我猜（`pictionary`）

**怎么判"轮到她"**：`roles.guesser == "agent"`。没有 `roles` 字段时**保守判定为不猜**（宁可不动，不抢玩家回合）。

**触发点是上行**：笔画来自玩家的 `submitStrokeBatch` 命令，上游**不会**为它广播局面变化。
所以要给 `browser.receive()` 加旁路观察，攒进 `PictionaryBoard` 后**直接**触发一次看图，去抖 700ms。

**要点**
- 渲染 256×256：底色是**纸色 `#EDE3CB`**（不是白色！前端橡皮画的就是纸色），
  笔刷半径 `clamp(round(width/400·S/2+1), 1, 9)`，相邻采样点**必须插值**。
- 一笔没画 ⇒ **不发请求**（空白画面猜出来必然是瞎猜）。
- 回复的答案在 **`clue.word`**（不是 `actions`）；`clueMode=true` 在这里的含义是"答案写进 clue"，
  与森林寻宝的"给提示词"**同名不同义**。
- 同一个词不重发（她会对同一张图连给同样答案）。

**易错点**
- 旁路观察必须放在 `toUpstreamFrame` **之后**（frame 已复制字节）；
  直接在 `WebSocketMessage` 上读文本会消费 payload，导致转发出去是空的。
- 上行帧很密（画画时每秒几十帧）⇒ 先用便宜的 `contains` 预筛，只有笔画帧才排进 SessionLoop。

---

## 共同的两个防呆（不是优化，是正确性）

1. **同一 `version` 只问一次**：上游按连接数广播 3 份（Q27），我们也会重连——不去重她会连走三步。
2. **没有 `headVersion` 就不发**：`expectedHeadVersion` 不对时上游**静默丢弃**（不报错、不回 ack），
   "她为什么不动"会变成查不出原因的谜。

## 报文形状：两种 dispatch 不一样

| 发送方 | 报文 |
|---|---|
| **玩家** | `{type, cartridgeId, actor:"player", requestId, expectedHeadVersion, cmd}` |
| **我们（agent）** | `{type, worldId, cartridgeId, actor:"agent", visibilityFenceId, cmd, expectedHeadVersion, requestId}` |

依据：玩家的是 golden 实测，agent 的是 `nori_game.js#sendAgent`。**两者不同这点很容易漏**。

## 开关

默认**关闭**，`-Dnori.game.driver=on` 打开。这条链路会替她往上游发命令，判错就是污染对局。

## 未验证 / 待校准

| 项 | 状态 |
|---|---|
| `chess` / `cakeduel` / `codenames` 的 `gameState` 字段名 | 从 JS 源码推，**未用真实帧核对** |
| `pictionary` 的 `roles/guesses/pinyin` | 同上 |
| `submitStrokeBatch` 的封装（`cmd.batch` vs 顶层 `batch`） | 两种都试了，**未确认** |
| 整条驱动链路的真机效果 | **未验证**（只有单测） |
| 寻宝「她给提示词」 | 未实现（需 key 地图） |
