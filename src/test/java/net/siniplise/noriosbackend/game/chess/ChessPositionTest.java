package net.siniplise.noriosbackend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 象棋门面：fen → 给大脑的请求。
 *
 * <p>重点是**文案与 JS 对齐**（{@code buildChess} 的输出格式）——
 * 那段 observation 是喂给语言模型的，格式变了她的理解就变了。
 * 所以这里断言的是"关键片段在不在"，不是整段字符串相等（后者一改文案就红，维护成本高）。
 */
class ChessPositionTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    @DisplayName("observation 含「你执白方」+ FEN + 合法走法清单")
    void observationShape() {
        String obs = ChessPosition.fromFen(START).observation(null);

        assertThat(obs).contains("轮到你走子");
        assertThat(obs).as("轮到白方 ⇒ 她执白方").contains("你执白方");
        assertThat(obs).contains("当前局面 FEN：" + START);
        assertThat(obs).contains("你的合法走法");
        assertThat(obs).as("开局 e2e4 要在列表里").contains("e2e4");
        assertThat(obs).contains("挑一个你满意的走法");
        assertThat(obs).as("没给最近几步时不该出现该行").doesNotContain("最近几步：");
    }

    @Test
    @DisplayName("observation 带最近几步")
    void observationWithRecent() {
        String obs = ChessPosition.fromFen(START).observation("e4 e5 Nf3");
        assertThat(obs).contains("最近几步：e4 e5 Nf3");
    }

    @Test
    @DisplayName("轮到黑方时她说「你执黑方」（方向不能搞反）")
    void observationBlackTurn() {
        ChessPosition pos = ChessPosition.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        assertThat(pos.turnIsWhite()).isFalse();
        assertThat(pos.observation(null)).contains("你执黑方");
    }

    @Test
    @DisplayName("legalOptions：每项都有 id 和 desc，desc 是「SAN (起→终)」")
    void legalOptionsShape() {
        List<Map<String, Object>> legal = ChessPosition.fromFen(START).legalOptions();
        assertThat(legal).hasSize(20);

        Map<String, Object> e4 = legal.stream()
                .filter(m -> "e2e4".equals(m.get("id")))
                .findFirst().orElseThrow();
        assertThat(e4.get("desc")).isEqualTo("e4 (e2→e4)");
    }

    @Test
    @DisplayName("开局 SAN 正确：兵记目标格、马记 Nf3")
    void sanBasics() {
        ChessPosition pos = ChessPosition.fromFen(START);
        Map<String, String> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> m : pos.legalOptions()) {
            byId.put(String.valueOf(m.get("id")), String.valueOf(m.get("desc")));
        }
        assertThat(byId.get("e2e4")).startsWith("e4 ");
        assertThat(byId.get("g1f3")).startsWith("Nf3 ");
        assertThat(byId.get("b1c3")).startsWith("Nc3 ");
    }

    @Test
    @DisplayName("SAN：吃子带 x、将军带 +、将死带 #")
    void sanCaptureCheckMate() {
        // 后走到 h5：h5-g6-f7-e8 正好斜打黑王 ⇒ 必须带 +（第一版我漏了这个后缀）
        Board b = Board.fromFen("4k3/8/8/4p3/8/8/8/4K2Q w - - 0 1");
        Move m = MoveGen.legalMoves(b).stream()
                .filter(x -> x.from() == Board.parseSquare("h1") && x.to() == Board.parseSquare("h5"))
                .findFirst().orElseThrow();
        assertThat(San.of(b, m)).isEqualTo("Qh5+");

        // 纯吃子、不将军：车从 a1 沿 a 线吃 a7 的兵
        Board capture = Board.fromFen("4k3/p7/8/8/8/8/8/R3K3 w - - 0 1");
        Move rx = MoveGen.legalMoves(capture).stream()
                .filter(x -> x.from() == Board.parseSquare("a1") && x.to() == Board.parseSquare("a7"))
                .findFirst().orElseThrow();
        assertThat(San.of(capture, rx)).as("吃子带 x，且不将军").isEqualTo("Rxa7");

        // 将死：后 + 王配合
        Board b3 = Board.fromFen("7k/8/6K1/8/8/8/8/7Q w - - 0 1");
        Move mate = MoveGen.legalMoves(b3).stream()
                .filter(x -> x.from() == Board.parseSquare("h1") && x.to() == Board.parseSquare("h7"))
                .findFirst().orElseThrow();
        assertThat(San.of(b3, mate)).as("Qh7# 将死").isEqualTo("Qh7#");
    }

    @Test
    @DisplayName("SAN 消歧义：两个马都能到同一格时补起点列")
    void sanDisambiguation() {
        // b1 与 f3 的马都能到 d2
        Board b = Board.fromFen("4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1");
        Move fromB1 = MoveGen.legalMoves(b).stream()
                .filter(m -> m.from() == Board.parseSquare("b1") && m.to() == Board.parseSquare("d2"))
                .findFirst().orElseThrow();
        assertThat(San.of(b, fromB1)).as("两个马都能到 d2 ⇒ 补列").isEqualTo("Nbd2");
    }

    @Test
    @DisplayName("SAN 易位：O-O / O-O-O")
    void sanCastling() {
        Board b = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Move shortC = MoveGen.legalMoves(b).stream()
                .filter(m -> m.castle() && m.to() == Board.parseSquare("g1"))
                .findFirst().orElseThrow();
        assertThat(San.of(b, shortC)).isEqualTo("O-O");
        Move longC = MoveGen.legalMoves(b).stream()
                .filter(m -> m.castle() && m.to() == Board.parseSquare("c1"))
                .findFirst().orElseThrow();
        assertThat(San.of(b, longC)).isEqualTo("O-O-O");
    }

    @Test
    @DisplayName("parseId：认得全部合法走法（含升变后缀），非法返回空")
    void parseId() {
        ChessPosition pos = ChessPosition.fromFen(START);
        assertThat(pos.parseId("e2e4")).isPresent();
        assertThat(pos.parseId("e2e5")).as("非法走法").isEmpty();
        assertThat(pos.parseId("")).isEmpty();
        assertThat(pos.parseId(null)).isEmpty();

        ChessPosition promo = ChessPosition.fromFen("8/4P3/8/8/8/8/8/K6k w - - 0 1");
        assertThat(promo.parseId("e7e8q")).isPresent();
        assertThat(promo.parseId("e7e8n")).as("四种升变都要认").isPresent();
    }

    @Test
    @DisplayName("toRequest：maxActions=1、clueMode=false、不带图")
    void toRequestShape() {
        GameTurnRequest req = ChessPosition.fromFen(START).toRequest("e4", "随便走");
        assertThat(req.game()).isEqualTo("chess");
        assertThat(req.maxActions()).isEqualTo(1);
        assertThat(req.clueMode()).isFalse();
        assertThat(req.image()).isNull();
        assertThat(req.directive()).isEqualTo("随便走");
        assertThat(req.legal()).hasSize(20);
        assertThat(req.observation()).contains("you执白方".replace("you", "你"));
    }

    @Test
    @DisplayName("复杂局面只列前 80 条，但 parseId 仍认得全部")
    void promptTruncationDoesNotAffectParse() {
        // 皇后在空盘上能走 27 步，超过也不会触发截断；这里用一个走法很多的局面
        ChessPosition pos = ChessPosition.fromFen("R6R/3Q4/1Q4Q1/4Q3/2Q4Q/Q4Q2/pp1Q4/kBNN1KB1 w - - 0 1");
        List<Map<String, Object>> all = pos.legalOptions();
        String obs = pos.observation(null);
        assertThat(all.size()).isGreaterThan(80);
        assertThat(obs).contains("只列了前 80 条");

        // 第 81 条也必须要能被解析（否则大脑选到它就被误判非法）
        String id81 = String.valueOf(all.get(80).get("id"));
        assertThat(pos.parseId(id81)).as("被 prompt 截断不代表非法").isPresent();
    }

    @Test
    @DisplayName("将死局面：hasMoves 为假")
    void checkmateHasNoMoves() {
        // 愚人开局后的将死局面
        ChessPosition mate = ChessPosition.fromFen(
                "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
        assertThat(mate.hasMoves()).isFalse();
        assertThat(mate.inCheck()).isTrue();
    }

    @Test
    @DisplayName("坏 fen 不抛：tryFromFen 返回空（不能让一条脏帧打断整条流）")
    void tryFromFenTolerates() {
        assertThat(ChessPosition.tryFromFen("garbage")).isEmpty();
        assertThat(ChessPosition.tryFromFen("")).isEmpty();
        assertThat(ChessPosition.tryFromFen(null)).isEmpty();
        assertThat(ChessPosition.tryFromFen(START)).isPresent();
    }
}
