package net.siniplise.noriosbackend.game;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 四枚「超限手势」的判据。<b>纯函数、无状态、可离线单测。</b>
 *
 * <p>判据不是我们发明的，是照 `NoriOSNew/nori_game.js` 的行为 1:1 对齐的，
 * 每条都对应 `NoriOSNew/_t_gesture.js` 里的既有用例。改动前先看那份用例——
 * 里面有大量"看起来可以放宽、实际放宽就错"的边界（例如「水母灯」不算「水母」）。
 *
 * <p><b>为什么判据要这么窄</b>：它们是剧情节点，一旦判松就变成"随便玩就送"，
 * 那枚 fact 的叙事重量就没了。用户 2026-10-04 定调时明确要过"严格"。
 */
public final class GestureJudge {

    private GestureJudge() {
    }

    /** 她连续踩到几处浆果才算数（对齐 JS 侧 {@code BERRY_STREAK}）。 */
    public static final int BERRY_STREAK = 3;

    /**
     * 国际象棋：**她的车吃我的后**。
     *
     * <p>真实事件结构（实测 `proxy.log`）：
     * <pre>{@code
     * {"type":"capture","by":"black","from":{"piece":"r"},"captured":{"piece":"q","square":"h1"}}
     * }</pre>
     * {@code by} = 吃子方；{@code from.piece} = 吃子的棋子；{@code captured.piece} = 被吃棋子。
     *
     * <p>三个条件**同时**成立才算：她的颜色 + 车 + 吃的是后。少一个都不行——
     * 这是用户点名的"她的车吃我的后"，不是"任何吃大子"。
     *
     * <p>历史坑（B-110）：JS 旧实现读 {@code e.captured}（**对象**）去 {@code toString()}，
     * 恒为 {@code "[object Object]"}，判据永不成立、这枚 fact 从未触发过。
     * 这里是按对象路径取的，不会重犯。
     *
     * @param playerSide 玩家执的颜色（{@code "white"} / {@code "black"}）；缺失按 white 处理，与 JS 一致
     */
    public static Optional<String> chessCapture(String playerSide, JsonNode captureEvent) {
        String eater = GameEvents.lowerNested(captureEvent, "from", "piece");
        String eaten = GameEvents.lowerNested(captureEvent, "captured", "piece");
        String by = GameEvents.lower(captureEvent, "by");
        if (eater == null || eaten == null || by == null) {
            return Optional.empty();
        }
        String herColor = "black".equalsIgnoreCase(safe(playerSide, "white")) ? "white" : "black";
        if (by.equals(herColor) && "r".equals(eater) && "q".equals(eaten)) {
            String square = GameEvents.text(captureEvent.get("captured"), "square");
            return Optional.of("象棋：她的车吃掉你的后（" + by + " Rx" + safe(square, "?") + "）");
        }
        return Optional.empty();
    }

    /**
     * 你画我猜：她猜出的词**精确等于「水母」**。
     *
     * <p>官方语义是她「**误以为**玩家画了水母」——所以与题目无关，猜错也能触发，
     * 这正是设计意图（"不管题目是什么，直接画水母让她猜出来"）。
     *
     * <p><b>必须精确</b>：「水母灯」「水母伞」都不算。JS 侧曾有 {@code PIC_SEA_RE}
     * 想放宽成整类海洋生物，被判定为"错上加错"并删除。
     */
    public static Optional<String> pictionaryGuess(String guess) {
        if (guess == null) {
            return Optional.empty();
        }
        // 她偶尔带引号/书名号出来，先剥掉再比（与 JS 的 trim 顺序一致）
        String g = guess.strip().replaceAll("^[\"'「『]+|[\"'」』]+$", "").strip();
        if ("水母".equals(g)) {
            return Optional.of("你画我猜：她猜出「" + g + "」（超限事件）");
        }
        return Optional.empty();
    }

    /**
     * 森林寻宝：她**连续**踩到 {@value #BERRY_STREAK} 处浆果。
     *
     * <p>为什么要逐次累计、而不是看终态格子上的标记：每格的 {@code bystanderMarks}
     * 只有两个槽位，会被后续猜测覆盖，光看状态根本数不出 3 次。
     *
     * <p>只有 {@code by=agent} 才算；玩家踩的不算。她一旦猜中（{@code result="agent"}）
     * 连击**清零**——"连续"两个字就是靠这个成立的。
     *
     * @param streak 当前连击数
     * @return 新连击数 + 命中时的 fact
     */
    public static BerryStep codenamesGuess(String by, String result, int streak) {
        if (!"agent".equals(by)) {
            return new BerryStep(streak, Optional.empty());
        }
        if ("bystander".equals(result)) {
            int next = streak + 1;
            if (next >= BERRY_STREAK) {
                return new BerryStep(next, Optional.of(
                        "森林寻宝：她连续 " + next + " 次踩到浆果"));
            }
            return new BerryStep(next, Optional.empty());
        }
        if ("agent".equals(result)) {
            // 她猜中了 → 连击归零
            return new BerryStep(0, Optional.empty());
        }
        // assassin（踩到怪兽）不是"浆果"，但确实是这一轮结束了 → 连击不再"连续"，归零
        return new BerryStep(0, Optional.empty());
    }

    /** {@link #codenamesGuess} 的返回值：新连击数 + 可能产出的 fact。 */
    public record BerryStep(int streak, Optional<String> fact) {
    }

    /**
     * 蛋糕对决：狼爵士（{@code wolfy}）被弃进阻挡堆。
     *
     * <p>为什么要靠 {@code card_discarded} 自己判：exe 的 {@code _discard_claims}
     * **只发 {@code card_discarded}、不发 {@code wolfy_taunt}**，而 wolfy 是 base 20 张牌里
     * 唯一带表现事件的牌 ⇒ 前端动画和我们的判据都在等一个永不到来的事件。
     * 代理这边补的是"我们的反应"（这枚 fact），**前端那段动画补不了**（没有下行注入通道）。
     *
     * @param cardList 当前牌表；{@code cardIds} 里的整数是牌表下标，字符串则直接当牌名
     */
    public static Optional<String> cakeduelDiscard(JsonNode cardDiscarded, List<String> cardList) {
        if (cardDiscarded == null) {
            return Optional.empty();
        }
        JsonNode ids = cardDiscarded.get("cardIds");
        if (ids == null || !ids.isArray()) {
            return Optional.empty();
        }
        for (JsonNode id : ids) {
            String name;
            if (id.isNumber() || (id.isString() && id.asString().matches("\\d+"))) {
                int idx = id.asInt();
                name = (cardList != null && idx >= 0 && idx < cardList.size()) ? cardList.get(idx) : null;
            } else {
                name = id.isString() ? id.asString() : null;
            }
            if ("wolfy".equals(name)) {
                return Optional.of("蛋糕对决：狼爵士被弃（代理补触发，exe 不发该事件）");
            }
        }
        return Optional.empty();
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }
}
