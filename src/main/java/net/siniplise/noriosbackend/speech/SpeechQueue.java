package net.siniplise.noriosbackend.speech;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;
import net.siniplise.noriosbackend.runtime.ThreadGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 台词播放队列：串行、可兜底推进、对迟到回报免疫。
 *
 * <p>四条来自旧栈的语义（HANDOFF §3.4）：
 * <ol>
 *   <li><b>单队列串行</b>：一句播完（`/nori-spoken`）才放下一句；</li>
 *   <li><b>9 秒兜底</b>：回报丢了按估算推进，否则队列永久卡死；</li>
 *   <li><b>`/nori-line` 是破坏性出队</b>：取走即清空，供客户端约 706ms 轮询一次；</li>
 *   <li><b>换局软清空</b>：当前句说完、其余丢弃（由 {@code lifecycle} 决定何时调）。</li>
 * </ol>
 *
 * <h2>为什么不加锁</h2>
 * 队列的可变状态只在会话线程（{@code SessionLoop}）内改，{@link ThreadGuard} 会当场拦越界访问。
 * HTTP 线程要读/改，一律投递任务进 loop 再等结果——这样「取走一行」与「入队一行」天然不会交错（Q3）。
 *
 * <h2>怎么防迟到回报（Q4）</h2>
 * 每次推进都让 {@code epoch} 自增；兜底推进也算一次推进。迟到的 `/nori-spoken` 命中不了当前句，
 * 只会被计一次 {@code staleAcks}，不会把队列再推一格（那会跳句）。
 */
public final class SpeechQueue {

    private static final Logger log = LoggerFactory.getLogger(SpeechQueue.class);

    /** 默认兜底 9000ms（HANDOFF §3.4：最短 9000ms）。 */
    public static final int DEFAULT_FALLBACK_MS = 9_000;

    /** 一句待播台词。{@code qid+qpart} 是客户端回报时带回来的键。 */
    public record Line(String qid, int qpart, int qof, String text, String emotion) {
        public Line {
            if (qid == null || qid.isBlank()) {
                throw new IllegalArgumentException("qid 不能为空");
            }
            if (text == null) {
                text = "";
            }
        }
    }

    /** 回报的处理结果。 */
    public enum AckOutcome {
        /** 命中当前句，已推进。 */
        ADVANCED,
        /** 命中不存在的句（可能是兜底已推进）——计一次 stale，不改变状态。 */
        STALE,
        /** 当前没有在播的句子。 */
        IDLE
    }

    private final ThreadGuard guard = new ThreadGuard("SpeechQueue");
    private final Deque<Line> pending = new ArrayDeque<>();
    private final int fallbackMs;
    private final LongSupplier clockMs;

    private Line current;
    private long epoch;
    private long currentSinceMs;
    private long staleAcks;

    public SpeechQueue() {
        this(DEFAULT_FALLBACK_MS, System::currentTimeMillis);
    }

    /** 可注入时钟，测试里不必真等 9 秒。 */
    public SpeechQueue(int fallbackMs, LongSupplier clockMs) {
        if (fallbackMs <= 0) {
            throw new IllegalArgumentException("fallbackMs 必须为正: " + fallbackMs);
        }
        this.fallbackMs = fallbackMs;
        this.clockMs = clockMs;
    }

    /** 把所有权交给当前线程（通常是 SessionLoop 线程）。 */
    public void ownedByCurrentThread() {
        guard.claim();
    }

    /** 入队一句（重复 qid 允许：同一句被重新触发是真实场景）。 */
    public void enqueue(Line line) {
        guard.assertOwner();
        pending.addLast(line);
    }

    /**
     * `/nori-line` 的语义：**取走待播并清空**。
     *
     * <p>注意不清 `current`——正在播的那句已经交给客户端了，再返回一次会让它重复上屏。
     */
    public List<Line> drainForClient() {
        guard.assertOwner();
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Line> out = new ArrayList<>(pending);
        pending.clear();
        return List.copyOf(out);
    }

    /** 从队列头取一句开始播；已有在播句子时返回 false（保持串行）。 */
    public boolean startNext() {
        guard.assertOwner();
        if (current != null) {
            return false;
        }
        Line next = pending.pollFirst();
        if (next == null) {
            return false;
        }
        current = next;
        currentSinceMs = clockMs.getAsLong();
        epoch++;
        return true;
    }

    /**
     * 处理播放回报。
     *
     * <p>幂等：同一句回两次只会推进一次；兜底已推进的句子回来只计 stale。
     */
    public AckOutcome ack(String qid, int qpart) {
        guard.assertOwner();
        if (current == null) {
            return AckOutcome.IDLE;
        }
        if (!current.qid().equals(qid) || current.qpart() != qpart) {
            staleAcks++;
            log.debug("speech: 迟到的回报 qid={} qpart={}，当前是 {}#{}, epoch={}",
                    qid, qpart, current.qid(), current.qpart(), epoch);
            return AckOutcome.STALE;
        }
        current = null;
        epoch++;
        return AckOutcome.ADVANCED;
    }

    /** 兜底是否到点（只在有在播句子时有意义）。 */
    public boolean fallbackDue() {
        guard.assertOwner();
        return current != null && clockMs.getAsLong() - currentSinceMs >= fallbackMs;
    }

    /** 兜底推进：丢掉当前句，允许下一句上来。返回是否真的推进了。 */
    public boolean forceAdvance() {
        guard.assertOwner();
        if (current == null) {
            return false;
        }
        log.warn("speech: 9s 未收到回报，兜底推进（丢弃当前句 {}/{}）", current.qid(), current.qpart());
        current = null;
        epoch++;
        return true;
    }

    /** 换局软清空：当前句保留（半句被吞比多说一句糟），其余丢弃。 */
    public int softReset() {
        guard.assertOwner();
        int dropped = pending.size();
        pending.clear();
        return dropped;
    }

    /** 硬清空（`resetStory` 这类场景）。 */
    public void clearAll() {
        guard.assertOwner();
        pending.clear();
        current = null;
        epoch++;
    }

    public boolean isPlaying() {
        guard.assertOwner();
        return current != null;
    }

    public int pendingCount() {
        guard.assertOwner();
        return pending.size();
    }

    /** 供 `*stat` 暴露：兜底次数、迟到回报次数。 */
    public long staleAcks() {
        guard.assertOwner();
        return staleAcks;
    }

    public long epoch() {
        guard.assertOwner();
        return epoch;
    }

    public int fallbackMs() {
        return fallbackMs;
    }

    /** 诊断用：当前在播的句子（没有则 null）。 */
    public Line current() {
        guard.assertOwner();
        return current;
    }
}
