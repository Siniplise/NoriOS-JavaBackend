package net.siniplise.noriosbackend.game.cakeduel;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 蛋糕对决的规则：卡牌属性 + 「玩家动作之后该轮到哪个相位」的推导。
 *
 * <p><b>为什么要模拟相位而不是直接读</b>：我们**扣住了玩家那一步**（先不发给上游，
 * 等算出她的应对再一起发），所以手里的 {@code gameState} 还停在"玩家动之前"。
 * 要问她"你打算怎么应"，就必须先在本地把相位推到"她该行动的那一刻"。
 * 这段推导是 `nori_game.js#simCakeduelAfterPlayerAction` 的 1:1 移植。
 *
 * <p><b>两条规则是踩坑改出来的，别"简化"回去</b>（JS 里都带着修正注释）：
 * <ul>
 *   <li>{@code block} 相位过牌（pass）后，引擎走的是**回到 attack、攻击权交给对方**，
 *       不是 review —— 原先按 review 写会让 actor 算错、我方漏补位 ⇒ 整局卡死；</li>
 *   <li>{@code challenge} 直接**结束本 bout**，不存在"挑战完接着 attack"。
 *       伪造后续 attack 同样会把 actor 算错。</li>
 * </ul>
 */
public final class CakeduelRules {

    /** 她在 players 里的下标。可用环境变量覆盖（对齐 JS 的 {@code NORI_AGENT_INDEX}）。 */
    public static final int AGENT_INDEX = agentIndexFromEnv();

    /** 玩家下标：永远是 1 - 她。 */
    public static final int PLAYER_INDEX = 1 - AGENT_INDEX;

    private static int agentIndexFromEnv() {
        String v = System.getenv("NORI_AGENT_INDEX");
        if (v == null || v.isBlank()) {
            return 1;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return (n == 0 || n == 1) ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private CakeduelRules() {
    }

    /**
     * 卡牌元数据（取自前端 bundle 的 base set）。
     *
     * @param type      伤害/功能类型：physical / magical / blocker / unclaimable / special
     * @param attack    攻击力；{@code null} 表示这张牌不能宣称攻击
     * @param blockType 能格挡的伤害类型；{@code null} 表示不能格挡
     */
    public record CardMeta(String type, Integer attack, String blockType) {
        /** 能不能在 attack 相位宣称。注意 {@code wolfy} 明明有 attack 字段也不能宣称。 */
        public boolean claimable() {
            return attack != null;
        }
    }

    private static final Map<String, CardMeta> CARDS = Map.ofEntries(
            Map.entry("soldier", new CardMeta("physical", 1, null)),
            Map.entry("archer", new CardMeta("physical", 1, null)),
            Map.entry("wizard", new CardMeta("magical", 2, null)),
            Map.entry("defender", new CardMeta("blocker", null, "physical")),
            Map.entry("scientist", new CardMeta("blocker", null, "magical")),
            // wolfy 是"不可宣称"的那张：它有攻击力但也明确不能打出（ARG 剧情节点就挂在它身上）
            Map.entry("wolfy", new CardMeta("unclaimable", null, null)),
            Map.entry("assassin", new CardMeta("physical", 5, null)),
            Map.entry("scout", new CardMeta("physical", 1, null)),
            Map.entry("summoner", new CardMeta("magical", 0, null)),
            Map.entry("quartermaster", new CardMeta("special", 0, null)),
            Map.entry("oracle", new CardMeta("special", 0, null)),
            Map.entry("priest", new CardMeta("special", 0, null)),
            Map.entry("angel", new CardMeta("special", 0, null)),
            Map.entry("baacrates", new CardMeta("special", 0, null)),
            Map.entry("agent_u", new CardMeta("special", 0, null)),
            Map.entry("pierrot", new CardMeta("special", 0, null)),
            Map.entry("robot", new CardMeta("special", null, null)));

    private static final CardMeta UNKNOWN = new CardMeta(null, null, null);

    /** 牌名 → 元数据；未知牌返回"什么都不能做"（宁可它不可选，也不要瞎猜它能攻击）。 */
    public static CardMeta meta(String name) {
        return name == null ? UNKNOWN : CARDS.getOrDefault(name, UNKNOWN);
    }

    /**
     * 一次模拟的结果。
     *
     * @param phase      推导出的相位：attack / block / review / pick / bout_end
     * @param actor      该行动的玩家下标；{@code -1} 表示这一轮/这一局结束，没人该动
     * @param attackType 对方宣称的伤害类型（block 时用来筛可用的格挡牌）
     */
    public record Phase(String phase, int actor, String attackType) {

        /** 这一轮结束了（换轮或整局结束），我方不该再出动作。 */
        public boolean isOver() {
            return "bout_end".equals(phase) || actor < 0;
        }

        /** 接下来是不是她行动。 */
        public boolean isAgentTurn() {
            return !isOver() && actor == AGENT_INDEX;
        }
    }

    /**
     * 玩家做了一个动作后，推导"下一步该谁、在什么相位"。
     *
     * @return {@code null} 表示推不出来（未知动作/相位不匹配）⇒ 调用方应当放弃，交给服务端推进
     */
    public static Phase simulateAfterPlayerAction(JsonNode g, JsonNode action) {
        if (g == null || !g.isObject() || action == null || !action.isObject()) {
            return null;
        }
        String type = text(action, "type");
        if (type == null) {
            return null;
        }
        String phase = text(g, "phase");
        int atk = intOr(g, "attackerIndex", -1);
        int pi = PLAYER_INDEX;
        String claimType = claimTypeOf(g);

        switch (type) {
            case "claim" -> {
                // 玩家在 attack 相位宣称 ⇒ 进入 block，轮到她格挡
                if ("attack".equals(phase) && atk == pi) {
                    String name = claimNameOf(g, action);
                    return new Phase("block", other(atk), meta(name).type());
                }
                // 玩家在 block 相位格挡 ⇒ 进入 review，轮到攻击方回应
                if ("block".equals(phase) && other(atk) == pi) {
                    return new Phase("review", atk, claimType);
                }
                return null;
            }
            case "pass" -> {
                if ("attack".equals(phase) && atk == pi) {
                    // 连续两次过牌 ⇒ 本轮结束
                    boolean alreadyPassed = boolOr(g, "lastAttackPassed", false);
                    boolean hasClaim = g.has("attackingClaim") && !g.get("attackingClaim").isNull();
                    if (!hasClaim && alreadyPassed) {
                        return new Phase("bout_end", -1, null);
                    }
                    return new Phase("attack", other(atk), null);
                }
                // ★ block 过牌 → 回到 attack，攻击权交给对方（不是 review）
                if ("block".equals(phase) && other(atk) == pi) {
                    return new Phase("attack", other(atk), null);
                }
                // ★ review 过牌 → 同样回到 attack，交给原攻击者
                if ("review".equals(phase) && atk == pi) {
                    return new Phase("attack", other(atk), null);
                }
                return null;
            }
            case "concede", "challenge" -> {
                // ★ 都是直接结束本 bout（challenge 不存在"挑战完接着 attack"）
                return new Phase("bout_end", -1, null);
            }
            case "pick" -> {
                if ("pick".equals(phase)) {
                    return new Phase("attack", other(atk), null);
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * **现在轮到谁行动**（对齐 JS 的 {@code actingIndexCake}）。
     *
     * <p>这是最容易写错的一处：行动者**不总是 {@code attackerIndex}**。
     * <ul>
     *   <li>{@code attack} / {@code review} —— 攻击方；</li>
     *   <li>{@code block} —— **被攻击方**（{@code 1 - attackerIndex}），因为是他要格挡；</li>
     *   <li>{@code pick} —— 看 {@code pickPhaseEffects[0].player}，两边都可能挑，不能按攻击方算；</li>
     *   <li>其它 —— -1（没人该动）。</li>
     * </ul>
     * 写错这里的后果是"我们替她发了一个不该她发的动作"，上游拒绝、或者相位错乱。
     *
     * @return 行动者下标；没人该动时 -1
     */
    public static int actingIndex(JsonNode g) {
        if (g == null || !g.isObject()) {
            return -1;
        }
        int atk = intOr(g, "attackerIndex", 0);
        String ph = text(g, "phase");
        if (ph == null) {
            return -1;
        }
        return switch (ph) {
            case "attack" -> atk;
            case "block" -> other(atk);
            case "review" -> atk;
            case "pick" -> {
                JsonNode effects = g.get("pickPhaseEffects");
                if (effects != null && effects.isArray() && !effects.isEmpty()) {
                    JsonNode first = effects.get(0);
                    JsonNode player = (first == null) ? null : first.get("player");
                    yield (player != null && player.isNumber()) ? player.asInt() : -1;
                }
                yield -1;
            }
            default -> -1;
        };
    }

    /** 她直接行动时（第二局由她开局，没有玩家动作可推）的相位；轮不到她就是 {@code isAgentTurn()==false}。 */
    public static Phase currentForAgent(JsonNode g) {
        if (g == null || !g.isObject()) {
            return null;
        }
        return new Phase(text(g, "phase"), actingIndex(g), claimTypeOf(g));
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    /** 对方下标（只有两人）。 */
    public static int other(int i) {
        return 1 - i;
    }

    /** 当前宣称的伤害类型；没有宣称就是 null。 */
    private static String claimTypeOf(JsonNode g) {
        JsonNode ac = g.get("attackingClaim");
        if (ac == null || !ac.isObject()) {
            return null;
        }
        String name = text(ac, "claim");
        return name == null ? null : CARD_TABLE_TYPE(name);
    }

    private static String CARD_TABLE_TYPE(String name) {
        String t = meta(name).type();
        return t == null ? null : t;
    }

    /** 玩家宣称的牌名：优先取 action.claim，否则用"手牌下标 → 牌表"解析。 */
    private static String claimNameOf(JsonNode g, JsonNode action) {
        String claim = text(action, "claim");
        if (claim != null && !claim.isBlank()) {
            return claim;
        }
        JsonNode idxs = action.get("handIndices");
        JsonNode list = g.get("cardList");
        JsonNode players = g.get("players");
        if (idxs == null || !idxs.isArray() || idxs.isEmpty() || list == null || !list.isArray()) {
            return null;
        }
        JsonNode me = (players != null && players.isArray() && players.size() > PLAYER_INDEX)
                ? players.get(PLAYER_INDEX) : null;
        JsonNode hand = (me != null && me.isObject()) ? me.get("hand") : null;
        if (hand == null || !hand.isArray()) {
            return null;
        }
        int hi = idxs.get(0).asInt();
        if (hi < 0 || hi >= hand.size()) {
            return null;
        }
        int cardId = hand.get(hi).asInt();
        return (cardId >= 0 && cardId < list.size()) ? list.get(cardId).asString() : null;
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    static int intOr(JsonNode n, String f, int fallback) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || !v.isNumber()) ? fallback : v.asInt();
    }

    static boolean boolOr(JsonNode n, String f, boolean fallback) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || !v.isBoolean()) ? fallback : v.asBoolean();
    }
}
