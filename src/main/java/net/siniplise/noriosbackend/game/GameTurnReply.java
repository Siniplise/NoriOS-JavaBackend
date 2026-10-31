package net.siniplise.noriosbackend.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 大脑的回复。形状以**实测响应**为准（一次真实的 `/game-turn` 调用）：
 *
 * <pre>{@code
 * {"ok":true,"game":"chess","actions":[],"clue":null,
 *  "line":"唔...棋盘好像卡住了呢。\n你那边还在吗？",
 *  "lines":["唔...棋盘好像卡住了呢。","你那边还在吗？"],
 *  "emotion":"doubtful","obeyed":true,
 *  "reason":"没有合法动作，只能等局面恢复","ms":1431}
 * }</pre>
 *
 * <p><b>{@code lines} 才是台词的真身</b>：{@code line} 只是把 {@code lines} 用 {@code \n}
 * 拼起来的整段。上屏/合成要按 {@code lines} 逐条走（每句一个 blockId），
 * 所以这里两个都给，但**以 lines 为准**。
 *
 * <p><b>{@code ok=false} 必须当失败</b>，不能当"没有动作"：JS 侧直接
 * {@code if (!j || !j.ok) throw}。照着空动作继续，会把局面卡死。
 *
 * @param obeyed  是否遵循了 {@code directive}（她可能嘴硬不听，但动作仍合法）
 * @param actions 要执行的动作 id 列表（已按 legal 校验过的那部分见调用方）
 * @param clue    森林寻宝的提示词，形如 {@code {word, count}}；其余场景为 {@code null}
 * @param lines   台词，逐句
 * @param emotion 情绪，缺失按 {@code neutral}
 * @param reason  她为什么这么做（日志/调试用，不要上屏）
 * @param ms      耗时
 */
public record GameTurnReply(
        boolean obeyed,
        List<String> actions,
        JsonNode clue,
        List<String> lines,
        String emotion,
        String reason,
        long ms) {

    /** 从原始 JSON 解析；{@code ok} 为假时返回 {@code null}（调用方据此判失败）。 */
    public static GameTurnReply parse(JsonNode node, long fallbackMs) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode ok = node.get("ok");
        if (ok == null || !ok.isBoolean() || !ok.asBoolean()) {
            return null;
        }

        List<String> actions = strings(node.get("actions"));
        List<String> lines = strings(node.get("lines"));
        // 老/简版只给 line：拆成 lines，保证下游只用一种形态
        if (lines.isEmpty()) {
            String line = text(node.get("line"));
            if (line != null) {
                for (String part : line.split("\n")) {
                    String t = part.strip();
                    if (!t.isEmpty()) {
                        lines.add(t);
                    }
                }
            }
        }

        JsonNode clue = node.get("clue");
        JsonNode ms = node.get("ms");
        boolean obeyed = node.has("obeyed") && node.get("obeyed").isBoolean() && node.get("obeyed").asBoolean();
        String emotion = text(node.get("emotion"));
        return new GameTurnReply(
                obeyed,
                List.copyOf(actions),
                (clue == null || clue.isNull()) ? null : clue,
                List.copyOf(lines),
                (emotion == null || emotion.isBlank()) ? "neutral" : emotion,
                text(node.get("reason")),
                (ms != null && ms.isNumber()) ? ms.asLong() : fallbackMs);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isString() && !n.asString().isBlank()) {
                    out.add(n.asString());
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return (n == null || n.isNull()) ? null : n.asString();
    }

    public int actionCount() {
        return actions.size();
    }

    /** 有没有话要说。 */
    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** 提示词，{@code clueMode} 时才有。 */
    public Optional<JsonNode> clueNode() {
        return Optional.ofNullable(clue);
    }
}
