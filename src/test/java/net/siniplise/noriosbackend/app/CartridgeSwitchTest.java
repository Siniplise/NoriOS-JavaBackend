package net.siniplise.noriosbackend.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.siniplise.noriosbackend.persona.PersonaClient;
import net.siniplise.noriosbackend.persona.ScriptLineWrite;
import net.siniplise.noriosbackend.script.ScriptTable;
import net.siniplise.noriosbackend.speech.SpeechQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 换局（`cartridge_unmounted`）的落地行为。
 *
 * <p>旧栈的语义：「**当前句说完，其余丢弃**」，且只有真的丢了才通报人格。
 * 这两条错了都不会报错——一个表现为"退出游戏后她还在念上局的台词"，
 * 另一个表现为"记忆里堆满截断备注"。
 */
class CartridgeSwitchTest {

    private static ScriptTable fixture() throws IOException {
        try (var in = CartridgeSwitchTest.class.getResourceAsStream("/script/fixture-beats.json")) {
            return ScriptTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static final class RecordingPersona implements PersonaClient {
        final List<String> notes = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> writeScriptLine(ScriptLineWrite write) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> gameNote(String game, int dropped) {
            notes.add(game + "/" + dropped);
            return Mono.empty();
        }

        @Override
        public Mono<String> state() {
            return Mono.just("{}");
        }
    }

    @Test
    @DisplayName("卸载卡带：当前句保留、其余丢弃，并通报丢弃句数")
    void unmountSoftResetsQueue() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            // 入队三句，开始播第一句
            runtime.enqueue(new SpeechQueue.Line("q1", 0, 3, "第一句", "neutral")).get(5, TimeUnit.SECONDS);
            runtime.enqueue(new SpeechQueue.Line("q1", 1, 3, "第二句", "neutral")).get(5, TimeUnit.SECONDS);
            runtime.enqueue(new SpeechQueue.Line("q1", 2, 3, "第三句", "neutral")).get(5, TimeUnit.SECONDS);
            runtime.startNextLine().get(5, TimeUnit.SECONDS);

            boolean switched = runtime.observeFrame(
                    "{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chess\"}").get(5, TimeUnit.SECONDS);

            assertThat(switched).as("应当识别为换局").isTrue();
            assertThat(persona.notes).as("丢弃了 2 句，需要通报").containsExactly("chess/2");

            // 待播队列已清空（当前句仍在播，不是 pending）
            assertThat(runtime.drainLines().get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("队列本来是空的：不通报（避免往记忆里塞无意义的备注）")
    void emptyQueueDoesNotNotify() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            runtime.observeFrame("{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chat\"}")
                    .get(5, TimeUnit.SECONDS);
            assertThat(persona.notes).isEmpty();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("同一个卸载帧重复到达：只处理一次")
    void repeatedUnmountIsIdempotent() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            runtime.enqueue(new SpeechQueue.Line("q1", 0, 2, "甲", "neutral")).get(5, TimeUnit.SECONDS);
            runtime.enqueue(new SpeechQueue.Line("q1", 1, 2, "乙", "neutral")).get(5, TimeUnit.SECONDS);

            String frame = "{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chess\"}";
            assertThat(runtime.observeFrame(frame).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.observeFrame(frame).get(5, TimeUnit.SECONDS))
                    .as("第二次不该再算一次换局").isFalse();
            assertThat(persona.notes).hasSize(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("挂载新卡带：记录 cartridge，但不触发换局")
    void mountRecordsCartridge() throws Exception {
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), null);
        try {
            assertThat(runtime.observeFrame(
                    "{\"type\":\"cartridge_mounted\",\"cartridgeId\":\"codenames\"}").get(5, TimeUnit.SECONDS))
                    .isFalse();
            var snapshot = runtime.snapshot().get(5, TimeUnit.SECONDS);
            assertThat(snapshot.cartridge()).isEqualTo("codenames");
            assertThat(snapshot.ended()).isFalse();
        } finally {
            runtime.close();
        }
    }
}
