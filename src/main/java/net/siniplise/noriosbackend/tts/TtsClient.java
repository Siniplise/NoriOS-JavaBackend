package net.siniplise.noriosbackend.tts;

import reactor.core.publisher.Mono;

/**
 * TTS 合成客户端：文本 → 32kHz 单声道 PCM。
 *
 * <p>为什么对外统一成 32k：前端 worklet 固定 32k（`OUT_RATE`），而 Inworld 整段模式回 48k。
 * 把「解码 + 重采样」收在实现里，调用方拿到的就是可直接切片的采样，
 * 少一个「忘了重采样」的出错点（那种错只表现为音调怪，不会报错）。
 *
 * <p><b>契约</b>：返回的 Mono 必须 emit 恰好一次；失败要抛带可读原因的异常
 * （上游 502/超时/非 PCM 都要能分辨），不要静默降级成"空音频"——
 * 静默降级的后果是「她不说话但也没报错」，比崩掉更难查。
 */
public interface TtsClient {

    /** 合成一句，返回 32kHz 单声道 16bit PCM。 */
    Mono<short[]> synthesize32k(String text, String voice);

    /** 用配置里的默认声音合成。 */
    default Mono<short[]> synthesize32k(String text) {
        return synthesize32k(text, null);
    }
}
