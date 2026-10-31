package net.siniplise.noriosbackend.game.codenames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import tools.jackson.databind.JsonNode;

/**
 * 森林寻宝（codenames）的「猜牌」侧：局面 → 给大脑的请求。
 *
 * <p>对齐 `nori_game.js#buildCodenames` 的猜牌分支。
 *
 * <p><b>⚠️ 本类尚未用真实帧校准</b>：golden 录制里没开过这个卡带，字段名是从 JS 源码
 * （{@code g.board} / {@code g.cells}）推出来的。所以解析一律**宽容**——
 * 缺字段就退化成空列表，绝不抛异常打断帧流。等你开一局寻宝时抓一帧，再回来核对。
 *
 * <p><b>两套动作的语义差别很大</b>：
 * <ul>
 *   <li>{@code cell:N} —— 翻第 N 格；翻到「怪兽」是**整局立刻输**（不是只结束回合），
 *       所以 observation 里花了整段篇幅劝她"宁可少猜"；</li>
 *   <li>{@code end} —— 收手，把回合交回玩家；</li>
 *   <li>{@code clue} —— 顺带给他出个提示词（写在 {@code clue} 字段里，不是动作里）。</li>
 * </ul>
 */
public final class CodenamesPosition {

    /** 一格的状态。 */
    public record Cell(int index, String text, boolean solved, boolean previouslyWrong) {
    }

    /** 提示词。 */
    public record Clue(String word, int count) {
        public boolean valid() {
            return word != null && !word.isBlank() && count >= 1;
        }

        /** 给 observation 读的形式，与 JS 的 {@code word（count 个）} 一致。 */
        public String display() {
            return valid() ? word + "（" + count + " 个）" : null;
        }
    }

    private static final int MAX_ACTIONS = 9;

    private final List<Cell> cells;

    private CodenamesPosition(List<Cell> cells) {
        this.cells = cells;
    }

    /**
     * 从 {@code state.gameState}（或 {@code state.game}）解析。
     *
     * <p>宽容策略：不是对象、缺 {@code board}、两者长度不一致 —— 都退化成"没有可翻的格子"，
     * 由调用方判断（{@link #hasOpenCells()}）。宁可这一回合不动，也不能因为一条脏帧抛异常。
     */
    public static CodenamesPosition from(JsonNode gameState) {
        if (gameState == null || !gameState.isObject()) {
            return new CodenamesPosition(List.of());
        }
        JsonNode board = gameState.get("board");
        JsonNode cells = gameState.get("cells");
        if (board == null || !board.isArray()) {
            return new CodenamesPosition(List.of());
        }
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            JsonNode wordNode = board.get(i);
            String text = null;
            if (wordNode != null && wordNode.isObject()) {
                // ★ 只取**标量**字段：text / id 若是对象（多语言表之类）就不能 asString，
                //   那会抛 JsonNodeException 并打断整条帧处理（象棋已经因为同类问题翻过车）。
                text = scalar(wordNode.get("text"));
                if (text == null) {
                    text = scalar(wordNode.get("id"));
                }
            } else {
                text = scalar(wordNode);
            }

            JsonNode cellNode = (cells != null && cells.isArray() && i < cells.size()) ? cells.get(i) : null;
            boolean solved = false;
            boolean wrong = false;
            if (cellNode != null && cellNode.isObject()) {
                JsonNode sb = cellNode.get("solvedBy");
                // solvedBy 可能是 "agent"/"player"/数字下标；空串/空白也算"未解开"
                solved = isMeaningful(sb);
                JsonNode marks = cellNode.get("bystanderMarks");
                if (marks != null && marks.isArray()) {
                    // JS 用 `some(x => x)`：只要有一个槽被填过，就说明"上次翻错过"
                    for (JsonNode m : marks) {
                        if (isMeaningful(m)) {
                            wrong = true;
                            break;
                        }
                    }
                }
            }
            out.add(new Cell(i, text == null ? "第" + i + "格" : text, solved, wrong));
        }
        return new CodenamesPosition(out);
    }

    /** 只接受标量（字符串/数字/布尔）并转成字符串；对象/数组一律返回 null，绝不 asString。 */
    private static String scalar(JsonNode n) {
        if (n == null || n.isNull() || n.isArray() || n.isObject()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? "true" : null;
        }
        return n.asString();
    }

    /** 这个节点是否代表"有值"（用于 solvedBy / bystanderMarks：空、null、空白、false 都算没值）。 */
    private static boolean isMeaningful(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return true;
        }
        if (n.isString()) {
            String v = n.asString();
            return !v.isBlank();
        }
        // 对象/数组：视为有值（保守：宁可多标一次"翻错过"，也不要漏）
        return true;
    }

    /** 还没翻开的格子。 */
    public List<Cell> openCells() {
        return cells.stream().filter(c -> !c.solved()).toList();
    }

    public boolean hasOpenCells() {
        return !openCells().isEmpty();
    }

    public List<Cell> allCells() {
        return cells;
    }

    /**
     * 合法动作：每个未翻开的格子 + {@code end} + {@code clue}。
     *
     * <p>顺序与 JS 一致（格子在前，end/clue 在后）——语言模型对列表末位的两项通常更敏感，
     * 而这两项恰好是"收手/给词"，把它们放最后是有意的。
     */
    public List<Map<String, Object>> legalOptions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Cell c : openCells()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "cell:" + c.index());
            item.put("desc", c.text());
            out.add(item);
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "end");
        end.put("desc", "这一回合到此为止（不再猜了）");
        out.add(end);

        Map<String, Object> clue = new LinkedHashMap<>();
        clue.put("id", "clue");
        clue.put("desc", "由你给他出一个提示词（写在你自己的 clue 字段里）");
        out.add(clue);
        return out;
    }

    /**
     * 拼给大脑的局面描述。
     *
     * <p><b>为什么把「宁可少猜」讲这么长</b>：翻到怪兽是**整局输**，而模型的默认倾向是
     * "多试几个提高命中率"。这段提示是行为约束，不是装饰——删了它她的猜牌策略会明显变激进。
     *
     * @param clue 他给的提示词；没有（本回合还没给过）时传 {@code null}
     */
    public String observation(Clue clue) {
        List<Cell> open = openCells();
        String clueText = (clue != null && clue.display() != null) ? clue.display() : "（没有提示词）";

        StringBuilder sb = new StringBuilder();
        sb.append("森林寻宝：现在是你要猜牌。他给你的提示词是「").append(clueText)
                .append("」，照这个提示在下面的词里挑。\n");
        sb.append("还没被翻开的词（").append(open.size()).append(" 个）：");
        sb.append(String.join("、", open.stream().map(Cell::text).toList())).append("。\n");

        List<String> marks = cells.stream()
                .filter(c -> !c.solved() && c.previouslyWrong())
                .map(c -> c.text() + "（上次翻错了）")
                .toList();
        if (!marks.isEmpty()) {
            sb.append("提醒：").append(String.join("、", marks)).append("。\n");
        }

        sb.append("【猜牌的取舍 —— 很重要】\n");
        sb.append("· 提示词后面的数字（count）是它**指向几个词**：count=1 就只猜那一个最贴的，猜中后**收手**。\n");
        sb.append("· **宁可少猜一张，也不要多翻** —— 翻到「怪兽」是**你们团队立刻整局输**（不是只结束回合）。\n");
        sb.append("  所以「反正顺手翻翻看」这种没把握的补猜，风险远大于收益。\n");
        sb.append("· count≥2 时，只有你**确实想到了第二个同族的词**才继续；只是想碰碰运气，就选 end 收手。\n");
        sb.append("· 你和他是**队友**：猜中的每一张都是**你们团队**的宝箱（全 15 个找齐才算赢）——\n");
        sb.append("  不存在「给谁送分」这回事，你的目标就是**尽量猜中**。翻到中性牌只是结束回合（不严重）。\n");
        sb.append("翻对了继续；翻错了这一回合就结束；翻到「怪兽」直接输。猜够了就先收手（选 end），然后由你给他出一个提示词。\n");
        sb.append("如果你也要给他一个提示词（clue）：**提示词不能是棋盘上任何一个词**、不能含空格/数字，1~6 个字，靠联想指向你想让他猜的词。\n");
        sb.append("例如 {\"word\":\"冷的东西\",\"count\":2}。");
        return sb.toString();
    }

    /** 产出一次「猜牌」请求。 */
    public GameTurnRequest toGuessRequest(Clue clue, String directive) {
        return new GameTurnRequest(
                "codenames",
                observation(clue),
                directive == null ? "" : directive,
                legalOptions(),
                MAX_ACTIONS,   // 一次可以猜多张（她给词后能连猜）
                false,         // 猜牌不是给词模式
                null);
    }

    /**
     * 把大脑返回的动作 id 翻译成上游要的指令序列。
     *
     * <p><b>顺序有讲究，这是 JS 里踩过的 bug</b>：
     * ① 先所有 {@code submitGuess}（翻牌），② 再 {@code endTurn} / {@code submitClue}。
     * 而且**她自己选了 end 就必须保留**——早期版本只拼猜牌、把大脑给的 end 丢了，
     * 结果该收手时没收手。
     *
     * <p>③ 如果她猜了牌却没说要收手，**自动补一个 endTurn**：本地对手会在"仍是她的回合"时
     * 替她连猜（而且它读得到答案），尽早交回回合是掐断那条链的唯一办法。
     *
     * @param clue 她给的提示词；{@code null} 表示这次不给她出词（跳过该动作，不报错）
     */
    public List<Map<String, Object>> toCommands(List<String> actionIds, Clue clue) {
        List<Map<String, Object>> guesses = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        boolean endChosen = false;

        if (actionIds != null) {
            for (String raw : actionIds) {
                if (raw == null) {
                    continue;
                }
                String id = raw.strip();
                if (id.startsWith("cell:")) {
                    Integer idx = parseCellIndex(id);
                    if (idx == null || idx < 0 || idx >= cells.size() || cells.get(idx).solved()) {
                        continue; // 非法或已翻开的格子，丢掉
                    }
                    Map<String, Object> cmd = new LinkedHashMap<>();
                    cmd.put("type", "submitGuess");
                    cmd.put("cell", idx);
                    guesses.add(cmd);
                } else if ("end".equals(id)) {
                    endChosen = true;
                    Map<String, Object> cmd = new LinkedHashMap<>();
                    cmd.put("type", "endTurn");
                    others.add(cmd);
                } else if ("clue".equals(id)) {
                    // 大脑选了 clue 但没给词 ⇒ 跳过（JS 侧曾用「诺莉」兜底，那正是假 AI 的占位词，
                    // 触发后玩家会以为"假 AI 又活了"）。跳过不会卡局，真正轮到给词的场合另有路径。
                    if (clue == null || !clue.valid()) {
                        continue;
                    }
                    Map<String, Object> clueObj = new LinkedHashMap<>();
                    clueObj.put("word", clue.word());
                    clueObj.put("count", clue.count());
                    Map<String, Object> cmd = new LinkedHashMap<>();
                    cmd.put("type", "submitClue");
                    cmd.put("clue", clueObj);
                    others.add(cmd);
                }
            }
        }

        List<Map<String, Object>> seq = new ArrayList<>(guesses);
        if (!guesses.isEmpty() && !endChosen) {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "endTurn");
            seq.add(end);
        }
        seq.addAll(others);
        return seq;
    }

    private static Integer parseCellIndex(String id) {
        try {
            return Integer.parseInt(id.substring("cell:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
