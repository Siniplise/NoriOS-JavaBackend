package net.siniplise.noriosbackend.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.siniplise.noriosbackend.runtime.ThreadGuard;

/**
 * 台词引擎：fact → 该说哪几句。
 *
 * <p>四条语义全部来自台词表 `_meta` 的说明与旧栈行为：
 * <ol>
 *   <li><b>一次性闸门</b>：普通 beat 说过就不再触发（`done` 表）；</li>
 *   <li><b>repeatable 跳过闸门</b>：交互类（摸头/摸尾）可反复触发，但 `memory` **只写第一次**；</li>
 *   <li><b>`pick` 轮换</b>：每次从 `lines` 里取 n 条，起点按触发次数推进（"不要每次都一样"）；</li>
 *   <li><b>空词条不落闸</b>：`lines` 与 `memory` 都空的 beat 直接跳过，且**不标记完成**——
 *       否则填词之后它永远不会说话，还得手工清状态。</li>
 * </ol>
 *
 * <p><b>线程</b>：可变状态（done / rotation / memWritten）只允许属主线程访问
 * （{@link ThreadGuard}），实际就是会话线程。外面要触发台词，投递进会话线程再说——
 * 这也是 Q2「beat 状态并发写」的解法：状态只有一双手在动。
 *
 * <p>**这里不 sleep**。`delayMs` / `sentinel` 原样带出去交给编排层；
 * 在会话线程里 sleep 会把整条语音链路堵死。
 */
public final class ScriptEngine {

    /** 一次触发的结果。 */
    public record Fire(
            ScriptBeat beat,
            List<String> lines,
            int lineGapMs,
            int delayMs,
            String sentinel,
            String memory,
            double memoryImportance) {

        /** 这次是否要写记忆（repeatable 只有第一次为 true）。 */
        public boolean writeMemory() {
            return memory != null;
        }
    }

    private final ThreadGuard guard = new ThreadGuard("ScriptEngine");
    private final ScriptTable table;

    /** beatId → 首次完成时间（只用于排障/观测，判断"说过没"只看 key 是否存在）。 */
    private final Map<String, Long> done = new LinkedHashMap<>();
    /** beatId → 已触发次数（`pick` 轮换的游标）。 */
    private final Map<String, Integer> rotation = new LinkedHashMap<>();
    /** 已经写过记忆的 beatId（repeatable 的 memory 只写一次）。 */
    private final Set<String> memoryWritten = new LinkedHashSet<>();

    public ScriptEngine(ScriptTable table) {
        this.table = table == null ? ScriptTable.empty() : table;
    }

    public ScriptTable table() {
        return table;
    }

    /** 所有权交接（交给会话线程）。 */
    public void ownedByCurrentThread() {
        guard.claim();
    }

    /**
     * 一个 fact 到达：返回该触发的 beat（可能要触发多条，按表内顺序）。
     *
     * <p>返回的列表为空是常态（大多数 fact 不挂 beat），不是错误。
     */
    public List<Fire> onFact(String fact, long nowMs) {
        guard.assertOwner();
        if (fact == null || fact.isBlank()) {
            return List.of();
        }
        List<Fire> fires = new ArrayList<>();
        for (ScriptBeat beat : table.forFact(fact)) {
            fire(beat, nowMs).ifPresent(fires::add);
        }
        return List.copyOf(fires);
    }

    private Optional<Fire> fire(ScriptBeat beat, long nowMs) {
        if (beat.empty()) {
            // 等填词：不触发、不标记完成
            return Optional.empty();
        }
        if (!beat.repeatable() && done.containsKey(beat.id())) {
            return Optional.empty();
        }

        int count = rotation.getOrDefault(beat.id(), 0);
        List<String> picked = pick(beat, count);

        // 只有真的说了什么才算"用掉一次"：memoryOnly 的 beat 没有台词，但也要落闸
        if (!beat.repeatable()) {
            done.putIfAbsent(beat.id(), nowMs);
        }
        rotation.put(beat.id(), count + 1);

        String memory = beat.memory();
        if (memory != null) {
            // repeatable 的记忆只写第一次；非 repeatable 本来就只会触发一次
            if (!memoryWritten.add(beat.id())) {
                memory = null;
            }
        }

        return Optional.of(new Fire(beat, picked, beat.lineGapMs(), beat.delayMs(),
                beat.sentinel(), memory, beat.memoryImportance()));
    }

    /**
     * 按 `pick` 取词并轮换起点。
     *
     * <p>`pick` 缺省 = 全部（普通 beat 一次说完）；`pick >= lines.size()` 也是全部。
     * 起点用「已触发次数 % 句数」，所以第 2 次触发会从第二句开始，绕回后继续轮换。
     */
    private static List<String> pick(ScriptBeat beat, int triggerCount) {
        List<String> lines = beat.lines();
        if (lines.isEmpty()) {
            return List.of();
        }
        int want = beat.pick() == null ? lines.size() : Math.min(beat.pick(), lines.size());
        int start = triggerCount % lines.size();
        List<String> out = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            out.add(lines.get((start + i) % lines.size()));
        }
        return List.copyOf(out);
    }

    /**
     * 新一局：清空一次性闸门与轮换游标，**保留**"记忆已写"标记。
     *
     * <p>为什么保留：新一局里台词该重说（玩家想再听一遍），
     * 但"她已经知道这件事"不该被重复写进长期记忆——记忆是一次性的知识，不是播放记录。
     */
    public void resetStory() {
        guard.assertOwner();
        done.clear();
        rotation.clear();
    }

    /** 观测用：说过多少个 beat。 */
    public int doneCount() {
        guard.assertOwner();
        return done.size();
    }

    /** 观测用：某个 beat 触发过几次。 */
    public int triggerCount(String beatId) {
        guard.assertOwner();
        return rotation.getOrDefault(beatId, 0);
    }

    /** 观测用：该 beat 是否已落闸。 */
    public boolean isDone(String beatId) {
        guard.assertOwner();
        return done.containsKey(beatId);
    }
}
