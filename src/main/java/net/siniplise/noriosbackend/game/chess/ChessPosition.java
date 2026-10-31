package net.siniplise.noriosbackend.game.chess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.siniplise.noriosbackend.game.GameTurnRequest;

/**
 * 象棋这一侧的门面：**fen 进，给大脑的请求出**。
 *
 * <p>对齐 `nori_game.js#buildChess` 的行为，包括三个容易忽略的细节：
 * <ol>
 *   <li>observation 里写的是「你执**白方**」——指的是**轮到走子的一方**（她），不是玩家。
 *       玩家执黑时她就是白方，这个方向别搞反。</li>
 *   <li>合法走法**只列前 80 条**。开局 20 条无所谓，但复杂局面能到 200+，
 *       全塞进 prompt 会挤掉真正重要的局面描述。</li>
 *   <li>{@code desc} 是 {@code SAN (起点→终点)} 两段式——大脑靠 SAN 理解棋，
 *       靠 id 回填动作，缺一个都不行。</li>
 * </ol>
 *
 * <p><b>maxActions 恒为 1</b>：象棋一次只走一步，这是规则决定的，不是策略。
 */
public final class ChessPosition {

    /** 给大脑的合法走法上限（对齐 JS 的 {@code legal.slice(0, 80)}）。 */
    private static final int MAX_LEGAL_IN_PROMPT = 80;

    private final Board board;

    private ChessPosition(Board board) {
        this.board = board;
    }

    public static ChessPosition fromFen(String fen) {
        return new ChessPosition(Board.fromFen(fen));
    }

    /** 从上游 {@code gameState.fen} 构造；fen 不合法时返回空（不能因此打断帧流）。 */
    public static Optional<ChessPosition> tryFromFen(String fen) {
        if (fen == null || fen.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromFen(fen));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String fen() {
        return board.toFen();
    }

    /**
     * **轮到白方走**（就是 {@code board.turn() == WHITE}）。
     *
     * <p>刻意不叫 {@code sheIsWhite()}：那个名字会让人以为它能直接回答"她执哪方"，
     * 实际它只说"现在轮到谁"——而"她执哪方"还取决于玩家选的颜色。
     * 起错名字的代价是"方向反了"这种最难查的 bug。
     */
    public boolean turnIsWhite() {
        return board.turn() == Board.WHITE;
    }

    /** 还有没有棋可走（无合法走法 = 将死或逼和）。 */
    public boolean hasMoves() {
        return !MoveGen.legalMoves(board).isEmpty();
    }

    /**
     * 合法走法，形如 {@code [{id:"e2e4", desc:"e4 (e2→e4)"}]}。
     *
     * <p>超出 {@value #MAX_LEGAL_IN_PROMPT} 条的截断**只影响 prompt**；
     * {@link #parseId} 仍然认得全部合法走法——否则大脑偶发给出第 81 条就会被误判成非法。
     */
    public List<Map<String, Object>> legalOptions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Move m : MoveGen.legalMoves(board)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.uci());
            item.put("desc", San.of(board, m) + " (" + Board.squareName(m.from()) + "→"
                    + Board.squareName(m.to()) + ")");
            out.add(item);
        }
        return out;
    }

    /**
     * 拼给大脑的局面描述（多行）。
     *
     * @param recentMoves 最近几步的 SAN，空格分隔；没有就传 {@code null}/空串
     */
    public String observation(String recentMoves) {
        List<Map<String, Object>> legal = legalOptions();
        String her = turnIsWhite() ? "白方" : "黑方";

        StringBuilder sb = new StringBuilder();
        sb.append("国际象棋：轮到你走子，你执").append(her).append("，他执另一方。");
        sb.append("\n当前局面 FEN：").append(board.toFen());
        if (recentMoves != null && !recentMoves.isBlank()) {
            sb.append("\n最近几步：").append(recentMoves.strip());
        }
        sb.append("\n你的合法走法（只能从中挑一个，id 就是坐标拼接）：");
        int n = Math.min(legal.size(), MAX_LEGAL_IN_PROMPT);
        for (int i = 0; i < n; i++) {
            Map<String, Object> item = legal.get(i);
            sb.append("\n- ").append(item.get("id")).append("  ").append(item.get("desc"));
        }
        if (legal.size() > MAX_LEGAL_IN_PROMPT) {
            sb.append("\n（只列了前 ").append(MAX_LEGAL_IN_PROMPT).append(" 条，共 ")
                    .append(legal.size()).append(" 条）");
        }
        sb.append("\n挑一个你满意的走法；走完可以在 line 里说一句你现在怎么想的。");
        return sb.toString();
    }

    /** 直接产出一次 {@code /game-turn} 请求。 */
    public GameTurnRequest toRequest(String recentMoves, String directive) {
        return new GameTurnRequest(
                "chess",
                observation(recentMoves),
                directive == null ? "" : directive,
                legalOptions(),
                1,          // 象棋一次只走一步
                false,      // 象棋没有给提示词模式
                null);      // 象棋不带图
    }

    /**
     * 把大脑返回的 id（{@code e2e4} / {@code e7e8q}）解析成走法。
     *
     * <p>**必须用全部合法走法比对**，不能只看 prompt 里列出的那 80 条：
     * 否则大脑选到第 81 条时会被当成非法丢弃，她这一步就白想了。
     */
    public Optional<Move> parseId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String want = id.strip();
        for (Move m : MoveGen.legalMoves(board)) {
            if (m.uci().equals(want)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** 走一步，返回新局面（供下一步循环用）。 */
    public Board applied(Move move) {
        return MoveGen.apply(board, move);
    }

    /** 是否被将军。 */
    public boolean inCheck() {
        return MoveGen.inCheck(board);
    }
}
