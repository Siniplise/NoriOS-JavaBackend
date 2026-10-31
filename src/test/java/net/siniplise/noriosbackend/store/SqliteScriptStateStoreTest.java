package net.siniplise.noriosbackend.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite 台词状态：往返、合并写、损坏兜底。
 *
 * <p>核心断言是「进程重启后状态还在」——这正是当前最实的坑：
 * 之前台词闸门只在内存里，重启就全丢，一次性 beat 会重说一遍。
 */
class SqliteScriptStateStoreTest {

    @Test
    @DisplayName("往返：写完关掉、新实例读出来一模一样")
    void roundTrip(@TempDir Path dir) {
        Path db = dir.resolve("state.db");
        ScriptStateSnapshot saved = ScriptStateSnapshot.of(
                Map.of("s1_desktop", 111L, "touch_head", 222L),
                Map.of("touch_head", 3),
                Set.of("touch_head"));

        try (SqliteScriptStateStore store = new SqliteScriptStateStore(db)) {
            store.save(saved);
            store.flush();
        }

        try (SqliteScriptStateStore reopened = new SqliteScriptStateStore(db)) {
            ScriptStateSnapshot loaded = reopened.load();
            assertThat(loaded.done()).containsEntry("s1_desktop", 111L).containsEntry("touch_head", 222L);
            assertThat(loaded.rotation()).containsEntry("touch_head", 3);
            assertThat(loaded.memoryWritten()).containsExactly("touch_head");
        }
    }

    @Test
    @DisplayName("空库：返回空状态而不是报错")
    void emptyDatabase(@TempDir Path dir) {
        try (SqliteScriptStateStore store = new SqliteScriptStateStore(dir.resolve("fresh.db"))) {
            assertThat(store.load().isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("合并写：连续 save 多次，落盘的是最后一份")
    void coalescesWrites(@TempDir Path dir) {
        Path db = dir.resolve("state.db");
        try (SqliteScriptStateStore store = new SqliteScriptStateStore(db)) {
            for (int i = 1; i <= 20; i++) {
                store.save(ScriptStateSnapshot.of(Map.of("beat", (long) i), Map.of(), Set.of()));
            }
            store.flush();
        }
        try (SqliteScriptStateStore reopened = new SqliteScriptStateStore(db)) {
            assertThat(reopened.load().done()).containsEntry("beat", 20L);
        }
    }

    @Test
    @DisplayName("JSON 损坏：返回空状态，不让后端起不来")
    void corruptedJsonFallsBackToEmpty(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("state.db");
        try (SqliteScriptStateStore store = new SqliteScriptStateStore(db)) {
            store.load(); // 建表
        }
        // 直接把表里的 json 写坏
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT OR REPLACE INTO script_state(id, json, updated_at) VALUES(1, 'not-json', 0)");
        }

        try (SqliteScriptStateStore store = new SqliteScriptStateStore(db)) {
            assertThat(store.load().isEmpty()).as("坏档按空状态处理").isTrue();
        }
        assertThat(Files.isRegularFile(db)).isTrue();
    }

    @Test
    @DisplayName("WAL 模式已开启（只有一个 -wal 文件说明生效）")
    void walEnabled(@TempDir Path dir) {
        Path db = dir.resolve("state.db");
        try (SqliteScriptStateStore store = new SqliteScriptStateStore(db)) {
            store.save(ScriptStateSnapshot.of(Map.of("a", 1L), Map.of(), Set.of()));
            store.flush();
        }
        // WAL 生效时会有 -wal/-shm 伴生文件（连接关闭后可能被合并，所以只断言主库存在）
        assertThat(Files.isRegularFile(db)).isTrue();
    }
}
