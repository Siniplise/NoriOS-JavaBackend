package net.siniplise.noriosbackend.game.codenames;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.HttpGameBrain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/**
 * 森林寻宝真实链路：我们的 observation/legal 她能不能读懂。
 *
 * <p>这条测试的价值在于**证明 id 格式与文案是可用的**：
 * 她必须从 {@code cell:0..cell:14} 里挑，而不是回一个自由文本。
 * 如果她把 {@code cell:N} 理解成别的意思，会返回空动作或非法 id。
 */
class CodenamesBrainIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static boolean personaUp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 25503), 600);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 15 个词的盘子，中间埋两个水果，让「水果/2」确实有落点。 */
    private static CodenamesPosition board() {
        return CodenamesPosition.from(MAPPER.readTree("""
                {
                  "board": [
                    {"text":"苹果"},{"text":"香蕉"},{"text":"月亮"},{"text":"火车"},{"text":"雨伞"},
                    {"text":"医生"},{"text":"钢琴"},{"text":"沙漠"},{"text":"镜子"},{"text":"辣椒"},
                    {"text":"舞台"},{"text":"钥匙"},{"text":"枕头"},{"text":"河流"},{"text":"影子"}
                  ],
                  "cells": [
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]},
                    {"solvedBy":null,"bystanderMarks":[null,null]}
                  ]
                }
                """));
    }

    @Test
    @DisplayName("★ 真实调用：她按提示词从 cell 里挑，且挑的 id 都合法")
    @Timeout(40)
    void realBrainPicksCells() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        CodenamesPosition pos = board();
        HttpGameBrain brain = new HttpGameBrain("http://127.0.0.1:25503");

        var req = pos.toGuessRequest(new CodenamesPosition.Clue("水果", 2), "");
        GameTurnReply reply = brain.turn(req).block(Duration.ofSeconds(30));

        assertThat(reply).as("大脑必须 ok=true").isNotNull();
        assertThat(reply.actions()).as("给了明确提示词，她应该会猜").isNotEmpty();

        Set<String> legal = req.legalIds();
        assertThat(legal).contains("end", "clue");
        for (String id : reply.actions()) {
            assertThat(legal)
                    .as("她返回的 %s 必须是合法动作之一", id)
                    .contains(id);
        }
        assertThat(reply.lines()).as("她应当说点什么").isNotEmpty();
    }

    @Test
    @DisplayName("★ 真实调用：动作用我们的 toCommands 能翻成上游指令")
    @Timeout(40)
    void realActionsTranslate() {
        Assumptions.assumeTrue(personaUp(), "25503 未运行，跳过真实大脑测试");

        CodenamesPosition pos = board();
        HttpGameBrain brain = new HttpGameBrain("http://127.0.0.1:25503");
        GameTurnReply reply = brain.turn(pos.toGuessRequest(new CodenamesPosition.Clue("水果", 2), ""))
                .block(Duration.ofSeconds(30));
        assertThat(reply).isNotNull();

        var cmds = pos.toCommands(reply.actions(), reply.clueNode()
                .map(c -> new CodenamesPosition.Clue(
                        c.get("word") == null ? null : c.get("word").asString(),
                        c.get("count") == null ? 1 : c.get("count").asInt()))
                .orElse(null));

        // 她猜了牌就必须以 endTurn 收尾（JS 侧的 containment 策略）
        boolean hasGuess = cmds.stream().anyMatch(m -> "submitGuess".equals(m.get("type")));
        if (hasGuess) {
            assertThat(cmds.get(cmds.size() - 1))
                    .as("猜完之后必须以 endTurn 或 submitClue 收尾")
                    .satisfiesAnyOf(
                            m -> assertThat(m).containsEntry("type", "endTurn"),
                            m -> assertThat(m).containsEntry("type", "submitClue"));
        }
        // 每个 submitGuess 的 cell 必在盘内
        for (var m : cmds) {
            if ("submitGuess".equals(m.get("type"))) {
                int cell = (Integer) m.get("cell");
                assertThat(cell).isBetween(0, 14);
            }
        }
    }
}
