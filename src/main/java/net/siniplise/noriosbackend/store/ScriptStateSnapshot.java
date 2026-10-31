package net.siniplise.noriosbackend.store;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 台词状态的持久化快照：说过哪些 beat、轮到第几句、哪些记忆已经写过。
 *
 * <p>三份数据对应 {@code ScriptEngine} 的三个内部表：
 * <ul>
 *   <li>{@code done}：beatId → 首次完成时间（一次性闸门）；</li>
 *   <li>{@code rotation}：beatId → 触发次数（`pick` 轮换游标）；</li>
 *   <li>{@code memoryWritten}：已经建过卡的 beatId（repeatable 的记忆只写一次）。</li>
 * </ul>
 *
 * <p>为什么整份存成 JSON 而不是拆成三张表：这张状态<b>很小</b>（14 个 beat，几 KB），
 * 拆表只会让"读一次、改一处、写回"变成三段事务。整份存 = 天然原子，
 * 也不会有"三个表各写一半"的中间态。
 */
public record ScriptStateSnapshot(
        Map<String, Long> done,
        Map<String, Integer> rotation,
        Set<String> memoryWritten) {

    public ScriptStateSnapshot {
        done = done == null ? Map.of() : Map.copyOf(done);
        rotation = rotation == null ? Map.of() : Map.copyOf(rotation);
        memoryWritten = memoryWritten == null ? Set.of() : Set.copyOf(memoryWritten);
    }

    /** 空状态（新存档）。 */
    public static ScriptStateSnapshot empty() {
        return new ScriptStateSnapshot(Map.of(), Map.of(), Set.of());
    }

    /** 可变的构建器（引擎取快照时用，避免直接暴露内部集合）。 */
    public static ScriptStateSnapshot of(Map<String, Long> done, Map<String, Integer> rotation,
                                         Set<String> memoryWritten) {
        return new ScriptStateSnapshot(new LinkedHashMap<>(done),
                new LinkedHashMap<>(rotation), new LinkedHashSet<>(memoryWritten));
    }

    public boolean isEmpty() {
        return done.isEmpty() && rotation.isEmpty() && memoryWritten.isEmpty();
    }
}
