package net.siniplise.noriosbackend.contract;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 媒体通道的一个音频帧（HANDOFF §2.4 / 上游 {@code docs/VERIFIED_PROTOCOL.md} 的 Media binary frame）。
 *
 * <pre>
 * +0   1   protocol version (1)
 * +1   1   channel (1 = chatAudio)
 * +2   2   flags (bit0 = isComplete)          little-endian
 * +4   4   sequence number                    全局递增，一次回复内跨句连续
 * +8   4   block id                           = 回复内句子序号（每回复从 0 开始）
 * +12  4   chunk id                           一次回复内跨句累计，回复之间重置
 * +16  16  operation UUID
 * +32  16  message UUID                       ← 与 blockId 一起才是唯一键
 * +48  ..  16-bit little-endian PCM
 * </pre>
 *
 * <p><b>唯一键是 (messageId, blockId)，不是 blockId。</b> blockId 只是「这句话是本次回复的第几句」，
 * 每次回复都会从 0 重新数；只按 blockId 分组会把不同回复的同一句混成一堆，看起来像「chunkId 重复」。
 *
 * <p>三条来自上游严格解析的硬约束（写错客户端会静默丢弃，不报错）：
 * <ol>
 *   <li>{@code version} 必须是 1，{@code channel} 1 表示 chatAudio；</li>
 *   <li>中间帧<b>绝不能</b>带 complete——worklet 会提前结束该 block，下一句既不播也不回 ack；</li>
 *   <li>每个 block 必须<b>恰好一个</b> complete（在末块）。</li>
 * </ol>
 *
 * <p>PCM 固定 32kHz 单声道 16bit：{@link #FRAME_SAMPLES} = 5760 采样 = 180ms/帧（上游注释 "matches backend framing"）。
 */
public record ArcadeFrame(
        int version,
        int channel,
        boolean complete,
        int sequence,
        int blockId,
        int chunkId,
        UUID operationId,
        UUID messageId,
        byte[] pcm) {

    /** 头部长度：48 字节，PCM 从这里开始。 */
    public static final int HEADER_BYTES = 48;

    /** 协议版本，上游写死为 1。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 通道号：1 = chatAudio（台词音频）。 */
    public static final int CHANNEL_CHAT_AUDIO = 1;

    /** 输出采样率：与前端 worklet 的 32k 对齐。 */
    public static final int OUT_RATE = 32_000;

    /** 每帧采样数：180ms @32k。上游 {@code FRAME_SAMPLES = 5760}。 */
    public static final int FRAME_SAMPLES = 5_760;

    /** 每帧 PCM 字节数（16bit 单声道）。 */
    public static final int FRAME_PCM_BYTES = FRAME_SAMPLES * 2;

    /** flags 的 bit0：这一块是所在 block 的最后一块。 */
    private static final int FLAG_COMPLETE = 1;

    public ArcadeFrame {
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("协议版本必须是 " + PROTOCOL_VERSION + "，实际: " + version);
        }
        if (pcm == null) {
            pcm = new byte[0];
        }
        if ((pcm.length & 1) != 0) {
            // 半个样本说明上游切分出错了，回放会整体错位
            throw new IllegalArgumentException("PCM 字节数必须是偶数（16bit 单声道），实际: " + pcm.length);
        }
        if (sequence < 0 || blockId < 0 || chunkId < 0) {
            throw new IllegalArgumentException("seq/blockId/chunkId 不能为负: " + sequence + "/" + blockId + "/" + chunkId);
        }
        if (operationId == null || messageId == null) {
            throw new IllegalArgumentException("operationId / messageId 不能为空");
        }
    }

    /** 组一个 chatAudio 帧（最常见的用法）。 */
    public static ArcadeFrame chatAudio(
            boolean complete, int sequence, int blockId, int chunkId,
            UUID operationId, UUID messageId, byte[] pcm) {
        return new ArcadeFrame(PROTOCOL_VERSION, CHANNEL_CHAT_AUDIO, complete,
                sequence, blockId, chunkId, operationId, messageId, pcm);
    }

    /** 是不是台词音频通道。 */
    public boolean isChatAudio() {
        return channel == CHANNEL_CHAT_AUDIO;
    }

    /** PCM 里的采样数。 */
    public int pcmSamples() {
        return pcm.length / 2;
    }

    /** 编码成一个可直接 send 的二进制帧。 */
    public byte[] encode() {
        byte[] out = new byte[HEADER_BYTES + pcm.length];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) version);
        b.put(1, (byte) channel);
        b.putShort(2, (short) (complete ? FLAG_COMPLETE : 0));
        b.putInt(4, sequence);
        b.putInt(8, blockId);
        b.putInt(12, chunkId);
        b.putLong(16, operationId.getMostSignificantBits());
        b.putLong(24, operationId.getLeastSignificantBits());
        b.putLong(32, messageId.getMostSignificantBits());
        b.putLong(40, messageId.getLeastSignificantBits());
        System.arraycopy(pcm, 0, out, HEADER_BYTES, pcm.length);
        return out;
    }

    /** 解析一个二进制帧；长度/版本不对就抛，不猜。 */
    public static ArcadeFrame decode(byte[] raw) {        if (raw == null || raw.length < HEADER_BYTES) {
            throw new IllegalArgumentException("帧太短，至少 " + HEADER_BYTES + " 字节，实际: "
                    + (raw == null ? "null" : raw.length));
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.get(0) & 0xFF;
        int channel = b.get(1) & 0xFF;
        boolean complete = (b.getShort(2) & FLAG_COMPLETE) != 0;
        UUID op = new UUID(b.getLong(16), b.getLong(24));
        UUID msg = new UUID(b.getLong(32), b.getLong(40));
        byte[] pcm = Arrays.copyOfRange(raw, HEADER_BYTES, raw.length);
        return new ArcadeFrame(version, channel, complete,
                b.getInt(4), b.getInt(8), b.getInt(12), op, msg, pcm);
    }

    /**
     * record 的默认 equals 对 {@code byte[]} 是**引用比较**——两段内容相同的 PCM 会被判不等。
     * 帧去重、影子对比、缓存命中都要靠值比较，所以必须显式覆写。
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ArcadeFrame that)) {
            return false;
        }
        return version == that.version
                && channel == that.channel
                && complete == that.complete
                && sequence == that.sequence
                && blockId == that.blockId
                && chunkId == that.chunkId
                && operationId.equals(that.operationId)
                && messageId.equals(that.messageId)
                && Arrays.equals(pcm, that.pcm);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version, channel, complete, sequence, blockId, chunkId, operationId, messageId);
        return 31 * result + Arrays.hashCode(pcm);
    }

    /** 不打印整段 PCM（一帧 11KB，日志会被撑爆）。 */
    @Override
    public String toString() {
        return "ArcadeFrame[v" + version + " ch" + channel + (complete ? " complete" : "")
                + " seq=" + sequence + " block=" + blockId + " chunk=" + chunkId
                + " msg=" + messageId + " pcm=" + pcm.length + "B]";
    }
}
