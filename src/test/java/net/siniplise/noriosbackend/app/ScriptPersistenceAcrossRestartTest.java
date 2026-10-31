package net.siniplise.noriosbackend.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.siniplise.noriosbackend.script.ScriptTable;
import net.siniplise.noriosbackend.store.SqliteScriptStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 重启后状态还在 —— 这就是 store 存在的理由。
 *
 * <p>之前台词闸门只在内存里：进程一重启，所有一次性 beat 都会重说一遍
 * （"欢迎回来，我等你好久了" 每次启动都说）。这个测试就是钉这件事。
 */
class ScriptPersistenceAcrossRestartTest {

    private static ScriptTable fixture() {
        try (var in = ScriptPersistenceAcrossRestartTest.class
                .getResourceAsStream("/script/fixture-beats.json")) {
            return ScriptTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读测试台词表失败", e);
        }
    }

    @Test
    @DisplayName("第一次运行说过 boot.completed；重启后它不该再说")
    void onceBeatSurvivesRestart(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("state.db");

        // 第一次运行
        SqliteScriptStateStore store1 = new SqliteScriptStateStore(db);
        SessionRuntime first = new SessionRuntime("s1", null, null, fixture(), null, store1);
        try {
            assertThat(first.onFact("boot.completed").get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            first.close();
            store1.close();
        }

        // 模拟重启：新 store + 新会话，同一个库
        SqliteScriptStateStore store2 = new SqliteScriptStateStore(db);
        SessionRuntime second = new SessionRuntime("s2", null, null, fixture(), null, store2);
        try {
            assertThat(second.onFact("boot.completed").get(5, TimeUnit.SECONDS))
                    .as("重启后一次性 beat 不能重说").isZero();
        } finally {
            second.close();
            store2.close();
        }
    }

    @Test
    @DisplayName("轮换游标也持久化：重启后从下一句继续，而不是回到第一句")
    void rotationSurvivesRestart(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("state.db");

        SqliteScriptStateStore store1 = new SqliteScriptStateStore(db);
        SessionRuntime first = new SessionRuntime("s1", null, null, fixture(), null, store1);
        try {
            first.onFact("touch.head").get(5, TimeUnit.SECONDS); // 说「一……」
            first.onFact("touch.head").get(5, TimeUnit.SECONDS); // 说「二……」
        } finally {
            first.close();
            store1.close();
        }

        SqliteScriptStateStore store2 = new SqliteScriptStateStore(db);
        SessionRuntime second = new SessionRuntime("s2", null, null, fixture(), null, store2);
        try {
            second.onFact("touch.head").get(5, TimeUnit.SECONDS);
            var lines = second.drainLines().get(5, TimeUnit.SECONDS);
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0).text()).as("重启后应当轮到第三句").isEqualTo("三……");
        } finally {
            second.close();
            store2.close();
        }
    }

    @Test
    @DisplayName("resetStory 也写盘：重建 runtime 后闸门仍是清的")
    void resetIsPersisted(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("state.db");
        SqliteScriptStateStore store = new SqliteScriptStateStore(db);
        SessionRuntime runtime = new SessionRuntime("s", null, null, fixture(), null, store);
        try {
            runtime.onFact("boot.completed").get(5, TimeUnit.SECONDS);
            runtime.resetStory().get(5, TimeUnit.SECONDS);
        } finally {
            runtime.close();
            store.close();
        }

        SqliteScriptStateStore reopened = new SqliteScriptStateStore(db);
        try {
            assertThat(reopened.load().isEmpty()).as("换局后磁盘上也应该是空的").isTrue();
        } finally {
            reopened.close();
        }
    }
}
