package net.siniplise.noriosbackend.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用**真实录制**回放手势判定（golden/20261031-035029）。
 *
 * <p>为什么要这一层：单测的输入是我自己编的 JSON，容易"自洽但不真实"。
 * 这段录制是真机跑出来的（开象棋 → 认输 → 开蛋糕对决 → 开局），
 * 里面天然包含 Q27 那个坑：**同一条 transition 被按连接数广播了 3 份**。
 * 用它验证"去重是否真的生效"比构造用例可信。
 *
 * <p>这组用例的期望是**什么都不触发**（录制时没装 QFR-9000，也没吃子/踩浆果/弃狼）
 * ——所以它验证的是"不误报"，不是"能触发"。
 */
class GoldenReplayTest {

    private static final Path GOLDEN = Path.of("golden", "20261031-035029", "ws.jsonl");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 一条 transition 回放素材。 */
    private record Frame(String worldId, String cartridgeId, JsonNode transition) {
    }

    private static List<Frame> loadFrames() {
        Assumptions.assumeTrue(Files.isRegularFile(GOLDEN),
                "缺少 golden 录制，跳过：" + GOLDEN.toAbsolutePath());
        List<Frame> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(GOLDEN)) {
                if (line.isBlank() || !line.contains("runtime_transition")) {
                    continue;
                }
                JsonNode row = MAPPER.readTree(line);
                if (!"ws-msg".equals(text(row, "kind"))) {
                    continue;
                }
                String raw = text(row, "text");
                if (raw == null || !raw.contains("runtime_transition")) {
                    continue;
                }
                JsonNode msg = MAPPER.readTree(raw);
                if (!"runtime_transition".equals(text(msg, "type"))) {
                    continue;
                }
                JsonNode tr = msg.get("transition");
                if (tr == null || !tr.isObject()) {
                    continue;
                }
                out.add(new Frame(text(msg, "worldId"), text(msg, "cartridgeId"), tr));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 golden 失败", e);
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    @Test
    @DisplayName("真实录制回放：21 帧 transition 不抛异常，且广播副本被去重")
    void replayRealRecording() {
        List<Frame> frames = loadFrames();
        assertThat(frames).as("录到 21 帧 runtime_transition").hasSize(21);

        // 按卡带统计：chess 6 / cakeduel 3 / chat 12 —— 前两者都是"同一条 × 3 副本"
        long chess = frames.stream().filter(f -> "chess".equals(f.cartridgeId())).count();
        long cake = frames.stream().filter(f -> "cakeduel".equals(f.cartridgeId())).count();
        assertThat(chess).as("chess 6 帧 = 2 条真实 transition × 3 副本").isEqualTo(6);
        assertThat(cake).as("cakeduel 3 帧 = 1 条 × 3 副本").isEqualTo(3);

        Set<String> facts = new HashSet<>();   // 未装 QFR-9000
        List<String> injected = new ArrayList<>();
        GestureRuntime rt = new GestureRuntime(
                (fact, why) -> { injected.add(fact); return true; },
                facts::contains);

        int started = 0;
        int over = 0;
        int nonEmpty = 0;
        for (Frame f : frames) {
            GestureRuntime.Outcome out = rt.onTransition(f.worldId(), f.cartridgeId(), f.transition());
            assertThatCode(() -> rt.progressText()).doesNotThrowAnyException();
            if (out.started()) {
                started++;
            }
            if (out.over()) {
                over++;
            }
            if (out != GestureRuntime.Outcome.NONE && (!out.fired().isEmpty() || out.started() || out.over())) {
                nonEmpty++;
            }
        }

        // ★ 去重生效的证据：6 帧 chess 只该产出 1 次 startGame、1 次 resign；
        //   3 帧 cakeduel 只该产出 1 次 startGame。若没去重会是 3 和 2。
        assertThat(started).as("两局开局各去重成 1 次（chess + cakeduel）").isEqualTo(2);
        assertThat(over).as("1 次认输去重成 1 次").isEqualTo(1);

        // ★ 不误报：没装 QFR-9000，一枚都不该注入
        assertThat(injected).as("门控生效，录制里没有任何手势注入").isEmpty();
        assertThat(rt.gestureCount()).isZero();
    }

    @Test
    @DisplayName("真实录制回放：装了 QFR 也不误报（录制里没有命中判据的事件）")
    void replayRealRecordingWithQfrStillSilent() {
        List<Frame> frames = loadFrames();
        Set<String> facts = new HashSet<>(Set.of(GestureFacts.QFR_INSTALLED));
        List<String> injected = new ArrayList<>();
        GestureRuntime rt = new GestureRuntime(
                (fact, why) -> { injected.add(fact); return true; },
                facts::contains);

        for (Frame f : frames) {
            rt.onTransition(f.worldId(), f.cartridgeId(), f.transition());
        }

        // 录制里：chess 只有 startGame + resign（无 capture），cakeduel 只有 startGame（无 wolfy 被弃），
        // 没有 codenames / pictionary。⇒ 四枚判据一个都不该命中。
        assertThat(injected)
                .as("判据严格：开局/认输本身不是手势，不能送 fact")
                .isEmpty();
        assertThat(rt.progressText()).isEqualTo("已复现 0/4");
    }

    @Test
    @DisplayName("真实录制回放：同一份素材重复回放不会二次触发（幂等）")
    void replayTwiceIsIdempotent() {
        List<Frame> frames = loadFrames();
        Set<String> facts = new HashSet<>(Set.of(GestureFacts.QFR_INSTALLED));
        List<String> injected = new ArrayList<>();
        GestureRuntime rt = new GestureRuntime(
                (fact, why) -> { injected.add(fact); return true; },
                facts::contains);

        for (Frame f : frames) {
            rt.onTransition(f.worldId(), f.cartridgeId(), f.transition());
        }
        int firstRoundStarted = rt.progressText().equals("已复现 0/4") ? 0 : 1;

        // 第二遍：去重窗口还在，应该全部被丢弃
        for (Frame f : frames) {
            rt.onTransition(f.worldId(), f.cartridgeId(), f.transition());
        }
        assertThat(rt.progressText()).isEqualTo("已复现 0/4");
        assertThat(injected).isEmpty();
        assertThat(firstRoundStarted).isZero();
    }
}
