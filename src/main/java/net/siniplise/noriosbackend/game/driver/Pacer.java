package net.siniplise.noriosbackend.game.driver;

/**
 * 节奏闸门：让她的动作**等演出播完**再发生。
 *
 * <h2>为什么需要它（这是 JS 版踩过的坑，不要重犯）</h2>
 * 大脑回复只要 1 秒左右。如果拿到就立刻发指令，会同时坏两件事：
 * <ol>
 *   <li><b>观感上的"抢跑"</b>：玩家还在看她上一句台词/看动画，她的下一手已经生效了。</li>
 *   <li><b>更严重的：指令太密会让前端丢掉过渡</b> ——
 *       `gameState` 已经推进，界面却停在旧状态，表现成"卡在 Nori 正在想提示"。
 *       这个症状极容易误判成"她卡住了"，实际是我们发太快。</li>
 * </ol>
 *
 * <h2>三个闸门</h2>
 * <ol>
 *   <li><b>最短思考时间</b>（{@link #thinkMs}）：从"轮到她"到"真正出手"至少隔这么久。
 *       象棋 2600 —— 一个正常人也要想这么久，秒回像个机器人。</li>
 *   <li><b>多步动作间隔</b>（{@link #STEP_MS}）：森林寻宝一次会发"翻牌 + 收手"几条指令，
 *       必须一条一条来，否则前端来不及播翻牌动画。</li>
 *   <li><b>质疑揭示后的静默</b>（{@link #AFTER_REVEAL_MS}）：蛋糕对决质疑一次，
 *       前端要播约 4 秒的揭示动画（`CHALLENGE_PRE_REVEAL_PAUSE 1000` + `REVEAL_HOLD 3000`），
 *       这期间**她绝对不能出手**，而且前端 `activeEffect` 会禁用交互（玩家点不了）。</li>
 * </ol>
 *
 * <p>数值来自 `nori_game.js` 的实测常量（那些注释是从 bundle 里读出来的真实动画时长，不是猜的）。
 * 可用系统属性覆盖，方便真机上调。
 */
public final class Pacer {

    /** 多步动作之间的间隔（JS 的 `AGENT_STEP_MS`，从 1100 调到 2200 才跟得上动画）。 */
    public static final long STEP_MS = longProp("nori.game.stepMs", 2200);

    /** 质疑揭示动画的静默窗口（前端实测 ≈4000，取 3600 留一点余量）。 */
    public static final long AFTER_REVEAL_MS = longProp("nori.game.afterRevealMs", 3600);

    private Pacer() {
    }

    /**
     * 各卡带的"最短思考时间"。
     *
     * <p>注意这是**从轮到她算起**的总时长，不是额外延迟——
     * 大脑本身要 1~1.5 秒，所以实际额外等待通常是 {@code thinkMs - 大脑耗时}。
     */
    public static long thinkMs(String cartridgeId) {
        if (cartridgeId == null) {
            return 0;
        }
        return switch (cartridgeId) {
            // 象棋要"想棋"，2600 与 JS 的 CHESS_THINK_MS 一致
            case "chess" -> longProp("nori.game.think.chess", 2600);
            // 蛋糕对决的常规节拍（CHALLENGE 之后的静默由 holdUntil 单独管）
            case "cakeduel" -> longProp("nori.game.think.cakeduel", 1400);
            // 森林寻宝：她在"看提示词、想同族的词"，给一点缓冲
            case "codenames" -> longProp("nori.game.think.codenames", 1600);
            // 你画我猜：由"玩家停笔"触发，已有 700ms 去抖；再等太久会让互动变迟钝
            case "pictionary" -> longProp("nori.game.think.pictionary", 1200);
            default -> 0;
        };
    }

    /**
     * 还要等多久才该出手。
     *
     * @param thinkMs 该卡带的最短思考时间
     * @param elapsed 从"轮到她"到现在已经过去的毫秒（含问大脑的耗时）
     * @return 额外需要等的毫秒；已经够了就返回 0
     */
    public static long waitMs(long thinkMs, long elapsed) {
        return Math.max(0, thinkMs - Math.max(0, elapsed));
    }

    private static long longProp(String key, long fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            long n = Long.parseLong(v.trim());
            return n < 0 ? fallback : n;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
