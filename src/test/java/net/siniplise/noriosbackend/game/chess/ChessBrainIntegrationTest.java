package net.siniplise.noriosbackend.game.chess;

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

/**
 * 完整链路：真实 fen → 我们的 {@code GameTurnRequest} → 25503 → 解析她选的走法。
 *
 * <p>这层测的是**别人看不到的东西**：请求形状对不对。单测只能证明"我们的解析没错"，
 * 证明不了"上游能读懂我们发的东西"。第一版我把 observation 写成对象、legal 写成对象数组，
 * 她回了"没有合法动作"——那种错只有真发一次才暴露。
 *
 * <p>25503 不在时整类跳过（{@link Assumptions}），不让 CI 因为没起服务而红。
 */
class ChessBrainIntegrationTest {

    private static boolean personaUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 25503), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("★ 真实调用：她能从我们给的开局走法里选一步，且该步合法")
    @Timeout(40)
    void realBrainPickIsLegal() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        ChessPosition pos = ChessPosition.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        HttpGameBrain brain = new HttpGameBrain("http://127.0.0.1:25503");

        GameTurnReply reply = brain.turn(pos.toRequest(null, "")).block(Duration.ofSeconds(30));

        assertThat(reply).as("大脑必须返回 ok=true").isNotNull();
        assertThat(reply.actions()).as("开局总有棋可走，必须给出动作").isNotEmpty();

        String id = reply.actions().get(0);
        assertThat(pos.parseId(id))
                .as("她选的 %s 必须是我们列出的合法走法（形状不对时她会回空动作）", id)
                .isPresent();
        assertThat(reply.lines()).as("她应当顺口说点什么").isNotEmpty();
        assertThat(reply.emotion()).isNotBlank();
    }

    @Test
    @DisplayName("★ 真实调用：她走完一步后，我们能接着算出新局面")
    @Timeout(40)
    void realBrainChainTwoMoves() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        ChessPosition pos = ChessPosition.fromFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        HttpGameBrain brain = new HttpGameBrain("http://127.0.0.1:25503");

        GameTurnReply reply = brain.turn(pos.toRequest(null, "")).block(Duration.ofSeconds(30));
        assertThat(reply).isNotNull();
        assertThat(reply.actions()).isNotEmpty();

        Move herMove = pos.parseId(reply.actions().get(0)).orElseThrow();
        Board after = pos.applied(herMove);

        // 她走完轮到黑方；黑方也必须有合法走法（说明局面推进是有效的）
        ChessPosition next = ChessPosition.fromFen(after.toFen());
        assertThat(next.turnIsWhite()).as("轮次应该已经切换").isFalse();
        assertThat(next.hasMoves()).as("新局面必须有棋可走").isTrue();
        assertThat(next.legalOptions()).isNotEmpty();
    }
}
