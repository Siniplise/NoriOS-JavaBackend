package net.siniplise.noriosbackend.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 手势编排：门控、广播去重、节流、集齐补发。
 *
 * <p>其中**去重**是实测逼出来的（Q27）：浏览器同时开 4 条 WS 连接，上游把同一条
 * {@code runtime_transition} 复制 N 份下发。不去重的话，一次「她的车吃我的后」
 * 会注入 3 次、播 3 遍台词。
 */
class GestureRuntimeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String WORLD = "world-1";

    /** 记录注入并可选放行/拦截的测试替身。 */
    private static final class Recorder implements GestureRuntime.Injector {
        final List<String> facts = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean inject(String fact, String why) {
            if (!accept) {
                return false;
            }
            facts.add(fact);
            return true;
        }
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    private static JsonNode transition(String eventsJson) {
        return json("{\"events\":" + eventsJson + ",\"patches\":[]}");
    }

    private static final String CHESS_START = """
            {"type":"game_start","playerSide":"white","version":1,"index":0}
            """;
    private static final String CAPTURE_HER_ROOK_MY_QUEEN = """
            {"type":"capture","by":"black","from":{"piece":"r","square":"h8"},
             "captured":{"piece":"q","square":"h1"},"version":2,"index":0}
            """;

    private static GestureRuntime runtime(Recorder rec, boolean qfrInstalled) {
        Set<String> facts = new HashSet<>();
        if (qfrInstalled) {
            facts.add(GestureFacts.QFR_INSTALLED);
        }
        GestureRuntime rt = new GestureRuntime(rec, facts::contains);
        rt.setCartridge("chess");
        return rt;
    }

    // ── 门控 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("① 未装 QFR-9000 ⇒ 判据命中也不注入")
    void blockedWithoutQfr() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, false);

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        var out = rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        assertThat(out.fired()).as("条件成立但被门控拦下").isEmpty();
        assertThat(rec.facts).isEmpty();
    }

    @Test
    @DisplayName("② 装了 QFR-9000 ⇒ 正常注入")
    void firesWithQfr() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        var out = rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        assertThat(out.fired()).hasSize(1);
        assertThat(out.fired().get(0).fact()).isEqualTo(GestureFacts.CHESS);
        assertThat(rec.facts).containsExactly(GestureFacts.CHESS);
    }

    // ── Q27 广播去重 ──────────────────────────────────────────────────

    @Test
    @DisplayName("③ 同一条 transition 被广播 N 次 ⇒ 只处理一次（Q27）")
    void dedupeBroadcastCopies() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);

        JsonNode start = transition("[" + CHESS_START + "]");
        rt.onTransition(WORLD, "chess", start);
        rt.onTransition(WORLD, "chess", start);
        rt.onTransition(WORLD, "chess", start);

        JsonNode capture = transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]");
        var a = rt.onTransition(WORLD, "chess", capture);
        var b = rt.onTransition(WORLD, "chess", capture);
        var c = rt.onTransition(WORLD, "chess", capture);

        assertThat(a.fired()).hasSize(1);
        assertThat(b.fired()).as("第 2 份副本必须丢弃").isEmpty();
        assertThat(c.fired()).as("第 3 份副本必须丢弃").isEmpty();
        assertThat(rec.facts).as("只注入一次，不是三次").containsExactly(GestureFacts.CHESS);
    }

    @Test
    @DisplayName("④ 内容不同但 version 相同的两帧 ⇒ 都要处理（不能只按 version 去重）")
    void differentContentSameVersion() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        rt.setCartridge("codenames");

        // 用「踩浆果」验证：它只累加连击、不产生注入，所以不受 8 秒冷却干扰，
        // 能把「去重键」这一件事单独隔离出来。
        // chess 实测里 startGame 与随后的 fence 都走 v1；只按 version 去重会误杀第二条。
        rt.onTransition(WORLD, "codenames", transition("""
                [{"type":"guess","by":"agent","result":"bystander","version":1}]
                """));
        rt.onTransition(WORLD, "codenames", transition("""
                [{"type":"guess","by":"agent","result":"bystander","version":1,"index":1}]
                """));

        assertThat(rt.berryStreak())
                .as("两帧内容不同 ⇒ 是两条独立事件，连击应累加到 2；若被误杀会是 1")
                .isEqualTo(2);
    }

    // ── 节流 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("⑤ 同一枚 fact 8 秒内不重发（对齐 JS 的 BEAT_TRIES 冷却）")
    void retryCooldown() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        AtomicLong clock = new AtomicLong(1_000_000L);
        rt.setClock(clock::get);

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        // 4 秒后再来一次（内容不同，躲过去重），应被冷却拦下
        clock.addAndGet(4_000);
        var blocked = rt.onTransition(WORLD, "chess", transition("""
                [{"type":"capture","by":"black","from":{"piece":"r"},"captured":{"piece":"q"},"version":7}]
                """));
        assertThat(blocked.fired()).isEmpty();
        assertThat(rec.facts).hasSize(1);

        // 再过 5 秒（累计 9 秒 > 8 秒）⇒ 放行
        clock.addAndGet(5_000);
        var allowed = rt.onTransition(WORLD, "chess", transition("""
                [{"type":"capture","by":"black","from":{"piece":"r"},"captured":{"piece":"q"},"version":8}]
                """));
        assertThat(allowed.fired()).hasSize(1);
        assertThat(rec.facts).hasSize(2);
    }

    // ── 卡带作用域 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("⑥ chess 的 capture 在 cakeduel 卡带下不生效（判据必须限定作用域）")
    void captureIgnoredInOtherCartridge() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        rt.setCartridge("cakeduel");

        var out = rt.onTransition(WORLD, "cakeduel", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));
        assertThat(out.fired()).isEmpty();
        assertThat(rec.facts).isEmpty();
    }

    // ── 集齐补发 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("⑦ 四枚集齐 ⇒ 补发 arg.gestures_complete")
    void completesAfterFour() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        AtomicLong clock = new AtomicLong(1_000_000L);
        rt.setClock(clock::get);

        // ① chess：她的车吃我的后
        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        // ② codenames：连续三次踩浆果
        rt.setCartridge("codenames");
        for (int i = 1; i <= 3; i++) {
            clock.addAndGet(10_000);
            rt.onTransition(WORLD, "codenames", transition("""
                    [{"type":"guess","by":"agent","result":"bystander","version":%d}]
                    """.formatted(i)));
        }

        // ③ cakeduel：狼爵士被弃
        rt.setCartridge("cakeduel");
        rt.setCakeCardList(List.of("soldier", "soldier", "wolfy"));
        clock.addAndGet(10_000);
        rt.onTransition(WORLD, "cakeduel", transition("""
                [{"type":"card_discarded","cardIds":[2],"zone":"block_pile","version":1}]
                """));

        assertThat(rt.gestureCount()).as("前三枚到齐，完成标记还没发").isEqualTo(3);
        assertThat(rec.facts).doesNotContain(GestureFacts.GESTURES_COMPLETE);

        // ④ pictionary：她猜出「水母」（触发点不在事件流，见 firePictionaryGuess）
        rt.setCartridge("pictionary");
        clock.addAndGet(10_000);
        var fired = rt.firePictionaryGuess("水母");

        assertThat(fired).isNotNull();
        assertThat(fired.fact()).isEqualTo(GestureFacts.PICTIONARY);
        assertThat(rt.gestureCount()).isEqualTo(4);
        assertThat(rec.facts)
                .containsExactly(
                        GestureFacts.CHESS, GestureFacts.CODENAMES,
                        GestureFacts.CAKEDUEL, GestureFacts.PICTIONARY,
                        GestureFacts.GESTURES_COMPLETE)
                .as("集齐后必须补发完成标记，且排在最后");
        assertThat(rt.progressText()).isEqualTo("已复现 4/4");
    }

    @Test
    @DisplayName("⑦b 第四枚是「水母灯」⇒ 只有三枚，不发完成标记")
    void notCompleteOnNearMiss() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        AtomicLong clock = new AtomicLong(1_000_000L);
        rt.setClock(clock::get);

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        rt.setCartridge("codenames");
        for (int i = 1; i <= 3; i++) {
            clock.addAndGet(10_000);
            rt.onTransition(WORLD, "codenames", transition("""
                    [{"type":"guess","by":"agent","result":"bystander","version":%d}]
                    """.formatted(i)));
        }

        rt.setCartridge("cakeduel");
        rt.setCakeCardList(List.of("wolfy"));
        clock.addAndGet(10_000);
        rt.onTransition(WORLD, "cakeduel", transition("""
                [{"type":"card_discarded","cardIds":[0],"zone":"block_pile","version":1}]
                """));

        clock.addAndGet(10_000);
        assertThat(rt.firePictionaryGuess("水母灯")).as("「水母灯」不算").isNull();
        assertThat(rt.gestureCount()).isEqualTo(3);
        assertThat(rec.facts).doesNotContain(GestureFacts.PICTIONARY, GestureFacts.GESTURES_COMPLETE);
    }

    @Test
    @DisplayName("⑧ 进度文案与 bundle 的 toast 一致")
    void progressText() {
        Recorder rec = new Recorder();
        GestureRuntime rt = runtime(rec, true);
        assertThat(rt.progressText()).isEqualTo("已复现 0/4");

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));
        assertThat(rt.progressText()).isEqualTo("已复现 1/4");
    }

    // ── 注入失败不能打死调用方 ─────────────────────────────────────────

    @Test
    @DisplayName("⑨ 注入器返回 false（无上游连接）⇒ 不记账、不抛异常")
    void injectorFailureIsNotFatal() {
        Recorder rec = new Recorder();
        rec.accept = false;
        GestureRuntime rt = runtime(rec, true);

        rt.onTransition(WORLD, "chess", transition("[" + CHESS_START + "]"));
        var out = rt.onTransition(WORLD, "chess", transition("[" + CAPTURE_HER_ROOK_MY_QUEEN + "]"));

        assertThat(out.fired()).isEmpty();
        assertThat(rt.gestureCount()).isZero();
    }

    // ── 线程所有权 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("⑩ 非属主线程改状态 ⇒ 直接抛（把并发 bug 变成确定性错误）")
    void threadGuard() throws Exception {
        GestureRuntime rt = runtime(new Recorder(), true);
        var ref = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try {
                rt.setCartridge("chess");
            } catch (Throwable t) {
                ref.set(t);
            }
        });
        other.start();
        other.join();

        assertThat(ref.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("属主线程");
    }
}
