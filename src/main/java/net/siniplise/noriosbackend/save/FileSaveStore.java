package net.siniplise.noriosbackend.save;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 文件式存档：`<dir>/slot-NN.json`，写盘一律「临时文件 + 原子替换」。
 *
 * <p>三条纪律（都是踩出来的）：
 * <ol>
 *   <li><b>先备份再覆盖</b>：旧档复制成 {@code .bak}，覆盖失败还能捡回来；</li>
 *   <li><b>临时文件 + {@code ATOMIC_MOVE}</b>：中途断电不会留下半个 JSON——
 *       半个 JSON 的存档比没有存档更糟，因为读的时候才发现；</li>
 *   <li><b>读坏了返回 empty 而不是抛</b>：一个坏档不该让"读列表"整个失败。</li>
 * </ol>
 *
 * <p>槽位上限来自配置 `save.maxSlots`；超范围直接拒绝，**不做静默覆盖**——
 * 静默覆盖是丢档最常见的原因。
 */
@Component
public class FileSaveStore {

    private static final Logger log = LoggerFactory.getLogger(FileSaveStore.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PREFIX = "slot-";
    private static final String SUFFIX = ".json";

    private final Path dir;
    private final int maxSlots;

    @org.springframework.beans.factory.annotation.Autowired
    public FileSaveStore(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        this(Path.of(configProvider.getIfAvailable(ConfigLoader::load).config().save().dir()),
                configProvider.getIfAvailable(ConfigLoader::load).config().save().maxSlots());
    }

    public FileSaveStore(Path dir, int maxSlots) {
        this.dir = dir;
        this.maxSlots = Math.max(1, maxSlots);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("建不了存档目录: " + dir, e);
        }
        log.info("save: 目录 {}（最多 {} 槽）", dir.toAbsolutePath(), this.maxSlots);
    }

    /** 槽位是否合法（1..maxSlots）。 */
    public boolean validSlot(int slot) {
        return slot >= 1 && slot <= maxSlots;
    }

    public int maxSlots() {
        return maxSlots;
    }

    /** 写入一个槽位（原子替换 + 先备份）。 */
    public void save(SaveGame game) {
        if (game == null) {
            throw new IllegalArgumentException("存档不能为空");
        }
        if (!validSlot(game.slot())) {
            throw new IllegalArgumentException("槽位超出范围 1.." + maxSlots + ": " + game.slot());
        }
        Path target = pathFor(game.slot());
        Path tmp = dir.resolve(PREFIX + game.slot() + ".tmp");
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(game);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            if (Files.isRegularFile(target)) {
                Path backup = dir.resolve(PREFIX + game.slot() + ".bak");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            // ATOMIC_MOVE：要么旧档、要么新档，不会出现半个文件
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("save: 已写入槽位 {}（{} 字节）", game.slot(), Files.size(target));
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 删不掉临时文件不影响正确性
            }
            throw new IllegalStateException("写存档失败: " + target, e);
        }
    }

    /** 读一个槽位；不存在或读坏都返回 empty。 */
    public Optional<SaveGame> load(int slot) {
        if (!validSlot(slot)) {
            return Optional.empty();
        }
        Path target = pathFor(slot);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            SaveGame game = MAPPER.readValue(json, SaveGame.class);
            if (game == null || !game.readableByThisVersion()) {
                log.warn("save: 槽位 {} 的结构版本不认识（schema={}），已忽略",
                        slot, game == null ? "null" : game.schemaVersion());
                return Optional.empty();
            }
            return Optional.of(game);
        } catch (IOException | JacksonException e) {
            log.error("save: 读槽位 {} 失败（返回空，不阻断列表）: {}", slot, e.toString());
            return Optional.empty();
        }
    }

    /** 槽位概览（给管理端/排障用），按槽位号升序。 */
    public List<SlotInfo> list() {
        List<SlotInfo> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(PREFIX)
                            && p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        int slot = Integer.parseInt(name.substring(PREFIX.length(),
                                name.length() - SUFFIX.length()));
                        SaveGame game = load(slot).orElse(null);
                        try {
                            out.add(new SlotInfo(slot, Files.size(p),
                                    game == null ? null : game.savedAt(),
                                    game == null ? null : game.session().phase()));
                        } catch (IOException e) {
                            out.add(new SlotInfo(slot, 0, null, null));
                        }
                    });
        } catch (IOException e) {
            log.error("save: 列目录失败: {}", e.toString());
        }
        out.sort(Comparator.comparingInt(SlotInfo::slot));
        return out;
    }

    /** 最近一次存档的保存时间（没有则 null）。 */
    public Instant lastSavedAt() {
        return list().stream()
                .map(SlotInfo::savedAt)
                .filter(java.util.Objects::nonNull)
                .map(Instant::parse)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 槽位概要。 */
    public record SlotInfo(int slot, long bytes, String savedAt, String phase) {
    }

    private Path pathFor(int slot) {
        return dir.resolve(PREFIX + slot + SUFFIX);
    }
}
