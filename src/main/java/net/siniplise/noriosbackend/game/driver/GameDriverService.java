package net.siniplise.noriosbackend.game.driver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.siniplise.noriosbackend.game.GameBrain;
import net.siniplise.noriosbackend.game.GameEvents;
import net.siniplise.noriosbackend.game.cakeduel.CakeduelPosition;
import net.siniplise.noriosbackend.game.cakeduel.CakeduelRules;
import net.siniplise.noriosbackend.game.codenames.CodenamesPosition;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import net.siniplise.noriosbackend.gateway.UpstreamChannels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 帧流驱动的编排器：**看到帧 → 判断该她动了 → 异步问她 → 把指令发回上游**。
 *
 * <h2>线程模型（这块最关键）</h2>
 * <ul>
 *   <li>{@link #onTransition} 在 {@code SessionLoop} 线程（唯一，串行）上被调用——它只做
 *       "判断 + 发起"，**绝不等待**。等 8 秒会冻住整条会话（台词上屏、音频推帧都在这条线上）。</li>
 *   <li>大脑的回复在 reactor 线程到达，必须**回到 loop** 才能改状态、发指令
 *       （{@code UpstreamChannels} 本身线程安全，但"防重入标记"不是）。</li>
 * </ul>
 *
 * <h2>两个防呆</h2>
 * <ol>
 *   <li><b>同一版本只问一次</b>：上游会把同一条 transition 按连接数广播多份（Q27），
 *       而且我们自己也可能重连——不去重就会连问她三次，她连走三步。</li>
 *   <li><b>没有 headVersion 就不发</b>：{@code expectedHeadVersion} 不对时上游**静默丢弃**，
 *       发了等于没发，还会让"她为什么不动"变成一个查不出原因的谜。</li>
 * </ol>
 *
 * <h2>开关</h2>
 * 默认**关闭**（{@code -Dnori.game.driver=on} 开启）。理由：这条链路会替她往上游发命令，
 * 一旦判断错就是"我们污染了对局"。默认关掉，让它在真机上被显式打开、被观察。
 */
public final class GameDriverService {

    private static final Logger log = LoggerFactory.getLogger(GameDriverService.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 把任务送回会话线程执行。 */
    @FunctionalInterface
    public interface LoopExecutor {
        void run(Runnable task);
    }

    private final GameBrain brain;
    private final UpstreamChannels.InjectorHolder injector;
    private final LoopExecutor loop;
    private final GameHeads heads = new GameHeads();

    /** 已问过的 (卡带 → 版本)，防同一回合重复问。 */
    private final Map<String, Integer> askedAt = new LinkedHashMap<>();

    /** 玩家执的颜色（从 game_start / settings 记下来）。 */
    private final Map<String, String> playerSides = new LinkedHashMap<>();

    /** 每个卡带的 visibilityFenceId（agent 的 dispatch 需要它）。 */
    private final Map<String, String> fences = new LinkedHashMap<>();

    /** 每个卡带最新的 gameState（你画我猜要用它取 roles/guesses/pinyin）。 */
    private final Map<String, JsonNode> lastStates = new LinkedHashMap<>();

    /** 你画我猜的画布（笔画只在上行命令里）。 */
    private net.siniplise.noriosbackend.game.pictionary.PictionaryBoard pictureBoard;

    /** 上次问"她画的是什么"的时间，用来去抖（玩家还在画就别猜）。 */
    private long lastPicAt;

    /** 上一次猜出来的词，用来避免同一张图反复发同一个答案。 */
    private String lastPicGuess;

    /** 各卡带"轮到她"的时刻，用来算"她还该想多久"（节奏闸门）。 */
    private final Map<String, Long> turnStartedAt = new LinkedHashMap<>();

    /** 蛋糕对决：质疑揭示动画结束前不许她出手。 */
    private long cakeHoldUntil;

    /** 各卡带当前这一回合对应的 version，延迟后用来判断"这条动作是不是过期了"。 */
    private final Map<String, Integer> turnVersion = new LinkedHashMap<>();

    /** 节奏闸门开关。生产恒开；单测关掉，否则每个用例都要真等 2.6 秒。 */
    private boolean pacing = true;

    /** 已经打印过"transition 里没有 gameState"的卡带（每个只打一次）。 */
    private final java.util.Set<String> transitionShapeLogged = new java.util.LinkedHashSet<>();

    /** 已经打印过"首次局面结构"的卡带（每个只打一次，避免刷屏）。 */
    private final java.util.Set<String> stateShapeLogged = new java.util.LinkedHashSet<>();

    /** 各卡带上一次"未驱动"的**原因**。只有原因变化时才用 INFO，否则 DEBUG。 */
    private final Map<String, String> lastSkipReason = new LinkedHashMap<>();

    /**
     * 玩家刚给过的提示词（从**上行** `submitClue` 命令抓）。
     *
     * <p><b>为什么必须自己记</b>：实测 codenames 的 `gameState` 里**没有**当前提示词
     * （顶层键只有 board/key/cells/tokensRemaining/whoseTurnToGive/phase/winner/history，
     * 而 `history` 在开局第一轮是**空数组**）。提示词只存在于玩家发出的上行命令里。
     */
    private net.siniplise.noriosbackend.game.codenames.CodenamesPosition.Clue pendingCodenamesClue;

    /**
     * 正在**串行发送指令**的卡带。
     *
     * <p>寻宝一次要发"翻牌 + 收手"两条，中间隔 {@link Pacer#STEP_MS}。这段时间里上游会广播
     * 局面变化 ⇒ 新 transition 又触发一次 drive ⇒ 两轮指令交错，第二轮的 head 已经比第一轮新，
     * 于是第一轮还没发的 endTurn 带着**过期 head** 发出去（被拒，或者打乱态）。
     * 实测 2026-10-31 就是这个症状：翻牌和收手交叉、动画乱。
     */
    private final java.util.Set<String> sendingCarts = new java.util.LinkedHashSet<>();

    /** 是否已经就"接到第一帧"打过日志。 */
    private final java.util.concurrent.atomic.AtomicBoolean firstFrameSeen =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 笔画去抖窗口：玩家停笔多久才看图。对齐 JS 的 {@code PIC_DEBOUNCE_MS}。 */
    private static final long PIC_DEBOUNCE_MS = 700L;

    /** 台词输出口（回到 loop 后调用）。传整个 reply 而不是只有 lines，是为了带上 emotion。 */
    private Consumer<GameTurnReply> onSpoken = reply -> { };
    private boolean enabled = false;
    private String worldId;

    public GameDriverService(GameBrain brain, UpstreamChannels.InjectorHolder injector, LoopExecutor loop) {
        this.brain = brain;
        this.injector = injector;
        this.loop = loop == null ? Runnable::run : loop;
        // 默认关闭：见类注释。用 -Dnori.game.driver=on 打开
        this.enabled = "on".equalsIgnoreCase(System.getProperty("nori.game.driver", "off"));
        // ★ 无论开没开都要出声。
        //   只开着才打日志的后果是：关着的时候**整份日志里一行都找不到**，
        //   于是"没接线"和"没开开关"看起来一模一样（这次就踩了）。
        if (brain == null) {
            log.warn("game: 驱动没有大脑客户端（GameBrain 未注入）—— 即使开启也不会动作");
        }
        if (enabled) {
            log.warn("game: 帧流驱动已开启（会替她向上游发指令）— 关闭用 -Dnori.game.driver=off");
        } else {
            log.info("game: 帧流驱动已就位但**未开启**（默认关闭）—— 打开用 -Dnori.game.driver=on");
        }
    }

    public void setOnSpoken(Consumer<GameTurnReply> sink) {
        if (sink != null) {
            this.onSpoken = sink;
        }
    }

    /**
     * 关掉节奏闸门（**仅供单测**）。
     *
     * <p>生产环境永远不要关：关掉后她会"秒回"，而且多条指令会连发，
     * 前端可能因此丢掉过渡（界面停在旧状态）。
     */
    public void setPacingEnabled(boolean on) {
        this.pacing = on;
    }

    /**
     * 记下玩家给的提示词（由会话层在上行帧里抓到时调用）。
     *
     * <p>实测（2026-10-31）：codenames 的 gameState **不含**提示词，
     * `history` 在开局第一轮又是空的 ⇒ 不去上行抓，就永远不知道他在提示什么。
     */
    public void noteClue(String word, int count) {
        if (word == null || word.isBlank()) {
            return;
        }
        int c = count <= 0 ? 1 : count;
        pendingCodenamesClue = new net.siniplise.noriosbackend.game.codenames.CodenamesPosition.Clue(
                word.strip(), c);
        log.info("game: 记下玩家的提示词「{}（{} 个）」", word.strip(), c);
    }

    /** 注入画布（由会话层提供，因为它同时被上行观察写入）。 */
    public void setPictureBoard(net.siniplise.noriosbackend.game.pictionary.PictionaryBoard board) {
        this.pictureBoard = board;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 测试用：直接打开/关闭，绕过系统属性。 */
    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public GameHeads heads() {
        return heads;
    }

    /**
     * 观察一条上游消息。**在 loop 线程调用。**
     *
     * <p>无论开关是否打开，都会记账（heads / playerSide）——这样打开开关的那一刻
     * 状态就是对的，不用等下一局。
     */
    public void observe(String rawText) {
        if (rawText == null) {
            return;
        }
        // ★ 先 strip 再判首字符：上游帧是干净的，但凡是"外部输入"就别假设没有前导空白，
        //   否则一条带空格的帧会被**静默丢掉**（表现和"没收到"完全一样，极难查）。
        String raw = rawText.strip();
        if (raw.length() < 2 || raw.charAt(0) != '{') {
            return;
        }
        JsonNode msg;
        try {
            msg = MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            return;
        }
        if (msg == null || !msg.isObject()) {
            return;
        }
        if (firstFrameSeen.compareAndSet(false, true)) {
            // 这一行是诊断的分水岭：
            //   有它 + enabled=false ⇒ 只差开关；有它 + enabled=true ⇒ 该看"轮到她"没命中；
            //   没它 ⇒ 帧流根本没进到驱动（WS 层问题），别在驱动里找原因。
            log.info("game: 驱动已接到第一帧（enabled={}，cart={}）", enabled, text(msg, "cartridgeId"));
        }

        String w = text(msg, "worldId");
        if (w != null && !w.isBlank()) {
            worldId = w;
        }
        String type = text(msg, "type");
        if (type == null) {
            return;
        }

        heads.observe(msg);
        String cart = text(msg, "cartridgeId");

        if ("runtime_transition".equals(type) && cart != null) {
            JsonNode tr = msg.get("transition");
            if (tr != null && tr.isObject()) {
                rememberPlayerSide(cart, tr);
                noteChallenge(cart, tr);
                JsonNode gs = latestGameState(tr);
                if (gs != null) {
                    lastStates.put(cart, gs);
                    // ★ 每个卡带**第一次**收到局面时，把结构打出来。
                    //   这一天为了"字段到底叫什么"来回猜了三轮（version / playerSide / headVersion / moveHistory），
                    //   每次都是靠猜——而这一行日志能让下一次直接看到真相，不用再等真机翻车。
                    if (stateShapeLogged.add(cart) && isDrivenCart(cart)) {
                        // 打"每个关键字段的长度"而不是整段 JSON：截断的 JSON 常常正好把要看的那段切掉
                        // （2026-10-31 就因此没看到 cells/history，白等了一轮）。
                        log.info("game: {} 首次收到局面 {} 字节 | 顶层键={} | board={} cells={} history={}"
                                        + " key={} clue={} phase={}",
                                cart, gs.toString().length(), topLevelKeys(gs),
                                sizeOf(gs, "board"), sizeOf(gs, "cells"), sizeOf(gs, "history"),
                                sizeOf(gs, "key"), sizeOf(gs, "clue"), shortText(gs, "phase"));
                        // 再把 history 单独打出来（寻宝的"轮到她"完全依赖它）
                        JsonNode hist = gs.get("history");
                        if (hist != null && hist.isArray()) {
                            String h = hist.toString();
                            log.info("game: {} history={}", cart,
                                    h.length() > 900 ? h.substring(0, 900) + "…" : h);
                        }
                    }
                }
            }
            if (enabled) {
                // ★ version 在**消息顶层**（实测 `msg.version=1, transition.version=null`），
                //   之前从 tr 里取，所以永远是 null ⇒ 去重失效 + 日志全 v=null。
                drive(cart, tr, intOrNull(msg, "version"));
            }
        } else if ("cartridge_mounted".equals(type) && cart != null) {
            // 新局：版本会从头开始，旧 head 留着会让新局所有指令被拒
            heads.reset(cart);
            askedAt.remove(cart);
            lastStates.remove(cart);
            // ★ 挂载消息里带着初始 headVersion（`runtimes[0].headVersion`）。
            //   不读它的话 head 会一直是 null ⇒ 后面所有"发指令"都会因为"不知道 headVersion"而放弃，
            //   而日志只会在最后一刻说一句"不知道 headVersion"，前面的判定看起来全都正常 —— 极难归因。
            Integer mountedHead = firstRuntimeInt(msg, "headVersion");
            if (mountedHead != null) {
                heads.bump(cart, mountedHead);
            }
            rememberFence(cart, msg);
            JsonNode mountedState = mountedGameState(msg);
            if (mountedState != null) {
                lastStates.put(cart, mountedState);
            }
            if (isDrivenCart(cart)) {
                log.info("game: 挂载 {} → head 已重置，等待 /gameState", cart);
            }
            rememberPlayerSide(cart, msg);
        } else if ("cartridge_unmounted".equals(type) && cart != null) {
            heads.reset(cart);
            askedAt.remove(cart);
            playerSides.remove(cart);
        }
    }

    /**
     * 判断并驱动。**只发不问**——真正的等待交给大脑的异步回调。
     *
     * @param cart 卡带
     * @param tr   {@code transition}（含 events / patches / version）
     */
    private void drive(String cart, JsonNode tr, Integer version) {
        if (brain == null || tr == null || !tr.isObject()) {
            return;
        }
        Integer already = askedAt.get(cart);
        if (version != null && already != null && already >= version) {
            return;   // 这一帧已经问过（上游按连接数广播多份）
        }
        if (sendingCarts.contains(cart)) {
            noteSkip(cart, version, "上一条指令还在串行发送中，本轮不重复驱动");
            return;
        }
        // 从 patches 里找出最新的 gameState（上游每次推进都会 replace 它）
        JsonNode gameState = latestGameState(tr);
        if (gameState == null) {
            // 有些 transition 只带 events、不带 /gameState（局面没变）。
            // 这时用上一次缓存的局面 —— 否则"纯事件帧"会让所有判定直接放弃。
            // （不会因此重复出手：同一 version 有去重兜底）
            gameState = lastStates.get(cart);
        }
        if (gameState == null) {
            if (isDrivenCart(cart)) {
                // 每个卡带只打一次 transition 的"长什么样"：cakeduel 实测就没有 /gameState，
                // 必须知道它的局面到底放在哪（patches 的哪个 path / 还是 events 里）。
                if (transitionShapeLogged.add(cart)) {
                    log.info("game: {} v={} 没有 /gameState —— transition 键={} patches={} events={}",
                            cart, version, topLevelKeys(tr), patchPaths(tr), eventTypes(tr));
                }
                noteSkip(cart, version, "这一帧没有 /gameState，也没有可用的缓存局面");
            }
            return;
        }

        // 三个"事后驱动"的卡带共用同一个套路：判是否轮到她 → 构造请求 → 问 → 回 loop 发指令。
        // 差异只在"怎么判"和"回答怎么变成 cmd"，所以按卡带分派、其余逻辑复用。
        Integer head = heads.head(cart);
        // ★ 先处理"非走子类"请求（悔棋 / 和棋）：它们跟轮次无关，而且处理完就不该再走子
        if ("chess".equals(cart) && handleChessMeta(cart, tr, head, version)) {
            return;
        }
        if ("chess".equals(cart)) {
            Optional<ChessDriver.Turn> plan = ChessDriver.plan(gameState, playerSides.get(cart), "");
            if (plan.isEmpty()) {
                noteSkip(cart, version, "不是她的回合 / 已终局 / 无棋可走（playerSide="
                        + playerSides.get(cart) + "）");
                return;
            }
            rememberTurn(cart, version);
            markAsked(cart, version);
            ChessDriver.Turn turn = plan.get();
            log.info("game: 轮到她 cart=chess head={} 合法 {} 步", head, turn.request().legalIds().size());
            brain.turn(turn.request()).subscribe(
                    reply -> onReply(cart, turn, reply, head, version),
                    error -> fail(cart, version, error));
            return;
        }
        if ("cakeduel".equals(cart)) {
            CakeduelRules.Phase phase = CakeduelRules.currentForAgent(gameState);
            if (phase == null || !phase.isAgentTurn()) {
                noteSkip(cart, version, phase == null ? "gameState 不完整"
                        : ("没轮到她（phase=" + phase.phase() + " actor=" + phase.actor()
                           + " 她=" + CakeduelRules.AGENT_INDEX + "）"));
                return;
            }
            // ★ 质疑揭示动画期间她不能出手（前端这 ~4 秒还会禁用交互，玩家点不了）。
            //   不是"跳过"而是"等一会儿再来"——等她一直不动，玩家会以为她卡了。
            long holdLeft = cakeHoldUntil - System.currentTimeMillis();
            if (holdLeft > 0) {
                log.info("cake: 质疑揭示动画中，{}ms 后再问她", holdLeft);
                final JsonNode trForLater = tr;
                scheduleAfter(holdLeft, () -> loop.run(() -> drive(cart, trForLater, version)));
                return;
            }
            rememberTurn(cart, version);
            markAsked(cart, version);
            var pos = CakeduelPosition.from(gameState, phase, CakeduelRules.AGENT_INDEX);
            GameTurnRequest req = pos.toRequest("");
            log.info("game: 轮到她 cart=cakeduel phase={} head={} 合法 {} 个", phase.phase(), head,
                    req.legalIds().size());
            brain.turn(req).subscribe(
                    reply -> onCardReply(cart, pos, reply, head, version),
                    error -> fail(cart, version, error));
            return;
        }
        if ("codenames".equals(cart)) {
            // ★ 只认**上行抓到的**提示词。
            //   不能再用 history 兜底：她猜中后上游会把这一轮写进 history，
            //   兜底逻辑又会把同一个 clue 找出来 ⇒ 反复驱动同一轮（实测就是这样交叉发指令的）。
            Optional<CodenamesPosition.Clue> clue = Optional.ofNullable(pendingCodenamesClue);
            var pos = CodenamesPosition.from(gameState);
            if (clue.isEmpty()) {
                noteSkip(cart, version, "还没收到玩家给的提示词（gameState 里也没有）");
                return;
            }
            if (!pos.hasOpenCells()) {
                noteSkip(cart, version, "格子都翻完了");
                return;
            }
            if (isAgentSide(text(gameState, "whoseTurnToGive"))) {
                // whoseTurnToGive 指向她 ⇒ 该她给词，不是猜。给词那条路径未实现（需要 key 地图），先跳过。
                noteSkip(cart, version, "现在轮到她给词（whoseTurnToGive=agent），给词路径未实现");
                return;
            }
            // 这个提示词已经被消费掉：避免同一轮反复猜
            pendingCodenamesClue = null;
            rememberTurn(cart, version);
            markAsked(cart, version);
            GameTurnRequest req = pos.toGuessRequest(clue.get(), "");
            log.info("game: 轮到她 cart=codenames 提示「{}」head={} 可选 {} 个", clue.get().display(), head,
                    req.legalIds().size());
            brain.turn(req).subscribe(
                    reply -> onCodenamesReply(cart, pos, reply, head, version),
                    error -> fail(cart, version, error));
        }
    }

    /**
     * 处理**非走子类**的棋局请求：悔棋、和棋。
     *
     * <p><b>为什么这类请求要单独一条通道</b>：它们不是规则决策，是**人格决策**——
     * 答不答应取决于她跟玩家什么关系、当前局面谁占优、她心情如何。
     * 硬编码"always accept"就毁了这个角色，所以必须问大脑。
     *
     * <p><b>好消息是不用改 25503</b>：`n_llm.js#gameTurn` 完全通用，`game` 只是个标签，
     * `legal` 是自由列表。所以这只是一次普通的 `/game-turn` 调用，
     * 把 observation 换成"他点了求悔棋"、legal 换成 accept/refuse 就行（已实测她能答）。
     *
     * <p>**与"轮到她走子"互斥**：处理了 meta 就 return，避免同一个 transition 又答又问又走子。
     *
     * @return 是否处理了这个 transition（true 时调用方不再走子逻辑）
     */
    private boolean handleChessMeta(String cart, JsonNode tr, Integer head, Integer version) {
        JsonNode events = tr.get("events");
        if (events == null || !events.isArray()) {
            return false;
        }
        for (JsonNode raw : events) {
            String type = GameEvents.type(raw);
            if (type == null) {
                continue;
            }
            String kind = switch (type) {
                case "request_takeback" -> "takeback";
                case "draw_offer" -> "draw";
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            if (brain == null) {
                return false;
            }
            markAsked(cart, version);
            askMeta(cart, kind, tr, head);
            return true;
        }
        return false;
    }

    /** 问她"答不答应"，把 accept/refuse 翻译成 respondTakeback / respondDraw。 */
    private void askMeta(String cart, String kind, JsonNode tr, Integer head) {
        JsonNode gs = latestGameState(tr);
        if (gs == null) {
            gs = lastStates.get(cart);   // 悔棋请求未必带 gameState，用缓存的当前局面
        }
        String fen = gs == null ? null : text(gs, "fen");
        String playerSide = playerSides.get(cart);

        boolean takeback = "takeback".equals(kind);
        StringBuilder obs = new StringBuilder();
        obs.append("国际象棋：他在对局中点了「").append(takeback ? "求悔棋，想退回上一步重走" : "和棋")
                .append("」，正在等你答复。\n");
        if (fen != null && !fen.isBlank()) {
            obs.append("当前局面 FEN：").append(fen).append('\n');
        }
        if (playerSide != null) {
            boolean playerWhite = "white".equalsIgnoreCase(playerSide);
            obs.append("他执").append(playerWhite ? "白方" : "黑方").append("，你执另一方。\n");
        }
        obs.append("答不答应由你决定：答应就").append(takeback ? "退回那一步" : "和棋成立")
                .append("，拒绝就继续下。\n你可以在 line 里说一句，别太长。");

        List<Map<String, Object>> legal = new ArrayList<>();
        Map<String, Object> accept = new LinkedHashMap<>();
        accept.put("id", "accept");
        accept.put("desc", takeback ? "答应他悔棋，把局面退回上一步" : "同意和棋");
        Map<String, Object> refuse = new LinkedHashMap<>();
        refuse.put("id", "refuse");
        refuse.put("desc", "不答应，继续下");
        legal.add(accept);
        legal.add(refuse);

        GameTurnRequest req = new GameTurnRequest(cart, obs.toString(), "", legal, 1, false, null);
        log.info("game: 收到{}请求，问大脑", takeback ? "悔棋" : "和棋");
        brain.turn(req).subscribe(
                reply -> onMetaReply(cart, kind, reply, head),
                error -> log.warn("game: 答复{}失败: {}", kind, error.toString()));
    }

    /** 把她的选择发回上游。**只有明确 accept 才当真**，其余一律按拒绝（拒绝不会改变局面，更安全）。 */
    private void onMetaReply(String cart, String kind, GameTurnReply reply, Integer head) {
        long wait = pacing ? Pacer.waitMs(Pacer.thinkMs(cart), 0) : 0;
        scheduleAfter(wait, () -> loop.run(() -> {
            try {
                if (reply.hasLines()) {
                    onSpoken.accept(reply);   // 话先播，和别的回复一致
                }
                if (head == null) {
                    log.warn("game: 不知道 {} 的 headVersion，放弃答复{}", cart, kind);
                    return;
                }
                boolean accept = reply.actions().stream()
                        .anyMatch(a -> a != null && "accept".equals(a.strip()));
                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("type", "takeback".equals(kind) ? "respondTakeback" : "respondDraw");
                cmd.put("accept", accept);
                boolean ok = injector.injectRaw(buildDispatch(cart, cmd, head));
                log.info("game: {} {}答复{} (head={})", ok ? "已" : "未",
                        "takeback".equals(kind) ? "悔棋" : "和棋", accept ? "同意" : "拒绝", head);
            } catch (RuntimeException e) {
                log.warn("game: 处理{}答复失败: {}", kind, e.toString());
            }
        }));
    }

    /**
     * 记录"这一帧为什么没驱动"。
     *
     * <p><b>为什么必须打</b>：`drive()` 里有七八处 return，全都静默的话，
     * 现象就是"日志里毫无动静"，而可能的原因有"没进游戏""没到她的回合""没有 gameState""没棋可走"……
     * 全靠猜。打出来之后，一次操作就能看出卡在哪一步。
     *
     * <p>限流 2 秒：WS 帧很密，每个 transition 都打会把日志淹掉。
     */
    private void noteSkip(String cart, Integer version, String why) {
        // 实测一局下来，"不是她的回合"每走一步都会出现一次 —— 那是**正常**的，
        // 用 INFO 会把日志刷满（用户要的是"有问题能看见"，不是"正常也说个没完"）。
        // 折中：**原因变化时用 INFO**（第一次、以及"变成终局/没有局面"这类新情况），
        //       同一个原因继续重复就用 DEBUG。
        //
        // 这样既能一眼看出"她为什么不动"，又不会在正常对局里刷屏。
        String prev = lastSkipReason.put(cart, why);
        if (why.equals(prev)) {
            log.debug("game: 未驱动 cart={} v={} 原因={}（同一原因，已在上文说明）", cart, version, why);
            return;
        }
        log.info("game: 未驱动 cart={} v={} 原因={}", cart, version, why);
    }

    /** {@code whoseTurnToGive} 之类的字段是否指向她（兼容 "agent" / "AGENT" / 1）。 */
    private static boolean isAgentSide(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String t = v.strip();
        if ("agent".equalsIgnoreCase(t) || "nori".equalsIgnoreCase(t)) {
            return true;
        }
        try {
            return Integer.parseInt(t) == CakeduelRules.AGENT_INDEX;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 这个回合是否已经结束。
     *
     * <p><b>必须判值不能只判字段存在</b>：`endedBy` 很可能存在但为 {@code null}（表示"没结束"），
     * 而 Jackson 的 {@code has()} 对 null 值返回 **true** ⇒ 会把每个回合都当成已结束 ⇒
     * 永远不驱动，且日志里毫无线索。
     */
    private static boolean roundEnded(JsonNode round) {
        JsonNode endedBy = (round == null) ? null : round.get("endedBy");
        if (endedBy == null || endedBy.isNull()) {
            return false;
        }
        if (endedBy.isString()) {
            return !endedBy.asString().isBlank();
        }
        if (endedBy.isBoolean()) {
            return endedBy.asBoolean();
        }
        return true;
    }

    /** 这个卡带是否在驱动范围内（四个游戏，其余一律不管）。 */
    private static boolean isDrivenCart(String cart) {
        return "chess".equals(cart) || "cakeduel".equals(cart)
                || "codenames".equals(cart) || "pictionary".equals(cart);
    }

    /** 记下"轮到她"的时刻，以及这一回合对应的 version（延迟后用来判断动作是否过期）。 */
    private void rememberTurn(String cart, Integer version) {
        turnStartedAt.put(cart, System.currentTimeMillis());
        if (version != null) {
            turnVersion.put(cart, version);
        }
    }

    /**
     * 这条动作是不是已经过期了。
     *
     * <p>节奏闸门会让她晚一点出手，但**晚的这段时间里局面可能已经变了**
     * （比如玩家抢先动了、或者上游推了别的 transition）。此时再发旧动作，
     * 要么被拒、要么把局面弄成对方没预料的样子。所以延迟结束后必须重新校验。
     */
    private boolean isStale(String cart, Integer version) {
        if (version == null) {
            return false;
        }
        Integer latest = turnVersion.get(cart);
        return latest != null && latest > version;
    }

    /**
     * 延迟执行。
     *
     * <p>用 reactor 的 {@code Mono.delay} 而不是 {@code Thread.sleep}：
     * 这个调用发生在 {@code SessionLoop} 线程上，睡一下就冻住整条会话（台词、音频都停）。
     */
    private void scheduleAfter(long delayMs, Runnable task) {
        if (!pacing || delayMs <= 0) {
            task.run();
            return;
        }
        reactor.core.publisher.Mono.delay(java.time.Duration.ofMillis(delayMs))
                .subscribe(t -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.warn("game: 延迟任务失败: {}", e.toString());
                    }
                });
    }

    /** 从 {@code transition.events} 里找质疑事件，开一个"动画静默窗口"。 */
    private void noteChallenge(String cart, JsonNode tr) {
        if (!"cakeduel".equals(cart)) {
            return;
        }
        JsonNode events = tr.get("events");
        if (events == null || !events.isArray()) {
            return;
        }
        for (JsonNode raw : events) {
            JsonNode e = net.siniplise.noriosbackend.game.GameEvents.flatten(raw);
            String type = net.siniplise.noriosbackend.game.GameEvents.text(e, "type");
            if ("challenge_made".equals(type)) {
                cakeHoldUntil = System.currentTimeMillis() + Pacer.AFTER_REVEAL_MS;
                log.info("cake: 检测到质疑 → 静默 {}ms（等揭示动画播完）", Pacer.AFTER_REVEAL_MS);
                return;
            }
        }
    }

    private void markAsked(String cart, Integer version) {
        if (version != null) {
            askedAt.put(cart, version);
        }
    }

    private void fail(String cart, Integer version, Throwable error) {
        log.warn("game: 大脑失败 cart={} : {}", cart, error.toString());
        // 清掉标记，让下一帧能重试（否则这一回合永远不动了）
        if (version != null) {
            loop.run(() -> askedAt.remove(cart));
        }
    }

    /**
     * 森林寻宝：当前生效的提示词。
     *
     * <p>取的是**回合记录里最后一条 clue**（`history[].clue` / `clueGiver`），而不是自己另记一份：
     * 提示词是玩家发的上行命令，我们没拦；而 `history` 是上游权威记录，两边不会漂。
     */
    private static Optional<CodenamesPosition.Clue> currentCodenamesClue(JsonNode gs) {
        JsonNode history = gs == null ? null : gs.get("history");
        if (history == null || !history.isArray() || history.isEmpty()) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            JsonNode h = history.get(i);
            // ★ 不能用 has("endedBy") 判断"已结束"：**字段存在但值是 null** 时 has() 仍返回 true，
            //   那样每个回合都会被当成已结束 ⇒ 永远不驱动（而且日志说不出所以然）。
            if (h == null || !h.isObject() || roundEnded(h)) {
                continue;
            }
            // 她自己给的词不算"她要猜的提示词"（否则她会去猜自己出的题）
            if (isAgentSide(text(h, "clueGiver"))) {
                continue;
            }
            JsonNode clue = h.get("clue");
            if (clue == null || !clue.isObject()) {
                continue;
            }
            JsonNode word = clue.get("word");
            JsonNode count = clue.get("count");
            if (word == null || word.isNull() || word.asString().isBlank()) {
                continue;
            }
            return Optional.of(new CodenamesPosition.Clue(
                    word.asString(), (count != null && count.isNumber()) ? count.asInt() : 1));
        }
        return Optional.empty();
    }

    /**
     * 森林寻宝：现在是不是**她猜牌**的回合。
     *
     * <p>判据：存在一个未结束的回合，且给词方**不是她**（`clueGiver != AGENT_INDEX`）。
     * 「她给提示词」那条路径依赖她能看到的 key 地图（信息不对称），字段尚未用真实帧校准，所以暂不驱动。
     */
    private static boolean herCodenamesGuessTurn(JsonNode gs) {
        JsonNode history = gs == null ? null : gs.get("history");
        if (history == null || !history.isArray() || history.isEmpty()) {
            return false;
        }
        JsonNode last = history.get(history.size() - 1);
        if (last == null || !last.isObject() || roundEnded(last)) {
            return false;
        }
        JsonNode giver = last.get("clueGiver");
        if (giver == null || giver.isNull() || giver.isObject() || giver.isArray()) {
            return false;
        }
        String g = giver.asString();
        // 兼容两种写法：下标（"0"/"1" 或数字）与角色名（"player"/"agent"）
        if ("agent".equalsIgnoreCase(g)) {
            return false;
        }
        if ("player".equalsIgnoreCase(g)) {
            return true;
        }
        try {
            return Integer.parseInt(g.strip()) == CakeduelRules.other(CakeduelRules.AGENT_INDEX);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 蛋糕对决的回复 → play 指令。 */
    private void onCardReply(String cart, CakeduelPosition pos, GameTurnReply reply, Integer head,
                             Integer version) {
        long elapsed = System.currentTimeMillis() - turnStartedAt.getOrDefault(cart, System.currentTimeMillis());
        long wait = pacing ? Pacer.waitMs(Pacer.thinkMs(cart), elapsed) : 0;
        scheduleAfter(wait, () -> loop.run(() -> {
            try {
                if (isStale(cart, version)) {
                    log.info("game: 等待期间局面已推进，丢弃过期动作");
                    return;
                }
                if (reply.hasLines()) {
                    onSpoken.accept(reply);   // 话先播，和象棋一致
                }
                var cmds = pos.toCommands(reply.actions());
                if (cmds.isEmpty()) {
                    log.info("game: 她这一步没能落到合法动作上（actions={}），不发指令", reply.actions());
                    return;
                }
                if (head == null) {
                    log.warn("game: 不知道 {} 的 headVersion，放弃发指令", cart);
                    return;
                }
                boolean ok = injector.injectRaw(buildDispatch(cart, cmds.get(0), head));
                log.info("game: {} 发出 {} → {} (head={})", ok ? "已" : "未", cart, cmds.get(0), head);
            } catch (RuntimeException e) {
                log.warn("game: 处理蛋糕对决回复失败: {}", e.toString());
            }
        }));
    }

    /** 森林寻宝的回复 → submitGuess / endTurn / submitClue 序列。 */
    private void onCodenamesReply(String cart, CodenamesPosition pos, GameTurnReply reply, Integer head,
                                  Integer version) {
        long elapsed = System.currentTimeMillis() - turnStartedAt.getOrDefault(cart, System.currentTimeMillis());
        long wait = pacing ? Pacer.waitMs(Pacer.thinkMs(cart), elapsed) : 0;
        scheduleAfter(wait, () -> loop.run(() -> {
            try {
                if (isStale(cart, version)) {
                    log.info("game: 等待期间局面已推进，丢弃过期指令");
                    return;
                }
                if (reply.hasLines()) {
                    onSpoken.accept(reply);
                }
                CodenamesPosition.Clue clue = reply.clueNode()
                        .map(c -> new CodenamesPosition.Clue(
                                c.get("word") == null ? null : c.get("word").asString(),
                                c.get("count") == null ? 1 : c.get("count").asInt()))
                        .orElse(null);
                var cmds = pos.toCommands(reply.actions(), clue);
                if (cmds.isEmpty()) {
                    log.info("game: 森林寻宝没有可执行指令（actions={}）", reply.actions());
                    return;
                }
                if (head == null) {
                    log.warn("game: 不知道 {} 的 headVersion，放弃发指令", cart);
                    return;
                }
                // ★ 多条指令要**一条一条**发，中间隔 {@link Pacer#STEP_MS}：
                //   连着发会让前端来不及播翻牌动画，甚至直接丢掉过渡（界面停在旧状态）。
                beginSequentialSend(cart);
                sendSequentially(cart, cmds, head, 0);
            } catch (RuntimeException e) {
                log.warn("game: 处理寻宝回复失败: {}", e.toString());
            }
        }));
    }

    /**
     * 玩家画布变了（攒到新笔画）⇒ 考虑问她"这是什么"。
     *
     * <p><b>为什么由笔画触发、而不是等 transition</b>：笔画是玩家的**上行命令**，
     * 上游不一定会为它广播局面变化；等 transition 她就会一直不动。
     * （JS 侧同样是 `noteStrokes → picKick()` 直接驱动。）
     *
     * <p>去抖 {@value #PIC_DEBOUNCE_MS} 毫秒：玩家还在画的时候看图，猜出来的必然是半成品。
     */
    public void pictureChanged() {
        if (!enabled || brain == null || pictureBoard == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPicAt < PIC_DEBOUNCE_MS) {
            return;
        }
        JsonNode gs = lastStates.get("pictionary");
        if (gs == null) {
            noteSkip("pictionary", null, "还没收到局面（gameState）");
            return;
        }
        var pos = net.siniplise.noriosbackend.game.pictionary.PictionaryPosition.from(gs, pictureBoard);
        if (!pos.sheGuesses()) {
            // 实测坑：roles 在 round 里面（round.roles.guesser），不在 gameState 顶层
            noteSkip("pictionary", null, "现在不是她猜（round.roles.guesser≠agent）");
            return;
        }
        if (!pos.hasPicture()) {
            noteSkip("pictionary", null, "还没有可看的画面（笔画为空）");
            return;
        }
        var req = pos.toRequest("");
        if (req == null) {
            return;
        }
        lastPicAt = now;
        Integer head = heads.head("pictionary");
        brain.turn(req).subscribe(
                reply -> onPicReply(pos, reply, head),
                error -> log.warn("game: 看图失败: {}", error.toString()));
    }

    /** 她猜出来的词 → {@code submitGuess}。 */
    private void onPicReply(net.siniplise.noriosbackend.game.pictionary.PictionaryPosition pos,
                            GameTurnReply reply, Integer head) {
        loop.run(() -> {
            try {
                String guess = net.siniplise.noriosbackend.game.pictionary.PictionaryPosition.guessOf(reply);
                if (reply.hasLines()) {
                    onSpoken.accept(reply);   // 话先播，和象棋保持一致
                }
                if (guess == null) {
                    log.info("game: 她没能给出一个词，跳过");
                    return;
                }
                // 同一个词不重复发：她可能连续两拍看到同一张图给出同样的答案
                if (guess.equals(lastPicGuess)) {
                    log.debug("game: 重复猜测「{}」，跳过", guess);
                    return;
                }
                if (head == null) {
                    log.warn("game: 不知道 pictionary 的 headVersion，放弃发猜测");
                    return;
                }
                lastPicGuess = guess;

                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("type", "submitGuess");
                cmd.put("atMs", System.currentTimeMillis());
                cmd.put("text", guess.length() > 50 ? guess.substring(0, 50) : guess);
                boolean ok = injector.injectRaw(buildDispatch("pictionary", cmd, head));
                log.info("game: {} 发出猜测「{}」(head={})", ok ? "已" : "未", guess, head);
            } catch (RuntimeException e) {
                log.warn("game: 处理猜测失败: {}", e.toString());
            }
        });
    }

    /**
     * 一条一条地发指令，中间隔 {@link Pacer#STEP_MS}。
     *
     * <p>为什么不能一次发完：森林寻宝一次会产出"翻牌 + 收手"，前端每翻一张都要播动画。
     * 指令连发时前端会**丢掉过渡**，`gameState` 推进了而界面停在旧状态 ——
     * 表现成"卡在 Nori 正在想提示"，而且极难归因（因为日志里我们确实发出去了）。
     */
    private void sendSequentially(String cart, List<Map<String, Object>> cmds, Integer head, int index) {
        if (index >= cmds.size()) {
            sendingCarts.remove(cart);
            log.info("game: 寻宝指令全部发出（共 {} 条，间隔 {}ms）", cmds.size(), Pacer.STEP_MS);
            return;
        }
        // ★ 每发一条都**重新读一次 head**：上一轮发出去的指令会让上游推进版本，
        //   沿用开头的旧 head 会让后续指令带着过期版本（被静默丢弃）。
        Integer current = heads.head(cart);
        if (current == null) {
            current = head;
        }
        if (current == null) {
            sendingCarts.remove(cart);
            log.warn("game: 不知道 {} 的 headVersion，放弃发指令", cart);
            return;
        }
        boolean ok = injector.injectRaw(buildDispatch(cart, cmds.get(index), current));
        log.info("game: {} 发出寻宝指令 {}/{} {} (head={})", ok ? "已" : "未",
                index + 1, cmds.size(), cmds.get(index).get("type"), current);
        // 后面的隔一会儿再发（不是递归调用本方法，而是排一个延迟任务回 loop）
        if (index + 1 < cmds.size()) {
            scheduleAfter(Pacer.STEP_MS,
                    () -> loop.run(() -> sendSequentially(cart, cmds, head, index + 1)));
        } else {
            sendingCarts.remove(cart);
        }
    }

    /** 标记开始串行发送（在 onCodenamesReply 里调用）。 */
    private void beginSequentialSend(String cart) {
        sendingCarts.add(cart);
    }

    /** 记住 visibilityFenceId（agent 的 dispatch 要带）。 */
    private void rememberFence(String cart, JsonNode node) {
        JsonNode runtimes = node == null ? null : node.get("runtimes");
        if (runtimes == null || !runtimes.isArray()) {
            return;
        }
        for (JsonNode rt : runtimes) {
            if (rt == null || !rt.isObject()) {
                continue;
            }
            String f = text(rt, "visibilityFenceId");
            if (f != null && !f.isBlank()) {
                fences.put(cart, f);
                return;
            }
        }
    }

    /** 大脑回复：回到 loop 里发指令 + 播台词。 */
    private void onReply(String cart, ChessDriver.Turn turn, GameTurnReply reply, Integer head,
                         Integer version) {
        long elapsed = System.currentTimeMillis() - turnStartedAt.getOrDefault(cart, System.currentTimeMillis());
        long wait = pacing ? Pacer.waitMs(Pacer.thinkMs(cart), elapsed) : 0;
        if (wait > 0) {
            log.info("game: 她还在想，{}ms 后落子（已过 {}ms，下限 {}ms）", wait, elapsed, Pacer.thinkMs(cart));
        }
        scheduleAfter(wait, () -> loop.run(() -> {
            try {
                if (isStale(cart, version)) {
                    log.info("game: 等待期间局面已推进，丢弃过期的走法");
                    return;
                }
                // ★ 台词先播、且**不管动作成不成功都播**：
                //   动作失败（非法走法 / 不知道 head）时如果连话都吞掉，玩家的观感是"她消失了"。
                //   她说了什么和她能不能落子，是两件事。
                if (reply.hasLines()) {
                    onSpoken.accept(reply);
                }

                Optional<Map<String, Object>> cmd = turn.toCmd(reply);
                if (cmd.isEmpty()) {
                    log.info("game: 她这一步没能落到合法走法上（actions={}），不发指令", reply.actions());
                    return;
                }
                if (head == null) {
                    log.warn("game: 不知道 {} 的 headVersion，放弃发指令（发了也会被静默丢弃）", cart);
                    return;
                }
                String json = buildDispatch(cart, cmd.get(), head);
                boolean ok = injector.injectRaw(json);
                log.info("game: {} 发出 {} → {} (head={})", ok ? "已" : "未", cart, cmd.get(), head);
            } catch (RuntimeException e) {
                log.warn("game: 处理大脑回复失败: {}", e.toString());
            }
        }));
    }

    /**
     * 拼 {@code dispatch} 报文。
     *
     * <p>字段以**实测**为准（golden 里玩家发的那些）：**没有 worldId**、
     * 有 {@code actor/expectedHeadVersion/requestId}。多带 worldId 上游能不能吃不确定，
     * 所以严格按实测形状来。
     */
    private String buildDispatch(String cart, Map<String, Object> cmd, int headVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "dispatch");
        // ★ 玩家发的 dispatch **不带** worldId/fence，但 agent 发的**要带**
        //   （依据是 nori_game.js#sendAgent 的真实报文）。两者形状不同这点很容易漏。
        if (worldId != null) {
            body.put("worldId", worldId);
        }
        body.put("cartridgeId", cart);
        body.put("actor", "agent");
        body.put("visibilityFenceId", fences.getOrDefault(cart, "ui"));
        body.put("cmd", cmd);
        body.put("expectedHeadVersion", headVersion);
        body.put("requestId", UUID.randomUUID().toString());
        try {
            return MAPPER.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("dispatch 序列化失败", e);
        }
    }

    /** 把所有 patch 的 {@code op path} 拼成一个短串，用来判断局面到底放在哪个 path。 */
    private static String patchPaths(JsonNode tr) {
        JsonNode patches = (tr == null) ? null : tr.get("patches");
        if (patches == null || !patches.isArray() || patches.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(patches.size(), 8); i++) {
            JsonNode p = patches.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(text(p, "op")).append(' ').append(text(p, "path"));
        }
        if (patches.size() > 8) {
            sb.append(", …共 ").append(patches.size()).append(" 条");
        }
        return sb.append(']').toString();
    }

    /** 事件类型列表（cakeduel 的局面很可能通过 game_started 之类的事件带来）。 */
    private static String eventTypes(JsonNode tr) {
        JsonNode events = (tr == null) ? null : tr.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(events.size(), 8); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(net.siniplise.noriosbackend.game.GameEvents.type(events.get(i)));
        }
        if (events.size() > 8) {
            sb.append(", …共 ").append(events.size()).append(" 条");
        }
        return sb.append(']').toString();
    }

    /** 顶层键名（只看第一层，不含嵌套的 id/text 之类）。 */
    private static java.util.List<String> topLevelKeys(JsonNode node) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (node != null && node.isObject()) {
            node.propertyNames().forEach(keys::add);
        }
        return keys;
    }

    /** 数组长度 / 对象字段数；不是容器就是 -1。 */
    private static int sizeOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return -1;
        }
        if (v.isArray() || v.isObject()) {
            return v.size();
        }
        return 0;
    }

    /** 取一个短字符串字段（超长截断），用于日志。 */
    private static String shortText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isArray() || v.isObject()) {
            return "-";
        }
        String t = v.asString();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    /** 从 {@code runtimes[0]} 里取一个整数字段（如挂载时的 {@code headVersion}）。 */
    private static Integer firstRuntimeInt(JsonNode msg, String field) {
        JsonNode runtimes = msg == null ? null : msg.get("runtimes");
        if (runtimes == null || !runtimes.isArray() || runtimes.isEmpty()) {
            return null;
        }
        JsonNode rt = runtimes.get(0);
        JsonNode v = (rt == null) ? null : rt.get(field);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }

    /**
     * 挂载消息里可能已经带了 gameState（{@code runtimes[].state.gameState}）。
     *
     * <p>实测 chess 挂载时它是**空对象**，所以通常拿不到东西；但其他卡带/版本可能直接给，
     * 有就收下——否则要等到下一次 transition 才第一次拿到局面。
     */
    private static JsonNode mountedGameState(JsonNode msg) {
        JsonNode runtimes = msg == null ? null : msg.get("runtimes");
        if (runtimes == null || !runtimes.isArray()) {
            return null;
        }
        for (JsonNode rt : runtimes) {
            JsonNode st = (rt == null) ? null : rt.get("state");
            JsonNode gs = (st == null) ? null : st.get("gameState");
            if (gs != null && gs.isObject() && gs.size() > 0) {
                return gs;
            }
        }
        return null;
    }

    /** 从 transition.patches 里找最后一次 {@code /gameState} 的值。 */
    private static JsonNode latestGameState(JsonNode tr) {
        JsonNode patches = tr.get("patches");
        JsonNode found = null;
        if (patches != null && patches.isArray()) {
            for (JsonNode p : patches) {
                String path = text(p, "path");
                if (path == null) {
                    continue;
                }
                if (path.equals("/gameState") || path.endsWith("/gameState")) {
                    JsonNode v = p.get("value");
                    if (v != null && v.isObject()) {
                        found = v;
                    }
                }
            }
        }
        return found;
    }

    /**
     * 记住玩家执哪一方。
     *
     * <p><b>真实位置是 {@code runtimes[0].state.settings.playerSide}</b>（实测 2026-10-31）。
     * 它**不在** gameState 里（gameState 只有 fen/turn/phase/status/…），
     * 也不在消息顶层。我最初查了后两处，结果一直是 null ⇒ 所有棋类判定都拿不到她的颜色。
     *
     * <p>其余几处仍保留作为兜底（不同版本/不同卡带可能放的位置不同）。
     */
    private void rememberPlayerSide(String cart, JsonNode node) {
        if (node == null) {
            return;
        }
        String side = text(node, "playerSide");
        if (side == null) {
            JsonNode settings = node.get("settings");
            if (settings != null && settings.isObject()) {
                side = text(settings, "playerSide");
            }
        }
        // ★ 主路径：runtimes[].state.settings.playerSide
        if (side == null) {
            JsonNode runtimes = node.get("runtimes");
            if (runtimes != null && runtimes.isArray()) {
                for (JsonNode rt : runtimes) {
                    JsonNode st = (rt == null) ? null : rt.get("state");
                    JsonNode settings = (st == null) ? null : st.get("settings");
                    if (settings != null && settings.isObject()) {
                        side = text(settings, "playerSide");
                        if (side != null) {
                            break;
                        }
                    }
                }
            }
        }
        if (side == null) {
            JsonNode gs = node.get("gameState");
            if (gs != null && gs.isObject()) {
                JsonNode settings = gs.get("settings");
                if (settings != null && settings.isObject()) {
                    side = text(settings, "playerSide");
                }
                if (side == null) {
                    side = text(gs, "playerSide");
                }
            }
        }
        if (side != null && !side.isBlank()) {
            if (!side.equals(playerSides.get(cart))) {
                log.info("game: {} 玩家执{}（她执另一方）", cart, side);
            }
            playerSides.put(cart, side);
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }
}
