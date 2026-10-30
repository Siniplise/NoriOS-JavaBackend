package net.siniplise.noriosbackend.app;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.siniplise.noriosbackend.lifecycle.LifecycleMachine;
import net.siniplise.noriosbackend.runtime.SessionLoop;
import net.siniplise.noriosbackend.speech.SpeechQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个会话的运行时：把「串行执行器 + 生命周期 + 台词队列」绑在一起。
 *
 * <p>存在的理由：这三样东西都要求**同一线程**访问（{@link LifecycleMachine} 与
 * {@link SpeechQueue} 都用 ThreadGuard 守着），而 HTTP 请求线程是并发的。
 * 所以对外只暴露 {@code CompletableFuture}：调用方（控制器 / WS 代理）投递任务后异步等结果，
 * 永远不直接碰内部状态。
 *
 * <p>为什么是 CompletableFuture 而不是 Mono：这一层不认识 WebFlux——
 * 换成 Netty 或命令行跑测试都能用。控制器那边一句 {@code Mono.fromFuture(...)} 就接上了。
 */
public final class SessionRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionRuntime.class);

    private final SessionLoop loop;
    private final LifecycleMachine lifecycle;
    private final SpeechQueue speech;

    public SessionRuntime(String name) {
        this.loop = new SessionLoop(name);
        this.lifecycle = new LifecycleMachine();
        this.speech = new SpeechQueue();
        // 把两个状态机的所有权交给 loop 线程；之后的任何访问都必须在 loop 内
        try {
            loop.submit(() -> {
                lifecycle.ownedByCurrentThread();
                speech.ownedByCurrentThread();
                return null;
            }).join();
        } catch (RuntimeException e) {
            loop.close();
            throw new IllegalStateException("会话初始化失败", e);
        }
        log.debug("session {} 已就绪", name);
    }

    /** 只读投递：给调用方一个不阻塞的入口。 */
    private <T> CompletableFuture<T> onLoop(java.util.function.Supplier<T> task) {
        if (!loop.isAccepting()) {
            return CompletableFuture.failedFuture(new IllegalStateException("会话已关闭"));
        }
        return loop.submit(task);
    }

    /** 生命周期快照（跨线程安全）。 */
    public CompletableFuture<LifecycleMachine.Snapshot> snapshot() {
        return onLoop(lifecycle::snapshot);
    }

    /** 观察一条服务端帧（只读解析，转发不受影响）。 */
    public CompletableFuture<Boolean> observeFrame(String rawText) {
        return onLoop(() -> lifecycle.observeFrame(rawText));
    }

    /** 记录一个 fact。 */
    public CompletableFuture<Boolean> observeFact(String fact) {
        return onLoop(() -> lifecycle.observeFact(fact));
    }

    /**
     * `/nori-line` 的语义：取走待播并清空。
     *
     * <p>串行执行器保证「取走」与「入队」不会交错（Q3）。
     */
    public CompletableFuture<List<SpeechQueue.Line>> drainLines() {
        return onLoop(speech::drainForClient);
    }

    /** 入队一句台词。 */
    public CompletableFuture<Void> enqueue(SpeechQueue.Line line) {
        return onLoop(() -> {
            speech.enqueue(line);
            return null;
        });
    }

    /** 开始播下一句；返回是否真的开始了。 */
    public CompletableFuture<Boolean> startNextLine() {
        return onLoop(speech::startNext);
    }

    /** 播放回报。 */
    public CompletableFuture<SpeechQueue.AckOutcome> ackSpoken(String qid, int qpart) {
        return onLoop(() -> speech.ack(qid, qpart));
    }

    /** 兜底检查（由定时器或轮询驱动）。 */
    public CompletableFuture<Boolean> forceAdvanceIfExpired() {
        return onLoop(() -> speech.fallbackDue() && speech.forceAdvance());
    }

    /** 换局软清空，返回丢弃句数。 */
    public CompletableFuture<Integer> softResetSpeech() {
        return onLoop(speech::softReset);
    }

    /** 摸头闸门：null 表示放行。 */
    public CompletableFuture<String> petGate() {
        return onLoop(lifecycle::petGate);
    }

    public int queueDepth() {
        return loop.depth();
    }

    @Override
    public void close() {
        loop.close();
    }
}
