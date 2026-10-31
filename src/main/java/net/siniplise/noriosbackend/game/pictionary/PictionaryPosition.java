package net.siniplise.noriosbackend.game.pictionary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.game.GameTurnRequest;
import tools.jackson.databind.JsonNode;

/**
 * 你画我猜的「猜词」侧：局面 + 画布 → 给大脑的请求。
 *
 * <p>对齐 `nori_game.js#buildPicGuess`。三个与众不同的地方：
 * <ol>
 *   <li><b>{@code clueMode=true} 但语义不同</b>：这里不是"让她给提示词"，
 *       而是**"答案写进 {@code clue.word}"**。字段共用、含义由 {@code game} 决定。</li>
 *   <li><b>带图</b>：{@code image} 是 PNG data URI（256×256）。图渲染不出来时退回字符画，
 *       两者都没有就干脆不猜（空白画面猜出来的必然是瞎猜）。</li>
 *   <li><b>答案是她的自由文本</b>，不是从合法动作里选——所以这里没有"id 列表"，
 *       只有一个 {@code {id:"guess"}} 占位。</li>
 * </ol>
 *
 * <p><b>为什么只给"答案字数"而不给拼音</b>：拼音/声母会让模型走谐音捷径（"听起来像"），
 * 而游戏要求的是**看形状**。字数不算泄露，反而帮她收缩范围。
 */
public final class PictionaryPosition {

    /** 已猜过的词最多带几个（对齐 JS 的 {@code slice(-8)}）。 */
    private static final int MAX_WRONG_IN_PROMPT = 8;

    private final boolean sheGuesses;
    private final int answerLength;
    private final List<String> wrongGuesses;
    private final String pngDataUri;
    private final String asciiArt;

    private PictionaryPosition(boolean sheGuesses, int answerLength, List<String> wrongGuesses,
                               String pngDataUri, String asciiArt) {
        this.sheGuesses = sheGuesses;
        this.answerLength = answerLength;
        this.wrongGuesses = wrongGuesses;
        this.pngDataUri = pngDataUri;
        this.asciiArt = asciiArt;
    }

    /**
     * 从 {@code gameState}（你画我猜那一份）+ 画布构造。
     *
     * @param gameState 上游局面；字段名从 JS 推（{@code roles/guesses/pinyin/strokes}），宽容解析
     * @param board     我们自己攒的笔画
     */
    public static PictionaryPosition from(JsonNode gameState, PictionaryBoard board) {
        boolean sheGuesses = false;
        int answerLength = 0;
        List<String> guesses = new ArrayList<>();

        if (gameState != null && gameState.isObject()) {
            // ★ 实测（2026-10-31）：pictionary 的 gameState 顶层只有
            //   [phase, score, round, history]，`roles` / `pinyin` 都在 **round 里面**。
            //   按 gameState.roles 读会永远拿不到"她是猜方"，表现就是"她完全不动"。
            JsonNode round = gameState.get("round");
            JsonNode roles = (round != null && round.isObject()) ? round.get("roles") : null;
            if (roles == null) {
                roles = gameState.get("roles");   // 兜底：老形状
            }
            if (roles != null && roles.isObject()) {
                JsonNode g = roles.get("guesser");
                sheGuesses = g != null && !g.isNull() && !g.isArray() && !g.isObject()
                        && "agent".equals(g.asString());
            }
            JsonNode py = (round != null && round.isObject()) ? round.get("pinyin") : null;
            if (py == null) {
                py = gameState.get("pinyin");
            }
            if (py != null && py.isArray()) {
                answerLength = py.size();
            }
            JsonNode gs = gameState.get("guesses");
            if (gs == null && round != null && round.isObject()) {
                gs = round.get("guesses");
            }
            if (gs != null && gs.isArray()) {
                for (JsonNode n : gs) {
                    if (n != null && n.isString() && !n.asString().isBlank()) {
                        guesses.add(n.asString());
                    }
                }
            }
        }

        List<PictionaryCanvas.Stroke> strokes = (board == null) ? List.of() : board.strokes();
        String png = PictionaryCanvas.renderPngDataUri(strokes);
        // 图渲染不出来才用字符画兜底；两者都没有就不猜
        String ascii = png == null ? PictionaryAscii.render(strokes) : "";

        return new PictionaryPosition(sheGuesses, answerLength, guesses, png, ascii);
    }

    /** 现在是不是她该猜。 */
    public boolean sheGuesses() {
        return sheGuesses;
    }

    /** 有没有画面可看（图或字符画）。 */
    public boolean hasPicture() {
        return pngDataUri != null || (asciiArt != null && !asciiArt.isBlank());
    }

    public String png() {
        return pngDataUri;
    }

    /** 拼给大脑的局面描述。 */
    public String observation() {
        StringBuilder sb = new StringBuilder();
        sb.append("你画我猜：他正在画一个词，你要**看着他的画**猜出是什么东西。\n");
        if (answerLength > 0) {
            sb.append("答案字数：").append(answerLength).append(" 个字。\n");
        }
        if (pngDataUri != null) {
            sb.append("（他的画作为图片附在本条消息里，直接看那张图。）\n");
        } else if (asciiArt != null && !asciiArt.isBlank()) {
            sb.append("他的画（字符画：第一行=顶部，越左越靠画面左边，空白=没画）：\n")
                    .append(asciiArt).append('\n');
        } else {
            sb.append("（他到现在一笔都没画，别猜，等下一轮）\n");
        }
        if (!wrongGuesses.isEmpty()) {
            int from = Math.max(0, wrongGuesses.size() - MAX_WRONG_IN_PROMPT);
            sb.append("你已经猜错过（别再猜这些）：")
                    .append(String.join("、", wrongGuesses.subList(from, wrongGuesses.size())))
                    .append('\n');
        }
        sb.append("先说一遍你在图上看到了什么形状，再据此给出答案词，写进 clue.word。\n");
        sb.append("**不要按读音联想，不要猜同音词** —— 只能靠画面上的形状。");
        return sb.toString();
    }

    /** 合法动作只有一个占位：答案写在 {@code clue.word}，不是在这里选 id。 */
    public List<Map<String, Object>> legalOptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "guess");
        m.put("desc", "你猜的词（写进 clue.word）");
        return List.of(m);
    }

    /** 产出请求；没有画面时返回 {@code null}（空白画面猜出来的必然是瞎猜）。 */
    public GameTurnRequest toRequest(String directive) {
        if (!hasPicture()) {
            return null;
        }
        return new GameTurnRequest(
                "pictionary",
                observation(),
                directive == null ? "" : directive,
                legalOptions(),
                1,
                true,          // ★ clueMode：此处含义是"答案写在 clue.word"
                pngDataUri);
    }

    /** 从回复里取她猜的词（{@code clue.word}；有些回复会放在 {@code actions[0]}）。 */
    public static String guessOf(net.siniplise.noriosbackend.game.GameTurnReply reply) {
        if (reply == null) {
            return null;
        }
        var clue = reply.clueNode();
        if (clue.isPresent()) {
            JsonNode w = clue.get().get("word");
            if (w != null && !w.isNull() && !w.asString().isBlank()) {
                return clean(w.asString());
            }
        }
        for (String a : reply.actions()) {
            if (a != null && !a.isBlank() && !"guess".equals(a)) {
                return clean(a);
            }
        }
        return null;
    }

    /** 去掉她可能自带的引号/书名号（JS 侧同样处理）。 */
    private static String clean(String s) {
        String t = s.strip().replaceAll("^[\"'「『]+|[\"'」』]+$", "").strip();
        return t.isEmpty() ? null : t;
    }
}
