package net.siniplise.noriosbackend.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * WAV 解码：只认 16-bit PCM，其余一律显式失败。
 *
 * <p>行为对齐旧代理（{@code nori_proxy.js} 的 {@code decodeWavInt16}）：
 * 从 offset 12 逐块走 {@code id + size}，找 {@code fmt } 与 {@code data}，块按 2 字节对齐跳过。
 * 刻意不引入第三方音频库——这段逻辑 60 行，加个依赖反而多一个出错面。
 *
 * <p>为什么不"尽力而为"：TTS 回的如果不是 16bit PCM，说明上游契约变了，
 * 这时静默播出一段噪音比直接抛异常难查得多。
 */
public final class WavDecoder {

    private static final int PCM_FORMAT = 1;
    private static final int HEADER_SCAN_START = 12;
    private static final int CHUNK_HEADER_BYTES = 8;

    /** 解析 WAV 字节，返回采样与格式。 */
    public static DecodedWav decode(byte[] wav) {
        if (wav == null || wav.length < 44) {
            throw new IllegalArgumentException("WAV 太短: " + (wav == null ? "null" : wav.length) + " 字节");
        }
        if (!"RIFF".equals(ascii(wav, 0)) || !"WAVE".equals(ascii(wav, 8))) {
            throw new IllegalArgumentException("不是 RIFF/WAVE: " + ascii(wav, 0) + "/" + ascii(wav, 8));
        }

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int audioFormat = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        byte[] data = null;

        int offset = HEADER_SCAN_START;
        while (offset + CHUNK_HEADER_BYTES <= wav.length) {
            String id = ascii(wav, offset);
            int size = buf.getInt(offset + 4);
            int body = offset + CHUNK_HEADER_BYTES;
            if ("fmt ".equals(id)) {
                audioFormat = buf.getShort(body) & 0xFFFF;
                channels = buf.getShort(body + 2) & 0xFFFF;
                sampleRate = buf.getInt(body + 4);
                bitsPerSample = buf.getShort(body + 14) & 0xFFFF;
            } else if ("data".equals(id)) {
                int end = Math.min(wav.length, body + size);
                data = new byte[end - body];
                System.arraycopy(wav, body, data, 0, data.length);
            }
            // 块按偶数字节对齐（RIFF 规范），奇数字节后面有一个填充字节
            offset = body + size + (size & 1);
        }

        if (data == null || channels == 0) {
            throw new IllegalArgumentException("WAV 缺少 fmt/data 块");
        }
        if (audioFormat != PCM_FORMAT) {
            throw new IllegalArgumentException("只支持 16-bit PCM，实际 audioFormat=" + audioFormat);
        }
        if (bitsPerSample != 16) {
            throw new IllegalArgumentException("只支持 16-bit PCM，实际 bitsPerSample=" + bitsPerSample);
        }

        int count = data.length / 2;
        short[] samples = new short[count];
        ByteBuffer body = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            samples[i] = body.getShort(i * 2);
        }
        return new DecodedWav(samples, sampleRate, channels);
    }

    private static String ascii(byte[] buf, int offset) {
        if (offset + 4 > buf.length) {
            return "";
        }
        return new String(buf, offset, 4, StandardCharsets.US_ASCII);
    }

    private WavDecoder() {
        throw new AssertionError("no instances");
    }
}
