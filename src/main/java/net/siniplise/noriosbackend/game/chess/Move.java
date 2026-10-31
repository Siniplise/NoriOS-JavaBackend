package net.siniplise.noriosbackend.game.chess;

/**
 * 一步走法。
 *
 * <p><b>为什么 {@code promotion} 用 0 表示"不升变"而不是 {@code Optional}</b>：
 * 走法在 perft 里会被生成几百万次，装箱的代价直接体现在测试耗时上；
 * 而 0 在棋子编码里天然是"空"，语义清晰不会混淆。
 *
 * <p><b>{@link #uci()} 必须与 JS 侧一致</b>：`nori_game.js` 给大脑的合法走法 id 是
 * {@code m.from + m.to + (m.promotion || '')}（例如 {@code e2e4}、{@code e7e8q}）。
 * 我们回填 {@code actions} 时用的就是这个串，所以两边必须逐字符相同，否则大脑选的 id 匹配不上。
 *
 * @param from      起点（0x88）
 * @param to        终点（0x88）
 * @param promotion 升变类型（{@link Board#KNIGHT} 等）；0 表示不升变
 * @param castle    是否王车易位（{@code to} 记的是王的落点，不是车的）
 * @param enPassant 是否吃过路兵（{@code to} 记的是被吃兵**身后**的格）
 * @param doublePush 兵是否一次走两格（用于设置过路兵目标格）
 */
public record Move(int from, int to, int promotion, boolean castle, boolean enPassant, boolean doublePush) {

    /** 普通走法。 */
    public static Move of(int from, int to) {
        return new Move(from, to, 0, false, false, false);
    }

    /** UCI 形式：{@code e2e4} / {@code e7e8q}。与 JS 侧的 legal id 同构。 */
    public String uci() {
        String s = Board.squareName(from) + Board.squareName(to);
        if (promotion != 0) {
            s += switch (promotion) {
                case Board.KNIGHT -> "n";
                case Board.BISHOP -> "b";
                case Board.ROOK -> "r";
                case Board.QUEEN -> "q";
                default -> "";
            };
        }
        return s;
    }

    /** 便于日志。 */
    @Override
    public String toString() {
        return uci();
    }
}
