package net.siniplise.noriosbackend.game.cakeduel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 蛋糕对决：卡牌属性 + 相位模拟 + 合法动作 + 动作映射。
 *
 * <p>相位模拟那几条用例直接对应 JS 里**被修正过的两条规则**（block 过牌回 attack、challenge 直接结束 bout）
 * ——它们是"写错了会让整局卡死"的地方，所以每条都单独钉。
 */
class CakeduelRulesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    // ── 卡牌元数据 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("卡牌：士兵可宣称 1 物理，盾兵可挡物理但不可宣称")
    void cardMeta() {
        assertThat(CakeduelRules.meta("soldier").claimable()).isTrue();
        assertThat(CakeduelRules.meta("soldier").attack()).isEqualTo(1);
        assertThat(CakeduelRules.meta("soldier").type()).isEqualTo("physical");

        assertThat(CakeduelRules.meta("wizard").attack()).isEqualTo(2);
        assertThat(CakeduelRules.meta("wizard").type()).isEqualTo("magical");

        assertThat(CakeduelRules.meta("defender").claimable()).as("盾兵不能攻击").isFalse();
        assertThat(CakeduelRules.meta("defender").blockType()).isEqualTo("physical");
        assertThat(CakeduelRules.meta("scientist").blockType()).isEqualTo("magical");

        assertThat(CakeduelRules.meta("wolfy").claimable()).as("狼爵士不能宣称").isFalse();
        assertThat(CakeduelRules.meta("不存在的牌").claimable()).isFalse();
        assertThat(CakeduelRules.meta(null).claimable()).isFalse();
    }

    // ── 相位模拟（核心）────────────────────────────────────────────────

    /** 玩家索引 0、她索引 1 的局面。 */
    private static JsonNode state(String phase, int attackerIndex, String extra) {
        return json("""
                {"phase":"%s","attackerIndex":%d %s}
                """.formatted(phase, attackerIndex, extra));
    }

    @Test
    @DisplayName("★ 玩家在 attack 宣称 ⇒ 进入 block，轮到她格挡，且带上伤害类型")
    void claimLeadsToBlock() {
        JsonNode g = state("attack", 0, """
                ,"players":[{"hand":[0],"cakes":0},{"hand":[3],"cakes":0}]
                ,"cardList":["soldier","archer","wizard","defender"]
                """);
        var phase = CakeduelRules.simulateAfterPlayerAction(g,
                json("{\"type\":\"claim\",\"handIndices\":[0],\"claim\":\"soldier\"}"));

        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("block");
        assertThat(phase.actor()).as("轮到她（1）").isEqualTo(1);
        assertThat(phase.attackType()).isEqualTo("physical");
        assertThat(phase.isAgentTurn()).isTrue();
    }

    @Test
    @DisplayName("玩家在 block 格挡 ⇒ 进入 review，轮到攻击方回应")
    void blockLeadsToReview() {
        // 攻击方是 1（她），玩家 0 在 block 相位格挡
        JsonNode g = state("block", 1, """
                ,"attackingClaim":{"claim":"soldier"}
                """);
        var phase = CakeduelRules.simulateAfterPlayerAction(g,
                json("{\"type\":\"claim\",\"handIndices\":[0],\"claim\":\"defender\"}"));

        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("review");
        assertThat(phase.actor()).as("回到攻击方（1）").isEqualTo(1);
        assertThat(phase.attackType()).isEqualTo("physical");
    }

    @Test
    @DisplayName("★ block 相位过牌 ⇒ 回 attack（不是 review），交给对方")
    void blockPassGoesToAttack() {
        JsonNode g = state("block", 1, "");
        var phase = CakeduelRules.simulateAfterPlayerAction(g, json("{\"type\":\"pass\"}"));

        assertThat(phase).isNotNull();
        assertThat(phase.phase()).as("JS 修正过：不是 review").isEqualTo("attack");
        assertThat(phase.actor()).isEqualTo(0);
    }

    @Test
    @DisplayName("★ review 相位过牌 ⇒ 也回 attack")
    void reviewPassGoesToAttack() {
        JsonNode g = state("review", 0, "");
        var phase = CakeduelRules.simulateAfterPlayerAction(g, json("{\"type\":\"pass\"}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("attack");
    }

    @Test
    @DisplayName("attack 相位连续两次过牌 ⇒ bout_end")
    void doublePassEndsBout() {
        JsonNode g = state("attack", 0, ",\"lastAttackPassed\":true");
        var phase = CakeduelRules.simulateAfterPlayerAction(g, json("{\"type\":\"pass\"}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("bout_end");
        assertThat(phase.actor()).isEqualTo(-1);
        assertThat(phase.isOver()).isTrue();
    }

    @Test
    @DisplayName("只是第一次过牌 ⇒ 还在 attack，换人")
    void firstPassStaysAttack() {
        JsonNode g = state("attack", 0, "");
        var phase = CakeduelRules.simulateAfterPlayerAction(g, json("{\"type\":\"pass\"}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("attack");
        assertThat(phase.actor()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ challenge ⇒ 直接 bout_end（不存在挑战完接着 attack）")
    void challengeEndsBout() {
        JsonNode g = state("block", 1, "");
        var phase = CakeduelRules.simulateAfterPlayerAction(g, json("{\"type\":\"challenge\"}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("bout_end");
        assertThat(phase.isOver()).isTrue();
    }

    @Test
    @DisplayName("concede ⇒ bout_end")
    void concedeEndsBout() {
        var phase = CakeduelRules.simulateAfterPlayerAction(state("attack", 0, ""),
                json("{\"type\":\"concede\"}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("bout_end");
    }

    @Test
    @DisplayName("pick 相位挑牌 ⇒ 回 attack")
    void pickGoesToAttack() {
        var phase = CakeduelRules.simulateAfterPlayerAction(state("pick", 0, ""),
                json("{\"type\":\"pick\",\"pickIndices\":[0]}"));
        assertThat(phase).isNotNull();
        assertThat(phase.phase()).isEqualTo("attack");
    }

    @Test
    @DisplayName("相位不匹配的动作 ⇒ null（交给服务端推进，别瞎猜）")
    void mismatchedActionReturnsNull() {
        assertThat(CakeduelRules.simulateAfterPlayerAction(state("attack", 1, ""),
                json("{\"type\":\"claim\",\"handIndices\":[0]}"))).as("不是玩家的攻击阶段").isNull();
        assertThat(CakeduelRules.simulateAfterPlayerAction(state("pick", 0, ""),
                json("{\"type\":\"claim\",\"handIndices\":[0]}"))).isNull();
        assertThat(CakeduelRules.simulateAfterPlayerAction(state("attack", 0, ""),
                json("{\"type\":\"unknown\"}"))).isNull();
        assertThat(CakeduelRules.simulateAfterPlayerAction(null, json("{\"type\":\"pass\"}"))).isNull();
    }

    // ── 合法动作 ───────────────────────────────────────────────────────

    /** 她（索引 1）手牌：soldier / defender / wolfy。 */
    private static JsonNode herTurn(String phase, String attackType) {
        return json("""
                {"phase":"%s","attackerIndex":1
                ,"players":[{"hand":[],"cakes":1},{"hand":[0,3,5],"cakes":2}]
                ,"cardList":["soldier","archer","wizard","defender","scientist","wolfy"]
                ,"attackingClaim":%s}
                """.formatted(phase, attackType == null ? "null" : "{\"claim\":\"" + attackType + "\"}"));
    }

    @Test
    @DisplayName("attack 相位：只有可宣称的牌能选，wolfy 被排除")
    void legalInAttack() {
        var pos = CakeduelPosition.from(herTurn("attack", null),
                new CakeduelRules.Phase("attack", 1, null), 1);
        List<String> ids = pos.legalOptions().stream().map(m -> String.valueOf(m.get("id"))).toList();

        assertThat(ids).contains("claim:0");            // soldier
        assertThat(ids).as("wolfy 不能宣称").doesNotContain("claim:2");
        assertThat(ids).as("defender 不能宣称").doesNotContain("claim:1");
        assertThat(ids).contains("pass", "concede");
        assertThat(ids).as("attack 相位不能 challenge").doesNotContain("challenge");
    }

    @Test
    @DisplayName("block 相位：格挡牌按伤害类型筛（物理→盾兵，魔法→科学家）")
    void legalInBlock() {
        var phys = CakeduelPosition.from(herTurn("block", "soldier"),
                new CakeduelRules.Phase("block", 1, "physical"), 1);
        List<String> ids = phys.legalOptions().stream().map(m -> String.valueOf(m.get("id"))).toList();
        assertThat(ids).contains("claim:1").as("盾兵能挡物理").doesNotContain("claim:0");

        var mag = CakeduelPosition.from(herTurn("block", "wizard"),
                new CakeduelRules.Phase("block", 1, "magical"), 1);
        List<String> magIds = mag.legalOptions().stream().map(m -> String.valueOf(m.get("id"))).toList();
        assertThat(magIds).as("她手里没有科学家，挡不了魔法").doesNotContain("claim:1");
        assertThat(magIds).contains("pass", "challenge", "concede");
    }

    @Test
    @DisplayName("pick 相位：只有挑牌，没有 pass")
    void legalInPick() {
        var pos = CakeduelPosition.from(herTurn("pick", null),
                new CakeduelRules.Phase("pick", 1, null), 1);
        List<String> ids = pos.legalOptions().stream().map(m -> String.valueOf(m.get("id"))).toList();
        assertThat(ids).contains("pick:0", "pick:1", "pick:2");
        assertThat(ids).as("pick 必须挑，不能过").doesNotContain("pass");
    }

    @Test
    @DisplayName("observation：阶段、手牌、蛋糕数、规则都在")
    void observationShape() {
        var pos = CakeduelPosition.from(herTurn("block", "soldier"),
                new CakeduelRules.Phase("block", 1, "physical"), 1);
        String obs = pos.observation();
        assertThat(obs).contains("当前阶段：block");
        assertThat(obs).contains("对方宣称的伤害类型是「physical」");
        assertThat(obs).contains("1.soldier").contains("2.defender").contains("3.wolfy");
        assertThat(obs).contains("你 2").contains("他 1");
        assertThat(obs).contains("狼爵士不能宣称");
    }

    // ── 动作映射 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("claim:i ⇒ play.action.claim，带 handIndices 与牌名")
    void mapClaim() {
        var pos = CakeduelPosition.from(herTurn("attack", null),
                new CakeduelRules.Phase("attack", 1, null), 1);
        var cmds = pos.toCommands(List.of("claim:0"));

        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0)).containsEntry("type", "play");
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) cmds.get(0).get("action");
        assertThat(action).containsEntry("type", "claim");
        assertThat(action).containsEntry("claim", "soldier");
        assertThat(action.get("handIndices")).isEqualTo(List.of(0));
    }

    @Test
    @DisplayName("pick:i ⇒ play.action.pick + pickIndices")
    void mapPick() {
        var pos = CakeduelPosition.from(herTurn("pick", null),
                new CakeduelRules.Phase("pick", 1, null), 1);
        var cmds = pos.toCommands(List.of("pick:2"));
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) cmds.get(0).get("action");
        assertThat(action).containsEntry("type", "pick");
        assertThat(action.get("pickIndices")).isEqualTo(List.of(2));
    }

    @Test
    @DisplayName("pass / challenge / concede ⇒ 无参 action")
    void mapSimple() {
        var pos = CakeduelPosition.from(herTurn("block", "soldier"),
                new CakeduelRules.Phase("block", 1, "physical"), 1);
        for (String id : List.of("pass", "challenge", "concede")) {
            var cmds = pos.toCommands(List.of(id));
            @SuppressWarnings("unchecked")
            var action = (Map<String, Object>) cmds.get(0).get("action");
            assertThat(action).containsEntry("type", id);
        }
    }

    @Test
    @DisplayName("★ 只取第一个合法 id（多给的忽略，否则会连发两个动作搞乱相位）")
    void takesOnlyFirst() {
        var pos = CakeduelPosition.from(herTurn("attack", null),
                new CakeduelRules.Phase("attack", 1, null), 1);
        var cmds = pos.toCommands(List.of("claim:0", "pass"));
        assertThat(cmds).hasSize(1);
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) cmds.get(0).get("action");
        assertThat(action).containsEntry("type", "claim");
    }

    @Test
    @DisplayName("非法 id 全部跳过；全非法 ⇒ 空指令")
    void illegalIdsDropped() {
        var pos = CakeduelPosition.from(herTurn("attack", null),
                new CakeduelRules.Phase("attack", 1, null), 1);
        assertThat(pos.toCommands(List.of("nonsense", "claim:99"))).isEmpty();
        assertThat(pos.toCommands(List.of())).isEmpty();
        assertThat(pos.toCommands(null)).isEmpty();
    }

    @Test
    @DisplayName("宽容解析：缺 players/cardList ⇒ 空手牌，不抛异常")
    void tolerant() {
        var pos = CakeduelPosition.from(json("{\"phase\":\"attack\"}"),
                new CakeduelRules.Phase("attack", 1, null), 1);
        assertThat(pos.legalOptions()).isNotEmpty();   // 只剩 pass / concede
        assertThat(pos.observation()).contains("1.".isEmpty() ? "" : "你的手牌：");
        assertThat(CakeduelPosition.from(null, null, 1).legalOptions()).isNotEmpty();
    }

    @Test
    @DisplayName("toRequest：maxActions=1、clueMode=false、无图")
    void requestShape() {
        var pos = CakeduelPosition.from(herTurn("attack", null),
                new CakeduelRules.Phase("attack", 1, null), 1);
        var req = pos.toRequest("走");
        assertThat(req.game()).isEqualTo("cakeduel");
        assertThat(req.maxActions()).isEqualTo(1);
        assertThat(req.clueMode()).isFalse();
        assertThat(req.image()).isNull();
    }

    // ── 行动者推导（最容易写错的一处）────────────────────────────────

    @Test
    @DisplayName("★ actingIndex：block 相位是**被攻击方**行动，不是攻击方")
    void actingIndexBlockIsDefender() {
        // 攻击方是 1 ⇒ 该 0（玩家）格挡
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"block\",\"attackerIndex\":1}")))
                .isEqualTo(0);
        // 攻击方是 0 ⇒ 该 1（她）格挡
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"block\",\"attackerIndex\":0}")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("actingIndex：attack / review 是攻击方，pick 看 pickPhaseEffects")
    void actingIndexOtherPhases() {
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"attack\",\"attackerIndex\":1}")))
                .isEqualTo(1);
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"review\",\"attackerIndex\":1}")))
                .isEqualTo(1);
        // pick：不能按攻击方算，两边都可能挑
        assertThat(CakeduelRules.actingIndex(json(
                "{\"phase\":\"pick\",\"attackerIndex\":1,\"pickPhaseEffects\":[{\"player\":0}]}")))
                .isEqualTo(0);
        assertThat(CakeduelRules.actingIndex(json(
                "{\"phase\":\"pick\",\"attackerIndex\":1,\"pickPhaseEffects\":[{\"player\":1}]}")))
                .isEqualTo(1);
        // 没有 pickPhaseEffects ⇒ 没人该动
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"pick\",\"attackerIndex\":1}")))
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("actingIndex：未知相位 / 空局面 ⇒ -1（没人该动）")
    void actingIndexUnknown() {
        assertThat(CakeduelRules.actingIndex(json("{\"phase\":\"bout_end\",\"attackerIndex\":1}")))
                .isEqualTo(-1);
        assertThat(CakeduelRules.actingIndex(json("{}"))).isEqualTo(-1);
        assertThat(CakeduelRules.actingIndex(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("★ currentForAgent：她在 block 相位当防守方时才算轮到她")
    void currentForAgentBlock() {
        // 攻击方 0（玩家）⇒ block 该 1（她）✅
        var herTurn = CakeduelRules.currentForAgent(
                json("{\"phase\":\"block\",\"attackerIndex\":0,\"attackingClaim\":{\"claim\":\"soldier\"}}"));
        assertThat(herTurn.isAgentTurn()).isTrue();
        assertThat(herTurn.attackType()).isEqualTo("physical");

        // 攻击方 1（她）⇒ block 该 0（玩家）⇒ 不该她动
        var notHer = CakeduelRules.currentForAgent(json("{\"phase\":\"block\",\"attackerIndex\":1}"));
        assertThat(notHer.isAgentTurn()).isFalse();
    }

}
