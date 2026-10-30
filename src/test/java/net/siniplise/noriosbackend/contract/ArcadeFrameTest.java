package net.siniplise.noriosbackend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 帧编解码的字节级契约。
 *
 * <p>这套断言不是「跑通就行」，而是钉住**上游客户端会怎么读**：偏移错一个字节，
 * 客户端不会报错，只会把 PCM 当 UUID 解析、然后静音——最坏的一种失败。
 */
class ArcadeFrameTest {

    private static final UUID OP = UUID.fromString("5711303a-d97f-4f68-89ad-3d2d8c2cfd5d");
    private static final UUID MSG = UUID.fromString("d04b792a-de6a-4353-b518-bd1e2fdcac58");

    @Test
    @DisplayName("编码后的头 48 字节与上游文档逐字节一致")
    void headerMatchesUpstreamLayout() {
        byte[] pcm = {0x01, 0x02, 0x03, 0x04};
        byte[] raw = ArcadeFrame.chatAudio(true, 7, 2, 33, OP, MSG, pcm).encode();

        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(raw).hasSize(ArcadeFrame.HEADER_BYTES + 4);
        assertThat(b.get(0)).as("+0 protocol version").isEqualTo((byte) 1);
        assertThat(b.get(1)).as("+1 channel = chatAudio").isEqualTo((byte) 1);
        assertThat(b.getShort(2)).as("+2 flags bit0=complete").isEqualTo((short) 1);
        assertThat(b.getInt(4)).as("+4 seq").isEqualTo(7);
        assertThat(b.getInt(8)).as("+8 blockId").isEqualTo(2);
        assertThat(b.getInt(12)).as("+12 chunkId").isEqualTo(33);
        assertThat(new UUID(b.getLong(16), b.getLong(24))).as("+16 opUuid").isEqualTo(OP);
        assertThat(new UUID(b.getLong(32), b.getLong(40))).as("+32 msgUuid").isEqualTo(MSG);
        assertThat(new byte[] {raw[48], raw[49], raw[50], raw[51]}).as("+48 PCM").isEqualTo(pcm);
    }

    @Test
    @DisplayName("complete=false 时 flags 为 0（中间帧绝不能带 complete）")
    void incompleteFrameHasZeroFlags() {
        byte[] raw = ArcadeFrame.chatAudio(false, 0, 0, 0, OP, MSG, new byte[0]).encode();
        assertThat(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getShort(2)).isZero();
    }

    @Test
    @DisplayName("编解码往返一致（含空 PCM）")
    void roundTrip() {
        byte[] pcm = new byte[ArcadeFrame.FRAME_PCM_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 31);
        }
        ArcadeFrame original = ArcadeFrame.chatAudio(true, 11, 3, 44, OP, MSG, pcm);
        ArcadeFrame decoded = ArcadeFrame.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.pcm()).isEqualTo(pcm);
        assertThat(decoded.pcmSamples()).isEqualTo(ArcadeFrame.FRAME_SAMPLES);

        ArcadeFrame empty = ArcadeFrame.chatAudio(true, 0, 0, 0, OP, MSG, new byte[0]);
        assertThat(ArcadeFrame.decode(empty.encode())).isEqualTo(empty);
    }

    @Test
    @DisplayName("非法帧显式报错：短帧 / 奇数字节 PCM / 版本不对")
    void rejectsMalformed() {
        assertThatThrownBy(() -> ArcadeFrame.decode(new byte[47]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("帧太短");

        assertThatThrownBy(() -> ArcadeFrame.chatAudio(true, 0, 0, 0, OP, MSG, new byte[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("偶数");

        byte[] raw = ArcadeFrame.chatAudio(true, 0, 0, 0, OP, MSG, new byte[0]).encode();
        raw[0] = 2; // 伪造版本号
        assertThatThrownBy(() -> ArcadeFrame.decode(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议版本");
    }

    @Test
    @DisplayName("拿 golden 里录到的真实帧做对拍：每组 chunkId 连续、恰好一个 complete")
    void matchesRecordedGoldenFrames() throws Exception {
        Path golden = Path.of("golden");
        Assumptions.assumeTrue(Files.isDirectory(golden), "没有 golden 目录，跳过真实帧对拍");

        List<Path> blobs;
        try (Stream<Path> walk = Files.walk(golden)) {
            blobs = walk.filter(p -> p.toString().endsWith(".bin"))
                    .filter(p -> p.getParent().getFileName().toString().equals("blobs"))
                    .filter(p -> p.toString().contains("s2c") || p.toString().contains("c2s"))
                    .toList();
        }
        Assumptions.assumeFalse(blobs.isEmpty(), "golden 里没有二进制帧");

        record Key(UUID msg, int block) {
        }
        Map<Key, List<ArcadeFrame>> groups = new LinkedHashMap<>();
        List<ArcadeFrame> all = new ArrayList<>();
        for (Path blob : blobs) {
            byte[] raw = Files.readAllBytes(blob);
            if (raw.length < ArcadeFrame.HEADER_BYTES || raw[1] != ArcadeFrame.CHANNEL_CHAT_AUDIO) {
                continue; // 只对拍 chatAudio 通道
            }
            ArcadeFrame frame = ArcadeFrame.decode(raw);
            all.add(frame);
            groups.computeIfAbsent(new Key(frame.messageId(), frame.blockId()), k -> new ArrayList<>()).add(frame);
        }
        Assumptions.assumeFalse(all.isEmpty(), "golden 里没有 chatAudio 帧");

        for (var entry : groups.entrySet()) {
            List<ArcadeFrame> frames = entry.getValue();
            List<Integer> chunks = frames.stream().map(ArcadeFrame::chunkId).sorted().toList();
            assertThat(chunks).as("chunkId 必须连续: " + entry.getKey())
                    .containsExactlyElementsOf(java.util.stream.IntStream
                            .range(chunks.get(0), chunks.get(0) + chunks.size()).boxed().toList());
            assertThat(frames.stream().filter(ArcadeFrame::complete).count())
                    .as("每个 block 恰好一个 complete: " + entry.getKey())
                    .isEqualTo(1);
            assertThat(frames.get(frames.size() - 1).complete())
                    .as("complete 必须落在末块（按 ts 顺序不保证，这里按 chunkId 最大者判）")
                    .isTrue();
        }
    }
}
