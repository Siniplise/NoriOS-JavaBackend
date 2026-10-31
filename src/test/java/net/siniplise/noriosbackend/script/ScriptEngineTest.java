package net.siniplise.noriosbackend.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 台词引擎：一次性闸门、repeatable 轮换、只记不说、空词条、换局重置。
 *
 * <p>这些都是"错了不会崩、只会表现成剧情不对"的规则，必须逐条钉死。
 */
class ScriptEngineTest {

    private static ScriptEngine engine() {
        return new ScriptEngine(ScriptTable.loadFromClasspath("/script/fixture-beats.json"));
    }

    @Test
    @DisplayName("一次性 beat：说过就不再触发")
    void onceOnly() {
        ScriptEngine engine = engine();

        List<ScriptEngine.Fire> first = engine.onFact("boot.completed", 1_000);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).lines()).containsExactly("欢迎回来。");
        assertThat(first.get(0).delayMs()).isEqualTo(1_200);
        assertThat(engine.isDone("once_beat")).isTrue();

        assertThat(engine.onFact("boot.completed", 2_000)).as("第二次必须空").isEmpty();
    }

    @Test
    @DisplayName("repeatable：可反复触发，pick 轮换起点")
    void repeatableRotates() {
        ScriptEngine engine = engine();

        assertThat(engine.onFact("touch.head", 1).get(0).lines()).containsExactly("一……");
        assertThat(engine.onFact("touch.head", 2).get(0).lines()).containsExactly("二……");
        assertThat(engine.onFact("touch.head", 3).get(0).lines()).containsExactly("三……");
        assertThat(engine.onFact("touch.head", 4).get(0).lines()).as("绕回第一句").containsExactly("一……");
        assertThat(engine.triggerCount("touch_head")).isEqualTo(4);
        assertThat(engine.isDone("touch_head")).as("repeatable 不落一次性闸门").isFalse();
    }

    @Test
    @DisplayName("repeatable 的 memory 只写第一次")
    void repeatableMemoryOnlyOnce() {
        ScriptEngine engine = engine();

        assertThat(engine.onFact("touch.head", 1).get(0).writeMemory()).isTrue();
        assertThat(engine.onFact("touch.head", 2).get(0).writeMemory()).as("第二次不再写记忆").isFalse();
        assertThat(engine.onFact("touch.head", 3).get(0).writeMemory()).isFalse();
    }

    @Test
    @DisplayName("只记不说：没有台词但有记忆，且落闸")
    void memoryOnlyBeat() {
        ScriptEngine engine = engine();

        List<ScriptEngine.Fire> fires = engine.onFact("arg.seal_released", 1);
        assertThat(fires).hasSize(1);
        assertThat(fires.get(0).lines()).isEmpty();
        assertThat(fires.get(0).writeMemory()).isTrue();
        assertThat(fires.get(0).memory()).isEqualTo("封印解除了。");
        assertThat(engine.isDone("memory_only")).isTrue();

        assertThat(engine.onFact("arg.seal_released", 2)).as("也受一次性闸门约束").isEmpty();
    }

    @Test
    @DisplayName("空词条：不触发、也不落闸（填词后还能用）")
    void emptyBeatIsSkippedWithoutMarking() {
        ScriptEngine engine = engine();

        assertThat(engine.onFact("cult.unpacked", 1)).isEmpty();
        assertThat(engine.isDone("empty_beat")).as("不能落闸，否则填词后永远不说").isFalse();
        assertThat(engine.triggerCount("empty_beat")).isZero();
    }

    @Test
    @DisplayName("换局：清空闸门与轮换，但保留「记忆已写」")
    void resetStoryKeepsMemoryMarks() {
        ScriptEngine engine = engine();
        engine.onFact("boot.completed", 1);
        engine.onFact("touch.head", 1);

        engine.resetStory();

        assertThat(engine.isDone("once_beat")).as("台词该重说").isFalse();
        assertThat(engine.onFact("boot.completed", 2)).hasSize(1);
        assertThat(engine.triggerCount("touch_head")).as("轮换游标也归零").isZero();

        ScriptEngine.Fire again = engine.onFact("touch.head", 3).get(0);
        assertThat(again.lines()).containsExactly("一……");
        assertThat(again.writeMemory()).as("记忆是一次性的知识，不随换局重写").isFalse();
    }

    @Test
    @DisplayName("未知 fact / 空 fact 不炸，返回空")
    void unknownFactIsHarmless() {
        ScriptEngine engine = engine();
        assertThat(engine.onFact("nope.nothing", 1)).isEmpty();
        assertThat(engine.onFact("", 1)).isEmpty();
        assertThat(engine.onFact(null, 1)).isEmpty();
    }

    @Test
    @DisplayName("非属主线程访问直接抛")
    void rejectsForeignThread() throws Exception {
        ScriptEngine engine = engine();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                engine.onFact("boot.completed", 1);
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "http-worker");
        other.start();
        other.join();

        assertThat(caught.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScriptEngine");
    }

    @Test
    @DisplayName("真实台词表：结构自洽（若工作区里有那份 JSON）")
    void realTableIfPresent() {
        Path real = Path.of("C:/Users/sinip/WorkBuddy/NoriOSNew/nori_script_lines.json");
        Assumptions.assumeTrue(Files.isRegularFile(real), "没有真实台词表，跳过");

        ScriptTable table = ScriptTable.load(real);
        assertThat(table.beats()).as("当前是 14 个 beat").hasSize(14);
        assertThat(table.beats()).extracting(ScriptBeat::id).doesNotHaveDuplicates();
        assertThat(table.beats()).allSatisfy(b -> assertThat(b.when()).as(b.id() + " 必须有 when").isNotEmpty());

        ScriptBeat head = table.byId("touch_head");
        assertThat(head).isNotNull();
        assertThat(head.repeatable()).as("摸头可反复触发").isTrue();
        assertThat(head.pick()).as("摸头每次取 1 条").isEqualTo(1);
        assertThat(head.memory()).as("摸头要写记忆").isNotBlank();

        assertThat(table.byId("arg_seal_released").memoryOnly()).as("只记不说").isTrue();
    }
}
