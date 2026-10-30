package net.siniplise.noriosbackend.speech;

import java.util.List;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.tts.SpeechFramer;
import net.siniplise.noriosbackend.tts.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 一句台词 → 一串媒体帧。
 *
 * <p>把「合成（I/O，可并发）」和「组帧（纯计算，需要 seq/chunkId 串行）」分开：
 * <ul>
 *   <li>合成走 {@link TtsClient}，返回 {@code Mono<short[]>}，可以在任意线程跑；</li>
 *   <li>组帧必须回到会话线程（{@link SpeechFramer} 的计数器是会话内唯一的）。</li>
 * </ul>
 * 这条边界就是 Q5（并发合成时 seq 与发送顺序不一致）的解法：**合成可并发，帧号与发送串行**。
 *
 * <p>句间静音由 {@link SpeechFramer} 拼在句尾，所以这里不额外插块。
 */
@Service
public class SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);

    private final TtsClient tts;

    public SpeechService(TtsClient tts) {
        this.tts = tts;
    }

    /**
     * 合成并组帧。
     *
     * @param framer  会话内的帧序号发生器（只能在会话线程调用本方法的**后半段**）
     * @param text    台词
     * @param voice   声音（null 用配置默认）
     * @param blockId 本句在回复内的序号
     * @param gapMs   句间静音；0 表示不加
     */
    public Mono<List<ArcadeFrame>> framesFor(
            SpeechFramer framer, String text, String voice, int blockId, int gapMs) {
        return tts.synthesize32k(text, voice)
                .map(pcm -> {
                    List<ArcadeFrame> frames = framer.sentence(pcm, blockId, gapMs);
                    log.debug("speech: block={} {} 采样 → {} 帧（末帧 complete={}）",
                            blockId, pcm.length, frames.size(),
                            frames.get(frames.size() - 1).complete());
                    return frames;
                });
    }

    /** 合成失败时给一句"空帧"占位：让 block 收尾，不把队列卡死。 */
    public Mono<List<ArcadeFrame>> placeholder(SpeechFramer framer, int blockId) {
        return Mono.fromSupplier(() -> List.of(
                ArcadeFrame.chatAudio(true, framer.sequence(), blockId, framer.nextChunkId(),
                        UUID.nameUUIDFromBytes(("op-" + blockId).getBytes()), UUID.randomUUID(), new byte[0])));
    }
}
