package net.siniplise.noriosbackend.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;

/**
 * 把一句 PCM 切成 WS 媒体帧，并维护 seq / chunkId 的递增规则。
 *
 * <p>规则全部来自旧代理 {@code streamSpeech()} 的实测行为（golden 三轮验证过）：
 * <ul>
 *   <li><b>seq</b>：整条 media 连接上单调递增，跨句连续；</li>
 *   <li><b>chunkId</b>：<b>一次回复内</b>跨句累计，回复之间重置为 0 —— 不是每句重置；</li>
 *   <li><b>blockId</b>：= 句子序号（本次回复的第几句），每句一个 block；</li>
 *   <li><b>complete</b>：只有该句最后一块是 true；中间帧带 true 会让 worklet 提前收尾，</li>
 *   <li>下一句既不播、也不回 ack（实测踩过，气泡会滞后十几秒）。</li>
 * </ul>
 *
 * <p>句间静音：旧代理是把 {@code NORI_TTS_GAP_MS}（默认 300ms）的静音<b>拼在该句音频尾部</b>
 * 再切片的，所以静音落在最后一帧里，而不是单独一帧。这里保持一致。
 *
 * <p>一个实例对应「一次回复」，用完即弃；下一次回复 new 一个（chunkId 自然归零）。
 */
public final class SpeechFramer {

    /** 句间静音默认值，对应 {@code NORI_TTS_GAP_MS || 300}。 */
    public static final int DEFAULT_GAP_MS = 300;

    private final UUID operationId;
    private final UUID messageId;
    private int sequence;
    private int chunkId;

    public SpeechFramer(UUID operationId, UUID messageId) {
        this(operationId, messageId, 0, 0);
    }

    /** 从指定的 seq / chunkId 起步（接管已有连接时用）。 */
    public SpeechFramer(UUID operationId, UUID messageId, int startSequence, int startChunkId) {
        if (operationId == null || messageId == null) {
            throw new IllegalArgumentException("operationId / messageId 不能为空");
        }
        this.operationId = operationId;
        this.messageId = messageId;
        this.sequence = startSequence;
        this.chunkId = startChunkId;
    }

    /** 切一句（不加静音）。 */
    public List<ArcadeFrame> sentence(short[] pcm32k, int blockId) {
        return sentence(pcm32k, blockId, 0);
    }

    /**
     * 切一句，并在尾部拼上 {@code gapMs} 的静音。
     *
     * @param pcm32k 32kHz 单声道采样
     * @param blockId 句子序号（本次回复内从 0 开始）
     * @param gapMs  句间静音毫秒数；0 表示不加
     */
    public List<ArcadeFrame> sentence(short[] pcm32k, int blockId, int gapMs) {
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId 不能为负: " + blockId);
        }
        short[] audio = pcm32k == null ? new short[0] : pcm32k;
        if (gapMs > 0) {
            audio = concat(audio, silence(gapMs));
        }

        List<ArcadeFrame> frames = new ArrayList<>();
        if (audio.length == 0) {
            // 空句也必须发一个 complete 帧：否则这个 block 永远悬着，客户端不会 ack，reveal 卡死
            frames.add(next(blockId, true, new byte[0]));
            return frames;
        }

        int total = (audio.length + ArcadeFrame.FRAME_SAMPLES - 1) / ArcadeFrame.FRAME_SAMPLES;
        for (int i = 0; i < total; i++) {
            int from = i * ArcadeFrame.FRAME_SAMPLES;
            int to = Math.min(from + ArcadeFrame.FRAME_SAMPLES, audio.length);
            boolean last = to >= audio.length;
            frames.add(next(blockId, last, pcmBytes(audio, from, to)));
        }
        return frames;
    }

    private ArcadeFrame next(int blockId, boolean complete, byte[] pcm) {
        return ArcadeFrame.chatAudio(complete, sequence++, blockId, chunkId++, operationId, messageId, pcm);
    }

    /** 已发出的帧数（= 下一个 seq）。 */
    public int sequence() {
        return sequence;
    }

    /** 下一个要用的 chunkId。 */
    public int nextChunkId() {
        return chunkId;
    }

    private static short[] silence(int gapMs) {
        int samples = (int) Math.round(ArcadeFrame.OUT_RATE * (gapMs / 1000.0));
        return new short[samples];
    }

    private static short[] concat(short[] a, short[] b) {
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] pcmBytes(short[] samples, int from, int to) {
        byte[] out = new byte[(to - from) * 2];
        for (int i = from; i < to; i++) {
            int v = samples[i];
            int o = (i - from) * 2;
            out[o] = (byte) (v & 0xFF);
            out[o + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }
}
