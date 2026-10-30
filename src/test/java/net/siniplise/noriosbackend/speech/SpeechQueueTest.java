package net.siniplise.noriosbackend.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 台词队列：串行、破坏性出队、9s 兜底、迟到回报免疫。
 *
 * <p>这几条都是「错了不会报错、只会表现成跳句/重复/卡死」的类型，所以必须逐条钉住。
 */
class SpeechQueueTest {

    private static SpeechQueue.Line line(String qid, int part, String text) {
        return new SpeechQueue.Line(qid, part, 1, text, "neutral");
    }

    @Test
    @DisplayName("/nori-line 是破坏性的：取走即清空，第二次取是空")
    void drainIsDestructive() {
        SpeechQueue queue = new SpeechQueue();
        queue.enqueue(line("q1", 0, "第一句"));
        queue.enqueue(line("q1", 1, "第二句"));

        List<SpeechQueue.Line> first = queue.drainForClient();
        assertThat(first).extracting(SpeechQueue.Line::text).containsExactly("第一句", "第二句");
        assertThat(queue.drainForClient()).isEmpty();
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    @DisplayName("严格串行：已有在播句子时 startNext 不抢")
    void startsOneAtATime() {
        SpeechQueue queue = new SpeechQueue();
        queue.enqueue(line("q1", 0, "甲"));
        queue.enqueue(line("q1", 1, "乙"));

        assertThat(queue.startNext()).isTrue();
        assertThat(queue.isPlaying()).isTrue();
        assertThat(queue.startNext()).as("串行：前一句没播完不许上下一句").isFalse();
        assertThat(queue.current().text()).isEqualTo("甲");

        assertThat(queue.ack("q1", 0)).isEqualTo(SpeechQueue.AckOutcome.ADVANCED);
        assertThat(queue.startNext()).isTrue();
        assertThat(queue.current().text()).isEqualTo("乙");
    }

    @Test
    @DisplayName("重复回报只推进一次（幂等）")
    void ackIsIdempotent() {
        SpeechQueue queue = new SpeechQueue();
        queue.enqueue(line("q1", 0, "甲"));
        queue.startNext();

        assertThat(queue.ack("q1", 0)).isEqualTo(SpeechQueue.AckOutcome.ADVANCED);
        assertThat(queue.ack("q1", 0)).as("已经推进过了，不能再来一次").isEqualTo(SpeechQueue.AckOutcome.IDLE);
        assertThat(queue.epoch()).isEqualTo(2);
    }

    @Test
    @DisplayName("9s 兜底：不到点不动，到点才推进")
    void fallbackAfterTimeout() {
        AtomicLong now = new AtomicLong(1_000);
        SpeechQueue queue = new SpeechQueue(SpeechQueue.DEFAULT_FALLBACK_MS, now::get);
        queue.enqueue(line("q1", 0, "甲"));
        queue.startNext();

        now.set(1_000 + 8_999);
        assertThat(queue.fallbackDue()).isFalse();

        now.set(1_000 + 9_000);
        assertThat(queue.fallbackDue()).isTrue();

        assertThat(queue.forceAdvance()).isTrue();
        assertThat(queue.isPlaying()).isFalse();
        assertThat(queue.fallbackDue()).as("没有在播句子就不该再触发").isFalse();
    }

    @Test
    @DisplayName("兜底推进后迟到的回报只计 stale，不会把队列再推一格")
    void lateAckAfterFallbackIsStale() {
        AtomicLong now = new AtomicLong(0);
        SpeechQueue queue = new SpeechQueue(9_000, now::get);
        queue.enqueue(line("q1", 0, "甲"));
        queue.enqueue(line("q1", 1, "乙"));
        queue.startNext();

        now.set(9_000);
        queue.forceAdvance();
        queue.startNext(); // 乙 上台

        assertThat(queue.ack("q1", 0)).as("甲 的回报已过期").isEqualTo(SpeechQueue.AckOutcome.STALE);
        assertThat(queue.staleAcks()).isEqualTo(1);
        assertThat(queue.current().text()).as("乙 不能被误推进").isEqualTo("乙");
    }

    @Test
    @DisplayName("换局软清空：当前句保留，其余丢弃并返回丢弃数")
    void softResetKeepsCurrent() {
        SpeechQueue queue = new SpeechQueue();
        queue.enqueue(line("q1", 0, "甲"));
        queue.enqueue(line("q1", 1, "乙"));
        queue.enqueue(line("q1", 2, "丙"));
        queue.startNext();

        assertThat(queue.softReset()).isEqualTo(2);
        assertThat(queue.current().text()).as("正在播的那句不许打断").isEqualTo("甲");
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    @DisplayName("非属主线程访问直接抛")
    void rejectsForeignThread() throws Exception {
        SpeechQueue queue = new SpeechQueue();
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Thread other = new Thread(() -> {
            try {
                queue.drainForClient();
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "http-worker");
        other.start();
        other.join();

        assertThat(caught.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SpeechQueue")
                .hasMessageContaining("http-worker");
    }
}
