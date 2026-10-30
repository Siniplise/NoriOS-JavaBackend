package net.siniplise.noriosbackend.tts;

/**
 * 一次 TTS 返回的 16-bit PCM 音频。
 *
 * @param samples    采样（16bit 有符号；立体声时是交错的，与旧代理读法一致）
 * @param sampleRate 采样率（Inworld 整段模式给的是 48000）
 * @param channels   声道数（当前实际只用 1）
 */
public record DecodedWav(short[] samples, int sampleRate, int channels) {

    /** 时长（秒），用来打日志、对账。 */
    public double seconds() {
        if (sampleRate <= 0 || channels <= 0) {
            return 0;
        }
        return (double) samples.length / channels / sampleRate;
    }
}
