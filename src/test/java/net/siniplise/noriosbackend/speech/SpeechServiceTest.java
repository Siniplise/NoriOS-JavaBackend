package net.siniplise.noriosbackend.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.tts.SpeechFramer;
import net.siniplise.noriosbackend.tts.TtsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 合成 + 组帧的组合行为。
 *
 * <p>盯的是「一句说完，block 必须收尾」这条：末帧没 complete，客户端不 ack，队列就卡住；
 * 中间帧带了 complete，worklet 提前结束，下一句不播。两个方向的错都要能测出来。
 */
class SpeechServiceTest {

    private static TtsClient fakeTts(int samplesPerSecond, int sampleRateUsed) {
        return new TtsClient() {
            @Override
            public Mono<short[]> synthesize32k(String text, String voice) {
                short[] pcm = new short[samplesPerSecond];
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) (i % 1000);
                }
                return Mono.just(pcm);
            }
        };
    }

    @Test
    @DisplayName("1 秒音频 → 6 帧（5760×5 + 3200），只有末帧 complete，chunkId 连续")
    void framesOneSecond() {
        SpeechService service = new SpeechService(fakeTts(32_000, 32_000));
        SpeechFramer framer = new SpeechFramer(UUID.randomUUID(), UUID.randomUUID());

        List<ArcadeFrame> frames = service.framesFor(framer, "你好", null, 0, 0)
                .block(Duration.ofSeconds(5));

        assertThat(frames).isNotNull();
        assertThat(frames).hasSize(6);
        assertThat(frames.get(5).pcmSamples()).isEqualTo(32_000 - ArcadeFrame.FRAME_SAMPLES * 5);
        assertThat(frames.stream().filter(ArcadeFrame::complete).count()).isEqualTo(1);
        assertThat(frames.get(5).complete()).isTrue();
        assertThat(frames).extracting(ArcadeFrame::chunkId).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(frames).extracting(ArcadeFrame::sequence).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(frames).allMatch(f -> f.blockId() == 0);
    }

    @Test
    @DisplayName("带 300ms 静音：总帧数增加，静音落在末帧里，仍然只有一个 complete")
    void gapGoesIntoLastFrame() {
        SpeechService service = new SpeechService(fakeTts(32_000, 32_000));
        SpeechFramer framer = new SpeechFramer(UUID.randomUUID(), UUID.randomUUID());

        List<ArcadeFrame> frames = service.framesFor(framer, "你好", null, 3, 300)
                .block(Duration.ofSeconds(5));

        // 32000 + 9600 = 41600 采样 → 8 帧（5760×7=40320，剩 1280）
        assertThat(frames).hasSize(8);
        assertThat(frames.get(7).pcmSamples()).isEqualTo(41_600 - ArcadeFrame.FRAME_SAMPLES * 7);
        assertThat(frames.stream().filter(ArcadeFrame::complete).count()).isEqualTo(1);
        assertThat(frames.get(0).blockId()).isEqualTo(3);
    }

    @Test
    @DisplayName("两句连续：第二句的 chunkId 接着第一句（回复内累计），seq 也接着")
    void secondSentenceContinuesCounters() {
        SpeechService service = new SpeechService(fakeTts(32_000, 32_000));
        SpeechFramer framer = new SpeechFramer(UUID.randomUUID(), UUID.randomUUID());

        List<ArcadeFrame> first = service.framesFor(framer, "第一句", null, 0, 0)
                .block(Duration.ofSeconds(5));
        List<ArcadeFrame> second = service.framesFor(framer, "第二句", null, 1, 0)
                .block(Duration.ofSeconds(5));

        assertThat(first.get(0).chunkId()).isZero();
        assertThat(second.get(0).chunkId()).as("chunkId 跨句累计，不是每句重置").isEqualTo(first.size());
        assertThat(second.get(0).sequence()).isEqualTo(first.size());
        assertThat(second).extracting(ArcadeFrame::blockId).containsOnly(1);
    }
}
