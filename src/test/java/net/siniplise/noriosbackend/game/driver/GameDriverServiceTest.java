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
 * 帧流驱动编排：开关、去重、报文形状。
 *
 * <p>这里测的是**最容易造成"她连走三步"或"她一动不动"** 的地方：
 * 上游会把同一条 transition 广播多份、我们也会重连，所以去重不是优化而是正确性要求。
 */
class GameDriverServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 玩家执白、轮到黑方 ⇒ 该她动。 */
    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";
    /** 玩家执白、轮到白方 ⇒ 不该她动。 */
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private record Harness(GameDriverService driver, List<String> sent, AtomicInteger asks,
                           List<GameTurnReply> spoken) {
    }

    private static Harness harness(String fen, int version, String actionId) {
        List<String> sent = new ArrayList<>();
        AtomicInteger asks = new AtomicInteger();
        List<GameTurnReply> spoken = new ArrayList<>();

        UpstreamChannels channels = new UpstreamChannels();
        channels.register(text -> {
            sent.add(text);
            return true;
        });
        UpstreamChannels.InjectorHolder holder = new UpstreamChannels.InjectorHolder(channels);

        GameBrain brain = req -> {
            asks.incrementAndGet();
            GameTurnReply reply = GameTurnReply.parse(
                    MAPPER.readTree("{\"ok\":true,\"actions\":[\"" + actionId + "\"],"
                            + "\"lines\":[\"应你一手\"],\"emotion\":\"happy\"}"), 50);
            return Mono.just(reply);
        };

        GameDriverService svc = new GameDriverService(brain, holder, Runnable::run);
        svc.setEnabled(true);
        svc.setPacingEnabled(false);   // 单测不等动画节拍
        svc.setOnSpoken(spoken::add);
        return new Harness(svc, sent, asks, spoken);
    }

    private static String transition(int version, String fen) {
        return "{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\",\"version\":" + version
                + ",\"transition\":{\"version\":" + version
                + ",\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\",\"value\":"
                + "{\"fen\":\"" + fen + "\",\"status\":\"playing\",\"settings\":{\"playerSide\":\"white\"}}}"
                + "]}}";
    }

    // ── 开关 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("默认关闭：即使轮到她也不问（避免默认就去污染对局）")
    void disabledByDefault() {
        var sent = new ArrayList<String>();
        var asks = new AtomicInteger();
        UpstreamChannels channels = new UpstreamChannels();
        channels.register(t -> { sent.add(t); return true; });
        GameBrain brain = req -> { asks.incrementAndGet(); return Mono.empty(); };

        var svc = new GameDriverService(brain, new UpstreamChannels.InjectorHolder(channels), Runnable::run);
        assertThat(svc.enabled()).isFalse();

        svc.observe(transition(5, AFTER_E4));
        assertThat(asks.get()).as("关着就不该问").isZero();
        assertThat(sent).isEmpty();
    }

    // ── 正常驱动 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 轮到她 ⇒ 问一次大脑，并按 dispatch 形状发回上游")
    void drivesOneTurn() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe(transition(5, AFTER_E4));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).hasSize(1);

        String json = h.sent().get(0);
        assertThat(json).contains("\"type\":\"dispatch\"");
        assertThat(json).as("actor 必须是 agent，不能是 player").contains("\"actor\":\"agent\"");
        assertThat(json).contains("\"cartridgeId\":\"chess\"");
        assertThat(json).contains("\"expectedHeadVersion\":5");
        assertThat(json).contains("\"cmd\":{\"type\":\"move\"");
        // 玩家发的 dispatch 不带 worldId/fence，但 **agent** 发的要带（依据 sendAgent 的真实报文）。
        // 这里没收到过 worldId，所以不该凭空编一个；fence 有默认值 "ui"。
        assertThat(json).as("不知道 worldId 时不要瞎编").doesNotContain("worldId");
        assertThat(json).as("agent 的 dispatch 必须带 visibilityFenceId")
                .contains("\"visibilityFenceId\":\"ui\"");
        assertThat(json).containsPattern("\"requestId\":\"[0-9a-f-]{36}\"");
    }

    @Test
    @DisplayName("★ 同一 version 再来（广播副本/重连）⇒ 不重复问")
    void dedupesSameVersion() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe(transition(5, AFTER_E4));
        h.driver().observe(transition(5, AFTER_E4));
        h.driver().observe(transition(5, AFTER_E4));

        assertThat(h.asks().get()).as("只问一次").isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
    }

    @Test
    @DisplayName("新版本 ⇒ 可以再问（局面推进了）")
    void allowsNextVersion() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe(transition(5, AFTER_E4));
        h.driver().observe(transition(6, AFTER_E4));
        assertThat(h.asks().get()).isEqualTo(2);
    }

    @Test
    @DisplayName("不该她动（玩家回合）⇒ 不问")
    void doesNotDrivePlayerTurn() {
        Harness h = harness(START, 5, "e2e4");
        h.driver().observe(transition(5, START));
        assertThat(h.asks().get()).isZero();
        assertThat(h.sent()).isEmpty();
    }

    @Test
    @DisplayName("她走完会说话 ⇒ 台词回调被触发")
    void speaksLines() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe(transition(5, AFTER_E4));

        assertThat(h.spoken()).hasSize(1);
        assertThat(h.spoken().get(0).lines()).containsExactly("应你一手");
        assertThat(h.spoken().get(0).emotion()).isEqualTo("happy");
    }

    // ── 安全边界 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("没有 headVersion ⇒ 不发（发了也会被静默丢弃，变成查不出的谜）")
    void noHeadNoSend() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        // 直接调 observe 但消息里没有 version，heads 就没记到
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\","
                + "\"transition\":{\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\","
                + "\"value\":{\"fen\":\"" + AFTER_E4 + "\",\"status\":\"playing\"}}]}}");

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).as("不知道 head，宁可不发").isEmpty();
    }

    @Test
    @DisplayName("大脑给的动作全非法 ⇒ 不发指令，但台词照播")
    void illegalActionNoCmd() {
        Harness h = harness(AFTER_E4, 5, "a1a8");   // 黑方走不出这个
        h.driver().observe(transition(5, AFTER_E4));

        assertThat(h.sent()).isEmpty();
        assertThat(h.spoken()).as("走不了但话还是要说").hasSize(1);
    }

    @Test
    @DisplayName("换局时清 head 与去重标记（新局版本从头开始）")
    void resetsOnMount() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe(transition(5, AFTER_E4));
        assertThat(h.driver().heads().head("chess")).isEqualTo(5);

        h.driver().observe("{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chess\"}");
        assertThat(h.driver().heads().head("chess")).isNull();
    }

    @Test
    @DisplayName("非 chess 卡带暂不驱动（其余卡带随后接）")
    void onlyChessForNow() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"cakeduel\",\"version\":3,"
                + "\"transition\":{\"version\":3,\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\","
                + "\"value\":{\"phase\":\"attack\",\"players\":[]}}]}}");
        assertThat(h.asks().get()).isZero();
    }

    @Test
    @DisplayName("脏帧不抛异常（一条坏消息不能打断帧流）")
    void toleratesGarbage() {
        Harness h = harness(AFTER_E4, 5, "e7e5");
        h.driver().observe("not json");
        h.driver().observe("");
        h.driver().observe(null);
        h.driver().observe("[]");
        h.driver().observe("{\"type\":\"unknown\"}");
        assertThat(h.asks().get()).isZero();
    }

    // ── 开关：必须能用 -Dnori.game.driver=on 打开 ──────────────────────

    @Test
    @DisplayName("★ 系统属性 nori.game.driver=on ⇒ enabled；默认 off ⇒ 关闭")
    void enabledBySystemProperty() {
        String key = "nori.game.driver";
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertThat(new GameDriverService(null, null, Runnable::run).enabled())
                    .as("默认关闭").isFalse();

            System.setProperty(key, "on");
            assertThat(new GameDriverService(null, null, Runnable::run).enabled())
                    .as("加了 -Dnori.game.driver=on 就该开").isTrue();

            System.setProperty(key, "ON");
            assertThat(new GameDriverService(null, null, Runnable::run).enabled())
                    .as("大小写不敏感").isTrue();

            System.setProperty(key, "true");
            assertThat(new GameDriverService(null, null, Runnable::run).enabled())
                    .as("只认 on，不认 true（避免误以为随便写点什么都能开）").isFalse();
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

}
