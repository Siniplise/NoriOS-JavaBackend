package net.siniplise.noriosbackend.game.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 象棋驱动：**什么时候该问她**，以及**她的回答怎么变成上游指令**。
 *
 * <p>"该不该问"错一次的后果是不对称的：
 * <ul>
 *   <li>该问没问 ⇒ 局停在那里，玩家看她不动（明显，容易发现）；</li>
 *   <li>不该问却问了 ⇒ 我们替她发一个**非法动作**，上游直接拒绝、或者更糟：相位错乱。</li>
 * </ul>
 * 所以这里对"不该问"的每个分支都单独钉一条。
 */
class ChessDriverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** 玩家走完 e4 之后：轮到黑方。 */
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    // ── 该不该问 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("玩家执白、轮到黑方 ⇒ 这是她的回合，要问")
    void herTurnWhenPlayerIsWhite() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        var turn = ChessDriver.plan(gs, "white", "");

        assertThat(turn).isPresent();
        assertThat(turn.get().request().game()).isEqualTo("chess");
        assertThat(turn.get().request().maxActions()).isEqualTo(1);
        assertThat(turn.get().request().legalIds()).as("黑方开局有 20 步").hasSize(20);
    }

    @Test
    @DisplayName("玩家执白、轮到白方 ⇒ 不该动（这是玩家自己的回合）")
    void notHerTurnWhenPlayerToMove() {
        var gs = json("{\"fen\":\"" + START + "\",\"status\":\"playing\"}");
        assertThat(ChessDriver.plan(gs, "white", "")).isEmpty();
    }

    @Test
    @DisplayName("玩家执黑、轮到白方 ⇒ 轮到她（方向反过来也要对）")
    void herTurnWhenPlayerIsBlack() {
        var gs = json("{\"fen\":\"" + START + "\",\"status\":\"playing\"}");
        assertThat(ChessDriver.plan(gs, "black", "")).isPresent();
    }

    @Test
    @DisplayName("玩家执黑、轮到黑方 ⇒ 不该动")
    void notHerTurnPlayerBlack() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        assertThat(ChessDriver.plan(gs, "black", "")).isEmpty();
    }

    @Test
    @DisplayName("playerSide 缺失 ⇒ 按玩家执白处理（与手势判据的默认值一致）")
    void defaultsToPlayerWhite() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        assertThat(ChessDriver.plan(gs, null, "")).isPresent();
    }

    @Test
    @DisplayName("终局不该问：status 是 checkmate/ended/draw 等一律跳过")
    void notOnGameOver() {
        for (String status : List.of("checkmate", "ended", "finished", "draw", "resigned", "gameover")) {
            var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"" + status + "\"}");
            assertThat(ChessDriver.plan(gs, "white", ""))
                    .as("status=%s 时不该问", status).isEmpty();
        }
    }

    @Test
    @DisplayName("phase 也是终局信号（上游可能没有 status 字段）")
    void notOnPhaseOver() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"phase\":\"ended\"}");
        assertThat(ChessDriver.plan(gs, "white", "")).isEmpty();
    }

    @Test
    @DisplayName("将死局面（轮到她但无棋可走）⇒ 不问，省一次推理")
    void notWhenNoMoves() {
        // 愚人开局将死：轮到白方（玩家执黑时就是她）
        var gs = json("{\"fen\":\"rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3\","
                + "\"status\":\"playing\"}");
        assertThat(ChessDriver.plan(gs, "black", "")).isEmpty();
    }

    @Test
    @DisplayName("无 fen / 坏 fen ⇒ 不问（一条脏帧不该触发推理）")
    void toleratesMissingOrBadFen() {
        assertThat(ChessDriver.plan(json("{\"status\":\"playing\"}"), "white", "")).isEmpty();
        assertThat(ChessDriver.plan(json("{\"fen\":\"\"}"), "white", "")).isEmpty();
        assertThat(ChessDriver.plan(json("{\"fen\":\"garbage\"}"), "white", "")).isEmpty();
        assertThat(ChessDriver.plan(null, "white", "")).isEmpty();
    }

    // ── 回答 → 指令 ───────────────────────────────────────────────────

    @Test
    @DisplayName("她的动作变成 move 指令（from/to 是格名）")
    void toCmdMove() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        var turn = ChessDriver.plan(gs, "white", "").orElseThrow();

        var reply = net.siniplise.noriosbackend.game.GameTurnReply.parse(
                json("{\"ok\":true,\"actions\":[\"e7e5\"],\"lines\":[\"应你一手\"]}"), 100);
        var cmd = turn.toCmd(reply);

        assertThat(cmd).isPresent();
        assertThat(cmd.get()).containsEntry("type", "move")
                .containsEntry("from", "e7").containsEntry("to", "e5");
        assertThat(cmd.get()).as("不升变就不带 promotion 字段").doesNotContainKey("promotion");
    }

    @Test
    @DisplayName("升变动作带上 promotion 字母")
    void toCmdPromotion() {
        // 白兵在 e7、e8 空（黑王挪到 a8，否则兵不能直吃）⇒ e7e8q 合法
        var gs = json("{\"fen\":\"k7/4P3/8/8/8/8/8/4K3 w - - 0 1\",\"status\":\"playing\"}");
        var turn = ChessDriver.plan(gs, "black", "").orElseThrow();
        assertThat(turn.request().legalIds()).as("四种升变都要列出来")
                .contains("e7e8q", "e7e8r", "e7e8b", "e7e8n");

        var reply = net.siniplise.noriosbackend.game.GameTurnReply.parse(
                json("{\"ok\":true,\"actions\":[\"e7e8q\"]}"), 100);
        var cmd = turn.toCmd(reply);
        assertThat(cmd).isPresent();
        assertThat(cmd.get()).containsEntry("promotion", "q");
    }

    @Test
    @DisplayName("她给了非法走法 ⇒ 跳过继续找；全非法 ⇒ 空（不发无效指令）")
    void toCmdSkipsIllegal() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        var turn = ChessDriver.plan(gs, "white", "").orElseThrow();

        var mixed = net.siniplise.noriosbackend.game.GameTurnReply.parse(
                json("{\"ok\":true,\"actions\":[\"e7e4\",\"e7e5\"]}"), 100);
        assertThat(turn.toCmd(mixed)).isPresent();
        assertThat(turn.toCmd(mixed).get()).containsEntry("to", "e5");

        var allBad = net.siniplise.noriosbackend.game.GameTurnReply.parse(
                json("{\"ok\":true,\"actions\":[\"a1a8\",\"nonsense\"]}"), 100);
        assertThat(turn.toCmd(allBad)).as("没有可执行的").isEmpty();

        assertThat(turn.toCmd(null)).isEmpty();
    }

    @Test
    @DisplayName("空动作列表 ⇒ 空指令")
    void toCmdEmptyActions() {
        var gs = json("{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}");
        var turn = ChessDriver.plan(gs, "white", "").orElseThrow();
        var reply = net.siniplise.noriosbackend.game.GameTurnReply.parse(
                json("{\"ok\":true,\"actions\":[]}"), 100);
        assertThat(turn.toCmd(reply)).isEmpty();
    }

    // ── headVersion 账本 ──────────────────────────────────────────────

    @Test
    @DisplayName("headVersion：从 runtime_transition 和 dispatch_ack 都要吃")
    void headsFromBothSources() {
        GameHeads heads = new GameHeads();
        heads.observe(json("{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\",\"version\":7}"));
        assertThat(heads.head("chess")).isEqualTo(7);

        heads.observe(json("{\"type\":\"dispatch_ack\",\"cartridgeId\":\"chess\",\"headVersion\":9}"));
        assertThat(heads.head("chess")).isEqualTo(9);
    }

    @Test
    @DisplayName("headVersion 只升不降（广播乱序时旧版本不能把 head 拉回去）")
    void headsNeverGoBackwards() {
        GameHeads heads = new GameHeads();
        heads.bump("chess", 10);
        heads.bump("chess", 4);
        assertThat(heads.head("chess")).isEqualTo(10);
    }

    @Test
    @DisplayName("headVersion：没见过就是 null（宁可放弃发命令，也不猜一个 0）")
    void headsUnknown() {
        GameHeads heads = new GameHeads();
        assertThat(heads.head("chess")).isNull();
        heads.observe(json("{\"type\":\"world_joined\",\"cartridgeId\":\"chess\",\"version\":3}"));
        assertThat(heads.head("chess")).as("world_joined 不参与记账").isNull();
    }

    @Test
    @DisplayName("新局 reset：版本从头开始，留着旧值会让新局所有指令被拒")
    void headsReset() {
        GameHeads heads = new GameHeads();
        heads.bump("chess", 42);
        heads.reset("chess");
        assertThat(heads.head("chess")).isNull();
        heads.bump("chess", 1);
        assertThat(heads.head("chess")).isEqualTo(1);
    }

    @Test
    @DisplayName("headVersion 也认 transition.version（有的实现把它包在里面）")
    void headsFromNestedVersion() {
        GameHeads heads = new GameHeads();
        heads.observe(json("{\"type\":\"runtime_transition\",\"cartridgeId\":\"cakeduel\","
                + "\"transition\":{\"version\":5}}"));
        assertThat(heads.head("cakeduel")).isEqualTo(5);
    }
}
