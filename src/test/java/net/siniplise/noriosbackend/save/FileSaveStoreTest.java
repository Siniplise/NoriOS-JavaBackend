package net.siniplise.noriosbackend.save;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.siniplise.noriosbackend.store.ScriptStateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 存档：往返、槽位边界、备份、坏档兜底。
 *
 * <p>存档是「丢了就真丢了」的东西，所以这里对**写入路径**特别苛刻：
 * 越界不静默覆盖、覆盖前必须留 .bak、坏档不能让列表整个挂掉。
 */
class FileSaveStoreTest {

    private static SaveGame sample(int slot) {
        return SaveGame.of(
                slot, "0.1.0-SNAPSHOT", "http://127.0.0.1:4174", "9f5df6c4c4", "Nori.Web.noai.exe",
                new SaveGame.Session("joined", "chat", false, 3,
                        List.of("session.ready", "boot.completed")),
                SaveGame.Script.from(ScriptStateSnapshot.of(
                        Map.of("s1_desktop", 111L), Map.of("touch_head", 2), Set.of("touch_head"))),
                new SaveGame.Speech(List.of(
                        new SaveGame.Speech.PendingLine("q1", 0, 2, "第一句", "happy")), 7),
                new SaveGame.PersonaRef("25503", "sha256:abc", "记忆本体在人格服务里"));
    }

    @Test
    @DisplayName("往返：存进去再读出来，关键字段一致")
    void roundTrip(@TempDir Path dir) {
        FileSaveStore store = new FileSaveStore(dir, 20);
        store.save(sample(3));

        SaveGame loaded = store.load(3).orElseThrow();
        assertThat(loaded.schemaVersion()).isEqualTo(SaveGame.CURRENT_SCHEMA);
        assertThat(loaded.slot()).isEqualTo(3);
        assertThat(loaded.session().phase()).isEqualTo("joined");
        assertThat(loaded.session().facts()).containsExactly("session.ready", "boot.completed");
        assertThat(loaded.script().done()).containsEntry("s1_desktop", 111L);
        assertThat(loaded.script().rotation()).containsEntry("touch_head", 2);
        assertThat(loaded.speech().pending()).hasSize(1);
        assertThat(loaded.speech().epoch()).isEqualTo(7);
        assertThat(loaded.persona().provider()).isEqualTo("25503");
        assertThat(loaded.savedAt()).isNotBlank();
    }

    @Test
    @DisplayName("槽位越界：直接拒绝，不静默覆盖别的槽")
    void rejectsOutOfRangeSlot(@TempDir Path dir) {
        FileSaveStore store = new FileSaveStore(dir, 3);
        assertThatThrownBy(() -> store.save(sample(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("槽位超出范围");
        assertThat(store.load(4)).isEmpty();
    }

    @Test
    @DisplayName("覆盖前留 .bak：第二次存档后还能捡回上一版")
    void keepsBackup(@TempDir Path dir) {
        FileSaveStore store = new FileSaveStore(dir, 5);
        store.save(sample(1));
        String first = read(dir.resolve("slot-1.json"));

        store.save(sample(1));
        Path backup = dir.resolve("slot-1.bak");
        assertThat(Files.isRegularFile(backup)).as("覆盖前必须备份").isTrue();
        assertThat(read(backup)).as("备份的内容应当是上一版").isEqualTo(first);
    }

    @Test
    @DisplayName("坏档：返回 empty，列表不崩")
    void corruptedFileIsIgnored(@TempDir Path dir) throws Exception {
        FileSaveStore store = new FileSaveStore(dir, 5);
        store.save(sample(1));
        Files.writeString(dir.resolve("slot-2.json"), "{ broken", StandardCharsets.UTF_8);

        assertThat(store.load(2)).isEmpty();
        assertThat(store.list()).extracting(FileSaveStore.SlotInfo::slot).contains(1, 2);
    }

    @Test
    @DisplayName("列表：按槽位升序，带字节数与阶段")
    void listsSlots(@TempDir Path dir) {
        FileSaveStore store = new FileSaveStore(dir, 20);
        store.save(sample(2));
        store.save(sample(1));

        assertThat(store.list()).extracting(FileSaveStore.SlotInfo::slot).containsExactly(1, 2);
        assertThat(store.list().get(0).phase()).isEqualTo("joined");
        assertThat(store.list().get(0).bytes()).isGreaterThan(0);
        assertThat(store.lastSavedAt()).isNotNull();
    }

    @Test
    @DisplayName("不存在的槽位：empty，不抛")
    void missingSlot(@TempDir Path dir) {
        assertThat(new FileSaveStore(dir, 20).load(9)).isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
