package net.siniplise.noriosbackend.game.driver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import net.siniplise.noriosbackend.game.chess.ChessPosition;
import net.siniplise.noriosbackend.game.chess.Move;
import tools.jackson.databind.JsonNode;

/**
 * 象棋的「驱动」：判断该不该问她、问什么、以及把她的回答变成上游能吃的指令。
 *
 * <p><b>与 JS 侧的根本差别 —— 我们是"事后驱动"</b>：
 * `nori_game.js` 会**扣住**玩家的那一步（先不发上游），等算出她的应对再一起发，
 * 所以它需要 `simCakeduelAfterPlayerAction` 这类"相位推测"。
 * 而我们的代理是**纯透传**：玩家的动作早就发给上游了，等上游把新局面广播回来，
 * 我们才看到"轮到她"。
 *
 * <p>这个差别让我们**不需要推测相位**（上游已经告诉我们了），代价是她的这一步
 * 比 JS 版晚一个往返。对回合制游戏这完全可接受，而且实现简单得多、错的机会更少。
 *
 * <p><b>不扣玩家动作是刻意的</b>：扣住意味着要接管帧序与 ack，一旦出错就是
 * "玩家点了没反应"——那比"她慢半拍"严重得多。
 */
public final class ChessDriver {

    private ChessDriver() {
    }

    /**
     * 一次待执行的她的回合。
     *
     * @param request 问大脑用的请求
     * @param position 局面（用来把动作 id 解析回坐标）
     * @param fen    局面 fen（日志用）
     */
    public record Turn(GameTurnRequest request, ChessPosition position, String fen) {

        /**
         * 把大脑的回答翻成上游 {@code cmd}。
         *
         * <p>只取第一个能解析成合法走法的 id：象棋一次只走一步，
         * 而且大脑偶尔会给个格式对但盘上不合法的串，那种要丢掉继续找。
         *
         * @return {@code cmd}（形如 {@code {"type":"move","from":"e2","to":"e4"}}），无可执行动作时为空
         */
        public Optional<Map<String, Object>> toCmd(GameTurnReply reply) {
            if (reply == null || reply.actions().isEmpty()) {
                return Optional.empty();
            }
            for (String raw : reply.actions()) {
                Optional<Move> m = position.parseId(raw);
                if (m.isEmpty()) {
                    continue;
                }
                Move move = m.get();
                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("type", "move");
                cmd.put("from", net.siniplise.noriosbackend.game.chess.Board.squareName(move.from()));
                cmd.put("to", net.siniplise.noriosbackend.game.chess.Board.squareName(move.to()));
                if (move.promotion() != 0) {
                    cmd.put("promotion", promotionLetter(move.promotion()));
                }
                return Optional.of(cmd);
            }
            return Optional.empty();
        }
    }

    /**
     * 判断现在是不是她的回合；是就产出一个待问的请求。
     *
     * <p>判定条件（任一不满足就返回空，**绝不在不该动的时候动**）：
     * <ol>
     *   <li>局面里有合法 fen；</li>
     *   <li>轮到她（{@code turn} 等于她的颜色）；</li>
     *   <li>这一局还没结束（{@code status} 不是 ended / checkmate / draw / resigned）；</li>
     *   <li>她确实有棋可走（将死/逼和时不该问，问了也是白花一次推理）。</li>
     * </ol>
     *
     * @param gameState  上游 {@code gameState}
     * @param playerSide 玩家执的颜色（来自 {@code settings.playerSide}），她执另一方
     * @param directive  玩家的口头指令，可空
     */
    public static Optional<Turn> plan(JsonNode gameState, String playerSide, String directive) {
        if (gameState == null || !gameState.isObject()) {
            return Optional.empty();
        }
        String fen = text(gameState, "fen");
        if (fen == null || fen.isBlank()) {
            return Optional.empty();
        }
        if (isOver(gameState)) {
            return Optional.empty();
        }
        Optional<ChessPosition> maybe = ChessPosition.tryFromFen(fen);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        ChessPosition pos = maybe.get();

        // 她执的是玩家颜色的反面。
        // 判定一句话就够：**"轮到白方" 与否，必须和 "玩家执白" 相反** 才轮到她。
        // （方向搞反是这类代码最难查的 bug，所以直接写成一个不等式，不引入中间变量。）
        boolean playerIsWhite = playerSide == null || playerSide.isBlank()
                || "white".equalsIgnoreCase(playerSide);
        if (pos.turnIsWhite() == playerIsWhite) {
            return Optional.empty();
        }
        if (!pos.hasMoves()) {
            return Optional.empty();
        }

        String recent = text(gameState, "recentMoves");
        if (recent == null) {
            // ★ moveHistory 是**数组**（元素可能是 {san} 或纯字符串）。
            //   一定要按数组读：曾经用 text() 直读，ArrayNode.asString() 直接抛异常，
            //   把整条 observe 打断 ⇒ 现象就是"她完全不动，日志里什么都没有"。
            recent = recentFromHistory(gameState);
        }
        GameTurnRequest req = pos.toRequest(recent, directive);
        return Optional.of(new Turn(req, pos, pos.fen()));
    }

    /** 从 {@code moveHistory} 数组里取最近几步的 SAN，空格分隔；没有就返回 null。 */
    private static String recentFromHistory(JsonNode gameState) {
        JsonNode mh = gameState == null ? null : gameState.get("moveHistory");
        if (mh == null || !mh.isArray() || mh.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, mh.size() - 8);
        for (int i = from; i < mh.size(); i++) {
            JsonNode item = mh.get(i);
            String san = null;
            if (item != null && item.isObject()) {
                san = text(item, "san");
            } else if (item != null && item.isString()) {
                san = item.asString();
            }
            if (san != null && !san.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(san.strip());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 这一局是否已经结束。字段名可能是 {@code status} / {@code phase}。 */
    private static boolean isOver(JsonNode gs) {
        String status = text(gs, "status");
        if (status == null) {
            status = text(gs, "phase");
        }
        if (status == null) {
            return false;
        }
        return switch (status.toLowerCase()) {
            case "ended", "finished", "over", "checkmate", "stalemate", "draw", "resigned", "gameover" -> true;
            default -> false;
        };
    }

    private static String promotionLetter(int type) {
        return switch (type) {
            case net.siniplise.noriosbackend.game.chess.Board.KNIGHT -> "n";
            case net.siniplise.noriosbackend.game.chess.Board.BISHOP -> "b";
            case net.siniplise.noriosbackend.game.chess.Board.ROOK -> "r";
            case net.siniplise.noriosbackend.game.chess.Board.QUEEN -> "q";
            default -> null;
        };
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    /** 供测试：列出一次计划里她可选的 id（确认 legal 真的来自局面）。 */
    public static List<String> legalIds(Turn turn) {
        return List.copyOf(turn.request().legalIds());
    }
}
