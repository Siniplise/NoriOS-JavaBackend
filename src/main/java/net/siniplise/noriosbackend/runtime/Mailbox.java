package net.siniplise.noriosbackend.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一条有序链路的子线程通信信箱：多生产者、单消费者。
 *
 * <p>为什么不直接堆 {@code ArrayBlockingQueue}：这里要的是「可观测 + 明确的失败语义」。
 * WS 帧链路对顺序极度敏感（§2.4 的 {@code blockId} 一旦错位，气泡和音频就全乱），
 * 所以队列必须能回答两个问题：现在积了多少（{@link #depth()}）、丢过多少（{@link #rejected()}）。
 *
 * <p><b>offer 不阻塞</b>：满了就返回 {@code false}，由调用方决定是丢帧、断连还是扩大容量。
 * 反代链路里「悄悄阻塞」比「明确拒绝」危险得多——阻塞会连带卡住 WS 读事件循环。
 *
 * @param <T> 消息类型；不接受 {@code null}（LinkedBlockingQueue 会直接抛 NPE）
 */
public final class Mailbox<T> {

    private final LinkedBlockingQueue<T> queue;
    private final LongAdder offered = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public Mailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, but was " + capacity);
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** 非阻塞入队。满了返回 {@code false} 并记一次 rejected，不抛异常。 */
    public boolean offer(T item) {
        offered.increment();
        if (queue.offer(item)) {
            return true;
        }
        rejected.increment();
        return false;
    }

    /** 阻塞入队，最多等 {@code timeout}；返回是否入队成功。 */
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        offered.increment();
        if (queue.offer(item, timeout, unit)) {
            return true;
        }
        rejected.increment();
        return false;
    }

    /** 阻塞直到有元素。 */
    public T take() throws InterruptedException {
        return queue.take();
    }

    /** 最多等 {@code timeout}；没有元素返回 {@code null}（用于循环里定期检查退出条件）。 */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int depth() {
        return queue.size();
    }

    public long offered() {
        return offered.sum();
    }

    public long rejected() {
        return rejected.sum();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}
