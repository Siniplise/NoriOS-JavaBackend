package net.siniplise.noriosbackend.lifecycle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.siniplise.noriosbackend.runtime.ThreadGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话生命周期状态机：`phase / cart / ended` + facts + `petGate` 四类闸门。
 *
 * <p><b>线程模型</b>：可变状态只允许 {@link ThreadGuard} 登记的属主线程改（实际就是每会话的
 * {@code SessionLoop}）。对外只给不可变快照 {@link Snapshot}，跨线程读也安全。
 * 这样「帧转发线程」与「HTTP 线程」不可能各写一半——那是 Q2 里最危险的一类 bug（丢 beat 标记）。
 *
 * <p><b>只读解析</b>：这里解析帧文本仅用于提取状态，<b>不参与转发</b>；
 * 转发路径永远是原始字节，绝不把解析结果再序列化回去（HANDOFF §2.3 铁律）。
 *
 * <p>为什么换局锚点是 {@code cartridge_unmounted}：`onGameEnd` 是「这局下完了」，
 * 她恰好在那时说收尾台词，挂它会吞掉那句；`cartridge_unmounted` 是「他离开了这个游戏」，
 * 换游戏时旧卡带必先 unmount，一次覆盖两种情况。
 */
public final class LifecycleMachine {

    private static final Logger log = LoggerFactory.getLogger(LifecycleMachine.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 快照：跨线程安全，只读。 */
    public record Snapshot(String phase, String cartridge, boolean ended, long seq,
                           Set<String> facts, String petGate) {
    }

    private final ThreadGuard guard = new ThreadGuard("LifecycleMachine");
    private final Set<String> facts = new LinkedHashSet<>();

    private String phase = "idle";
    private String cartridge;
    private boolean ended;
    private long seq;

    public LifecycleMachine() {
        // 构造即登记属主
    }

    /** 交给 SessionLoop 时把所有权交接给 loop 线程。 */
    public void ownedByCurrentThread() {
        guard.claim();
    }

    /**
     * 观察一条服务端帧文本（只读）。
     *
     * @return 状态是否发生变化
     */
    public boolean observeFrame(String rawText) {
        guard.assertOwner();
        if (rawText == null || rawText.isEmpty()) {
            return false;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawText);
        } catch (RuntimeException e) {
            // 不是 JSON（可能只是日志行）——静默忽略，不中断帧流
            return false;
        }
        if (node == null || !node.isObject()) {
            return false;
        }

        String type = text(node, "type");
        boolean changed = false;

        switch (type) {
            case "world_joined" -> {
                changed = setPhase("joined");
            }
            case "cartridge_mounted" -> {
                changed = setCartridge(text(node, "cartridgeId"));
            }
            case "cartridge_unmounted" -> {
                // 换局锚点：只有这里推 ended
                String cart = text(node, "cartridgeId");
                if (cart != null) {
                    cartridge = cart;
                }
                if (!ended) {
                    ended = true;
                    seq++;
                    changed = true;
                    log.info("lifecycle: 离开游戏 cart={} ⇒ ended，seq={}", cartridge, seq);
                }
            }
            case "runtime_transition" -> {
                changed = setCartridge(text(node, "cartridgeId"));
                JsonNode cmdType = node.at("/transition/cmd/type");
                if (cmdType != null && cmdType.isString()) {
                    changed |= setPhase(cmdType.asString());
                }
            }
            case "web_world_reset_ack" -> {
                if (ended) {
                    ended = false;
                    seq++;
                    changed = true;
                }
                changed |= setPhase("joined");
            }
            default -> {
                // 其余类型不参与生命周期
            }
        }
        return changed;
    }

    /** 观察一条 fact（`client.emitFact` / 游戏事件都汇总到这里）。 */
    public boolean observeFact(String fact) {
        guard.assertOwner();
        if (fact == null || fact.isBlank()) {
            return false;
        }
        return facts.add(fact);
    }

    /**
     * `petGate`：能不能摸头。返回 null 表示放行，否则是被拦的原因。
     *
     * <p>四类闸门与 HANDOFF §3.5 一致，顺序有意义（先过场、再腐化、再记忆、最后 down）——
     * 同一时刻只报最靠前的那条，避免一会儿说 coldopen 一会儿说 memory。
     */
    public String petGate() {
        guard.assertOwner();
        if (facts.contains("session.ready") && !facts.contains("boot.completed")) {
            return "coldopen";
        }
        if (facts.contains("corrupt.armed") && !facts.contains("virus.cleared")) {
            return "corrupt";
        }
        if (facts.contains("arg.memory.start") && !facts.contains("arg.memory.shown")) {
            return "memory";
        }
        if (facts.contains("arg.memory.shown") && !facts.contains("arg.ending.shown")) {
            return "down";
        }
        return null;
    }

    /**
     * 从存档恢复（会话线程内调用一次）。
     *
     * <p>整份替换：存档就是"那一刻的事实"，与内存里的残留合并只会得到第三种状态。
     */
    public void restore(String phase, String cartridge, boolean ended, long seq, java.util.Collection<String> factsIn) {
        guard.assertOwner();
        this.phase = phase == null || phase.isBlank() ? "idle" : phase;
        this.cartridge = cartridge;
        this.ended = ended;
        this.seq = seq;
        this.facts.clear();
        if (factsIn != null) {
            this.facts.addAll(factsIn);
        }
    }

    /** 不可变快照，可跨线程传。 */
    public Snapshot snapshot() {
        guard.assertOwner();
        return new Snapshot(phase, cartridge, ended, seq, Set.copyOf(facts), petGate());
    }

    private boolean setPhase(String next) {
        if (next == null || next.isBlank() || next.equals(phase)) {
            return false;
        }
        phase = next;
        return true;
    }

    private boolean setCartridge(String next) {
        if (next == null || next.isBlank() || next.equals(cartridge)) {
            return false;
        }
        cartridge = next;
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** 供测试/诊断：当前 facts 的只读视图。 */
    public List<String> factsView() {
        guard.assertOwner();
        return List.copyOf(facts);
    }
}
