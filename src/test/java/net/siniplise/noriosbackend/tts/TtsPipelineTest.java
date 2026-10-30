package net.siniplise.noriosbackend.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TTS 帧流水线：WAV 解码 → 重采样 → 切片 → 组帧。
 *
 * <p>这是整个后端最容易「看起来能响、其实已经错了」的一段：
 * 采样率差一倍听着只是音调怪、complete 位置错一句就卡住 reveal、chunkId 不连续客户端直接丢包。
 * 所以每条不变式都写成断言。
 */
class TtsPipelineTest {

    private static final UUID OP = UUID.randomUUID();
    private static final UUID MSG = UUID.randomUUID();

    // ── WAV 解码 ────────────────────────────────────────────────────────────

    /** 造一个最小可用的 WAV（16bit PCM，44 字节头）。 */
    private static byte[] wav(short[] samples, int rate, int channels) {
        int dataBytes = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    @Test
    @DisplayName("WAV 解码：采样、采样率、声道都对得上")
    void decodesWav() {
        short[] samples = {0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE};
        DecodedWav decoded = WavDecoder.decode(wav(samples, 48_000, 1));

        assertThat(decoded.samples()).containsExactly(samples);
        assertThat(decoded.sampleRate()).isEqualTo(48_000);
        assertThat(decoded.channels()).isEqualTo(1);
        assertThat(decoded.seconds()).isCloseTo(5.0 / 48_000, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("WAV 解码：不是 RIFF / 不是 16bit 一律显式失败，不静默播噪音")
    void rejectsNonPcm() {
        assertThatThrownBy(() -> WavDecoder.decode(new byte[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太短");

        byte[] bad = wav(new short[]{1, 2}, 48_000, 1);
        bad[0] = 'X';
        assertThatThrownBy(() -> WavDecoder.decode(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RIFF");
    }

    // ── 重采样 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("48k → 32k：样本数按 2/3 收缩，首尾值保留")
    void resamples48kTo32k() {
        short[] in = new short[4_800];
        for (int i = 0; i < in.length; i++) {
            in[i] = (short) Math.round(10_000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0));
        }
        short[] out = Resampler.resample(in, 48_000, 32_000);

        assertThat(out).hasSize(3_200);
        assertThat(out[0]).isEqualTo(in[0]);
        // 振幅不应被放大或削平
        int max = 0;
        for (short s : out) {
            max = Math.max(max, Math.abs(s));
        }
        assertThat(max).isBetween(9_000, 10_100);
    }

    @Test
    @DisplayName("采样率一致时直接返回原数组（不复制）")
    void sameRateIsIdentity() {
        short[] in = {1, 2, 3};
        assertThat(Resampler.resample(in, 32_000, 32_000)).isSameAs(in);
    }

    // ── 切片与组帧 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("一句 11000 采样切两帧：首帧不完整、末帧 complete，chunkId 与 seq 递增")
    void slicesOneSentence() {
        short[] pcm = new short[11_000];
        SpeechFramer framer = new SpeechFramer(OP, MSG);
        List<ArcadeFrame> frames = framer.sentence(pcm, 0);

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).complete()).isFalse();
        assertThat(frames.get(1).complete()).isTrue();
        assertThat(frames.get(0).pcmSamples()).isEqualTo(ArcadeFrame.FRAME_SAMPLES);
        assertThat(frames.get(1).pcmSamples()).isEqualTo(11_000 - ArcadeFrame.FRAME_SAMPLES);

        assertThat(frames).extracting(ArcadeFrame::sequence).containsExactly(0, 1);
        assertThat(frames).extracting(ArcadeFrame::chunkId).containsExactly(0, 1);
        assertThat(frames).extracting(ArcadeFrame::blockId).containsOnly(0);
        assertThat(frames).extracting(ArcadeFrame::messageId).containsOnly(MSG);
        assertThat(frames).allMatch(ArcadeFrame::isChatAudio);
    }

    @Test
    @DisplayName("句间静音拼在句尾（落在最后一帧里），而不是单独一帧")
    void gapIsAppendedIntoLastFrame() {
        short[] pcm = new short[11_000];
        SpeechFramer framer = new SpeechFramer(OP, MSG);
        List<ArcadeFrame> frames = framer.sentence(pcm, 0, SpeechFramer.DEFAULT_GAP_MS);

        // 11000 + 9600(300ms@32k) = 20600 → 4 帧：5760*3 + 3320
        assertThat(frames).hasSize(4);
        assertThat(frames.get(3).pcmSamples()).isEqualTo(20_600 - ArcadeFrame.FRAME_SAMPLES * 3);
        assertThat(frames.stream().filter(ArcadeFrame::complete).count()).isEqualTo(1);
        assertThat(frames.get(3).complete()).isTrue();
    }

    @Test
    @DisplayName("多句：chunkId 跨句累计、blockId 随句递增、seq 单调")
    void chunkIdAccumulatesAcrossSentences() {
        SpeechFramer framer = new SpeechFramer(OP, MSG);
        List<ArcadeFrame> first = framer.sentence(new short[6_000], 0);
        List<ArcadeFrame> second = framer.sentence(new short[7_000], 1);

        assertThat(first).extracting(ArcadeFrame::chunkId).containsExactly(0, 1);
        assertThat(second).extracting(ArcadeFrame::chunkId).containsExactly(2, 3);
        assertThat(second).extracting(ArcadeFrame::blockId).containsOnly(1);
        assertThat(second.get(0).sequence()).isEqualTo(2);
        assertThat(framer.nextChunkId()).isEqualTo(4);
    }

    @Test
    @DisplayName("空句也要发一个 complete 帧，否则该 block 永远悬着、客户端不 ack")
    void emptySentenceStillCompletes() {
        SpeechFramer framer = new SpeechFramer(OP, MSG);
        List<ArcadeFrame> frames = framer.sentence(new short[0], 2);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).complete()).isTrue();
        assertThat(frames.get(0).pcm()).isEmpty();
        assertThat(frames.get(0).blockId()).isEqualTo(2);
    }

    @Test
    @DisplayName("整条链路：48k WAV → 解码 → 32k → 组帧，时长守恒")
    void endToEndDurationIsPreserved() {
        short[] at48k = new short[48_000]; // 整整 1 秒
        DecodedWav decoded = WavDecoder.decode(wav(at48k, 48_000, 1));
        short[] at32k = Resampler.resample(decoded.samples(), decoded.sampleRate(), ArcadeFrame.OUT_RATE);

        List<ArcadeFrame> frames = new SpeechFramer(OP, MSG).sentence(at32k, 0, 0);
        int totalSamples = frames.stream().mapToInt(ArcadeFrame::pcmSamples).sum();

        assertThat(at32k).hasSize(32_000);
        assertThat(totalSamples).isEqualTo(32_000);
        assertThat(frames).hasSize((32_000 + ArcadeFrame.FRAME_SAMPLES - 1) / ArcadeFrame.FRAME_SAMPLES);
        assertThat(frames.get(frames.size() - 1).complete()).isTrue();
    }
}
