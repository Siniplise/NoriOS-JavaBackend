package net.siniplise.noriosbackend.game;

import tools.jackson.databind.JsonNode;

/**
 * 卡带事件归一化：把四种卡带**并存的两套封装**抹平成一个视图。
 *
 * <p><b>为什么需要它</b>（实测，`golden/20261031-035029`）：
 * <ul>
 *   <li>{@code chess} 开局：{@code events[0] = {"type":"game_start","playerSide":"white",…}} —— <b>平铺</b></li>
 *   <li>{@code cakeduel} 开局：{@code events[0] = {"type":"engine","event":{"type":"game_started",…}}} —— <b>嵌套两层</b></li>
 * </ul>
 * 只看外层 {@code type} 的话，cakeduel 的开局/换轮事件会被当成 {@code "engine"} 整个漏掉。
 * JS 侧踩过这个坑（`nori_game.js` 2026-09-27 的注释），这里照抄它的解法。
 *
 * <p><b>只拆一层</b>：故意不做递归。真出现三层封装说明协议变了，应该显式发现并报错，
 * 而不是被一个"聪明的"递归默默吞掉。
 *
 * <p>本类只读，不拥有任何状态，因此不需要 {@code ThreadGuard}。
 */
public final class GameEvents {

    private GameEvents() {
    }

    /** 拆掉 {@code {"type":"engine","event":{…}}} 这层包装；不是这种形状就原样返回。 */
    public static JsonNode flatten(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return raw;
        }
        if (!"engine".equals(text(raw, "type"))) {
            return raw;
        }
        JsonNode inner = raw.get("event");
        if (inner == null || !inner.isObject() || text(inner, "type") == null) {
            return raw;
        }
        return inner;
    }

    /** 取字符串字段，缺失/null 返回 {@code null}（不返回 ""，好让调用方区分"没有"和"空串"）。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        // ★ 容器节点不能 asString（会抛 JsonNodeException）。
        //   `moveHistory` / `patches` / `events` 都是数组，只要谁误用 text() 去读就会炸整个调用链。
        if (v == null || v.isNull() || v.isArray() || v.isObject()) {
            return null;
        }
        return v.asString();
    }

    /** 取字符串字段并小写（协议里的 {@code by} / {@code piece} 大小写不保证）。 */
    public static String lower(JsonNode node, String field) {
        String s = text(node, field);
        return s == null ? null : s.toLowerCase();
    }

    /** 取嵌套字段的字符串，例如 {@code captured.piece}。 */
    public static String lowerNested(JsonNode node, String object, String field) {
        if (node == null) {
            return null;
        }
        JsonNode sub = node.get(object);
        return (sub == null || !sub.isObject()) ? null : lower(sub, field);
    }

    /** 取整数字段，缺失返回 {@code null}（0 是合法版本号，不能用 0 当哨兵）。 */
    public static Long number(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || !v.isNumber()) ? null : v.asLong();
    }

    /**
     * 这条事件的类型，**已拆过 engine 包装**。
     *
     * @return 可能为 {@code null}（事件没写 type —— 协议里不该出现，但不能因此抛异常打断帧流）
     */
    public static String type(JsonNode raw) {
        return text(flatten(raw), "type");
    }

    /** 是否是「新的一局 / 新的一轮」信号。 */
    public static boolean isStart(String type) {
        return "game_start".equals(type)
                || "game_started".equals(type)
                || "session_started".equals(type)
                || "round_started".equals(type)
                || "bout_started".equals(type);
    }

    /**
     * 是否是「整局结束」信号。
     *
     * <p>注意 {@code bout_ended} <b>不在</b>其内：那只是蛋糕对决的一"轮"结束（要赢 3 轮才结束整局）。
     * 把它当整局结束会让代理在第二局就挂起战报注入。
     */
    public static boolean isOver(String type) {
        return "game_over".equals(type)
                || "game_ended".equals(type)
                || "session_finished".equals(type)
                || "game_outcome".equals(type);
    }
}
