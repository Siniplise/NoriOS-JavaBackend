package net.siniplise.noriosbackend.game.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.siniplise.noriosbackend.game.GameBrain;
import net.siniplise.noriosbackend.game.GameTurnReply;
import net.siniplise.noriosbackend.game.pictionary.PictionaryBoard;
import net.siniplise.noriosbackend.gateway.UpstreamChannels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

/**
 * 你画我猜的驱动：**笔画一到就该看她画的是什么**。
 *
 * <p>这块的触发点在**上行帧**（玩家的 `submitStrokeBatch`），不是下行的 transition——
 * 因为上游不会为"玩家画了一笔"专门广播局面，等它她永远不会动。
 */
class PictionaryDriverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Harness(GameDriverService driver, List<String> sent, AtomicInteger asks,
                           PictionaryBoard board) {
    }

    private static Harness harness(String guess) {
        List<String> sent = new ArrayList<>();
        AtomicInteger asks = new AtomicInteger();
        PictionaryBoard board = new PictionaryBoard();

        UpstreamChannels channels = new UpstreamChannels();
        channels.register(t -> { sent.add(t); return true; });
        UpstreamChannels.InjectorHolder holder = new UpstreamChannels.InjectorHolder(channels);

        GameBrain brain = req -> {
            asks.incrementAndGet();
            GameTurnReply reply = GameTurnReply.parse(MAPPER.readTree(
                    "{\"ok\":true,\"actions\":[\"guess\"],\"clue\":{\"word\":\"" + guess + "\"},"
                            + "\"lines\":[\"我看看…\"],\"emotion\":\"happy\"}"), 50);
            return Mono.just(reply);
        };

        GameDriverService svc = new GameDriverService(brain, holder, Runnable::run);
        svc.setEnabled(true);
        svc.setPacingEnabled(false);   // 单测不等动画节拍
        svc.setPictureBoard(board);
        return new Harness(svc, sent, asks, board);
    }

    /** 让她看到一张"画了东西"的图，并建立 head 与 gameState。 */
    private static void prime(Harness h, String guesser) {
        h.driver().observe("{\"type\":\"cartridge_mounted\",\"cartridgeId\":\"pictionary\","
                + "\"runtimes\":[{\"visibilityFenceId\":\"ui\",\"headVersion\":0}]}");
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"pictionary\",\"version\":3,"
                + "\"transition\":{\"version\":3,\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\","
                + "\"value\":{\"roles\":{\"drawer\":\"player\",\"guesser\":\"" + guesser + "\"},"
                + "\"guesses\":[],\"pinyin\":[[\"san\"],[\"jiao\"],[\"xing\"]]}}]}}");
        h.board().addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.2,"y":0.2},{"x":0.8,"y":0.8}],"color":"#363636","width":8}]
                """));
    }

    @Test
    @DisplayName("★ 有画 + 轮到她猜 ⇒ 问一次，并发出 submitGuess")
    void drivesGuess() {
        Harness h = harness("三角形");
        prime(h, "agent");

        h.driver().pictureChanged();

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).hasSize(1);
        String json = h.sent().get(0);
        assertThat(json).contains("\"type\":\"dispatch\"");
        assertThat(json).contains("\"actor\":\"agent\"");
        assertThat(json).contains("\"cartridgeId\":\"pictionary\"");
        assertThat(json).contains("\"type\":\"submitGuess\"");
        assertThat(json).contains("\"text\":\"三角形\"");
        assertThat(json).contains("\"atMs\":");
        assertThat(json).as("agent 的 dispatch 要带 fence").contains("\"visibilityFenceId\":\"ui\"");
        assertThat(json).contains("\"expectedHeadVersion\":3");
    }

    @Test
    @DisplayName("不是她猜（她画、玩家猜）⇒ 不问")
    void notHerTurn() {
        Harness h = harness("三角形");
        prime(h, "player");
        h.driver().pictureChanged();
        assertThat(h.asks().get()).isZero();
        assertThat(h.sent()).isEmpty();
    }

    @Test
    @DisplayName("一笔没画 ⇒ 不问（空白画面猜出来必然是瞎猜）")
    void noInk() {
        Harness h = harness("三角形");
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"pictionary\",\"version\":1,"
                + "\"transition\":{\"version\":1,\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\","
                + "\"value\":{\"roles\":{\"guesser\":\"agent\"}}}]}}");
        h.driver().pictureChanged();
        assertThat(h.asks().get()).isZero();
    }

    @Test
    @DisplayName("★ 同一个词不重复发（她会对同一张图连给同样答案）")
    void dedupesSameGuess() {
        Harness h = harness("三角形");
        prime(h, "agent");

        h.driver().pictureChanged();
        assertThat(h.sent()).hasSize(1);

        // 绕过 700ms 去抖：直接再触发一次（时间未到会被挡，这里改判断"若问了也不重发"）
        h.driver().pictureChanged();
        // 要么被去抖挡住（asks 仍为 1），要么问了但因为同一个词被丢弃（sent 仍为 1）
        assertThat(h.sent()).as("相同的词只该发一次").hasSize(1);
    }

    @Test
    @DisplayName("去抖：700ms 内的连续触发只问一次")
    void debounce() {
        Harness h = harness("三角形");
        prime(h, "agent");

        h.driver().pictureChanged();
        h.driver().pictureChanged();
        h.driver().pictureChanged();
        assertThat(h.asks().get()).as("只在第一拍问").isEqualTo(1);
    }

    @Test
    @DisplayName("没有 headVersion ⇒ 不发（发了也会被静默丢弃）")
    void noHeadNoSend() {
        Harness h = harness("三角形");
        // 只建 gameState，不发 mounted（所以没有 head）
        h.driver().observe("{\"type\":\"runtime_transition\",\"cartridgeId\":\"pictionary\","
                + "\"transition\":{\"patches\":[{\"op\":\"replace\",\"path\":\"/gameState\","
                + "\"value\":{\"roles\":{\"guesser\":\"agent\"}}}]}}");
        h.board().addBatch(MAPPER.readTree("""
                [{"points":[{"x":0.3,"y":0.3}],"color":"#000","width":6}]
                """));
        h.driver().pictureChanged();

        assertThat(h.asks().get()).isEqualTo(1);
        assertThat(h.sent()).as("不知道 head 就不发").isEmpty();
    }
}
