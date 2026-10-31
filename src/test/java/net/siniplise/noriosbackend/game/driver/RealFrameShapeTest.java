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
 * **真实帧形状**回归：这几个字段的实际位置，全靠 golden 实测确认过。
 *
 * <p>写这个类的缘由：最初我把两处位置猜错了，现象是"日志里一片安静"——
 * `version=null`、`playerSide=null`，于是棋类判定永远拿不到她的颜色。
 * 猜错的位置如下（`2026-10-31` 用 golden 核对）：
 *
 * <pre>
 * version       → 在**消息顶层** msg.version，不在 transition 里（transition.version = null）
 * playerSide    → 在 runtimes[0].state.settings.playerSide
 *                 不在 gameState（gameState 只有 fen/turn/phase/status/…），也不在消息顶层
 * gameState     → 挂载时是**空对象**，要等 game_started 的 /gameState patch 才有 fen
 * </pre>
 */
class RealFrameShapeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 与实测一致：白方行走完 e4，轮到黑方。 */
    private static final String FEN_AFTER_E4 =
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";

    private record Harness(GameDriverService driver, List<String> sent, AtomicInteger asks) {
    }

    private static Harness harness() {
        List<String> sent = new ArrayList<>();
        AtomicInteger asks = new AtomicInteger();

        UpstreamChannels channels = new UpstreamChannels();
        channels.register(t -> { sent.add(t); return true; });

        GameBrain brain = req -> {
            asks.incrementAndGet();
            GameTurnReply reply = GameTurnReply.parse(MAPPER.readTree(
                    "{\"ok\":true,\"actions\":[\"e7e5\"],\"lines\":[\"应你一手\"],\"emotion\":\"happy\"}"), 50);
            return Mono.just(reply);
        };

        GameDriverService svc = new GameDriverService(brain,
                new UpstreamChannels.InjectorHolder(channels), Runnable::run);
        svc.setEnabled(true);
        svc.setPacingEnabled(false);
        return new Harness(svc, sent, asks);
    }

    /** 真实的 mounted：settings 在 runtimes[0].state.settings，gameState 是空对象。 */
    private static final String REAL_MOUNTED = """
            {"type":"cartridge_mounted","worldId":"23c9def7-ad8d-41f0-aaba-b27309dff98d",
             "cartridgeId":"chess","transition":"created",
             "runtimes":[{"visibilityFenceId":"ui","headVersion":0,"visibleVersion":0,
               "state":{"settings":{"playerSide":"white","difficulty":"casual","locale":"en"},
                        "gameState":{},
                        "drawOffer":null,"takebackRequest":null,"tutorial":null}}]}
            """;

    /** 真实的 transition：version 在**消息顶层**。 */
    private static String realTransition(int version) {
        return """
                {"type":"runtime_transition","worldId":"23c9def7-ad8d-41f0-aaba-b27309dff98d",
                 "cartridgeId":"chess","version":%d,
                 "transition":{"actor":"player","cmd":{"type":"move","from":"e2","to":"e4"},
                   "patches":[{"op":"replace","path":"/gameState","value":{
                     "fen":"%s","pgn":"1. e4","turn":"black","phase":"opening","status":"playing",
                     "fullMoveNumber":1,"halfMoveClock":0,"isCheck":false,"isCheckmate":false,
                     "isStalemate":false,"isDraw":false,"winner":null,"moveHistory":[]}}],
                   "events":[{"type":"move"}]}}
                """.formatted(version, FEN_AFTER_E4);
    }

    @Test
    @DisplayName("★ version 取自消息顶层 ⇒ expectedHeadVersion 是真实值，不是 null")
    void versionFromMessageTopLevel() {
        Harness h = harness();
        h.driver().observe(REAL_MOUNTED);
        h.driver().observe(realTransition(1));

        assertThat(h.asks().get()).as("玩家执白、轮黑 ⇒ 轮到她").isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        assertThat(h.sent().get(0))
                .as("如果 version 取成 null，这里会是 -1 或干脆不发")
                .contains("\"expectedHeadVersion\":1");
    }

    @Test
    @DisplayName("★ playerSide 取自 runtimes[].state.settings ⇒ 能正确判断\"轮到她\"")
    void playerSideFromRuntimeState() {
        Harness h = harness();
        h.driver().observe(REAL_MOUNTED);
        // 若 playerSide 没读到（null）会按"玩家执白"处理，巧合正确；所以这里再验一次反向：
        h.driver().observe(realTransition(2));
        assertThat(h.asks().get()).as("玩家执白 + 轮黑 ⇒ 她该动").isEqualTo(1);

        // 反向：玩家执黑 + 轮黑 ⇒ 不该她动
        Harness h2 = harness();
        h2.driver().observe(REAL_MOUNTED.replace("\"playerSide\":\"white\"", "\"playerSide\":\"black\""));
        h2.driver().observe(realTransition(2));
        assertThat(h2.asks().get()).as("玩家执黑 + 轮黑 ⇒ 是玩家自己的回合").isZero();
    }

    @Test
    @DisplayName("挂载时 gameState 是空对象 ⇒ 不缓存空局面，等 patch 来")
    void emptyMountedGameStateIgnored() {
        Harness h = harness();
        h.driver().observe(REAL_MOUNTED);
        // 只有挂载、没有 transition：不该问（没有 fen）
        assertThat(h.asks().get()).isZero();
        assertThat(h.sent()).isEmpty();
    }

    @Test
    @DisplayName("同一 version 重复广播 ⇒ 只问一次（去重依赖真实 version）")
    void dedupeWithRealVersion() {
        Harness h = harness();
        h.driver().observe(REAL_MOUNTED);
        h.driver().observe(realTransition(5));
        h.driver().observe(realTransition(5));
        h.driver().observe(realTransition(5));
        // 上游按连接数广播多份
        assertThat(h.asks().get()).as("version 若取成 null，去重会失效 ⇒ 连问三次").isEqualTo(1);
    }

    @Test
    @DisplayName("mounted 里带非空 gameState 时会立刻收下（有的卡带这么给）")
    void mountedGameStateAcceptedWhenPresent() {
        Harness h = harness();
        h.driver().observe("""
                {"type":"cartridge_mounted","cartridgeId":"chess",
                 "runtimes":[{"visibilityFenceId":"ui","headVersion":0,
                   "state":{"settings":{"playerSide":"white"},
                            "gameState":{"fen":"%s","status":"playing"}}}]}
                """.formatted(FEN_AFTER_E4));
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\",\"version\":3,"
                + "\"transition\":{\"events\":[],\"patches\":[]}}");

        // transition 没有 gameState patch，但挂载时已经给过 ⇒ 仍应能用缓存驱动
        assertThat(h.asks().get()).as("用挂载时缓存的局面").isEqualTo(1);
    }

    @Test
    @DisplayName("最小定位：真实的 mounted 帧能被解析（head 记账成功）")
    void parsesRealMounted() {
        Harness h = harness();
        h.driver().observe(REAL_MOUNTED);
        assertThat(h.driver().heads().head("chess"))
                .as("解析失败则这里是 null（说明 observe 提前 return 了）")
                .isEqualTo(0);
    }

}
