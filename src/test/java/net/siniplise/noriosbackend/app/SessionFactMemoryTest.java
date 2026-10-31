package net.siniplise.noriosbackend.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.siniplise.noriosbackend.persona.PersonaClient;
import net.siniplise.noriosbackend.persona.ScriptLineWrite;
import net.siniplise.noriosbackend.script.ScriptTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * fact → 台词入队 → 记忆旁路写入 的组合行为。
 *
 * <p>要钉住的两条：
 * <ol>
 *   <li>首次触发带 memory（建卡），重复触发必须 `transient`（只上屏）——
 *       否则摸 20 次头会往长期记忆塞 20 张几乎一样的卡；</li>
 *   <li>记忆写入失败**不能**影响台词入队——旁路就是旁路。</li>
 * </ol>
 */
class SessionFactMemoryTest {

    private static ScriptTable fixture() throws IOException {
        String json = new String(SessionFactMemoryTest.class
                .getResourceAsStream("/script/fixture-beats.json").readAllBytes(), StandardCharsets.UTF_8);
        return ScriptTable.parse(json);
    }

    /** 记录所有写入；可选地让写入失败。 */
    private static final class RecordingPersona implements PersonaClient {
        final List<ScriptLineWrite> writes = new CopyOnWriteArrayList<>();
        boolean failNext;

        @Override
        public Mono<Void> writeScriptLine(ScriptLineWrite write) {
            writes.add(write);
            return failNext ? Mono.error(new IllegalStateException("persona down")) : Mono.empty();
        }

        @Override
        public Mono<Void> gameNote(String game, int dropped) {
            return Mono.empty();
        }

        @Override
        public Mono<String> state() {
            return Mono.just("{}");
        }
    }

    @Test
    @DisplayName("首次摸头：入队台词 + 写记忆（非 transient）；第二次：只上屏（transient）")
    void firstWritesMemoryThenTransient() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            assertThat(runtime.onFact("touch.head").get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(persona.writes).hasSize(1);
            ScriptLineWrite first = persona.writes.get(0);
            assertThat(first.beat()).isEqualTo("touch_head");
            assertThat(first.memory()).as("首次必须带 memory 才能建卡").isNotBlank();
            assertThat(first.transientOnly()).isFalse();

            assertThat(runtime.onFact("touch.head").get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(persona.writes).hasSize(2);
            ScriptLineWrite second = persona.writes.get(1);
            assertThat(second.transientOnly()).as("重复触发只上屏").isTrue();
            assertThat(second.memory()).isNull();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("记忆写入失败：台词照样入队（旁路不影响主链路）")
    void memoryFailureDoesNotBlockLines() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        persona.failNext = true;
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            int queued = runtime.onFact("touch.head").get(5, TimeUnit.SECONDS);
            assertThat(queued).as("台词必须已经入队").isEqualTo(1);

            // 入队的台词确实能被 /nori-line 取到
            List<?> lines = runtime.drainLines().get(5, TimeUnit.SECONDS);
            assertThat(lines).hasSize(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("只记不说的一次性 beat：写一次记忆、不入队台词，第二次无动作")
    void memoryOnlyBeatWritesOnce() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            assertThat(runtime.onFact("arg.seal_released").get(5, TimeUnit.SECONDS))
                    .as("只有 memory、没有 lines ⇒ 不入队").isZero();
            assertThat(persona.writes).hasSize(1);
            assertThat(persona.writes.get(0).memory()).isEqualTo("封印解除了。");
            assertThat(persona.writes.get(0).transientOnly()).isFalse();

            assertThat(runtime.onFact("arg.seal_released").get(5, TimeUnit.SECONDS)).isZero();
            assertThat(persona.writes).as("一次性闸门：只写一次").hasSize(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("没有 memory 的一次性 beat：不写记忆，只入队台词")
    void beatWithoutMemoryWritesNothing() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            assertThat(runtime.onFact("boot.completed").get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(runtime.onFact("boot.completed").get(5, TimeUnit.SECONDS)).isZero();
            assertThat(persona.writes).as("fixture 里 once_beat 没有 memory ⇒ 不该有写入").isEmpty();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("换局后：台词重说，但记忆不再重写")
    void resetStoryRepeatsLinesNotMemory() throws Exception {
        RecordingPersona persona = new RecordingPersona();
        SessionRuntime runtime = new SessionRuntime("test", null, null, fixture(), persona);
        try {
            runtime.onFact("touch.head").get(5, TimeUnit.SECONDS);
            runtime.resetStory().get(5, TimeUnit.SECONDS);
            runtime.onFact("touch.head").get(5, TimeUnit.SECONDS);

            assertThat(persona.writes).hasSize(2);
            assertThat(persona.writes.get(1).transientOnly())
                    .as("换局后重说的这一句仍然只是上屏，不该再建卡").isTrue();
        } finally {
            runtime.close();
        }
    }
}
