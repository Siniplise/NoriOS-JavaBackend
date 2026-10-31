package net.siniplise.noriosbackend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 走法生成验收 —— **perft**。
 *
 * <p>perft 是这一层的"标准答案"：给定局面和深度，走法树的叶子节点数在世界范围内是已知常量。
 * 数字对上了，基本可以认为走法生成、吃子、易位、过路兵、升变、将军剔除全都对；
 * 数字只要差一个，就说明某条边界的处理有漏。这比"我挑几个局面看看"强得多，
 * 也是敢自己写规则引擎的全部底气。
 *
 * <p>数字来源：chessprogramming.org 的 perft 结果表（初始局面 1~4 层 + Kiwipete 1~3 层）。
 */
class MoveGenTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    /** 经典测试局面：易位/牵制/复杂吃子都齐。 */
    private static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";

    @Test
    @DisplayName("初始局面 perft(1..4) —— 20 / 400 / 8902 / 197281")
    @Timeout(120)
    void perftStart() {
        Board b = Board.fromFen(START);
        assertThat(MoveGen.perft(b, 1)).isEqualTo(20L);
        assertThat(MoveGen.perft(b, 2)).isEqualTo(400L);
        assertThat(MoveGen.perft(b, 3)).isEqualTo(8902L);
        assertThat(MoveGen.perft(b, 4)).as("这一层能抓出绝大多数边界 bug").isEqualTo(197_281L);
    }

    @Test
    @DisplayName("Kiwipete perft(1..3) —— 48 / 2039 / 97862（易位+牵制全在这里）")
    @Timeout(180)
    void perftKiwipete() {
        Board b = Board.fromFen(KIWIPETE);
        assertThat(MoveGen.perft(b, 1)).isEqualTo(48L);
        assertThat(MoveGen.perft(b, 2)).isEqualTo(2039L);
        assertThat(MoveGen.perft(b, 3)).isEqualTo(97_862L);
    }

    @Test
    @DisplayName("残局 perft(1..3) —— 14 / 191 / 2812（过路兵与车兵残局）")
    @Timeout(120)
    void perftEndgame() {
        Board b = Board.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        assertThat(MoveGen.perft(b, 1)).isEqualTo(14L);
        assertThat(MoveGen.perft(b, 2)).isEqualTo(191L);
        assertThat(MoveGen.perft(b, 3)).isEqualTo(2812L);
    }

    @Test
    @DisplayName("升变局面 perft(1..3) —— 6 / 264 / 9467（四种升变都要算）")
    @Timeout(120)
    void perftPromotions() {
        Board b = Board.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");
        assertThat(MoveGen.perft(b, 1)).isEqualTo(6L);
        assertThat(MoveGen.perft(b, 2)).isEqualTo(264L);
        assertThat(MoveGen.perft(b, 3)).isEqualTo(9467L);
    }

    // ── FEN 往返 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FEN 往返：解析再输出必须一字不差")
    void fenRoundTrip() {
        for (String fen : List.of(
                START,
                KIWIPETE,
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                "8/8/8/8/8/8/8/K6k w - - 99 123")) {
            assertThat(Board.fromFen(fen).toFen()).isEqualTo(fen);
        }
    }

    @Test
    @DisplayName("FEN 坏输入：字段缺失/行数不对/非法字符都要明确抛")
    void fenRejectsGarbage() {
        assertThatThrownBy(() -> Board.fromFen("")).isInstanceOf(IllegalArgumentException.class);
        // 4 段就够解析（halfmove/fullmove 可缺省）——用 assertThatCode，别用 assertThatThrownBy
        assertThatCode(() -> Board.fromFen("8/8/8/8/8/8/8/8 w - -")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Board.fromFen("8/8/8/8/8/8/8 w - - 0 1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8 行");
        assertThatThrownBy(() -> Board.fromFen("8/8/8/8/8/8/8/9 w - - 0 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Board.fromFen("8/8/8/8/8/8/8/QQQQQQQQX w - - 0 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("坐标：a1=0x00、h8=0x77，parse/name 互逆")
    void squareCoords() {
        assertThat(Board.parseSquare("a1")).isEqualTo(0x00);
        assertThat(Board.parseSquare("h8")).isEqualTo(0x77);
        assertThat(Board.parseSquare("e2")).isEqualTo(0x14);
        assertThat(Board.squareName(Board.parseSquare("e2"))).isEqualTo("e2");
        assertThatThrownBy(() -> Board.parseSquare("z9")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UCI 形式与 JS 侧 legal id 同构（含升变后缀）")
    void uciMatchesJs() {
        assertThat(Move.of(Board.parseSquare("e2"), Board.parseSquare("e4")).uci()).isEqualTo("e2e4");
        assertThat(new Move(Board.parseSquare("e7"), Board.parseSquare("e8"), Board.QUEEN, false, false, false)
                .uci()).isEqualTo("e7e8q");
        assertThat(new Move(Board.parseSquare("e7"), Board.parseSquare("e8"), Board.KNIGHT, false, false, false)
                .uci()).isEqualTo("e7e8n");
    }

    // ── 具体规则点（perft 之外的定点验证）─────────────────────────────

    @Test
    @DisplayName("开局可以走兵双格，且会设置过路兵目标格")
    void doublePushSetsEp() {
        Board b = Board.fromFen(START);
        // 必须从 legalMoves 里取：手搓 Move.of(e2,e4) 不带 doublePush 标记，
        // 那样即使引擎正确也不会设过路兵格（这条测试第一版就是这么写错的）
        Move e4 = MoveGen.legalMoves(b).stream()
                .filter(m -> m.uci().equals("e2e4"))
                .findFirst().orElseThrow();
        assertThat(e4.doublePush()).isTrue();

        Board after = MoveGen.apply(b, e4);
        assertThat(after.epSquare()).isEqualTo(Board.parseSquare("e3"));
        assertThat(after.toFen()).contains(" b ").contains(" e3 ");
    }

    @Test
    @DisplayName("易位后车要跟着走，且易位权被撤掉")
    void castlingMovesRook() {
        Board b = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        List<Move> moves = MoveGen.legalMoves(b);
        Move shortCastle = moves.stream()
                .filter(m -> m.castle() && m.to() == Board.parseSquare("g1"))
                .findFirst().orElseThrow();
        Board after = MoveGen.apply(b, shortCastle);

        assertThat(after.pieceAt(Board.parseSquare("g1")) & Board.TYPE_MASK).isEqualTo(Board.KING);
        assertThat(after.pieceAt(Board.parseSquare("f1")) & Board.TYPE_MASK)
                .as("车要从 h1 挪到 f1").isEqualTo(Board.ROOK);
        assertThat(after.pieceAt(Board.parseSquare("h1"))).isEqualTo(Board.EMPTY);
        assertThat(after.castling())
                .as("白方两侧权利撤掉，黑方的 kq 要保留")
                .isEqualTo(Board.CASTLE_BK | Board.CASTLE_BQ);
    }

    @Test
    @DisplayName("王经过被攻击的格子时不能易位")
    void cannotCastleThroughAttackedSquare() {
        // 黑车在 f 线上打 f1 ⇒ 白方短易位非法
        Board b = Board.fromFen("4k3/8/8/8/8/8/5r2/R3K2R w KQ - 0 1");
        List<Move> moves = MoveGen.legalMoves(b);
        assertThat(moves.stream().noneMatch(m -> m.castle() && m.to() == Board.parseSquare("g1")))
                .as("f1 被攻击，短易位必须被拒").isTrue();
    }

    @Test
    @DisplayName("吃过路兵：被吃的兵要消失，且目标格是被吃兵身后那格")
    void enPassantCapturesPawn() {
        Board b = Board.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2");
        Move ep = MoveGen.legalMoves(b).stream()
                .filter(Move::enPassant)
                .findFirst().orElseThrow();
        assertThat(ep.uci()).isEqualTo("e5d6");

        Board after = MoveGen.apply(b, ep);
        assertThat(after.pieceAt(Board.parseSquare("d6")) & Board.TYPE_MASK)
                .as("白兵落到 d6").isEqualTo(Board.PAWN);
        assertThat(after.pieceAt(Board.parseSquare("d5")))
                .as("被吃的黑兵要从 d5 消失").isEqualTo(Board.EMPTY);
    }

    @Test
    @DisplayName("被将军时只能走解将的着法（不能随便动别的子）")
    void mustResolveCheck() {
        // 黑王被白车将军，黑方只有一个王能挡/躲
        Board b = Board.fromFen("4k3/8/8/8/8/8/8/4R1K1 b - - 0 1");
        List<Move> moves = MoveGen.legalMoves(b);
        assertThat(MoveGen.inCheck(b)).isTrue();
        assertThat(moves).isNotEmpty();
        for (Move m : moves) {
            Board after = MoveGen.apply(b, m);
            assertThat(MoveGen.kingAttacked(after, Board.BLACK))
                    .as("走 %s 之后还在被将", m.uci()).isFalse();
        }
    }
}
