package net.siniplise.noriosbackend.script;

import java.util.List;

/**
 * 一条 beat（台词事件）——对应 `nori_script_lines.json` 里 `beats[]` 的一项。
 *
 * <p>字段语义（来自表里的 `_meta` 说明，不是猜的）：
 * <ul>
 *   <li>{@code when}：触发它的 fact 名；一个 beat 可以挂多个 fact；</li>
 *   <li>{@code repeatable}：交互类（摸头/摸尾）可反复触发，不受 `done` 一次性闸门限制；</li>
 *   <li>{@code pick}：每次从 `lines` 里取几条，**按触发次数轮换起点**（V4.4：「不要每次都一样」）；</li>
 *   <li>{@code memory}：写进长期记忆的一句话；repeatable 的**只写第一次**，
 *       否则反复摸头会往记忆里塞一堆重复卡；</li>
 *   <li>{@code lines} 与 {@code memory} 都为空 ⇒ 该 beat 不触发（等填词），且**不标记完成**。</li>
 * </ul>
 *
 * <p>`delayMs` / `sentinel` 是"什么时候说"的调度参数，属于编排层；
 * 这里只原样带出去，不在引擎里 sleep——sleep 会把会话线程堵住，那是绝对不行的。
 */
public record ScriptBeat(
        String id,
        String label,
        int act,
        List<String> when,
        String emotion,
        int lineGapMs,
        List<String> lines,
        boolean repeatable,
        Integer pick,
        String memory,
        double memoryImportance,
        String sentinel,
        int delayMs) {

    public ScriptBeat {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("beat 必须有 id");
        }
        when = when == null ? List.of() : List.copyOf(when);
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (memory != null && memory.isBlank()) {
            memory = null;
        }
    }

    /** 有台词可上屏。 */
    public boolean hasLines() {
        return !lines.isEmpty();
    }

    /** 只记不说（只有 memory）。 */
    public boolean memoryOnly() {
        return lines.isEmpty() && memory != null;
    }

    /** 空词条：等填词，不触发、也不标记完成。 */
    public boolean empty() {
        return lines.isEmpty() && memory == null;
    }
}
