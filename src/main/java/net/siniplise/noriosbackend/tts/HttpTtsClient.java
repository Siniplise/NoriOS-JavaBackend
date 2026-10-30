package net.siniplise.noriosbackend.tts;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Inworld TTS 的整段客户端（`POST 8880/tts`）。
 *
 * <p>为什么先做整段、不做 `/tts.stream`：
 * 用户明确把前端流式关了（Inworld 流式延迟太大），实测 4 次 `/nori-tts` 全是整段 WAV。
 * 先实现"现在真正被调用"的那条路，流式留一个 TODO——先做没人走的分支是浪费。
 *
 * <p><b>超时</b>：实测整段耗时 1.2–3.7s（p50 2.3s）。给 30s 上限是防上游卡死把队列拖住；
 * 连接超时 3s，因为 8880 在本机，连不上就是没起——那种情况应该秒失败并给出明确提示
 * （实测最常见的故障就是 8880 没起来，错误是 `ECONNREFUSED`）。
 *
 * <p><b>并发</b>：这里不管并发，闸门在上层（旧栈是上限 3，`NORI_TTS_PARALLEL`）。
 * 客户端保持无状态，方便上层任意调度。
 */
@Component
public class HttpTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTtsClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final String defaultVoice;
    private final WebClient client;

    public HttpTtsClient(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        ConfigLoader.Loaded loaded = configProvider.getIfAvailable(ConfigLoader::load);
        this.endpoint = URI.create(loaded.config().upstreams().tts() + "/tts");
        this.defaultVoice = loaded.config().tts().voice();
        this.client = WebClient.builder().build();
        log.info("tts: 合成端点 {}", endpoint);
    }

    @Override
    public Mono<short[]> synthesize32k(String text, String voice) {
        if (text == null || text.isBlank()) {
            return Mono.error(new IllegalArgumentException("text 不能为空"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        String useVoice = voice == null || voice.isBlank() ? defaultVoice : voice;
        if (useVoice != null && !useVoice.isBlank()) {
            body.put("voice", useVoice);
        }

        return client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() >= 400, response -> response
                        .bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errorBody -> new TtsException(
                                "TTS 上游返回 " + response.statusCode().value() + ": " + shorten(errorBody))))
                .bodyToMono(byte[].class)
                .map(this::toSamples)
                .doOnError(error -> log.error("tts: 合成失败 text[{}]{}: {}",
                        text.length() > 12 ? text.substring(0, 12) + "…" : text, " ", error.toString()));
    }

    /** WAV → 16bit → 重采样到 32k。非 PCM 显式失败（WavDecoder 会抛）。 */
    private short[] toSamples(byte[] wav) {
        DecodedWav decoded = WavDecoder.decode(wav);
        short[] samples = Resampler.resample(decoded.samples(), decoded.sampleRate(), ArcadeFrame.OUT_RATE);
        log.debug("tts: {} 字节 WAV，{}Hz/{}ch，时长 {}ms → {} 采样@32k",
                wav.length, decoded.sampleRate(), decoded.channels(),
                Math.round(decoded.seconds() * 1000), samples.length);
        return samples;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    /** TTS 侧的显式错误：带上 HTTP 状态与上游原话，便于区分「没起」和「合成失败」。 */
    public static final class TtsException extends RuntimeException {
        public TtsException(String message) {
            super(message);
        }
    }
}
