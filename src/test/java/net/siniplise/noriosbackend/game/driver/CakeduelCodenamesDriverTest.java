package net.siniplise.noriosbackend.game.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.siniplise.noriosbackend.game.GameBrain;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.gateway.UpstreamChannels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 蛋糕对决 / 森林寻宝的驱动分支。
 *
 * <p>这两个卡带的"轮到谁"判定跟象棋不一样：
 * <ul>
 *   <li>蛋糕对决看**相位**（而且 block 相位的行动者是被攻击方，不是攻击方）；</li>
 *   <li>森林寻宝看**回合记录**（`history` 最后一条是否结束、给词方是谁）。</li>
 * </ul>
 * 判错的后果是"我们替她发了一个不该她发的动作"，所以这里每条边界都单独钉。
 */
class CakeduelCodenamesDriverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Harness(GameDriverService driver, List<String> sent, AtomicInteger asks) {
    }

    private static Harness harness(String actionId) {
        List<String> sent = new ArrayList<>();
        AtomicInteger asks = new AtomicInteger();

        UpstreamChannels channels = new UpstreamChannels();
        channels.register(t -> { sent.add(t); return true; });

        GameBrain brain = req -> {
            asks.incrementAndGet();
            GameTurnReply reply = GameTurnReply.parse(MAPPER.readTree(
                    "{\"ok\":true,\"actions\":[\"" + actionId + "\"],\"lines\":[\"看我的\"],"
                            + "\"emotion\":\"happy\"}"), 50);
            return Mono.just(reply);
        };

        GameDriverService svc = new GameDriverService(brain,
                new UpstreamChannels.InjectorHolder(channels), Runnable::run);
        svc.setEnabled(true);
        svc.setPacingEnabled(false);   // 单测不等动画节拍
        return new Harness(svc, sent, asks);
    }

    private static String transition(String cart, int version, String gameStateJson) {
        return "{\"type\":\"runtime_transition\",\"cartridgeId\":\"" + cart + "\",\"version\":" + version
                + ",\"transition\":{\"version\":" + version
                + ",\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\",\"value\":"
                + gameStateJson + "}]}}";
    }

    private static void primeHead(GameDriverService svc, String cart) {
        svc.observe("{\"type\":\"cartridge_mounted\",\"cartridgeId\":\"" + cart + "\","
                + "\"runtimes\":[{\"visibilityFenceId\":\"ui\",\"headVersion\":0}]}");
    }

    // ── 蛋糕对决 ───────────────────────────────────────────────────────

    /** 她（1）的 attack 回合，手牌 soldier/defender/wolfy。 */
    private static final String CAKE_HER_TURN = """
            {"phase":"attack","attackerIndex":1,
             "players":[{"hand":[],"cakes":1},{"hand":[0,3,5],"cakes":2}],
             "cardList":["soldier","archer","wizard","defender","scientist","wolfy"]}
            """;

    @Test
    @DisplayName("★ 蛋糕对决：她的 attack 回合 ⇒ 问一次并发出 play")
    void drivesCake() {
        Harness h = harness("claim:0");
        primeHead(h.driver(), "cakeduel");
        h.driver().observe(transition("cakeduel", 3, CAKE_HER_TURN));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        String json = h.sent().get(0);
        assertThat(json).contains("\"cartridgeId\":\"cakeduel\"");
        assertThat(json).contains("\"type\":\"play\"");
        assertThat(json).contains("\"actor\":\"agent\"");
        assertThat(json).contains("\"visibilityFenceId\":\"ui\"");
        assertThat(json).contains("\"expectedHeadVersion\":3");
    }

    @Test
    @DisplayName("★ 蛋糕对决：block 相位若是玩家格挡（攻击方是她）⇒ 不问")
    void notHerBlock() {
        Harness h = harness("claim:0");
        primeHead(h.driver(), "cakeduel");
        // 攻击方 1（她）⇒ block 该 0（玩家）格挡
        h.driver().observe(transition("cakeduel", 4,
                "{\"phase\":\"block\",\"attackerIndex\":1,\"players\":[{\"hand\":[0]},{\"hand\":[3]}]}"));

        assertThat(h.asks().get()).isZero();
        assertThat(h.sent()).isEmpty();
    }

    @Test
    @DisplayName("★ 蛋糕对决：block 相位若攻击方是玩家 ⇒ 轮到她格挡，要问")
    void herBlock() {
        // 注意：claim 后面的下标是**手牌下标**（不是牌表下标）。她这张手牌只有 1 张 ⇒ claim:0
        Harness h = harness("claim:0");   // 用盾兵格挡
        primeHead(h.driver(), "cakeduel");
        h.driver().observe(transition("cakeduel", 5,
                "{\"phase\":\"block\",\"attackerIndex\":0,\"attackingClaim\":{\"claim\":\"soldier\"},"
                        + "\"players\":[{\"hand\":[]},{\"hand\":[3]}],"
                        + "\"cardList\":[\"soldier\",\"archer\",\"wizard\",\"defender\"]}"));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        assertThat(h.sent().get(0)).contains("\"type\":\"play\"");
    }

    @Test
    @DisplayName("蛋糕对决：bout_end 相位没人该动 ⇒ 不问")
    void cakeBoutEnd() {
        Harness h = harness("pass");
        primeHead(h.driver(), "cakeduel");
        h.driver().observe(transition("cakeduel", 6, "{\"phase\":\"bout_end\",\"attackerIndex\":1}"));
        assertThat(h.asks().get()).isZero();
    }

    // ── 森林寻宝 ───────────────────────────────────────────────────────

    /** 玩家给过提示词、她还没猜完。 */
    private static final String CODEX_HER_TURN = """
            {"board":[{"text":"苹果"},{"text":"月亮"},{"text":"辣椒"}],
             "cells":[{"solvedBy":null},{"solvedBy":null},{"solvedBy":null}],
             "history":[{"clueGiver":"player","clue":{"word":"水果","count":1}}]}
            """;

    @Test
    @DisplayName("★ 森林寻宝：玩家给词（上行抓到）⇒ 发 submitGuess 且自动补 endTurn")
    void drivesCodenames() {
        Harness h = harness("cell:0");
        primeHead(h.driver(), "codenames");
        // 实测：gameState 里没有提示词，必须由会话层从上行 submitClue 抓来喂进去
        h.driver().noteClue("水果", 1);
        h.driver().observe(transition("codenames", 7, CODEX_HER_TURN));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).as("一条 submitGuess + 一条 endTurn").hasSize(2);
        assertThat(h.sent().get(0)).contains("\"type\":\"submitGuess\"").contains("\"cell\":0");
        assertThat(h.sent().get(1)).as("猜完必须收手（掐断本地对手连猜）").contains("\"type\":\"endTurn\"");
        assertThat(h.sent().get(0)).contains("\"cartridgeId\":\"codenames\"");
    }

    @Test
    @DisplayName("森林寻宝：没收到提示词 ⇒ 不问（这才是实测的真实开局：history 为空）")
    void codenamesNoClue() {
        Harness h = harness("cell:0");
        primeHead(h.driver(), "codenames");
        h.driver().observe(transition("codenames", 8, CODEX_HER_TURN));

        assertThat(h.asks().get()).as("gameState 里没有提示词，也没从上行抓到 ⇒ 不该驱动").isZero();
    }

    @Test
    @DisplayName("★ 森林寻宝：whoseTurnToGive 指向她 ⇒ 该她给词，跳过猜牌")
    void codenamesSheGivesClue() {
        Harness h = harness("cell:0");
        primeHead(h.driver(), "codenames");
        h.driver().noteClue("水果", 1);
        h.driver().observe(transition("codenames", 9, """
                {"board":[{"text":"苹果"}],"cells":[{"solvedBy":null}],"history":[],
                 "phase":"NORMAL","whoseTurnToGive":"agent"}
                """));

        assertThat(h.asks().get()).as("轮到她给词，不是猜").isZero();
    }

    @Test
    @DisplayName("★ 同一个提示词只能驱动一次（不能因为局面推进就再猜一遍）")
    void codenamesClueConsumedOnce() {
        Harness h = harness("cell:0");
        primeHead(h.driver(), "codenames");
        h.driver().noteClue("水果", 1);

        h.driver().observe(transition("codenames", 10, CODEX_HER_TURN));
        assertThat(h.asks().get()).isEqualTo(1);

        // 她猜中后上游会推进局面（history 里出现这一轮），不能因此再驱动一次
        h.driver().observe(transition("codenames", 11, """
                {"board":[{"text":"苹果"},{"text":"月亮"}],
                 "cells":[{"solvedBy":"agent"},{"solvedBy":null}],
                 "history":[{"clueGiver":"player","clue":{"word":"水果","count":1}}]}
                """));
        assertThat(h.asks().get()).as("同一个 clue 已经消费掉了").isEqualTo(1);
    }

    @Test
    @DisplayName("森林寻宝：格子全翻完了 ⇒ 不问（没得猜）")
    void codenamesAllSolved() {
        Harness h = harness("cell:0");
        primeHead(h.driver(), "codenames");
        h.driver().noteClue("水果", 1);
        h.driver().observe(transition("codenames", 12, """
                {"board":[{"text":"苹果"}],"cells":[{"solvedBy":"agent"}],
                 "history":[],"phase":"NORMAL"}
                """));
        assertThat(h.asks().get()).isZero();
    }

    @Test
    @DisplayName("四个卡带都能识别：未知卡带（chat/manifold.web）一律不驱动")
    void unknownCartridgeIgnored() {
        Harness h = harness("x");
        h.driver().observe(transition("chat", 1, "{\"any\":1}"));
        h.driver().observe(transition("manifold.web", 1, "{\"facts\":{}}"));
        assertThat(h.asks().get()).isZero();
    }
}
