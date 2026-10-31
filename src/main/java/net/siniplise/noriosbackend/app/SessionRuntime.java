package net.siniplise.noriosbackend.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.lifecycle.LifecycleMachine;
import net.siniplise.noriosbackend.persona.PersonaClient;
import net.siniplise.noriosbackend.persona.ScriptLineWrite;
import net.siniplise.noriosbackend.runtime.SessionLoop;
import net.siniplise.noriosbackend.script.ScriptEngine;
import net.siniplise.noriosbackend.script.ScriptTable;
import net.siniplise.noriosbackend.speech.MediaSink;
import net.siniplise.noriosbackend.speech.MediaSinkProvider;
import net.siniplise.noriosbackend.speech.SpeechQueue;
import net.siniplise.noriosbackend.tts.SpeechFramer;
import net.siniplise.noriosbackend.tts.TtsClient;
import reactor.core.publisher.Mono;
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
    private final TtsClient tts;
    private final MediaSinkProvider media;
    private final ScriptEngine script;
    private final PersonaClient persona;

    /** 一次回复内的帧序号发生器；换回复就换一个（chunkId 归零）。 */
    private SpeechFramer framer = new SpeechFramer(UUID.randomUUID(), UUID.randomUUID());

    public SessionRuntime(String name) {
        this(name, null, null, ScriptTable.empty());
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media) {
        this(name, tts, media, ScriptTable.empty());
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media, ScriptTable table) {
        this(name, tts, media, table, null);
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media,
                          ScriptTable table, PersonaClient persona) {
        this.tts = tts;
        this.media = media;
        this.persona = persona;
        this.script = new ScriptEngine(table);
        this.loop = new SessionLoop(name);
        this.lifecycle = new LifecycleMachine();
        this.speech = new SpeechQueue();
        // 把两个状态机的所有权交给 loop 线程；之后的任何访问都必须在 loop 内
        try {
            loop.submit(() -> {
                lifecycle.ownedByCurrentThread();
                speech.ownedByCurrentThread();
                script.ownedByCurrentThread();
                return null;
            }).join();
        } catch (RuntimeException e) {
            loop.close();
            throw new IllegalStateException("会话初始化失败", e);
        }
        log.debug("session {} 已就绪", name);
    }

    /**
     * 播一句：合成（可并发）→ 组帧（会话线程）→ 推给媒体通道。
     *
     * <p>为什么组帧必须回到会话线程：`seq/chunkId` 的唯一性与发送顺序必须同源（Q5）。
     * 合成放在外面，是为了让多句合成能并行，而帧号与写出保持串行。
     *
     * @return 实际发出去的帧数（没有媒体连接时为 0）
     */
    public CompletableFuture<Integer> speak(String text, String voice, int blockId, int gapMs) {
        if (tts == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("未配置 TtsClient"));
        }
        return tts.synthesize32k(text, voice)
                .flatMap(pcm -> Mono.fromFuture(onLoop(() -> {
                    SpeechFramer current = framer;
                    List<ArcadeFrame> frames = current.sentence(pcm, blockId, gapMs);
                    MediaSink sink = media == null ? null : media.current();
                    if (sink == null) {
                        log.debug("speak: 没有媒体连接，丢弃 {} 帧（block={}）", frames.size(), blockId);
                        return 0;
                    }
                    int sent = 0;
                    for (ArcadeFrame frame : frames) {
                        sink.send(frame);
                        sent++;
                    }
                    return sent;
                })))
                .toFuture();
    }

    /**
     * 一个 fact 到达：跑台词引擎，把命中的台词入队（供 `/nori-line` 取走）。
     *
     * <p>只入队不上屏：上屏是客户端轮询 `/nori-line` 后自己画的。
     * 需要发声时由调用方再用 {@link #speak} 推帧——两件事分开，才不会出现"上屏了但没声音"时无从判断是谁的问题。
     *
     * @return 本次入队的台词条数
     */
    /** 一条 fact 在会话线程内的处理结果：入队句数 + 需要写给人格侧的记录。 */
    private record FactResult(int queued, List<ScriptLineWrite> writes) {
    }

    public CompletableFuture<Integer> onFact(String fact) {
        return onLoop(() -> {
            lifecycle.observeFact(fact);
            var fires = script.onFact(fact, System.currentTimeMillis());
            int queued = 0;
            List<ScriptLineWrite> writes = new ArrayList<>();
            for (var fire : fires) {
                for (int i = 0; i < fire.lines().size(); i++) {
                    speech.enqueue(new SpeechQueue.Line(
                            fire.beat().id(), i, fire.lines().size(),
                            fire.lines().get(i), fire.beat().emotion()));
                    queued++;
                }
                // 首次带 memory（建卡）；repeatable 的后续触发只上屏（transient）
                if (fire.writeMemory()) {
                    writes.add(ScriptLineWrite.firstTime(fire.beat().id(), fire.beat().emotion(),
                            fire.lines(), fire.memory(), fire.memoryImportance()));
                } else if (fire.beat().repeatable() && !fire.lines().isEmpty()) {
                    writes.add(ScriptLineWrite.repeatOnly(fire.beat().id(), fire.beat().emotion(),
                            fire.lines()));
                }
            }
            if (queued > 0) {
                log.debug("script: fact={} 命中 {} 条 beat，入队 {} 句", fact, fires.size(), queued);
            }
            return new FactResult(queued, List.copyOf(writes));
        }).thenApply(result -> {
            // 记忆写入是旁路：失败只记日志，绝不影响已经入队的台词
            if (persona != null) {
                for (ScriptLineWrite write : result.writes()) {
                    persona.writeScriptLine(write)
                            .onErrorResume(e -> {
                                log.warn("script: 记忆写入失败 beat={}（旁路）: {}", write.beat(), e.toString());
                                return Mono.empty();
                            })
                            .subscribe();
                }
            }
            return result.queued();
        });
    }

    /** 新一局：清台词闸门（台词重说），保留记忆标记。 */
    public CompletableFuture<Void> resetStory() {
        return onLoop(() -> {
            script.resetStory();
            return null;
        });
    }

    /** 观测：台词引擎已落闸的 beat 数。 */
    public CompletableFuture<Integer> scriptDoneCount() {
        return onLoop(script::doneCount);
    }

    /** 新一次回复：chunkId/seq 归零（blockId 从 0 重新数）。 */
    public CompletableFuture<Void> beginReply() {
        return onLoop(() -> {
            framer = new SpeechFramer(UUID.randomUUID(), UUID.randomUUID());
            return null;
        });
    }

    /** 媒体通道是否在线。 */
    public boolean hasMedia() {
        return media != null && media.current() != null;
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
