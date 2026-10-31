package net.siniplise.noriosbackend.game.cakeduel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import tools.jackson.databind.JsonNode;

/**
 * 蛋糕对决：局面 + 相位 → 给大脑的请求。
 *
 * <p>对齐 `nori_game.js#buildCakeduel`。与象棋/寻宝最大的不同是——
 * **合法动作取决于相位**：同一个 {@code claim:i} 在 attack 相位是"宣称打出"，
 * 在 block 相位是"用这张牌格挡"。所以 {@link CakeduelRules.Phase} 必须由调用方先算好再传进来。
 *
 * <p><b>无法从真实帧校准</b>（golden 里只录到"挂载"，没开局），字段名从 JS 推。
 * 解析一律宽容：缺 {@code players}/{@code hand} 就当空手牌，绝不抛异常打断帧流。
 */
public final class CakeduelPosition {

    /** 一方的可见状态。 */
    public record Side(List<Integer> hand, Integer cakes) {
    }

    private final String phase;
    private final String attackType;
    private final List<Integer> hand;
    private final String[] cardList;
    private final Integer myCakes;
    private final Integer oppCakes;
    private final int agentIndex;

    private CakeduelPosition(String phase, String attackType, List<Integer> hand, String[] cardList,
                             Integer myCakes, Integer oppCakes, int agentIndex) {
        this.phase = phase;
        this.attackType = attackType;
        this.hand = hand;
        this.cardList = cardList;
        this.myCakes = myCakes;
        this.oppCakes = oppCakes;
        this.agentIndex = agentIndex;
    }

    /**
     * 从 {@code gameState} + 推导好的相位构造。
     *
     * @param agentIndex 她在 players 里的下标（生产用 {@link CakeduelRules#AGENT_INDEX}，测试可传 0/1）
     */
    public static CakeduelPosition from(JsonNode gs, CakeduelRules.Phase phase, int agentIndex) {
        if (gs == null || !gs.isObject()) {
            return new CakeduelPosition("attack", null, List.of(), new String[0], null, null, agentIndex);
        }
        List<Integer> hand = handOf(gs, agentIndex);
        String[] cards = cardListOf(gs);
        Side me = sideOf(gs, agentIndex);
        Side opp = sideOf(gs, CakeduelRules.other(agentIndex));
        return new CakeduelPosition(
                phase == null ? CakeduelRules.text(gs, "phase") : phase.phase(),
                phase == null ? null : phase.attackType(),
                hand, cards, me.cakes(), opp.cakes(), agentIndex);
    }

    /**
     * 合法动作。
     *
     * <p>三条容易漏的规则：
     * ① {@code wolfy} **不能宣称**（哪怕它看起来像张攻击牌）——它是 ARG 剧情节点；
     * ② block 相位的格挡牌要**按伤害类型匹配**（盾兵只能挡物理、科学家只能挡魔法）；
     * ③ pick 相位没有 pass（必须在手牌里挑一张），其余相位都有。
     */
    public List<Map<String, Object>> legalOptions() {
        List<Map<String, Object>> legal = new ArrayList<>();

        for (int i = 0; i < hand.size(); i++) {
            String name = cardName(hand.get(i));
            CakeduelRules.CardMeta meta = CakeduelRules.meta(name);

            if ("attack".equals(phase) && meta.claimable() && !"wolfy".equals(name)) {
                legal.add(option("claim:" + i,
                        "宣称打出 " + name + "（手牌第" + (i + 1) + "张"
                                + (meta.type() == null ? "" : "，" + meta.type()) + "）"));
            }
            if ("block".equals(phase) && meta.blockType() != null
                    && (attackType == null || meta.blockType().equals(attackType) || "all_types".equals(attackType))) {
                legal.add(option("claim:" + i, "用 " + name + " 格挡（手牌第" + (i + 1) + "张）"));
            }
            if ("pick".equals(phase)) {
                legal.add(option("pick:" + i, "挑走 " + name));
            }
        }

        if (!"pick".equals(phase)) {
            legal.add(option("pass", "这一轮先过（不宣称）"));
        }
        if ("block".equals(phase) || "review".equals(phase)) {
            legal.add(option("challenge", "质疑对方的宣称"));
        }
        legal.add(option("concede", "认输这一整局"));
        return legal;
    }

    /** 拼给大脑的局面描述。 */
    public String observation() {
        StringBuilder handText = new StringBuilder();
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) {
                handText.append("  ");
            }
            handText.append(i + 1).append('.').append(cardName(hand.get(i)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("蛋糕对决：轮到你行动。当前阶段：").append(phase);
        if (attackType != null && !attackType.isBlank()) {
            sb.append("，对方宣称的伤害类型是「").append(attackType).append("」");
        }
        sb.append("。\n");
        sb.append("你的手牌：").append(handText).append("。\n");
        sb.append("双方蛋糕数：你 ").append(myCakes == null ? "?" : myCakes)
                .append("，他 ").append(oppCakes == null ? "?" : oppCakes).append("。\n");
        sb.append("规则：士兵/弓箭手=物理1伤、法师=魔法2伤（可攻击）；盾兵防物理、科学家防魔法（可格挡）；狼爵士不能宣称。\n");
        sb.append("阶段 attack=你要宣称攻击；block=对方宣称了、你要格挡或过；review=回应对面的格挡。\n");
        sb.append("只能从下面的合法动作里挑一个。");
        return sb.toString();
    }

    /** 产出一次请求（{@code maxActions} 恒为 1：一次只能出一个动作）。 */
    public GameTurnRequest toRequest(String directive) {
        return new GameTurnRequest(
                "cakeduel",
                observation(),
                directive == null ? "" : directive,
                legalOptions(),
                1,
                false,
                null);
    }

    /**
     * 把大脑选的 id 翻成上游指令。
     *
     * <p>**只取第一个合法 id**（对齐 JS 的 {@code find}）：一次只能动一下，
     * 多给的忽略——否则会连发两个动作把相位搞乱。
     */
    public List<Map<String, Object>> toCommands(List<String> actionIds) {
        if (actionIds == null || actionIds.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        for (Map<String, Object> o : legalOptions()) {
            allowed.add(String.valueOf(o.get("id")));
        }
        for (String raw : actionIds) {
            if (raw == null) {
                continue;
            }
            String id = raw.strip();
            if (!allowed.contains(id)) {
                continue;
            }
            Map<String, Object> action = toAction(id);
            if (action == null) {
                continue;
            }
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("type", "play");
            cmd.put("action", action);
            return List.of(cmd);   // 只取第一个
        }
        return List.of();
    }

    /** 单个 id → {@code action} 对象。 */
    private Map<String, Object> toAction(String id) {
        if (id.startsWith("claim:")) {
            Integer i = parseIndex(id, "claim:");
            if (i == null || i < 0 || i >= hand.size()) {
                return null;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "claim");
            action.put("handIndices", List.of(i));
            action.put("claim", cardName(hand.get(i)));
            return action;
        }
        if (id.startsWith("pick:")) {
            Integer i = parseIndex(id, "pick:");
            if (i == null || i < 0 || i >= hand.size()) {
                return null;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "pick");
            action.put("pickIndices", List.of(i));
            return action;
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", id);   // pass / challenge / concede
        return action;
    }

    private static Integer parseIndex(String id, String prefix) {
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> option(String id, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("desc", desc);
        return m;
    }

    /** 手牌索引 → 牌名；越界返回 "?"。 */
    private String cardName(int cardId) {
        return (cardId >= 0 && cardId < cardList.length) ? cardList[cardId] : "?";
    }

    // ── 解析工具 ───────────────────────────────────────────────────────

    private static List<Integer> handOf(JsonNode gs, int idx) {
        JsonNode players = gs.get("players");
        if (players == null || !players.isArray() || idx < 0 || idx >= players.size()) {
            return List.of();
        }
        JsonNode me = players.get(idx);
        JsonNode hand = (me != null && me.isObject()) ? me.get("hand") : null;
        if (hand == null || !hand.isArray()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (JsonNode c : hand) {
            if (c.isNumber()) {
                out.add(c.asInt());
            }
        }
        return out;
    }

    private static Side sideOf(JsonNode gs, int idx) {
        JsonNode players = gs.get("players");
        if (players == null || !players.isArray() || idx < 0 || idx >= players.size()) {
            return new Side(List.of(), null);
        }
        JsonNode p = players.get(idx);
        if (p == null || !p.isObject()) {
            return new Side(List.of(), null);
        }
        JsonNode cakes = p.get("cakes");
        return new Side(List.of(), (cakes != null && cakes.isNumber()) ? cakes.asInt() : null);
    }

    private static String[] cardListOf(JsonNode gs) {
        JsonNode list = gs.get("cardList");
        if (list == null || !list.isArray()) {
            return new String[0];
        }
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).isString() ? list.get(i).asString() : "?";
        }
        return out;
    }
}
