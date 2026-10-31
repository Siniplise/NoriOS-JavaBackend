package net.siniplise.noriosbackend.game;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次「问大脑」的请求体，字段与 `nori_game.js#buildChess/buildCodenames/...` 逐字段对齐。
 *
 * <p><b>⚠️ 这个契约不是猜的，是拿真实响应校准过的。</b> 第一版我按"结构化的 observation + 对象化的
 * legal + 对象数组 actions"设计，实测 25503 回的是
 * {@code {"ok":true,"actions":[],"reason":"没有合法动作"}} —— 她根本没看懂我发的局面。
 * 真实形状是：
 * <ul>
 *   <li>{@code observation} —— **多行字符串**，是给 LLM 读的自然语言描述
 *       （局面、最近几步、以及"你的合法走法"清单），不是结构化对象；</li>
 *   <li>{@code legal} —— **数组**，每项形如 {@code {id:"e2e4", desc:"e4 (e2→e4)"}}，
 *       {@code id} 是机器用的句柄、{@code desc} 给人看；</li>
 *   <li>{@code actions}（回复里）—— **id 字符串数组**，不是对象数组。</li>
 * </ul>
 *
 * <p><b>为什么 legal 是 {@code List<Map<String,Object>>} 而不是强类型 record</b>：
 * 四个卡带的 legal 项虽然都有 id/desc，但可能各自带额外字段（卡牌带 zone、格子带类型）。
 * 用一个只有两字段的 record 会在上游加字段时**静默丢信息**，而这里丢一点就可能让动作非法。
 * 宽容结构 + 靠 id 匹配，是这里更安全的取舍。
 *
 * @param game        {@code chess} / {@code codenames} / {@code cakeduel} / {@code pictionary}
 * @param observation 自然语言局面描述（多行）
 * @param directive   玩家对她的口头指令；没有就是空串
 * @param legal       合法动作数组（每项含 {@code id}）
 * @param maxActions  本回合最多要几个动作（象棋 1、寻宝猜牌 9、寻宝给词 1）
 * @param clueMode    「给提示词」模式（森林寻宝专用），其余恒 false
 * @param image       PNG data URI；只有你画我猜会带，其余为 {@code null}
 */
public record GameTurnRequest(
        String game,
        String observation,
        String directive,
        List<Map<String, Object>> legal,
        int maxActions,
        boolean clueMode,
        String image) {

    /**
     * 转成发给 `/game-turn` 的 Map。
     *
     * <p>{@code clueMode} 恒发（服务端会读）；{@code image} 只在有图时发 —— 与 JS 侧
     * `image: spec.image || null` 等价：显式 null 和缺字段对上游一样，少一个字段少一处误解。
     */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game", game);
        body.put("observation", observation == null ? "" : observation);
        body.put("directive", directive == null ? "" : directive);
        body.put("legal", legal == null ? List.of() : legal);
        body.put("maxActions", maxActions);
        body.put("clueMode", clueMode);
        if (image != null && !image.isBlank()) {
            body.put("image", image);
        }
        return body;
    }

    /** 合法动作的 id 集合，用来校验大脑返回的 actions（不合法的一律丢弃）。 */
    public Set<String> legalIds() {
        if (legal == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : legal) {
            if (item != null && item.get("id") != null) {
                ids.add(String.valueOf(item.get("id")));
            }
        }
        return ids;
    }
}
