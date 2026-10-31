package net.siniplise.noriosbackend.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.siniplise.noriosbackend.runtime.ThreadGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 超限手势的编排器：`runtime_transition` → 判据 → 门控 → 注入。
 *
 * <p>三件事必须同时成立才会真的注入一枚 fact：
 * <ol>
 *   <li><b>判据命中</b> —— 交给 {@link GestureJudge}（纯函数）</li>
 *   <li><b>前置门控</b> —— {@code qfr.installed} 必须在 facts 里（玩家先装了 QFR-9000）</li>
 *   <li><b>节流</b> —— 同一枚 fact 8 秒内不重发，累计最多 12 次（对齐 JS 的 {@code BEAT_TRIES}）</li>
 * </ol>
 *
 * <p><b>去重是硬要求（Q27）</b>：实测同一时刻浏览器会开 4 条 WS 连接，上游把同一条
 * {@code runtime_transition} **复制 N 份**分别下发（只有 {@code *_ack} 回发起方）。
 * 不去重的话，一次"她的车吃我的后"会注入 3 次、播 3 遍台词。
 * 去重键用 {@code worldId|cartridgeId|transition 原文}——同一帧的副本原文完全一致，
 * 不同帧必然因为 version 或内容变化而不同。
 *
 * <p><b>线程模型</b>：可变状态只允许属主线程（每会话的 {@code SessionLoop}）改。
 * 与 {@code LifecycleMachine} 同一套心智模型，不加锁。
 */
public final class GestureRuntime {

    private static final Logger log = LoggerFactory.getLogger(GestureRuntime.class);

    /** 去重窗口大小。一帧三条副本，留 64 条足够覆盖任何广播延迟。 */
    private static final int DEDUPE_WINDOW = 64;
    /** 同一枚 fact 的重发冷却（毫秒），对齐 JS 的 {@code 8000}。 */
    private static final long RETRY_COOLDOWN_MS = 8_000L;
    /** 同一枚 fact 的累计注入上限，对齐 JS 的 {@code 12}。 */
    private static final int RETRY_CAP = 12;

    /** 注入出口：把一枚 fact 送到 manifold.dev.jump.request。返回是否成功发出。 */
    @FunctionalInterface
    public interface Injector {
        boolean inject(String fact, String why);
    }

    /** fact 查询口：门控用。接到 {@code LifecycleMachine} 的 facts 上。 */
    @FunctionalInterface
    public interface FactLookup {
        boolean has(String fact);
    }

    /** 一次注入的记录，给上层写日志/驱动台词用。 */
    public record Fired(String fact, String why) {
    }

    /** 一帧的处理结果。 */
    public record Outcome(boolean started, boolean over, List<Fired> fired) {
        static final Outcome NONE = new Outcome(false, false, List.of());
    }

    private final ThreadGuard guard = new ThreadGuard("GestureRuntime");
    private final Injector injector;
    private final FactLookup facts;

    /** 去重窗口（LRU，插入序淘汰）。 */
    private final Map<String, Boolean> seen = new LinkedHashMap<>(128, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUPE_WINDOW;
        }
    };

    private final Map<String, BeatTry> beatTries = new HashMap<>();
    /** 已经成功注入过的 fact（集齐判定用）。 */
    private final List<String> firedOrder = new ArrayList<>();

    private String chessPlayerSide = "white";
    private int berryStreak;
    private List<String> cakeCardList = List.of();
    /** 当前挂载的卡带。判据要靠它限定作用域（别让 chess 的 capture 在 cakeduel 里生效）。 */
    private String cartridgeId;

    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    public GestureRuntime(Injector injector, FactLookup facts) {
        this.injector = injector;
        this.facts = facts;
    }

    /** 交接所有权给 {@code SessionLoop} 线程。 */
    public void ownedByCurrentThread() {
        guard.claim();
    }

    /** 当前挂载的卡带（由上层在 {@code cartridge_mounted} / {@code world_joined} 时设置）。 */
    public void setCartridge(String cartridgeId) {
        guard.assertOwner();
        this.cartridgeId = cartridgeId;
    }

    /**
     * 设置蛋糕对决的牌表。
     *
     * <p>{@code card_discarded} 里的 {@code cardIds} 是**牌表下标**，没有牌表就解不出牌名。
     * 牌表来自 {@code cartridge_mounted.runtimes[].state.game.cardList}，卡带每局重发一次。
     */
    public void setCakeCardList(List<String> cardList) {
        guard.assertOwner();
        this.cakeCardList = cardList == null ? List.of() : List.copyOf(cardList);
    }

    /** 已注入的 fact（按注入顺序，去重）。 */
    public List<String> firedFacts() {
        guard.assertOwner();
        return List.copyOf(firedOrder);
    }

    public int berryStreak() {
        guard.assertOwner();
        return berryStreak;
    }

    /**
     * 处理一帧 {@code runtime_transition}。
     *
     * @param worldId      world_joined 给的 worldId（去重键的一部分）
     * @param cartridgeId  这帧属于哪个卡带
     * @param transition   {@code transition} 对象（含 {@code cmd / patches / events}）
     */
    public Outcome onTransition(String worldId, String cartridgeId, JsonNode transition) {
        guard.assertOwner();
        if (transition == null || !transition.isObject()) {
            return Outcome.NONE;
        }
        String key = dedupeKey(worldId, cartridgeId, transition);
        if (seen.put(key, Boolean.TRUE) != null) {
            // 同一帧的广播副本 —— 静默丢弃（这条日志会刷屏，所以只记 debug）
            log.debug("game: 丢弃广播副本 cart={} key={}", cartridgeId, key);
            return Outcome.NONE;
        }

        JsonNode events = transition.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            return Outcome.NONE;
        }

        boolean started = false;
        boolean over = false;
        List<Fired> fired = new ArrayList<>();

        for (JsonNode raw : events) {
            if (raw == null || !raw.isObject()) {
                continue;
            }
            JsonNode e = GameEvents.flatten(raw);
            String type = GameEvents.text(e, "type");
            if (type == null) {
                continue;
            }
            switch (type) {
                case "game_start", "game_started", "session_started", "round_started", "bout_started" -> {
                    started = true;
                    onStart(cartridgeId, e, raw);
                }
                case "capture" -> {
                    if ("chess".equals(cartridgeId)) {
                        GestureJudge.chessCapture(chessPlayerSide, e)
                                .ifPresent(why -> fired.add(fire(GestureFacts.CHESS, why)));
                    }
                }
                case "guess" -> {
                    if ("codenames".equals(cartridgeId)) {
                        var step = GestureJudge.codenamesGuess(
                                GameEvents.text(e, "by"), GameEvents.text(e, "result"), berryStreak);
                        berryStreak = step.streak();
                        step.fact().ifPresent(why -> fired.add(fire(GestureFacts.CODENAMES, why)));
                    }
                }
                case "card_discarded" -> {
                    if ("cakeduel".equals(cartridgeId)) {
                        GestureJudge.cakeduelDiscard(e, cakeCardList)
                                .ifPresent(why -> fired.add(fire(GestureFacts.CAKEDUEL, why)));
                    }
                }
                case "wolfy_taunt" -> {
                    if ("cakeduel".equals(cartridgeId)) {
                        fired.add(fire(GestureFacts.CAKEDUEL, "蛋糕对决：狼爵士 wolfy taunt"));
                    }
                }
                case "game_over", "game_ended", "session_finished", "game_outcome" -> {
                    over = true;
                    // 蛋糕对决的兜底：狼爵士那条事件 exe 可能不发，整局结束也认
                    if ("cakeduel".equals(cartridgeId) && "game_ended".equals(type)) {
                        fired.add(fire(GestureFacts.CAKEDUEL, "蛋糕对决：整局结束（兜底）"));
                    }
                }
                default -> {
                    // 其余事件（move / turn_end / clue / agent_turn …）暂不参与手势判定
                }
            }
        }
        fired.removeIf(java.util.Objects::isNull);
        return new Outcome(started, over, List.copyOf(fired));
    }

    /**
     * 新局/新轮：重置连击、刷新玩家颜色、抓牌表。
     *
     * <p>玩家颜色只从 {@code game_start} 的 {@code playerSide} 读。JS 侧有段"玩家执黑 ⇒ 她执白先手"
     * 的主动调度逻辑，那是 AI 循环的事，不属于判据层，这里不搬。
     *
     * <p>蛋糕对决的牌表在这里抓而不是在 {@code cartridge_mounted} 抓，是实测决定的：
     * 挂载时 {@code state.game} 还是 {@code null}，牌表要等 {@code game_started} 才出现
     * （形如 {@code {"type":"engine","event":{"type":"game_started","cardList":[…]}}}）。
     */
    private void onStart(String cart, JsonNode e, JsonNode raw) {
        berryStreak = 0;
        if ("chess".equals(cart)) {
            String side = GameEvents.text(e, "playerSide");
            if (side == null) {
                side = GameEvents.text(raw, "playerSide");
            }
            if (side != null && !side.isEmpty()) {
                chessPlayerSide = side.toLowerCase();
            }
        }
        if ("cakeduel".equals(cart)) {
            List<String> list = cardListOf(e);
            if (list == null) {
                list = cardListOf(raw);
            }
            if (list != null) {
                cakeCardList = list;
                log.debug("game: cakeduel 牌表 {} 张", list.size());
            }
        }
    }

    private static List<String> cardListOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode arr = node.get("cardList");
        if (arr == null || !arr.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode c : arr) {
            out.add(c.isString() ? c.asString() : c.toString());
        }
        return out;
    }

    /**
     * 真正注入一枚 fact（三闸门：去重已完成 → 门控 → 节流 → 注入）。
     *
     * @return 成功时返回 {@link Fired}，被拦下时返回 {@code null}（调用方负责剔除）
     */
    private Fired fire(String fact, String why) {
        // 闸门一：前置剧情。没装 QFR-9000 就一律不触发（JS 侧同一判据）
        if (!facts.has(GestureFacts.QFR_INSTALLED)) {
            log.info("game: 手势 {} 跳过（未装 QFR-9000，qfr.installed 未满足）— {}", fact, why);
            return null;
        }
        // 闸门二：节流
        BeatTry st = beatTries.computeIfAbsent(fact, k -> new BeatTry());
        long nowMs = now();
        if (st.count >= RETRY_CAP || nowMs - st.lastAt < RETRY_COOLDOWN_MS) {
            log.debug("game: 手势 {} 节流（已 {} 次，距上次 {}ms）", fact, st.count, nowMs - st.lastAt);
            return null;
        }
        st.count++;
        st.lastAt = nowMs;

        boolean ok;
        try {
            ok = injector.inject(fact, "游戏进度 — " + why);
        } catch (RuntimeException ex) {
            log.warn("game: 注入 {} 失败: {}", fact, ex.toString());
            return null;
        }
        if (!ok) {
            log.warn("game: 注入 {} 未发出（无可用上游连接）— {}", fact, why);
            return null;
        }
        if (!firedOrder.contains(fact)) {
            firedOrder.add(fact);
        }
        log.info("game: 手势注入 {} #{} — {}", fact, st.count, why);
        if (firedOrder.containsAll(GestureFacts.ALL) && !firedOrder.contains(GestureFacts.GESTURES_COMPLETE)) {
            // 四枚集齐：1.2s 后补发完成标记（JS 侧同一延迟）。这里同步补，由上层决定要不要延后。
            firedOrder.add(GestureFacts.GESTURES_COMPLETE);
            injector.inject(GestureFacts.GESTURES_COMPLETE, "四枚超限手势集齐");
            log.info("game: 四枚超限手势集齐 → {}", GestureFacts.GESTURES_COMPLETE);
        }
        return new Fired(fact, why);
    }

    /**
     * 你画我猜的触发点**不在事件流里**，而在"她猜词落地"的那一刻。
     *
     * <p>原因：她的猜测是我们调大脑（{@code /game-turn}）得到、再 {@code submitGuess} 发上去的，
     * 事件的 {@code guess} 是**回声**。要在"落地"处判，才能保证用我们自己发出去的那个词，
     * 而不是服务端本地假对手替她猜的词（JS 侧有专门的 {@code cutOpponentRun} 在处理这件事）。
     *
     * <p>所以由上层（将来的 {@code picTick}）在拿到词后调这里。
     *
     * @return 注入成功时返回记录，未命中/被门控拦下返回 {@code null}
     */
    public Fired firePictionaryGuess(String guess) {
        guard.assertOwner();
        return GestureJudge.pictionaryGuess(guess)
                .map(why -> fire(GestureFacts.PICTIONARY, why))
                .orElse(null);
    }

    private String dedupeKey(String worldId, String cart, JsonNode transition) {
        // 用 transition 原文而不是只 version：同一 version 可能存在多条不同的 transition
        // （例如 chess 的 startGame v1 与紧随其后的 visibility_fence 也走 v1），只按 version
        // 去重会把后一条误杀。原文一致 ⇔ 同一帧的广播副本。
        return (worldId == null ? "-" : worldId) + "|" + cart + "|" + transition.toString();
    }

    /** 可注入的时钟，单测用。 */
    public void setClock(java.util.function.LongSupplier clock) {
        guard.assertOwner();
        this.clock = clock;
    }

    private long now() {
        return clock.getAsLong();
    }

    private static final class BeatTry {
        int count;
        long lastAt;
    }

    /** 供上层/单测查询：某枚 fact 还能不能发。 */
    public OptionalLong lastFiredAt(String fact) {
        BeatTry st = beatTries.get(fact);
        return st == null || st.count == 0 ? OptionalLong.empty() : OptionalLong.of(st.lastAt);
    }

    /** 便捷：已集齐几枚。 */
    public int gestureCount() {
        guard.assertOwner();
        return (int) GestureFacts.ALL.stream().filter(firedOrder::contains).count();
    }

    /** 便捷：进度文案，与 bundle 的 toast 一致（「已复现 n/4」）。 */
    public String progressText() {
        return "已复现 " + gestureCount() + "/" + GestureFacts.ALL.size();
    }
}
