package net.siniplise.noriosbackend.game;

import java.util.List;

/**
 * 「超限手势」四枚 fact 的常量表。
 *
 * <p><b>它们是什么</b>：不是"玩过这个游戏"的成就，而是 ARG 剧情里的
 * 「交互模块负载超限」节点——集齐四枚会触发终局面板与后续剧情。
 * 客户端只负责**读出来计数**（`QfrDock` 的 {@code gesturesDone: countTrue, cap:4}，
 * toast 文案「交互模块负载超限 · 已复现 n/4」），**从不写**。
 *
 * <p><b>谁写</b>：我们自己。链路（实测 + 源码双向确认）：
 * <pre>
 * 游戏卡带 runtime_transition（上游 4174）
 *   → 判据命中
 *   → fireGesture(id)            ← 门控：hasFact('qfr.installed')
 *   → injectFact(id)
 *   → event{channel:"manifold.dev.jump.request", payload:{facts:[id]}}
 *   → manifold.web（ARG）卡带落库
 * </pre>
 *
 * <p><b>为什么不能做成"点开游戏就触发"</b>：实测 {@code dispatch{cmd:"startGame"}} 的
 * {@code runtime_transition.patches} 里只有 {@code /gameState} 和 {@code /settings}，
 * 没有任何 fact 写入；四枚 fact 的判据都是**局内具体事件**（见 {@link GestureJudge}）。
 */
public final class GestureFacts {

    private GestureFacts() {
    }

    public static final String CHESS = "gesture.chess";
    public static final String CODENAMES = "gesture.codenames";
    public static final String PICTIONARY = "gesture.pictionary";
    public static final String CAKEDUEL = "gesture.cakeduel";

    /** 四枚集齐后 1.2s 补发的完成标记（对齐 JS 侧 {@code GESTURE_DONE}）。 */
    public static final String GESTURES_COMPLETE = "arg.gestures_complete";

    /** 前置门控：玩家必须先装上 QFR-9000（量子文件恢复仪），否则一枚都不触发。 */
    public static final String QFR_INSTALLED = "qfr.installed";

    public static final List<String> ALL = List.of(CHESS, CODENAMES, PICTIONARY, CAKEDUEL);

    /** 四枚集齐后补发完成标记的延迟（毫秒）。与 JS 侧 {@code setTimeout(…, 1200)} 一致。 */
    public static final long COMPLETE_DELAY_MS = 1200L;

    /**
     * 注入通道名。
     *
     * <p>这是 manifold.web 卡带的**调试跳转**通道：本地 exe 不落库，代理用它让官方逻辑补上 fact。
     * 格式（实测 + `nori_proxy.js#injectFact`）：
     * {@code {"type":"event","channel":"manifold.dev.jump.request","payload":{"facts":[fact]},"requestId":uuid}}
     */
    public static final String INJECT_CHANNEL = "manifold.dev.jump.request";

    /** 台词里用的中文名，与 bundle 的 {@code labels} 一致。 */
    public static String displayName(String fact) {
        return switch (fact) {
            case CHESS -> "国际象棋";
            case CODENAMES -> "森林寻宝";
            case PICTIONARY -> "你画我猜";
            case CAKEDUEL -> "蛋糕对决";
            default -> fact;
        };
    }
}
