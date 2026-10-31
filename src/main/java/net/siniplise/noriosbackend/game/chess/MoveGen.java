package net.siniplise.noriosbackend.game.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * 走法生成 + 走法执行。公开正确的 perft 数字来验收（见 {@code MoveGenTest}）。
 *
 * <p><b>两步式</b>：先按棋子类型生成**伪合法**走法（不管走完自己的王会不会被吃），
 * 再逐个试走、把"自己王被将"的剔除。这样做比"边生成边判将"简单得多，
 * 代价是每个走法要多一次拷贝 + 一次 {@link #isAttacked}。
 * perft(4) 在 20 万量级，这个代价完全可接受——**正确性优先**。
 *
 * <p>{@link #isAttacked} 用**反向**判断：从目标格出发，看能不能被对方某个棋子吃到。
 * 比"把对方所有走法都生成一遍"快一个数量级，也是所有正经实现的写法。
 */
public final class MoveGen {

    private MoveGen() {
    }

    // 8 个方向的偏移（0x88）
    private static final int[] KNIGHT_OFFSETS = {33, 31, 18, 14, -14, -18, -31, -33};
    private static final int[] BISHOP_DIRS = {15, 17, -15, -17};
    private static final int[] ROOK_DIRS = {1, -1, 16, -16};
    private static final int[] KING_DIRS = {1, -1, 15, 16, 17, -15, -16, -17};

    /** 全部合法走法（已剔除会让自己被将的）。 */
    public static List<Move> legalMoves(Board board) {
        int mover = board.turn();   // 在 apply 之前取：apply 会切换 turn
        List<Move> out = new ArrayList<>(64);
        for (Move m : pseudoLegalMoves(board)) {
            Board next = apply(board, m);
            // 走完不能让自己的王在被打的位置
            if (!kingAttacked(next, mover)) {
                out.add(m);
            }
        }
        return out;
    }

    /** 伪合法走法：只管走法形状与吃子规则，不管将。 */
    public static List<Move> pseudoLegalMoves(Board b) {
        List<Move> out = new ArrayList<>(64);
        int us = b.turn();
        int them = us == Board.WHITE ? Board.BLACK : Board.WHITE;
        for (int sq = 0; sq < 128; sq++) {
            if ((sq & Board.MASK88) != 0) {
                continue;
            }
            int piece = b.pieceAt(sq);
            if (piece == Board.EMPTY || Board.colorOf(piece) != us) {
                continue;
            }
            switch (Board.typeOf(piece)) {
                case Board.PAWN -> genPawn(b, sq, us, them, out);
                case Board.KNIGHT -> genStep(b, sq, us, KNIGHT_OFFSETS, out);
                case Board.BISHOP -> genSlide(b, sq, us, BISHOP_DIRS, out);
                case Board.ROOK -> genSlide(b, sq, us, ROOK_DIRS, out);
                case Board.QUEEN -> {
                    genSlide(b, sq, us, BISHOP_DIRS, out);
                    genSlide(b, sq, us, ROOK_DIRS, out);
                }
                case Board.KING -> {
                    genStep(b, sq, us, KING_DIRS, out);
                    genCastles(b, sq, us, out);
                }
                default -> {
                    // 不会发生
                }
            }
            // 让编译器/读者都清楚：跳转马与王走完后继续下一个格子
        }
        return out;
    }

    // ── 各类棋子 ───────────────────────────────────────────────────────

    private static void genPawn(Board b, int sq, int us, int them, List<Move> out) {
        int dir = us == Board.WHITE ? 16 : -16;
        int startRank = us == Board.WHITE ? 1 : 6;
        int promoRank = us == Board.WHITE ? 7 : 0;

        int one = sq + dir;
        if (onBoard(one) && b.pieceAt(one) == Board.EMPTY) {
            addPawnMove(out, sq, one, promoRank);
            int two = sq + dir * 2;
            if ((sq >> 4) == startRank && onBoard(two) && b.pieceAt(two) == Board.EMPTY) {
                out.add(new Move(sq, two, 0, false, false, true));
            }
        }
        // 吃子（含过路兵）
        for (int d : new int[] {dir - 1, dir + 1}) {
            int to = sq + d;
            if (!onBoard(to)) {
                continue;
            }
            int target = b.pieceAt(to);
            if (target != Board.EMPTY && Board.colorOf(target) == them) {
                addPawnMove(out, sq, to, promoRank);
            } else if (to == b.epSquare()) {
                out.add(new Move(sq, to, 0, false, true, false));
            }
        }
    }

    private static void addPawnMove(List<Move> out, int from, int to, int promoRank) {
        if ((to >> 4) == promoRank) {
            // 升变：四种都生成（数量会影响 perft，不能只给后）
            for (int t : new int[] {Board.QUEEN, Board.ROOK, Board.BISHOP, Board.KNIGHT}) {
                out.add(new Move(from, to, t, false, false, false));
            }
        } else {
            out.add(Move.of(from, to));
        }
    }

    private static void genStep(Board b, int sq, int us, int[] offsets, List<Move> out) {
        for (int off : offsets) {
            int to = sq + off;
            if (!onBoard(to)) {
                continue;
            }
            int target = b.pieceAt(to);
            if (target == Board.EMPTY || Board.colorOf(target) != us) {
                out.add(Move.of(sq, to));
            }
        }
    }

    private static void genSlide(Board b, int sq, int us, int[] dirs, List<Move> out) {
        for (int dir : dirs) {
            int to = sq + dir;
            while (onBoard(to)) {
                int target = b.pieceAt(to);
                if (target == Board.EMPTY) {
                    out.add(Move.of(sq, to));
                } else {
                    if (Board.colorOf(target) != us) {
                        out.add(Move.of(sq, to));
                    }
                    break; // 撞到任何子就停
                }
                to += dir;
            }
        }
    }

    /**
     * 王车易位。
     *
     * <p>三个条件缺一不可：① 有易位权；② 王与车之间全空；
     * ③ 王**当前、经过、落点**三格都不被攻击（第二条最容易被漏掉——
     * "王路过被将的格子"也是非法的）。
     */
    private static void genCastles(Board b, int sq, int us, List<Move> out) {
        if (us == Board.WHITE) {
            if (sq != Board.parseSquare("e1")) {
                return;
            }
            if ((b.castling() & Board.CASTLE_WK) != 0
                    && b.pieceAt(Board.parseSquare("f1")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("g1")) == Board.EMPTY
                    && !isAttacked(b, Board.parseSquare("e1"), Board.BLACK)
                    && !isAttacked(b, Board.parseSquare("f1"), Board.BLACK)
                    && !isAttacked(b, Board.parseSquare("g1"), Board.BLACK)) {
                out.add(new Move(sq, Board.parseSquare("g1"), 0, true, false, false));
            }
            if ((b.castling() & Board.CASTLE_WQ) != 0
                    && b.pieceAt(Board.parseSquare("d1")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("c1")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("b1")) == Board.EMPTY
                    && !isAttacked(b, Board.parseSquare("e1"), Board.BLACK)
                    && !isAttacked(b, Board.parseSquare("d1"), Board.BLACK)
                    && !isAttacked(b, Board.parseSquare("c1"), Board.BLACK)) {
                out.add(new Move(sq, Board.parseSquare("c1"), 0, true, false, false));
            }
        } else {
            if (sq != Board.parseSquare("e8")) {
                return;
            }
            if ((b.castling() & Board.CASTLE_BK) != 0
                    && b.pieceAt(Board.parseSquare("f8")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("g8")) == Board.EMPTY
                    && !isAttacked(b, Board.parseSquare("e8"), Board.WHITE)
                    && !isAttacked(b, Board.parseSquare("f8"), Board.WHITE)
                    && !isAttacked(b, Board.parseSquare("g8"), Board.WHITE)) {
                out.add(new Move(sq, Board.parseSquare("g8"), 0, true, false, false));
            }
            if ((b.castling() & Board.CASTLE_BQ) != 0
                    && b.pieceAt(Board.parseSquare("d8")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("c8")) == Board.EMPTY
                    && b.pieceAt(Board.parseSquare("b8")) == Board.EMPTY
                    && !isAttacked(b, Board.parseSquare("e8"), Board.WHITE)
                    && !isAttacked(b, Board.parseSquare("d8"), Board.WHITE)
                    && !isAttacked(b, Board.parseSquare("c8"), Board.WHITE)) {
                out.add(new Move(sq, Board.parseSquare("c8"), 0, true, false, false));
            }
        }
    }

    // ── 执行 ───────────────────────────────────────────────────────────

    /**
     * 在副本上走一步，返回新局面。
     *
     * <p>负责的事：移子、吃子（含过路兵的被吃兵）、升变、易位的车、更新易位权、
     * 设置/清除过路兵目标格、更新回合与计数。
     *
     * <p><b>易位权要按"动的子/被吃的子"逐个撤</b>：王动就撤双方权利、
     * 车动就撤那一侧、车被吃也要撤——只判"王动"会在车被吃时留下一个永远无法行使的权利，
     * 后续 perft 会因此多算。
     */
    public static Board apply(Board src, Move m) {
        Board b = src.copy();
        int piece = b.pieceAt(m.from());
        int color = Board.colorOf(piece);
        boolean capture = b.pieceAt(m.to()) != Board.EMPTY || m.enPassant();

        b.setPiece(m.from(), Board.EMPTY);
        // 升变：落到终点的子换成升变类型
        b.setPiece(m.to(), m.promotion() != 0 ? Board.make(color, m.promotion()) : piece);

        if (m.enPassant()) {
            // 被吃的兵在"终点身后一格"（相对走子方）
            int capturedSq = m.to() + (color == Board.WHITE ? -16 : 16);
            b.setPiece(capturedSq, Board.EMPTY);
        }

        if (m.castle()) {
            // 王已就位，把车也挪过去
            boolean kingSide = (m.to() & 7) == 6; // g 列
            int rookFrom = kingSide ? m.to() + 1 : m.to() - 2;
            int rookTo = kingSide ? m.to() - 1 : m.to() + 1;
            int rook = b.pieceAt(rookFrom);
            b.setPiece(rookFrom, Board.EMPTY);
            b.setPiece(rookTo, rook);
        }

        // 易位权
        int rights = b.castling();
        int type = Board.typeOf(piece);
        if (type == Board.KING) {
            rights &= color == Board.WHITE
                    ? ~(Board.CASTLE_WK | Board.CASTLE_WQ)
                    : ~(Board.CASTLE_BK | Board.CASTLE_BQ);
        }
        // 车离开原位
        rights = dropRookRight(rights, m.from());
        // 车被吃
        rights = dropRookRight(rights, m.to());
        b.setCastling(rights);

        // 过路兵目标格：只有兵的"双推"才设
        b.setEpSquare(m.doublePush() ? (m.from() + m.to()) / 2 : -1);

        b.advanceClocks(type == Board.PAWN || capture);
        b.setTurn(color == Board.WHITE ? Board.BLACK : Board.WHITE);
        return b;
    }

    private static int dropRookRight(int rights, int sq) {
        return switch (sq) {
            case 0x00 -> rights & ~Board.CASTLE_WQ;      // a1
            case 0x07 -> rights & ~Board.CASTLE_WK;      // h1
            case 0x70 -> rights & ~Board.CASTLE_BQ;      // a8
            case 0x77 -> rights & ~Board.CASTLE_BK;      // h8
            default -> rights;
        };
    }

    // ── 攻击判定 ───────────────────────────────────────────────────────

    /** {@code by} 方是否攻击 {@code sq}。 */
    public static boolean isAttacked(Board b, int sq, int by) {
        // 兵：白兵向上吃，所以要往下找"白兵在 sq 的下方两格"
        int pawnDir = by == Board.WHITE ? -16 : 16;
        for (int d : new int[] {pawnDir - 1, pawnDir + 1}) {
            int from = sq + d;
            if (onBoard(from)) {
                int p = b.pieceAt(from);
                if (p != Board.EMPTY && Board.colorOf(p) == by && Board.typeOf(p) == Board.PAWN) {
                    return true;
                }
            }
        }
        // 马
        for (int off : KNIGHT_OFFSETS) {
            int from = sq + off;
            if (onBoard(from)) {
                int p = b.pieceAt(from);
                if (p != Board.EMPTY && Board.colorOf(p) == by && Board.typeOf(p) == Board.KNIGHT) {
                    return true;
                }
            }
        }
        // 王
        for (int off : KING_DIRS) {
            int from = sq + off;
            if (onBoard(from)) {
                int p = b.pieceAt(from);
                if (p != Board.EMPTY && Board.colorOf(p) == by && Board.typeOf(p) == Board.KING) {
                    return true;
                }
            }
        }
        // 象/后（斜线）
        for (int dir : BISHOP_DIRS) {
            int from = sq + dir;
            while (onBoard(from)) {
                int p = b.pieceAt(from);
                if (p != Board.EMPTY) {
                    if (Board.colorOf(p) == by) {
                        int t = Board.typeOf(p);
                        if (t == Board.BISHOP || t == Board.QUEEN) {
                            return true;
                        }
                    }
                    break;
                }
                from += dir;
            }
        }
        // 车/后（直线）
        for (int dir : ROOK_DIRS) {
            int from = sq + dir;
            while (onBoard(from)) {
                int p = b.pieceAt(from);
                if (p != Board.EMPTY) {
                    if (Board.colorOf(p) == by) {
                        int t = Board.typeOf(p);
                        if (t == Board.ROOK || t == Board.QUEEN) {
                            return true;
                        }
                    }
                    break;
                }
                from += dir;
            }
        }
        return false;
    }

    /** 某方的王是否正在被打。找不到王时返回 true（局面异常，宁可当成被将）。 */
    public static boolean kingAttacked(Board b, int color) {
        for (int sq = 0; sq < 128; sq++) {
            if ((sq & Board.MASK88) != 0) {
                continue;
            }
            int p = b.pieceAt(sq);
            if (p != Board.EMPTY && Board.colorOf(p) == color && Board.typeOf(p) == Board.KING) {
                return isAttacked(b, sq, color == Board.WHITE ? Board.BLACK : Board.WHITE);
            }
        }
        return true;
    }

    /** 是否被将军（轮到走子的一方）。 */
    public static boolean inCheck(Board b) {
        return kingAttacked(b, b.turn());
    }

    private static boolean onBoard(int sq) {
        return (sq & Board.MASK88) == 0;
    }

    /** perft：走法生成的标准验收（叶子节点数）。 */
    public static long perft(Board b, int depth) {
        if (depth == 0) {
            return 1;
        }
        List<Move> moves = legalMoves(b);
        if (depth == 1) {
            return moves.size();
        }
        long n = 0;
        for (Move m : moves) {
            n += perft(apply(b, m), depth - 1);
        }
        return n;
    }
}
