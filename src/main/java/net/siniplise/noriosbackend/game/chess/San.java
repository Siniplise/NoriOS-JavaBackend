package net.siniplise.noriosbackend.game.chess;

import java.util.List;

/**
 * SAN（标准代数记谱）+ 中文坐标说明。
 *
 * <p><b>为什么需要它</b>：`nori_game.js` 给大脑的每条合法走法是
 * {@code {id:"e2e4", desc:"e4 (e2→e4)"}} —— 其中 {@code e4} 就是 SAN。
 * 大脑是语言模型，读 {@code e4} 比读 {@code e2e4} 更接近棋谱直觉；
 * 而 {@code id} 是给程序用的。两个都得给。
 *
 * <p><b>SAN 里最容易写错的是"消歧义"</b>：两个同类型棋子都能到同一格时，
 * 必须补起点信息，否则记谱有歧义。规则是**先试列、再试行、都不行给完整格名**。
 */
public final class San {

    private San() {
    }

    /** 生成一步的 SAN（含 + / # 后缀）。 */
    public static String of(Board board, Move move) {
        int piece = board.pieceAt(move.from());
        int type = Board.typeOf(piece);

        if (move.castle()) {
            // 易位用 O-O / O-O-O，即使将军也不加消歧义
            String base = (move.to() & 7) == 6 ? "O-O" : "O-O-O";
            return base + suffix(board, move);
        }

        boolean capture = board.pieceAt(move.to()) != Board.EMPTY || move.enPassant();
        String target = Board.squareName(move.to());
        StringBuilder sb = new StringBuilder();

        if (type == Board.PAWN) {
            if (capture) {
                // 兵吃子必须写起点列：exd5
                sb.append((char) ('a' + (move.from() & 7))).append('x');
            }
            sb.append(target);
            if (move.promotion() != 0) {
                sb.append('=').append(letter(move.promotion()));
            }
        } else {
            sb.append(letter(type));
            sb.append(disambiguation(board, move, type, piece));
            if (capture) {
                sb.append('x');
            }
            sb.append(target);
        }
        return sb.append(suffix(board, move)).toString();
    }

    /**
     * 消歧义：找出"同样能到这个目的格"的其它同类棋子。
     *
     * <p>先比列（file）：只有一个能到 ⇒ 用列；多个共用一列 ⇒ 再比行；
     * 行列都一样（理论上不会发生）⇒ 给完整格名。
     */
    private static String disambiguation(Board board, Move move, int type, int piece) {
        int color = Board.colorOf(piece);
        List<Move> all = MoveGen.legalMoves(board);
        boolean ambiguous = false;
        boolean sameFile = false;
        boolean sameRank = false;
        for (Move other : all) {
            if (other.from() == move.from() || other.to() != move.to()) {
                continue;
            }
            int p = board.pieceAt(other.from());
            if (Board.colorOf(p) != color || Board.typeOf(p) != type) {
                continue;
            }
            ambiguous = true;
            if ((other.from() & 7) == (move.from() & 7)) {
                sameFile = true;
            }
            if ((other.from() >> 4) == (move.from() >> 4)) {
                sameRank = true;
            }
        }
        if (!ambiguous) {
            return "";
        }
        if (!sameFile) {
            return String.valueOf((char) ('a' + (move.from() & 7)));
        }
        if (!sameRank) {
            return String.valueOf((char) ('1' + (move.from() >> 4)));
        }
        return Board.squareName(move.from());
    }

    /** 走完是不是将军 / 将死。 */
    private static String suffix(Board board, Move move) {
        Board after = MoveGen.apply(board, move);
        if (!MoveGen.kingAttacked(after, after.turn())) {
            return "";
        }
        return MoveGen.legalMoves(after).isEmpty() ? "#" : "+";
    }

    private static char letter(int type) {
        return switch (type) {
            case Board.KNIGHT -> 'N';
            case Board.BISHOP -> 'B';
            case Board.ROOK -> 'R';
            case Board.QUEEN -> 'Q';
            case Board.KING -> 'K';
            default -> '?';
        };
    }
}
