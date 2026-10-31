package net.siniplise.noriosbackend.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.game.GestureFacts;
import net.siniplise.noriosbackend.game.GestureRuntime;
import net.siniplise.noriosbackend.gateway.UpstreamChannels;
import net.siniplise.noriosbackend.lifecycle.LifecycleMachine;
import net.siniplise.noriosbackend.obs.logging.Critical;
import net.siniplise.noriosbackend.obs.logging.FatalGuard;
import net.siniplise.noriosbackend.persona.PersonaClient;
import net.siniplise.noriosbackend.persona.ScriptLineWrite;
import net.siniplise.noriosbackend.runtime.SessionLoop;
import net.siniplise.noriosbackend.script.ScriptEngine;
import net.siniplise.noriosbackend.script.ScriptTable;
import net.siniplise.noriosbackend.store.ScriptStateStore;
import net.siniplise.noriosbackend.speech.MediaSink;
import net.siniplise.noriosbackend.speech.MediaSinkProvider;
import net.siniplise.noriosbackend.speech.SpeechQueue;
import net.siniplise.noriosbackend.tts.SpeechFramer;
import net.siniplise.noriosbackend.tts.TtsClient;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
    private final ScriptStateStore scriptStore;
    private final GestureRuntime gesture;
    /** 帧流驱动：该她动的时候替她问大脑并发指令。默认关闭（见类注释）。 */
    private final net.siniplise.noriosbackend.game.driver.GameDriverService driver;
    /** 你画我猜的画布：笔畫面只存在于**上行**命令里，服务端不回传，只能自己攒。 */
    private final net.siniplise.noriosbackend.game.pictionary.PictionaryBoard picBoard =
            new net.siniplise.noriosbackend.game.pictionary.PictionaryBoard();

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 当前 worldId（从 {@code world_joined} / 各消息里带下来），手势去重要用。 */
    private String worldId;

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
        this(name, tts, media, table, persona, null);
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media,
                          ScriptTable table, PersonaClient persona, ScriptStateStore scriptStore) {
        this(name, tts, media, table, persona, scriptStore, null);
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media,
                          ScriptTable table, PersonaClient persona, ScriptStateStore scriptStore,
                          UpstreamChannels upstream) {
        this(name, tts, media, table, persona, scriptStore, upstream, null);
    }

    public SessionRuntime(String name, TtsClient tts, MediaSinkProvider media,
                          ScriptTable table, PersonaClient persona, ScriptStateStore scriptStore,
                          UpstreamChannels upstream,
                          net.siniplise.noriosbackend.game.GameBrain brain) {
        this.tts = tts;
        this.media = media;
        this.persona = persona;
        this.scriptStore = scriptStore;
        this.script = new ScriptEngine(table);
        this.loop = new SessionLoop(name);
        this.lifecycle = new LifecycleMachine();
        this.speech = new SpeechQueue();
        // 手势注入器：没有上游通道时退化为"只判定、不注入"（日志会喊，方便发现装配漏了）
        UpstreamChannels.InjectorHolder holder = new UpstreamChannels.InjectorHolder(upstream);
        this.gesture = new GestureRuntime(holder::inject,
                fact -> lifecycle.snapshot().facts().contains(fact));
        this.driver = new net.siniplise.noriosbackend.game.driver.GameDriverService(
                brain, holder,
                // 把任务送回 loop：大脑的回复在 reactor 线程到达，改状态必须回会话线程
                task -> loop.submit(() -> { task.run(); return null; }));
        // 画布要在 driver 里也能读到（上行观察写入、驱动读取）
        this.driver.setPictureBoard(this.picBoard);
        // 她打牌时顺口说的话，走和台词引擎同一条队列（这样 /nori-line 能取到、也会被 TTS 念出来）
        this.driver.setOnSpoken(reply -> {
            var lines = reply.lines();
            if (lines.isEmpty()) {
                return;
            }
            // qid 要唯一：ack（/nori-spoken）按它回报，重复会让上一句被提前标记成已播
            String qid = "game-" + UUID.randomUUID().toString().substring(0, 8);
            for (int i = 0; i < lines.size(); i++) {
                speech.enqueue(new SpeechQueue.Line(qid, i, lines.size(), lines.get(i), reply.emotion()));
            }
            log.info("game: 她的台词入队 {} 句（emotion={}）", lines.size(), reply.emotion());
        });
        // 把三个状态机的所有权交给 loop 线程；之后的任何访问都必须在 loop 内
        try {
            loop.submit(() -> {
                lifecycle.ownedByCurrentThread();
                speech.ownedByCurrentThread();
                script.ownedByCurrentThread();
                gesture.ownedByCurrentThread();
                // 启动即恢复上次的台词闸门：否则重启后所有一次性 beat 会重说一遍
                if (scriptStore != null) {
                    script.restore(scriptStore.load());
                    log.info("session: 已恢复台词状态（说过 {} 个 beat）", script.doneCount());
                }
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

    /** 一条 fact 在会话线程内的处理结果：入队句数 + 需要写给人格侧的记录。 */
    private record FactResult(int queued, List<ScriptLineWrite> writes) {
    }

    /**
     * 一个 fact 到达：跑台词引擎，把命中的台词入队（供 `/nori-line` 取走）。
     *
     * <p>只入队不上屏：上屏是客户端轮询 `/nori-line` 后自己画的。
     * 需要发声时由调用方再用 {@link #speak} 推帧——两件事分开，才不会出现"上屏了但没声音"时无从判断是谁的问题。
     *
     * @return 本次入队的台词条数
     */
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
            if (!fires.isEmpty() && scriptStore != null) {
                // 只把快照交给 store：真正的磁盘 IO 在 store 的写线程里，会话线程不等
                scriptStore.save(script.snapshot());
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
            // 换局也要落盘：否则重启后会把已经清掉的闸门又"恢复"回来
            if (scriptStore != null) {
                scriptStore.save(script.snapshot());
            }
            return null;
        });
    }

    /**
     * 观察一条服务端帧（WS 文本帧）。**只读解析，不参与转发**。
     *
     * <p>这里承担两件事：
     * <ol>
     *   <li><b>游戏事件</b>：喂给 {@link GestureRuntime} 判四枚超限手势（Q22）。</li>
     *   <li><b>换局</b>：`cartridge_unmounted` 到达后 ① 软清空语音队列（当前句说完、其余丢弃）；
     *       ② 只有真的丢了东西才通报人格（`/game-note`，只记不答）。</li>
     * </ol>
     * 条件通报很关键——每退出一次就往记忆里塞一条"被截断"，她的记忆会被噪音填满。
     *
     * <p><b>顺序</b>：先喂 lifecycle（facts 会更新，`qfr.installed` 门控要用），再判手势。
     * 反过来会让"这一帧刚到、她刚装上 QFR"的那枚 fact 被旧快照拦下。
     *
     * @return 是否发生了换局（ended 由 false 变 true）
     */
    public CompletableFuture<Boolean> observeFrame(String rawText) {
        // ★ 兜底：这是**所有下行帧的总入口**，里面任何未预期异常都会让"她不动/台词不出"，
        //   而调用方（WS 转发）只关心"别断连接"。不兜底的话异常会被 future 吞掉 —— 静默。
        return onLoop(() -> FatalGuard.call("observeFrame（下行帧处理）", () -> {
            boolean wasEnded = lifecycle.snapshot().ended();
            boolean changed = lifecycle.observeFrame(rawText);
            dispatchGame(rawText);
            if (driver != null) {
                // 驱动自己管记账（heads/playerSide）与开关，异常一律吞掉：一条脏帧不能打断帧流
                try {
                    driver.observe(rawText);
                } catch (RuntimeException e) {
                    // ★ 这里用 CRTL 而不是 warn/debug：驱动里的异常都是**代码 bug**（不是网络问题），
                    //   而且后果是"她完全不动"。之前吞成 debug 导致排查了两轮，绝不再犯。
                    log.error(Critical.CRITICAL,
                            "game: 驱动观察失败（她这一帧不会动）—— 这是代码 bug，不是网络问题", e);
                }
            }
            if (!changed || wasEnded || !lifecycle.snapshot().ended()) {
                return false;
            }
            // 换局：画布也要清（否则新一局会带着上一局的画去猜）
            picBoard.reset();
            // 换局：软清空（保留正在播的那句）
            String cart = lifecycle.snapshot().cartridge();
            int dropped = speech.softReset();
            if (dropped > 0 && persona != null) {
                persona.gameNote(cart, dropped)
                        .onErrorResume(e -> {
                            log.warn("cartridge: 通报截断失败 cart={} dropped={}: {}", cart, dropped, e.toString());
                            return Mono.empty();
                        })
                        .subscribe();
            }
            log.info("cartridge: 换局 cart={} 丢弃 {} 句（{}）", cart, dropped,
                    dropped > 0 ? "已通报人格" : "队列为空，未通报");
            return true;
        }, false));
    }

    /**
     * 观察一条**上行**帧（浏览器 → 上游）。
     *
     * <p>只有你画我猜需要它：玩家的笔画是通过 `submitStrokeBatch` 命令发上去的，
     * 而服务端的 `gameState` 里**没有"这张画长什么样"**——要喂图给视觉模型，只能自己攒。
     *
     * <p><b>性能</b>：上行帧在玩家画画时很密（每秒几十帧）。这里先用一次便宜的
     * {@code contains} 预筛（在 reactor 线程做，不进 loop），只有笔画帧才排进会话线程。
     * 否则会给串行的 loop 塞进大量无关任务，连累下行帧的处理。
     *
     * <p>**不返回结果**：观察是旁路，失败也不能影响转发。
     */
    public void observeUpstreamFrame(String rawText) {
        if (rawText == null || rawText.length() < 16) {
            return;
        }
        // 上行命令里有两类我们需要旁路记下：
        //   · submitStrokeBatch —— 你画我猜的笔画（服务端不回传"这张画长什么样"）
        //   · submitClue        —— 森林寻宝的提示词（gameState 里**没有**，history 开局又为空）
        boolean isStroke = rawText.contains("submitStrokeBatch");
        boolean isClue = rawText.contains("submitClue");
        if (!isStroke && !isClue) {
            return;
        }
        onLoop(() -> FatalGuard.call("observeUpstreamFrame（上行帧处理）", () -> {
            JsonNode msg;
            try {
                msg = MAPPER.readTree(rawText);
            } catch (RuntimeException e) {
                log.warn("pic: 上行帧不是合法 JSON，丢弃: {}", rawText.length() > 120
                        ? rawText.substring(0, 120) + "…" : rawText);
                return null;
            }
            if (isStroke) {
                JsonNode batch = strokeBatchOf(msg);
                if (batch != null) {
                    picBoard.addBatch(batch);
                    log.debug("pic: 已攒 {} 笔", picBoard.strokeCount());
                    // 笔画到达就直接考虑"她要不要猜"，不等上游广播（笔画是上行命令，未必触发 transition）
                    if (driver != null) {
                        driver.pictureChanged();
                    }
                }
            }
            if (isClue && driver != null) {
                JsonNode clue = clueOf(msg);
                if (clue != null) {
                    JsonNode w = clue.get("word");
                    JsonNode c = clue.get("count");
                    if (w != null && !w.isNull() && !w.isArray() && !w.isObject()) {
                        driver.noteClue(w.asString(),
                                (c != null && c.isNumber()) ? c.asInt() : 1);
                    }
                }
            }
            return null;
        }, null)).exceptionally(e -> {
            // FatalGuard 已经吃掉了 Throwable，走到这里基本只剩"loop 被关掉"这类调度问题
            log.error(Critical.CRITICAL,
                    "pic: 上行观察调度失败（这张画的笔画会缺一段）", e);
            return null;
        });
    }

    /**
     * 从上行消息里挖出 {@code clue} 对象（{@code {word, count}}）。
     *
     * <p>兼容 `{cmd:{type:"submitClue", clue:{…}}}` 和 `{type:"submitClue", clue:{…}}` 两种封装——
     * 和 {@link #strokeBatchOf} 同样的理由：玩家命令的封装方式没有文档，两个位置都找。
     */
    private static JsonNode clueOf(JsonNode msg) {
        if (msg == null || !msg.isObject()) {
            return null;
        }
        JsonNode cmd = msg.get("cmd");
        if (cmd != null && cmd.isObject()) {
            JsonNode c = cmd.get("clue");
            if (c != null && c.isObject()) {
                return c;
            }
        }
        JsonNode c = msg.get("clue");
        return (c != null && c.isObject()) ? c : null;
    }

    /**
     * 从上行消息里挖出 {@code batch}（你画我猜的笔画）。
     *
     * <p>兼容两种封装：`{type:"dispatch", cmd:{type:"submitStrokeBatch", batch:[…]}}`
     * 和直接 `{type:"submitStrokeBatch", batch:[…]}`。玩家命令的封装方式我们没有文档，
     * 所以两个位置都找——多找一处不会错，找少了整张图就永远是空的。
     */
    private static JsonNode strokeBatchOf(JsonNode msg) {
        if (msg == null || !msg.isObject()) {
            return null;
        }
        JsonNode cmd = msg.get("cmd");
        if (cmd != null && cmd.isObject()) {
            JsonNode b = cmd.get("batch");
            if (b != null && b.isArray()) {
                return b;
            }
        }
        JsonNode b = msg.get("batch");
        return (b != null && b.isArray()) ? b : null;
    }

    /** 供驱动/测试：当前画布。 */
    public net.siniplise.noriosbackend.game.pictionary.PictionaryBoard pictureBoard() {
        return picBoard;
    }

    /**
     * 从一帧文本里挑出游戏相关消息，喂给手势判定。
     *
     * <p>只在 loop 线程调用。解析失败一律静默——这一层绝不能因为一条畸形帧打断帧流。
     */
    private void dispatchGame(String rawText) {
        if (rawText == null || rawText.length() < 2 || rawText.charAt(0) != '{') {
            return;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawText);
        } catch (RuntimeException e) {
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        String type = net.siniplise.noriosbackend.game.GameEvents.text(node, "type");
        if (type == null) {
            return;
        }
        // worldId：有的消息在顶层，有的裹在 world 里
        String w = net.siniplise.noriosbackend.game.GameEvents.text(node, "worldId");
        if (w == null) {
            JsonNode world = node.get("world");
            if (world != null && world.isObject()) {
                w = net.siniplise.noriosbackend.game.GameEvents.text(world, "worldId");
            }
        }
        if (w != null && !w.isEmpty()) {
            worldId = w;
        }

        switch (type) {
            case "runtime_transition" -> {
                String cart = net.siniplise.noriosbackend.game.GameEvents.text(node, "cartridgeId");
                JsonNode tr = node.get("transition");
                if (cart == null || tr == null || !tr.isObject()) {
                    return;
                }
                GestureRuntime.Outcome out = gesture.onTransition(worldId, cart, tr);
                for (GestureRuntime.Fired f : out.fired()) {
                    // 台词驱动的接入点：将来在这里调 script.onFact(f.fact())
                    log.info("game: 手势命中 {} — {}", f.fact(), f.why());
                }
                if (out.started() || out.over()) {
                    log.debug("game: cart={} started={} over={} 手势 {}", cart, out.started(), out.over(),
                            gesture.progressText());
                }
            }
            case "cartridge_mounted", "cartridge_unmounted" -> {
                String cart = net.siniplise.noriosbackend.game.GameEvents.text(node, "cartridgeId");
                gesture.setCartridge("cartridge_unmounted".equals(type) ? null : cart);
            }
            case "world_joined" -> {
                // 进世界时把已挂载的卡带同步过来，免得第一帧 transition 因为 cart 为 null 被忽略
                JsonNode world = node.get("world");
                JsonNode mounted = world == null ? null : world.get("mountedCartridges");
                if (mounted != null && mounted.isArray() && !mounted.isEmpty()) {
                    String last = null;
                    for (JsonNode mc : mounted) {
                        String id = net.siniplise.noriosbackend.game.GameEvents.text(mc, "cartridgeId");
                        if (id != null && !"manifold.web".equals(id) && !"chat".equals(id)) {
                            last = id;
                        }
                    }
                    if (last != null) {
                        gesture.setCartridge(last);
                    }
                }
            }
            default -> {
                // 其它消息与手势判定无关
            }
        }
    }

    /**
     * 从存档恢复（读档）。
     *
     * <p><b>顺序是有讲究的</b>：`script → lifecycle → speech`。
     * 先恢复台词闸门（否则接下来触发的 fact 会用旧闸门判断），再恢复生命周期（facts 决定 petGate），
     * 最后塞回待播队列。反过来做的话，恢复 lifecycle 时 facts 会覆盖刚恢复的闸门状态。
     *
     * <p>恢复后立刻落盘一次：读档本身就该是一个新的"当前状态"，
     * 不能等下一次 fact 触发才写。
     */
    public CompletableFuture<Void> restoreFrom(net.siniplise.noriosbackend.save.SaveGame game) {
        if (game == null || !game.readableByThisVersion()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("存档不可读"));
        }
        return onLoop(() -> {
            script.restore(game.script().toSnapshot());
            var session = game.session();
            lifecycle.restore(session.phase(), session.cartridge(), session.ended(),
                    session.seq(), session.facts());
            List<SpeechQueue.Line> pending = new ArrayList<>();
            if (game.speech() != null) {
                for (var line : game.speech().pending()) {
                    pending.add(new SpeechQueue.Line(line.qid(), line.qpart(), line.qof(),
                            line.text(), line.emotion()));
                }
            }
            speech.restore(pending, game.speech() == null ? 0 : game.speech().epoch());
            if (scriptStore != null) {
                scriptStore.save(script.snapshot());
            }
            log.info("session: 已读档 slot={} phase={} facts={} 待播={}",
                    game.slot(), session.phase(), session.facts().size(), pending.size());
            return null;
        });
    }

    /** 台词状态快照（存档用）。 */
    public CompletableFuture<net.siniplise.noriosbackend.store.ScriptStateSnapshot> scriptSnapshot() {
        return onLoop(script::snapshot);
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
        // 先让台词状态落盘，再停循环：顺序反了会丢掉最后一次触发
        if (scriptStore != null) {
            scriptStore.flush();
        }
        loop.close();
    }
}
