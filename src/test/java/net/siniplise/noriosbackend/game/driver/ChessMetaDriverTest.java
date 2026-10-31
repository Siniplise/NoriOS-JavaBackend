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
 * 悔棋 / 和棋：**通道是我们的，决策是 LLM 的**。
 *
 * <p>这里测的边界是"通道正确性"，不是"她答得对不对"——后者是人格的事，
 * 已用真实调用验证过（她回了 accept + 一句"下次可不一定让着你哦"）。
 *
 * <p>最容易错的两处：
 * <ul>
 *   <li>把应答硬编码成 accept（那等于替她做人）</li>
 *   <li>同一个 transition 既应答又走子（局面会被推两次）</li>
 * </ul>
 */
class ChessMetaDriverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String FEN = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1";

    private record Harness(GameDriverService driver, List<String> sent, AtomicInteger asks) {
    }

    private static Harness harness(String answer) {
        List<String> sent = new ArrayList<>();
        AtomicInteger asks = new AtomicInteger();

        UpstreamChannels channels = new UpstreamChannels();
        channels.register(t -> { sent.add(t); return true; });

        GameBrain brain = req -> {
            asks.incrementAndGet();
            GameTurnReply reply = GameTurnReply.parse(MAPPER.readTree(
                    "{\"ok\":true,\"actions\":[\"" + answer + "\"],\"lines\":[\"让我想想\"],"
                            + "\"emotion\":\"doubtful\"}"), 50);
            return Mono.just(reply);
        };

        GameDriverService svc = new GameDriverService(brain,
                new UpstreamChannels.InjectorHolder(channels), Runnable::run);
        svc.setEnabled(true);
        svc.setPacingEnabled(false);
        return new Harness(svc, sent, asks);
    }

    private static void primeHead(GameDriverService svc) {
        svc.observe("{\"type\":\"cartridge_mounted\",\"cartridgeId\":\"chess\","
                + "\"runtimes\":[{\"visibilityFenceId\":\"ui\",\"headVersion\":0}]}");
    }

    private static String transitionWithEvent(int version, String eventJson) {
        return "{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\",\"version\":" + version
                + ",\"transition\":{\"version\":" + version
                + ",\"events\":[" + eventJson + "]"
                + ",\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\",\"value\":"
                + "{\"fen\":\"" + FEN + "\",\"status\":\"playing\",\"settings\":{\"playerSide\":\"white\"}}}]}}";
    }

    @Test
    @DisplayName("★ 求悔棋 ⇒ 问大脑 ⇒ 按她的选择发 respondTakeback")
    void takebackAccepted() {
        Harness h = harness("accept");
        primeHead(h.driver());
        h.driver().observe(transitionWithEvent(4, "{\"type\":\"request_takeback\"}"));

        assertThat(h.asks().get()).as("必须问一次").isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        String json = h.sent().get(0);
        assertThat(json).contains("\"type\":\"respondTakeback\"");
        assertThat(json).contains("\"accept\":true");
        assertThat(json).contains("\"actor\":\"agent\"");
    }

    @Test
    @DisplayName("★ 她拒绝 ⇒ accept:false（不是硬编码同意）")
    void takebackRefused() {
        Harness h = harness("refuse");
        primeHead(h.driver());
        h.driver().observe(transitionWithEvent(5, "{\"type\":\"request_takeback\"}"));

        assertThat(h.sent()).hasSize(1);
        assertThat(h.sent().get(0)).contains("\"type\":\"respondTakeback\"")
                .contains("\"accept\":false");
    }

    @Test
    @DisplayName("和棋请求 ⇒ respondDraw")
    void drawOffer() {
        Harness h = harness("accept");
        primeHead(h.driver());
        h.driver().observe(transitionWithEvent(6, "{\"type\":\"draw_offer\"}"));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        assertThat(h.sent().get(0)).contains("\"type\":\"respondDraw\"").contains("\"accept\":true");
    }

    @Test
    @DisplayName("★ 处理了 meta 就不再走子（同一 transition 不能被推两次）")
    void metaSuppressesMove() {
        // 这个 transition 同时满足"该她走"（黑方回合、玩家执白）和"有悔棋请求"
        Harness h = harness("accept");
        primeHead(h.driver());
        h.driver().observe(transitionWithEvent(7, "{\"type\":\"request_takeback\"}"));

        assertThat(h.asks().get()).as("只问一次（问的是悔棋，不是走子）").isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        assertThat(h.sent().get(0)).as("发的是应答，不是 move").contains("\"respondTakeback\"");
        assertThat(h.sent().get(0)).doesNotContain("\"type\":\"move\"");
    }

    @Test
    @DisplayName("嵌套事件（engine 包裹）也要认出来")
    void nestedEvent() {
        Harness h = harness("refuse");
        primeHead(h.driver());
        h.driver().observe(transitionWithEvent(8,
                "{\"type\":\"engine\",\"event\":{\"type\":\"request_takeback\"}}"));

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent().get(0)).contains("\"respondTakeback\"");
    }

    @Test
    @DisplayName("无关事件不影响走子：不该发应答，但该走子还是要走")
    void irrelevantEventFallsThroughToMove() {
        // 这个用例要验"走子分支照常工作"，所以让大脑返回一个**合法走法**（不是 accept）
        Harness h = harness("e7e5");
        primeHead(h.driver());
        // 这个 transition 的局面本身就轮到她（黑方回合、玩家执白）
        h.driver().observe(transitionWithEvent(9, "{\"type\":\"castling\"}"));

        assertThat(h.sent()).as("绝不能把无关事件当悔棋去应答")
                .noneMatch(j -> j.contains("respondTakeback") || j.contains("respondDraw"));
        assertThat(h.asks().get()).as("该她走子，所以要问（问的是走子）").isEqualTo(1);
        assertThat(h.sent().get(0)).as("发的是 move").contains("\"type\":\"move\"");
    }

    @Test
    @DisplayName("没有 headVersion ⇒ 问了但不发（发了也会被静默丢弃）")
    void noHeadNoSend() {
        Harness h = harness("accept");
        // 不 primeHead
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\","
                + "\"transition\":{\"events\":[{\"type\":\"request_takeback\"}],"
                + "\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\",\"value\":{\"fen\":\""
                + FEN + "\",\"status\":\"playing\"}}]}}");

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).as("不知道 head 就不发").isEmpty();
    }
}
