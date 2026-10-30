package net.siniplise.noriosbackend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生命周期状态机：换局锚点、petGate 四闸门、线程所有权。
 *
 * <p>重点不是覆盖率，而是**几条只在线上才会以怪现象出现的规则**：
 * 换局只认 `cartridge_unmounted`、重复帧不能把 seq 推两次、闸门顺序决定"报哪一条"。
 */
class LifecycleMachineTest {

    @Test
    @DisplayName("换局锚点只看 cartridge_unmounted，onGameEnd 之类的 transition 不推 ended")
    void unmountIsTheOnlyEndAnchor() {
        LifecycleMachine machine = new LifecycleMachine();

        machine.observeFrame("{\"type\":\"runtime_transition\",\"cartridgeId\":\"chess\","
                + "\"transition\":{\"cmd\":{\"type\":\"onGameEnd\"}}}");
        assertThat(machine.snapshot().ended()).as("整局结束不是换局").isFalse();
        assertThat(machine.snapshot().cartridge()).isEqualTo("chess");

        assertThat(machine.observeFrame("{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chess\"}"))
                .as("卸载才算离开").isTrue();
        assertThat(machine.snapshot().ended()).isTrue();
        assertThat(machine.snapshot().seq()).isEqualTo(1);

        // 同一个 unmount 帧多头到达只处理一次
        assertThat(machine.observeFrame("{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chess\"}"))
                .isFalse();
        assertThat(machine.snapshot().seq()).as("seq 不能推两次").isEqualTo(1);
    }

    @Test
    @DisplayName("petGate 四闸门按顺序短路：coldopen → corrupt → memory → down")
    void petGateOrder() {
        LifecycleMachine machine = new LifecycleMachine();
        assertThat(machine.petGate()).as("什么都没有时放行").isNull();

        machine.observeFact("session.ready");
        assertThat(machine.petGate()).isEqualTo("coldopen");

        machine.observeFact("boot.completed");
        assertThat(machine.petGate()).as("过场结束就放行").isNull();

        machine.observeFact("corrupt.armed");
        machine.observeFact("arg.memory.start");
        assertThat(machine.petGate()).as("腐化优先于记忆").isEqualTo("corrupt");

        machine.observeFact("virus.cleared");
        assertThat(machine.petGate()).isEqualTo("memory");

        machine.observeFact("arg.memory.shown");
        assertThat(machine.petGate()).isEqualTo("down");

        machine.observeFact("arg.ending.shown");
        assertThat(machine.petGate()).isNull();
    }

    @Test
    @DisplayName("不是 JSON 的帧文本被忽略，不中断帧流")
    void ignoresNonJson() {
        LifecycleMachine machine = new LifecycleMachine();
        assertThat(machine.observeFrame("[nori] 这只是一行日志")).isFalse();
        assertThat(machine.observeFrame("")).isFalse();
        assertThat(machine.observeFrame(null)).isFalse();
        assertThat(machine.snapshot().phase()).isEqualTo("idle");
    }

    @Test
    @DisplayName("world_joined / web_world_reset_ack 都能回到 joined，并解除 ended")
    void joinedAndReset() {
        LifecycleMachine machine = new LifecycleMachine();
        machine.observeFrame("{\"type\":\"world_joined\",\"world\":{},\"session\":{}}");
        assertThat(machine.snapshot().phase()).isEqualTo("joined");

        machine.observeFrame("{\"type\":\"cartridge_unmounted\",\"cartridgeId\":\"chat\"}");
        assertThat(machine.snapshot().ended()).isTrue();

        machine.observeFrame("{\"type\":\"web_world_reset_ack\",\"worldId\":\"w1\"}");
        assertThat(machine.snapshot().ended()).isFalse();
        assertThat(machine.snapshot().phase()).isEqualTo("joined");
    }

    @Test
    @DisplayName("非属主线程访问直接抛，不留并发窗口")
    void rejectsForeignThread() throws Exception {
        LifecycleMachine machine = new LifecycleMachine();
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Thread other = new Thread(() -> {
            try {
                machine.snapshot();
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "intruder");
        other.start();
        other.join();

        assertThat(caught.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LifecycleMachine")
                .hasMessageContaining("intruder");
    }

    @Test
    @DisplayName("快照是隔离的：改状态不会影响已取出的快照")
    void snapshotIsDetached() {
        LifecycleMachine machine = new LifecycleMachine();
        machine.observeFact("session.ready");
        LifecycleMachine.Snapshot before = machine.snapshot();

        machine.observeFact("boot.completed");
        assertThat(before.facts()).contains("session.ready").doesNotContain("boot.completed");
        assertThat(before.petGate()).isEqualTo("coldopen");
        assertThat(machine.snapshot().petGate()).isNull();
    }

    @Test
    @DisplayName("所有权可以交接（构造线程 → SessionLoop 线程）")
    void ownershipCanBeHandedOver() {
        LifecycleMachine machine = new LifecycleMachine();
        Thread loopThread = new Thread(machine::ownedByCurrentThread, "nori-loop-test");
        loopThread.start();
        try {
            loopThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 交接后主线程不再有权限
        assertThatThrownBy(machine::snapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nori-loop-test");
    }
}
