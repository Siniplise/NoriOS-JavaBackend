package net.siniplise.noriosbackend.persona;

import java.util.List;

/**
 * 往人格服务写一条"她刚说了这句 / 刚经历了这件事"。
 *
 * <p>字段与语义来自 `n_llm.js` 的 `/nori-scriptline`（不是猜的）：
 * <ul>
 *   <li>{@code memory}：作者按她的口吻写好的一句话，会变成一张可被"想起"的记忆卡；</li>
 *   <li>{@code transient}：**只上屏、不进记忆**。repeatable beat 的后续触发必须带它——
 *       否则摸 20 次头就往长期记忆里塞 20 张几乎一样的卡，把记忆挤满；</li>
 *   <li>{@code importance}：记忆卡权重（0~1），缺省 0.8。</li>
 * </ul>
 *
 * <p>为什么单独立一个 record 而不是直接传 Map：这个 body 一旦漏字段，
 * 人格侧会**静默**退化成"用台词拼一张卡"（看起来也能用，但记忆质量完全不同）。
 * 用类型钉住字段名，编译期就不会拼错。
 */
public record ScriptLineWrite(
        String beat,
        String emotion,
        List<String> lines,
        String memory,
        double importance,
        boolean transientOnly) {

    public ScriptLineWrite {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 首次触发（要建记忆卡）。 */
    public static ScriptLineWrite firstTime(String beat, String emotion, List<String> lines,
                                            String memory, double importance) {
        return new ScriptLineWrite(beat, emotion, lines, memory, importance, false);
    }

    /** 后续重复触发（只上屏）。 */
    public static ScriptLineWrite repeatOnly(String beat, String emotion, List<String> lines) {
        return new ScriptLineWrite(beat, emotion, lines, null, 0.0, true);
    }
}
