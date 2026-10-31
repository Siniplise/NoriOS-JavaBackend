package net.siniplise.noriosbackend.game.codenames;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 森林寻宝：局面解析 + 动作翻译。
 *
 * <p>重点钉的是 {@code toCommands} 的**顺序与补齐**——那是 JS 侧踩过的真 bug
 * （大脑给了 end 却被丢掉；猜完不收手会被本地对手接着替她连猜）。
 */
class CodenamesPositionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    /** 4 格的最小局面：0/1 未翻开、2 已翻开、3 上次翻错过。 */
    private static CodenamesPosition sample() {
        return CodenamesPosition.from(json("""
                {
                  "board": [{"text":"苹果"},{"text":"香蕉"},{"text":"樱桃"},{"text":"西瓜"}],
                  "cells": [
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":"agent","bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,"agent"]}
                  ]
                }
                """));
    }

    // ── 解析 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：已翻开的格子不进合法动作；翻错过的会被标记")
    void parseCells() {
        CodenamesPosition pos = sample();
        assertThat(pos.allCells()).hasSize(4);
        assertThat(pos.openCells()).extracting(CodenamesPosition.Cell::index).containsExactly(0, 1, 3);
        assertThat(pos.hasOpenCells()).isTrue();
        assertThat(pos.allCells().get(2).solved()).isTrue();
        assertThat(pos.allCells().get(3).previouslyWrong()).as("bystanderMarks 有值即翻错过").isTrue();
        assertThat(pos.allCells().get(0).previouslyWrong()).isFalse();
    }

    @Test
    @DisplayName("解析宽容：缺 board / 结构不对 ⇒ 空局面，不抛异常")
    void parseTolerant() {
        assertThat(CodenamesPosition.from(null).hasOpenCells()).isFalse();
        assertThat(CodenamesPosition.from(json("{}")).hasOpenCells()).isFalse();
        assertThat(CodenamesPosition.from(json("{\"board\":\"not-an-array\"}")).hasOpenCells()).isFalse();
        assertThat(CodenamesPosition.from(json("[]")).hasOpenCells()).isFalse();
        // 有 board 没 cells：格子全部当"未翻开"
        assertThat(CodenamesPosition.from(json("{\"board\":[{\"text\":\"a\"}]}")).openCells()).hasSize(1);
    }

    @Test
    @DisplayName("词表：优先 text，其次 id，都没有就用「第N格」")
    void wordFallback() {
        CodenamesPosition pos = CodenamesPosition.from(json("""
                {"board":[{"text":"苹果"},{"id":"B2"},{}]}
                """));
        assertThat(pos.allCells()).extracting(CodenamesPosition.Cell::text)
                .containsExactly("苹果", "B2", "第2格");
    }

    // ── 合法动作 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("legalOptions：格子在前，end 与 clue 在最后")
    void legalOrder() {
        List<Map<String, Object>> legal = sample().legalOptions();
        assertThat(legal).extracting(m -> m.get("id"))
                .containsExactly("cell:0", "cell:1", "cell:3", "end", "clue");
        assertThat(legal.get(0).get("desc")).isEqualTo("苹果");
        assertThat(legal.get(3).get("desc")).asString().contains("到此为止");
    }

    // ── observation 文案 ──────────────────────────────────────────────

    @Test
    @DisplayName("observation：提示词、未翻开的词、翻错提醒、怪兽警告都在")
    void observationShape() {
        String obs = sample().observation(new CodenamesPosition.Clue("水果", 2));

        assertThat(obs).contains("他给你的提示词是「水果（2 个）」");
        assertThat(obs).contains("还没被翻开的词（3 个）").contains("苹果").contains("香蕉").contains("西瓜");
        assertThat(obs).as("已翻开的格子不该出现在待猜列表").doesNotContain("樱桃");
        assertThat(obs).contains("西瓜（上次翻错了）");
        assertThat(obs).as("怪兽警告必须在，否则她会变激进").contains("翻到「怪兽」是**你们团队立刻整局输**");
        assertThat(obs).contains("宁可少猜一张");
        assertThat(obs).contains("{\"word\":\"冷的东西\",\"count\":2}");
    }

    @Test
    @DisplayName("observation：没给过提示词时写「没有提示词」")
    void observationNoClue() {
        assertThat(sample().observation(null)).contains("（没有提示词）");
        assertThat(sample().observation(new CodenamesPosition.Clue(null, 0))).contains("（没有提示词）");
    }

    // ── 动作翻译（这里最容易退化）──────────────────────────────────────

    @Test
    @DisplayName("★ 猜了但没选 end ⇒ 自动补 endTurn（掐断本地对手连猜）")
    void autoAppendEnd() {
        List<Map<String, Object>> cmds = sample().toCommands(List.of("cell:0", "cell:1"), null);

        assertThat(cmds).hasSize(3);
        assertThat(cmds.get(0)).containsEntry("type", "submitGuess").containsEntry("cell", 0);
        assertThat(cmds.get(1)).containsEntry("type", "submitGuess").containsEntry("cell", 1);
        assertThat(cmds.get(2)).containsEntry("type", "endTurn");
    }

    @Test
    @DisplayName("★ 她自己选了 end ⇒ 保留，且不重复补")
    void keepsChosenEnd() {
        List<Map<String, Object>> cmds = sample().toCommands(List.of("cell:0", "end"), null);

        assertThat(cmds).hasSize(2);
        assertThat(cmds.get(0)).containsEntry("type", "submitGuess");
        assertThat(cmds.get(1)).containsEntry("type", "endTurn");
        assertThat(cmds.stream().filter(m -> "endTurn".equals(m.get("type"))))
                .as("不能出现两个 endTurn").hasSize(1);
    }

    @Test
    @DisplayName("只选 end（不猜）⇒ 只有 endTurn")
    void endOnly() {
        List<Map<String, Object>> cmds = sample().toCommands(List.of("end"), null);
        assertThat(cmds).hasSize(1);
        assertThat(cmds.get(0)).containsEntry("type", "endTurn");
    }

    @Test
    @DisplayName("clue + 有词 ⇒ submitClue 排在最后（先翻牌再给词）")
    void clueAfterGuesses() {
        List<Map<String, Object>> cmds = sample().toCommands(
                List.of("cell:0", "clue"), new CodenamesPosition.Clue("水果", 1));

        assertThat(cmds).extracting(m -> m.get("type"))
                .containsExactly("submitGuess", "endTurn", "submitClue");
        assertThat(cmds.get(2).get("clue")).isEqualTo(Map.of("word", "水果", "count", 1));
    }

    @Test
    @DisplayName("★ clue 但没给词 ⇒ 跳过该动作，不发占位词（「诺莉」是假 AI 的记号）")
    void skipClueWithoutWord() {
        assertThat(sample().toCommands(List.of("clue"), null)).isEmpty();
        assertThat(sample().toCommands(List.of("clue"), new CodenamesPosition.Clue("", 2))).isEmpty();
    }

    @Test
    @DisplayName("非法 / 已翻开 / 越界的 cell 一律丢弃，不报错")
    void dropsInvalidCells() {
        List<Map<String, Object>> cmds = sample().toCommands(
                List.of("cell:2", "cell:99", "cell:abc", "nonsense"), null);
        assertThat(cmds).as("全被丢弃后连 endTurn 都不该补（因为没有猜牌）").isEmpty();

        // 混一个合法的：只有它 + 补的 end
        List<Map<String, Object>> mixed = sample().toCommands(
                List.of("cell:2", "cell:1", "nonsense"), null);
        assertThat(mixed).extracting(m -> m.get("type")).containsExactly("submitGuess", "endTurn");
        assertThat(mixed.get(0)).containsEntry("cell", 1);
    }

    @Test
    @DisplayName("空动作列表 ⇒ 空指令（不补 endTurn）")
    void emptyActions() {
        assertThat(sample().toCommands(List.of(), null)).isEmpty();
        assertThat(sample().toCommands(null, null)).isEmpty();
    }

    @Test
    @DisplayName("toGuessRequest：maxActions=9、clueMode=false")
    void requestShape() {
        var req = sample().toGuessRequest(new CodenamesPosition.Clue("水果", 3), "按我说的猜");
        assertThat(req.game()).isEqualTo("codenames");
        assertThat(req.maxActions()).isEqualTo(9);
        assertThat(req.clueMode()).isFalse();
        assertThat(req.image()).isNull();
        assertThat(req.legal()).hasSize(5);
        assertThat(req.legalIds()).contains("cell:0", "end", "clue");
    }
}
