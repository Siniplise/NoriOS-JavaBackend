package net.siniplise.noriosbackend.game.chess;

/**
 * 棋盘状态 + FEN 解析/输出。
 *
 * <p><b>为什么自研而不是引库</b>：需要的能力很窄——「fen → 合法走法 → SAN」，
 * 而市面上的 Java 棋库要么坐标不在国内镜像（`chesslib` 只在 JitPack，pom 有 jar 无），
 * 要么带一整套引擎/UI。自己写反而边界清晰，而且**可以用 perft 证明正确**
 * （见 {@code MoveGenTest}），并不比"引个库祈祷它没 bug"更冒险。
 *
 * <p><b>为什么用 0x88 表示</b>：棋盘是 128 长的一维数组，行列各占 4 位。
 * 判越界只要一次 {@code (sq & 0x88) != 0}，滑子（车/象/后）的循环因此能写成
 * "走一步、查一次越界"的干净形式，不用为每种方向单独算边界。
 * 代价是浪费一半数组——128 个 int，可以忽略。
 *
 * <p><b>坐标约定</b>：{@code rank 0 = 第一横线}（白方底线），{@code file 0 = a 列}，
 * 所以 {@code a1 = 0x00}、{@code h8 = 0x77}。FEN 的第一个字段是第 8 横线，解析时对应 {@code rank 7}。
 *
 * <p>本类**可变**（走子会改数组）：每次生成走法都拷贝一份局面，比不可变结构省事且更快。
 */
public final class Board {

    /** 0x88 越界掩码。 */
    public static final int MASK88 = 0x88;

    public static final int EMPTY = 0;
    /** 棋子类型：低 3 位。 */
    public static final int PAWN = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK = 4;
    public static final int QUEEN = 5;
    public static final int KING = 6;

    /** 颜色：第 4 位（0=白，8=黑）。 */
    public static final int WHITE = 0;
    public static final int BLACK = 8;

    public static final int TYPE_MASK = 0x7;
    public static final int COLOR_MASK = 0x8;

    /** 易位权位标记。 */
    public static final int CASTLE_WK = 1;
    public static final int CASTLE_WQ = 2;
    public static final int CASTLE_BK = 4;
    public static final int CASTLE_BQ = 8;

    private final int[] squares = new int[128];
    private int turn = WHITE;
    private int castling = 0;
    /** 过路兵目标格（0x88）；-1 表示没有。 */
    private int epSquare = -1;
    private int halfmove;
    private int fullmove = 1;

    private Board() {
    }

    public static Board fromFen(String fen) {
        Board b = new Board();
        b.loadFen(fen);
        return b;
    }

    /** 拷贝：走法生成时每个候选走法都在副本上试走，避免污染原局面。 */
    public Board copy() {
        Board b = new Board();
        System.arraycopy(squares, 0, b.squares, 0, 128);
        b.turn = turn;
        b.castling = castling;
        b.epSquare = epSquare;
        b.halfmove = halfmove;
        b.fullmove = fullmove;
        return b;
    }

    public int pieceAt(int sq) {
        return squares[sq];
    }

    void setPiece(int sq, int piece) {
        squares[sq] = piece;
    }

    public int turn() {
        return turn;
    }

    void setTurn(int color) {
        this.turn = color;
    }

    public int castling() {
        return castling;
    }

    void setCastling(int rights) {
        this.castling = rights;
    }

    public int epSquare() {
        return epSquare;
    }

    void setEpSquare(int sq) {
        this.epSquare = sq;
    }

    public int halfmove() {
        return halfmove;
    }

    public int fullmove() {
        return fullmove;
    }

    static int typeOf(int piece) {
        return piece & TYPE_MASK;
    }

    static int colorOf(int piece) {
        return piece & COLOR_MASK;
    }

    static int make(int color, int type) {
        return color | type;
    }

    // ── FEN ────────────────────────────────────────────────────────────

    /**
     * 解析 FEN。
     *
     * <p>只做**结构**校验（字段数、行数）；不校验局面合法性（例如"两个白王"）——
     * 上游 exe 给的 fen 应当合法，而我们多写一层校验只会在协议演进时把自己拒之门外。
     */
    public void loadFen(String fen) {
        if (fen == null || fen.isBlank()) {
            throw new IllegalArgumentException("FEN 为空");
        }
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("FEN 字段不足（至少要 4 段）: " + fen);
        }
        java.util.Arrays.fill(squares, EMPTY);

        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("FEN 棋盘必须 8 行，实际 " + ranks.length + ": " + fen);
        }
        // FEN 第一段是第 8 横线 ⇒ rank 7
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i;
            int file = 0;
            for (char c : ranks[i].toCharArray()) {
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                } else {
                    int piece = charToPiece(c);
                    if (piece == EMPTY) {
                        throw new IllegalArgumentException("FEN 出现非法字符 '" + c + "': " + fen);
                    }
                    if (file > 7) {
                        throw new IllegalArgumentException("FEN 第 " + (i + 1) + " 行超出 8 列: " + fen);
                    }
                    squares[rank * 16 + file] = piece;
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("FEN 第 " + (i + 1) + " 行列数不是 8: " + fen);
            }
        }

        turn = "b".equals(parts[1]) ? BLACK : WHITE;

        castling = 0;
        if (!"-".equals(parts[2])) {
            for (char c : parts[2].toCharArray()) {
                castling |= switch (c) {
                    case 'K' -> CASTLE_WK;
                    case 'Q' -> CASTLE_WQ;
                    case 'k' -> CASTLE_BK;
                    case 'q' -> CASTLE_BQ;
                    default -> 0;
                };
            }
        }

        epSquare = "-".equals(parts[3]) ? -1 : parseSquare(parts[3]);
        halfmove = parts.length > 4 ? parseIntOr(parts[4], 0) : 0;
        fullmove = parts.length > 5 ? parseIntOr(parts[5], 1) : 1;
    }

    /** 输出 FEN。与 {@link #loadFen} 严格互逆（往返测试见单测）。 */
    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = squares[rank * 16 + file];
                if (p == EMPTY) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(pieceToChar(p));
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        sb.append(' ').append(turn == WHITE ? 'w' : 'b').append(' ');
        if (castling == 0) {
            sb.append('-');
        } else {
            if ((castling & CASTLE_WK) != 0) {
                sb.append('K');
            }
            if ((castling & CASTLE_WQ) != 0) {
                sb.append('Q');
            }
            if ((castling & CASTLE_BK) != 0) {
                sb.append('k');
            }
            if ((castling & CASTLE_BQ) != 0) {
                sb.append('q');
            }
        }
        sb.append(' ').append(epSquare < 0 ? "-" : squareName(epSquare));
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    /** 走完一步后更新半回合/整回合计数（与 FEN 语义一致：兵动或吃子则清零）。 */
    void advanceClocks(boolean pawnMoveOrCapture) {
        if (pawnMoveOrCapture) {
            halfmove = 0;
        } else {
            halfmove++;
        }
        if (turn == BLACK) {
            fullmove++;
        }
    }

    // ── 坐标工具 ───────────────────────────────────────────────────────

    /** {@code e2} → 0x14（rank 1, file 4）。 */
    public static int parseSquare(String name) {
        if (name == null || name.length() < 2) {
            throw new IllegalArgumentException("非法格名: " + name);
        }
        int file = name.charAt(0) - 'a';
        int rank = name.charAt(1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) {
            throw new IllegalArgumentException("非法格名: " + name);
        }
        return rank * 16 + file;
    }

    /** 0x14 → {@code e2}。 */
    public static String squareName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 4));
    }

    private static int charToPiece(char c) {
        int color = Character.isUpperCase(c) ? WHITE : BLACK;
        return switch (Character.toLowerCase(c)) {
            case 'p' -> make(color, PAWN);
            case 'n' -> make(color, KNIGHT);
            case 'b' -> make(color, BISHOP);
            case 'r' -> make(color, ROOK);
            case 'q' -> make(color, QUEEN);
            case 'k' -> make(color, KING);
            default -> EMPTY;
        };
    }

    private static char pieceToChar(int piece) {
        char c = switch (typeOf(piece)) {
            case PAWN -> 'p';
            case KNIGHT -> 'n';
            case BISHOP -> 'b';
            case ROOK -> 'r';
            case QUEEN -> 'q';
            case KING -> 'k';
            default -> '?';
        };
        return colorOf(piece) == WHITE ? Character.toUpperCase(c) : c;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return toFen();
    }
}
