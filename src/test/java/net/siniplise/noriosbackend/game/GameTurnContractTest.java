package net.siniplise.noriosbackend.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `/game-turn` 的契约 —— 用**两次真实调用**的响应做夹具（2026-10-31，25503 在跑）。
 *
 * <p>第一份是我按"结构化对象"猜的请求她回的那次（{@code actions:[]} + 一句"棋盘卡住了"），
 * 第二份是改成正确的多行 observation + `{id,desc}` 数组之后，她真的走出 `e2e4` 的那次。
 * 两份都留着：前者钉住"空动作也是合法回复"，后者钉住"正常回复长什么样"。
 */
class GameTurnContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    /** 实测①：请求形状不对时，她仍然 ok=true，但 actions 为空并给出 reason。 */
    private static final String REAL_EMPTY_REPLY = """
            {"ok":true,"game":"chess","actions":[],"clue":null,
             "line":"唔...棋盘好像卡住了呢。\\n你那边还在吗？",
             "lines":["唔...棋盘好像卡住了呢。","你那边还在吗？"],
             "emotion":"doubtful","obeyed":true,
             "reason":"没有合法动作，只能等局面恢复","ms":1431}
            """;

    /** 实测②：契约正确时的正常回复。 */
    private static final String REAL_MOVE_REPLY = """
            {"ok":true,"game":"chess","actions":["e2e4"],"clue":null,
             "line":"嘿嘿，Nori 先走一步啦。\\n你可要认真点哦。",
             "lines":["嘿嘿，Nori 先走一步啦。","你可要认真点哦。"],
             "emotion":"happy","obeyed":true,"reason":"开局先占中心","ms":1129}
            """;

    @Test
    @DisplayName("真实回复②：actions 是 id 字符串数组，lines 是逐句台词")
    void parsesRealMoveReply() {
        GameTurnReply r = GameTurnReply.parse(json(REAL_MOVE_REPLY), 999);

        assertThat(r).isNotNull();
        assertThat(r.actions()).containsExactly("e2e4");
        assertThat(r.lines()).containsExactly("嘿嘿，Nori 先走一步啦。", "你可要认真点哦。");
        assertThat(r.emotion()).isEqualTo("happy");
        assertThat(r.obeyed()).isTrue();
        assertThat(r.reason()).isEqualTo("开局先占中心");
        assertThat(r.ms()).as("上游给了 ms，就不用 fallback").isEqualTo(1129);
        assertThat(r.hasLines()).isTrue();
    }

    @Test
    @DisplayName("真实回复①：actions 为空仍然 ok=true（空动作是合法回复，不是失败）")
    void parsesRealEmptyReply() {
        GameTurnReply r = GameTurnReply.parse(json(REAL_EMPTY_REPLY), 999);

        assertThat(r).isNotNull();
        assertThat(r.actions()).isEmpty();
        assertThat(r.lines()).hasSize(2);
        assertThat(r.emotion()).isEqualTo("doubtful");
        assertThat(r.reason()).contains("没有合法动作");
        assertThat(r.hasLines()).as("没动作但有话要说").isTrue();
    }

    @Test
    @DisplayName("ok=false ⇒ 返回 null（必须当失败，不能当'没有动作'）")
    void okFalseIsFailure() {
        assertThat(GameTurnReply.parse(json("{\"ok\":false}"), 100)).isNull();
        assertThat(GameTurnReply.parse(json("{\"error\":\"boom\"}"), 100)).isNull();
        assertThat(GameTurnReply.parse(json("[]"), 100)).isNull();
        assertThat(GameTurnReply.parse(null, 100)).isNull();
    }

    @Test
    @DisplayName("只有 line 没有 lines ⇒ 按换行拆成逐句（老格式兼容）")
    void splitsLegacyLine() {
        GameTurnReply r = GameTurnReply.parse(json("""
                {"ok":true,"actions":["x"],"line":"第一句\\n第二句\\n  \\n第三句","emotion":"happy"}
                """), 50);
        assertThat(r).isNotNull();
        assertThat(r.lines()).as("空行要丢掉").containsExactly("第一句", "第二句", "第三句");
    }

    @Test
    @DisplayName("缺 emotion / ms / obeyed 都有默认值，不能 NPE")
    void defaults() {
        GameTurnReply r = GameTurnReply.parse(json("{\"ok\":true}"), 777);
        assertThat(r).isNotNull();
        assertThat(r.emotion()).isEqualTo("neutral");
        assertThat(r.ms()).isEqualTo(777);
        assertThat(r.obeyed()).isFalse();
        assertThat(r.actions()).isEmpty();
        assertThat(r.lines()).isEmpty();
        assertThat(r.clueNode()).isEmpty();
    }

    @Test
    @DisplayName("森林寻宝给提示词时 clue 是结构化对象")
    void clueShape() {
        GameTurnReply r = GameTurnReply.parse(json("""
                {"ok":true,"actions":["submitClue"],"clue":{"word":"海洋","count":3},
                 "lines":["这个提示给他们！"],"emotion":"happy"}
                """), 10);
        assertThat(r).isNotNull();
        assertThat(r.clueNode()).isPresent();
        assertThat(r.clueNode().get().get("word").asString()).isEqualTo("海洋");
        assertThat(r.clueNode().get().get("count").asInt()).isEqualTo(3);
    }

    // ── 请求体：字段名与 legal 形状 ──────────────────────────────────

    @Test
    @DisplayName("请求体：observation 是字符串，legal 是 {id,desc} 数组")
    void requestBodyShape() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "e2e4");
        a.put("desc", "e4 (e2→e4)");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "d2d4");
        b.put("desc", "d4 (d2→d4)");

        GameTurnRequest req = new GameTurnRequest(
                "chess", "多行局面描述", null, List.of(a, b), 1, false, null);

        var body = req.toBody();
        assertThat(body.get("game")).isEqualTo("chess");
        assertThat(body.get("observation")).isEqualTo("多行局面描述");
        assertThat(body.get("directive")).as("null 要变成空串，服务端按字符串读").isEqualTo("");
        assertThat(body.get("maxActions")).isEqualTo(1);
        assertThat(body.get("clueMode")).isEqualTo(false);
        assertThat(body).as("没图就不发 image 字段").doesNotContainKey("image");
        assertThat(req.legalIds()).containsExactly("e2e4", "d2d4");
    }

    @Test
    @DisplayName("请求体：有图时带 image")
    void requestBodyWithImage() {
        GameTurnRequest req = new GameTurnRequest(
                "pictionary", "看这张图", "", List.of(), 1, false, "data:image/png;base64,AAA");
        assertThat(req.toBody()).containsKey("image");
    }

    @Test
    @DisplayName("legalIds：容忍缺 id / 空项，不抛异常")
    void legalIdsTolerant() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("id", "cell:1");
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("desc", "没有 id");

        GameTurnRequest req = new GameTurnRequest(
                "codenames", "x", "", List.of(ok, noId), 1, true, null);
        assertThat(req.legalIds()).containsExactly("cell:1");
    }
}
