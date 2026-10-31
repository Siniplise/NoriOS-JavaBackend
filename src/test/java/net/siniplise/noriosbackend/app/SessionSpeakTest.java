package net.siniplise.noriosbackend.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.speech.MediaSink;
import net.siniplise.noriosbackend.speech.MediaSinkProvider;
import net.siniplise.noriosbackend.tts.TtsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 「合成 → 组帧 → 推给媒体通道」这条链路的端到端验证（不依赖网络）。
 *
 * <p>盯三件事：
 * <ol>
 *   <li>帧按 seq 顺序、连续发出——乱序就是客户端拼错音频（Q5）；</li>
 *   <li>每句只有一个 complete，且在末帧——错了就卡 reveal（Q4 的同源问题）；</li>
 *   <li>没有媒体连接时不能抛错，只能是"发了 0 帧"——否则一句台词能让整个会话崩掉。</li>
 * </ol>
 */
class SessionSpeakTest {

    /** 收集器：模拟 WS 出口。 */
    private static final class CollectingSink implements MediaSink {
        final List<ArcadeFrame> frames = Collections.synchronizedList(new ArrayList<>());
        volatile boolean open = true;

        @Override
        public void send(ArcadeFrame frame) {
            if (open) {
                frames.add(frame);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    private static TtsClient fixedTts(int samples) {
        return (text, voice) -> Mono.just(new short[samples]);
    }

    @Test
    @DisplayName("一句话：帧按 seq 连续、chunkId 从 0 起、末帧 complete")
    void speakPushesOrderedFrames() throws Exception {
        CollectingSink sink = new CollectingSink();
        SessionRuntime runtime = new SessionRuntime("test", fixedTts(32_000), () -> sink);
        try {
            int sent = runtime.speak("你好", null, 0, 0).get(5, TimeUnit.SECONDS);

            assertThat(sent).isEqualTo(6);
            assertThat(sink.frames).hasSize(6);
            assertThat(sink.frames).extracting(ArcadeFrame::sequence)
                    .containsExactly(0, 1, 2, 3, 4, 5);
            assertThat(sink.frames).extracting(ArcadeFrame::chunkId)
                    .containsExactly(0, 1, 2, 3, 4, 5);
            assertThat(sink.frames.stream().filter(ArcadeFrame::complete).count()).isEqualTo(1);
            assertThat(sink.frames.get(5).complete()).isTrue();
            assertThat(sink.frames).allMatch(f -> f.blockId() == 0);
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("同一回复两句：seq/chunkId 接着走，blockId 递增")
    void secondSentenceContinues() throws Exception {
        CollectingSink sink = new CollectingSink();
        SessionRuntime runtime = new SessionRuntime("test", fixedTts(6_000), () -> sink);
        try {
            runtime.speak("第一句", null, 0, 0).get(5, TimeUnit.SECONDS);
            runtime.speak("第二句", null, 1, 0).get(5, TimeUnit.SECONDS);

            List<ArcadeFrame> frames = new ArrayList<>(sink.frames);
            assertThat(frames).hasSize(4); // 每句 6000 采样 → 2 帧
            assertThat(frames).extracting(ArcadeFrame::chunkId).containsExactly(0, 1, 2, 3);
            assertThat(frames).extracting(ArcadeFrame::sequence).containsExactly(0, 1, 2, 3);
            assertThat(frames.get(0).blockId()).isZero();
            assertThat(frames.get(3).blockId()).isEqualTo(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("换回复后 chunkId 归零（blockId 从 0 重新数）")
    void beginReplyResetsCounters() throws Exception {
        CollectingSink sink = new CollectingSink();
        SessionRuntime runtime = new SessionRuntime("test", fixedTts(6_000), () -> sink);
        try {
            runtime.speak("旧回复", null, 0, 0).get(5, TimeUnit.SECONDS);
            runtime.beginReply().get(5, TimeUnit.SECONDS);
            sink.frames.clear();
            runtime.speak("新回复", null, 0, 0).get(5, TimeUnit.SECONDS);

            assertThat(sink.frames).isNotEmpty();
            assertThat(sink.frames.get(0).chunkId()).as("新回复的 chunkId 必须重新从 0 开始").isZero();
            assertThat(sink.frames.get(0).sequence()).isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("没有媒体连接：返回 0 帧而不是抛错")
    void noMediaIsNotAnError() throws Exception {
        SessionRuntime runtime = new SessionRuntime("test", fixedTts(6_000), () -> null);
        try {
            assertThat(runtime.hasMedia()).isFalse();
            assertThat(runtime.speak("没人听", null, 0, 0).get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("合成失败：异常透传给调用方，不静默")
    void synthesisFailurePropagates() {
        TtsClient broken = (text, voice) -> Mono.error(new IllegalStateException("tts down"));
        SessionRuntime runtime = new SessionRuntime("test", broken, CollectingSink::new);
        try {
            Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                    () -> runtime.speak("会失败", null, 0, 0).get(5, TimeUnit.SECONDS));
            assertThat(error).isInstanceOf(ExecutionException.class);
            assertThat(error.getCause()).hasMessageContaining("tts down");
        } finally {
            runtime.close();
        }
    }
}
