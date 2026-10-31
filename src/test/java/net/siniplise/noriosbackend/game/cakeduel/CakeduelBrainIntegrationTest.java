package net.siniplise.noriosbackend.game.cakeduel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.HttpGameBrain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * 蛋糕对决真实链路：给她一个 attack 相位 + 三张手牌，看她会不会宣称士兵。
 *
 * <p>这里能验证一件单测验不了的事：**她读不读得懂"阶段/手牌/规则"那段文案**。
 * 如果 observation 写拧了，她会挑 {@code wolfy}（不能宣称的那张）或者干脆选 pass，
 * 而单测只能证明"我筛出来的 legal 是合规的"，证明不了"她能挑对"。
 */
class CakeduelBrainIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static boolean personaUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 25503), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 她的回合（attack）：手牌 soldier / defender / wolfy，只有 soldier 可宣称。 */
    private static CakeduelPosition herAttackTurn() {
        var gs = MAPPER.readTree("""
                {"phase":"attack","attackerIndex":1,"lastAttackPassed":false,"attackingClaim":null,
                 "players":[{"hand":[],"cakes":1},{"hand":[0,3,5],"cakes":2}],
                 "cardList":["soldier","archer","wizard","defender","scientist","wolfy"]}
                """);
        var phase = CakeduelRules.currentForAgent(gs);
        return CakeduelPosition.from(gs, phase, 1);
    }

    @Test
    @DisplayName("★ 真实调用：她的选择必须是合法动作，且不选 wolfy")
    @Timeout(40)
    void realBrainPicksLegal() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        CakeduelPosition pos = herAttackTurn();
        HttpGameBrain brain = new HttpGameBrain("http://127.0.0.1:25503");

        var req = pos.toRequest("");
        GameTurnReply reply = brain.turn(req).block(Duration.ofSeconds(30));

        assertThat(reply).as("大脑必须 ok=true").isNotNull();
        assertThat(reply.actions()).as("轮到她行动，必须给动作").isNotEmpty();

        String id = reply.actions().get(0);
        assertThat(req.legalIds()).as("%s 必须是合法动作之一", id).contains(id);
        assertThat(id).as("wolfy 是 claim:2，绝不能选").isNotEqualTo("claim:2");

        // 映射成上游指令：必须是一条 play
        var cmds = pos.toCommands(reply.actions());
        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0)).containsEntry("type", "play");
    }

    @Test
    @DisplayName("★ 真实调用：她能读懂 block 相位，并只挑能挡物理的牌")
    @Timeout(40)
    void realBrainBlocks() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        // 对方宣称了物理攻击，她手里只有 defender（能挡物理）和 wizard（不能挡）
        var gs = MAPPER.readTree("""
                {"phase":"block","attackerIndex":0,"attackingClaim":{"claim":"soldier"},
                 "players":[{"hand":[0],"cakes":1},{"hand":[2,3],"cakes":2}],
                 "cardList":["soldier","archer","wizard","defender","scientist","wolfy"]}
                """);
        var phase = CakeduelRules.currentForAgent(gs);
        CakeduelPosition pos = CakeduelPosition.from(gs, phase, 1);

        var req = pos.toRequest("");
        // 她只能选 claim:1（defender）或 pass/challenge/concede
        assertThat(req.legalIds()).doesNotContain("claim:0").contains("claim:1");

        GameTurnReply reply = new HttpGameBrain("http://127.0.0.1:25503")
                .turn(req).block(Duration.ofSeconds(30));
        assertThat(reply).isNotNull();
        assertThat(reply.actions()).isNotEmpty();

        String id = reply.actions().get(0);
        assertThat(req.legalIds()).as("她选的 %s 必须合法", id).contains(id);
        assertThat(id).as("不能拿法师去挡物理").isNotEqualTo("claim:0");
    }
}
