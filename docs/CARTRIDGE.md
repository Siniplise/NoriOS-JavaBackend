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

关键发现（实测）：`gesture.chess / gesture.codenames / gesture.pictionary / gesture.cakeduel`
这四个 fact **是前端 bundle 自己发的**（`NormalApp` 的 QfrDock 里写死），不是代理造的。

所以映射关系是：

```
前端 QfrDock（点开游戏）──emitFact gesture.<id>──► WS ──► 我们的 lifecycle.observeFact
                                                          └─► script 引擎 ─► gesture_<id> beat ─► 台词 + 记忆
```

我们**不重新发明这套 fact**，只保证：
- 收到 `gesture.*` 时按普通 fact 走（`ScriptEngine.onFact`）；
- 台词表里四个 `gesture_*` beat 是唯一真源，**改台词不改代码**。

## 6. 与上游的差异（明确"不照搬"的三处）

| 上游做法 | 我们的做法 | 理由 |
|---|---|---|
| cartridge 是纯协议对象，只维护 version/fence | 只读 `cartridgeId`，另建"ended"叙事状态 | 复制 version 会与 exe 打架，排障时无法界定责任 |
| 卸载只回 `cartridge_unmounted` | 额外做软清空 + 条件通报 | 否则退出游戏后她还在念上一局的台词 |
| 游戏交互靠协议事件 | 额外接 `gesture.*` fact 驱动台词 | 我们的叙事层需要"他打开了这个游戏"这种语义，协议里没有 |

## 7. 待定（不要当成已实现）

- **Q22**：`gesture.*` 目前只在"点开游戏"时发一次。玩到一半再点开同一个游戏，会不会重发？
  若不重发，`gesture_*` beat 就是一次性 —— 与"每次玩都该说一句"的产品预期不符，需要实测。
- **Q23**：`runtimes` 里的 `state` 我们完全没用。如果将来要在管理器里显示"当前局面"，
  得先确认哪些字段是**公开可依赖**的（`VERIFIED_PROTOCOL` 只给了形状，没给字段语义）。
- **Q24**：多开窗口时 `cartridgeId` 会互相覆盖（单会话假设）。真要多会话，得先拆 `SessionRuntime`。
- **Q25**：`boot.completed` 与 `cartridge_unmounted` 的先后顺序在"退出后立刻开新游戏"时未验证，
  可能导致 `resetStory` 与软清空交错。

## 8. 当前代码落点

| 关注点 | 位置 |
|---|---|
| 状态推导（只读帧） | `lifecycle.LifecycleMachine#observeFrame` |
| 换局落地（软清空 + 通报） | `app.SessionRuntime#observeFrame` |
| 帧旁路观察（不改帧） | `gateway.ArcadeWebSocketProxy`（文本帧转发前只读读一眼） |
| 台词映射 | `script.ScriptEngine`（`gesture_*` beat）+ `nori_script_lines.json` |
| 存档里的痕迹 | `save.SaveGame.Session#cartridge / ended` |
