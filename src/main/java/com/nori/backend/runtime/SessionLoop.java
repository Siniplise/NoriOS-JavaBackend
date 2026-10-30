package com.nori.backend.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个会话（或一条有序链路）的串行执行器。
 *
 * <p>解决的问题：WS 帧转发、台词队列、摸头计数这些东西共享同一份状态，
 * 一旦多线程直接改，就会出现「两个音频帧交换顺序」「queue 里同一句被推两次」这类
 * 极难复现的问题。做法很土但很可靠——<b>每会话一个消费线程，所有变更排队执行</b>，
 * 顺序由入队顺序唯一决定。
 *
 * <p>线程用的是<b>虚拟线程</b>而非平台线程：单机场景会话数量虽然少，
 * 但每个会话都可能阻塞在 TTS 上游或 LLM 上游；虚拟线程让「阻塞式写法」不付出线程成本，
 * 也省掉手写回调地狱。
 *
 * <p><b>唯一的坑</b>：不要在 loop 内部 {@code submit(...).get()} 同一个 loop 的任务——自己等自己，
 * 必然死锁。需要串联就用 {@link CompletableFuture#thenApply} 之类的异步链。
 *
 * <p>纪律：一个 loop 只服务一个会话；loop 之间不共享可变状态。
 */
public final class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    /** 默认容量：按「一次会话最多积压的待处理动作」估，4096 足够而不是无界。 */
    private static final int DEFAULT_CAPACITY = 4096;

    /** 退出时等排空的上限，超时就打断消费线程（宁可丢尾帧也不能卡住停机）。 */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** 空闲时的轮询间隔：决定了 close() 之后多久能察觉「该退了」。 */
    private static final long IDLE_POLL_MS = 50;

    private final String name;
    private final Mailbox<Runnable> mailbox;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final Thread worker;

    /** false 表示已停止接收新任务（close 之后），已入队的仍会跑完。 */
    private volatile boolean accepting = true;

    public SessionLoop(String name) {
        this(name, DEFAULT_CAPACITY);
    }

    public SessionLoop(String name, int capacity) {
        this.name = name;
        this.mailbox = new Mailbox<>(capacity);
        this.worker = Thread.ofVirtual().name("nori-loop-" + name).unstarted(this::runLoop);
        this.worker.start();
        log.debug("session loop started: name={} capacity={}", name, capacity);
    }

    /**
     * 提交一个有返回值的任务，严格按入队顺序执行。
     * 任务内部抛出的异常会落到返回的 future 上，不会打断 loop。
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        enqueue(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** 提交一个无返回值的任务（帧转发、计数、上屏这类副作用）。 */
    public void execute(Runnable task) {
        enqueue(task);
    }

    private void enqueue(Runnable task) {
        if (!accepting) {
            throw new IllegalStateException("session loop is closed: " + name);
        }
        if (!mailbox.offer(task)) {
            // 显式拒绝：让调用方看见积压，而不是把线程读循环一起拖死
            throw new IllegalStateException(
                    "session loop mailbox full: name=" + name + " depth=" + mailbox.depth());
        }
    }

    private void runLoop() {
        try {
            while (true) {
                Runnable task = mailbox.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                if (task == null) {
                    if (!accepting && mailbox.depth() == 0) {
                        break;
                    }
                    continue;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    // 单个任务炸了不能带走整条链路：这是「一句话没上屏」和「整个会话死掉」的区别
                    log.error("task failed in session loop: name={}", name, t);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("session loop interrupted: name={}", name);
        } finally {
            finished.complete(null);
            log.debug("session loop finished: name={}", name);
        }
    }

    /** 等当前积压跑完（测试与优雅停机用）。 */
    public void awaitDrained(Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (mailbox.depth() > 0) {
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("mailbox not drained: name=" + name + " depth=" + mailbox.depth());
            }
            Thread.sleep(1);
        }
    }

    public int depth() {
        return mailbox.depth();
    }

    public long rejectedCount() {
        return mailbox.rejected();
    }

    public boolean isAccepting() {
        return accepting;
    }

    /**
     * 优雅停机：先拒绝新任务，再把已入队的跑完，最后等消费线程退出。
     * 超过 {@link #CLOSE_TIMEOUT} 仍没退就打断。
     */
    @Override
    public void close() {
        accepting = false;
        try {
            finished.get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("session loop close timeout, interrupting: name={} depth={}", name, mailbox.depth());
            worker.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        } catch (ExecutionException e) {
            log.error("session loop failed: name={}", name, e.getCause());
        }
    }
}
