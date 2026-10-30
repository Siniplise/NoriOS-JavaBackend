package com.nori.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 线程底座的行为契约。
 *
 * <p>这些断言不是「测覆盖率」，每一条都对应一个换语言后仍然会咬人的真实故障：
 * 帧序错位、单句上屏两次、ack 串句、停机卡死。
 */
class SessionLoopTest {

    @Test
    @DisplayName("同一 loop 内的任务严格按入队顺序执行")
    void tasksRunInSubmissionOrder() {
        List<Integer> seen = new ArrayList<>();
        try (SessionLoop loop = new SessionLoop("order", 16_384)) {
            for (int i = 0; i < 2_000; i++) {
                int seq = i;
                loop.execute(() -> seen.add(seq));
            }
        } // close() 会等积压跑完
        assertThat(seen).hasSize(2_000).isSorted().containsExactlyElementsOf(range(2_000));
    }

    @Test
    @DisplayName("多线程提交时，每个提交者自己的子序列不乱序")
    void perProducerOrderIsPreserved() throws Exception {
        int producers = 4;
        int perProducer = 500;
        List<int[]> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        try (SessionLoop loop = new SessionLoop("multi", 16_384)) {
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int producerId = p;
                Thread t = Thread.ofVirtual().unstarted(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perProducer; i++) {
                        int seq = i;
                        loop.execute(() -> seen.add(new int[] {producerId, seq}));
                    }
                });
                threads.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : threads) {
                t.join(10_000);
            }
        }

        assertThat(seen).hasSize(producers * perProducer);
        // 交错是允许的（入队顺序本来就交错），但每个生产者内部必须单调递增
        for (int p = 0; p < producers; p++) {
            List<Integer> seqs = new ArrayList<>();
            for (int[] item : seen) {
                if (item[0] == p) {
                    seqs.add(item[1]);
                }
            }
            assertThat(seqs).as("producer %d 的子序列", p).isSorted();
        }
    }

    @Test
    @DisplayName("单句 fail 不带走整条链路（后面的话照样上屏）")
    void failingTaskDoesNotKillLoop() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        try (SessionLoop loop = new SessionLoop("resilience")) {
            CompletableFuture<String> boom = loop.submit(() -> {
                throw new IllegalStateException("模拟 TTS 上游炸了");
            });
            loop.execute(() -> seen.add("next-line"));

            assertThatThrownBy(() -> boom.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            loop.awaitDrained(java.time.Duration.ofSeconds(2));
        }
        assertThat(seen).containsExactly("next-line");
    }

    @Test
    @DisplayName("submit 能取回返回值，且执行在 loop 线程上")
    void submitReturnsValueOnLoopThread() throws Exception {
        try (SessionLoop loop = new SessionLoop("values")) {
            CompletableFuture<String> name = loop.submit(() -> Thread.currentThread().getName());
            assertThat(name.get(2, TimeUnit.SECONDS)).startsWith("nori-loop-values");
        }
    }

    @Test
    @DisplayName("close 之后拒绝新任务，且不静默吞掉")
    void closedLoopRejectsNewTasks() {
        SessionLoop loop = new SessionLoop("closed");
        loop.close();
        assertThat(loop.isAccepting()).isFalse();
        assertThatThrownBy(() -> loop.execute(() -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("信箱满了显式拒绝，而不是把调用方阻塞死")
    void mailboxRejectsWhenFull() {
        Mailbox<String> mailbox = new Mailbox<>(2);
        assertThat(mailbox.offer("a")).isTrue();
        assertThat(mailbox.offer("b")).isTrue();
        assertThat(mailbox.offer("c")).isFalse();
        assertThat(mailbox.depth()).isEqualTo(2);
        assertThat(mailbox.rejected()).isEqualTo(1);
        assertThat(mailbox.offered()).isEqualTo(3);
    }

    @Test
    @DisplayName("容量非法直接抛，不留半死不活的状态")
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new Mailbox<String>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionLoop("bad", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("loop 满时抛 IllegalStateException 并且带上积压深度")
    void fullLoopSurfacesBacklog() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        SessionLoop loop = new SessionLoop("backpressure", 2);
        try {
            loop.execute(() -> await(block));       // 占住消费线程
            loop.execute(() -> { });                // 填满信箱
            Thread.sleep(50);                        // 让消费线程先拿走第一个
            loop.execute(() -> { });                // 这一条能不能进取决于时序
            // 只要出现拒绝，就必须是带 depth 的 IllegalStateException
            boolean rejectedSeen = false;
            for (int i = 0; i < 8; i++) {
                try {
                    loop.execute(() -> { });
                } catch (IllegalStateException e) {
                    rejectedSeen = true;
                    assertThat(e.getMessage()).contains("mailbox full").contains("depth=");
                    break;
                }
            }
            assertThat(rejectedSeen).as("信箱容量为 2 时应当出现拒绝").isTrue();
        } finally {
            block.countDown();
            loop.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Integer> range(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }
}
