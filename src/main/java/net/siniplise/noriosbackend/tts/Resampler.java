package net.siniplise.noriosbackend.tts;

/**
 * 16-bit PCM 线性重采样。
 *
 * <p>逐行对齐旧代理的 {@code resampleInt16}：线性插值、四舍五入、越界钳位、尾部用最后一个样本兜住。
 * 不追求音质（没有抗混叠滤波），追求**与旧实现逐样本一致**——影子对比时任何差异都必须是真实差异，
 * 而不是我们换算法引入的噪声。
 *
 * <p>为什么需要它：TTS 整段模式回的是 48kHz WAV，而前端 worklet 固定 32k（{@code OUT_RATE}）。
 * 流式模式（{@code /tts.stream}）直接就是 32k，可以跳过这一步。
 */
public final class Resampler {

    /** 采样率相同则原样返回（不复制，省一次 200KB 级的内存搬动）。 */
    public static short[] resample(short[] samples, int inRate, int outRate) {
        if (inRate == outRate) {
            return samples;
        }
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("采样率必须为正: " + inRate + " -> " + outRate);
        }

        double ratio = (double) outRate / inRate;
        int outCount = (int) Math.floor(samples.length * ratio);
        short[] out = new short[outCount];
        if (samples.length == 0) {
            return out;
        }

        for (int i = 0; i < outCount; i++) {
            double src = i / ratio;
            int i0 = (int) Math.floor(src);
            double frac = src - i0;
            int a = i0 < samples.length ? samples[i0] : 0;
            int b = samples[Math.min(i0 + 1, samples.length - 1)];
            int v = (int) Math.round(a + (b - a) * frac);
            if (v > Short.MAX_VALUE) {
                v = Short.MAX_VALUE;
            } else if (v < Short.MIN_VALUE) {
                v = Short.MIN_VALUE;
            }
            out[i] = (short) v;
        }
        return out;
    }

    private Resampler() {
        throw new AssertionError("no instances");
    }
}
