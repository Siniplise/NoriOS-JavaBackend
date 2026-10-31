package net.siniplise.noriosbackend.persona;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * `/nori-scriptline`、`/game-note`、`/state` 的 HTTP 实现。
 *
 * <p>超时给得短（3s）：这些调用都在"说台词"的路径旁边，
 * 上游卡住时宁可丢一次记忆写入，也不能把语音链路拖慢。
 *
 * <p>{@code /nori-scriptline} 的 body 字段名必须与 `n_llm.js` 一致：
 * `beat / emotion / lines / memory / importance / transient`。
 * 少一个 `transient` 的后果是——重复摸头会不断建卡，把长期记忆挤满。
 */
@Component
public class HttpPersonaClient implements PersonaClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPersonaClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final URI base;
    private final WebClient client;

    public HttpPersonaClient(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        ConfigLoader.Loaded loaded = configProvider.getIfAvailable(ConfigLoader::load);
        this.base = URI.create(loaded.config().upstreams().persona());
        this.client = WebClient.builder().build();
        log.info("persona: 客户端指向 {}", base);
    }

    @Override
    public Mono<Void> writeScriptLine(ScriptLineWrite write) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("beat", write.beat());
        body.put("emotion", write.emotion() == null ? "neutral" : write.emotion());
        body.put("lines", write.lines());
        if (write.memory() != null && !write.memory().isBlank()) {
            body.put("memory", write.memory());
        }
        if (write.memory() != null) {
            body.put("importance", write.importance());
        }
        // 只有 true 才发：缺省 false，发 false 也无害，但少一个字段少一处误解
        if (write.transientOnly()) {
            body.put("transient", true);
        }

        return client.post()
                .uri(base + "/nori-scriptline")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .doOnError(error -> log.warn("persona: 写 scriptline 失败 beat={}（旁路，不影响上屏）: {}",
                        write.beat(), error.toString()))
                .then();
    }

    @Override
    public Mono<Void> gameNote(String game, int dropped) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game", game == null ? "" : game);
        body.put("dropped", dropped);

        return client.post()
                .uri(base + "/game-note")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .doOnError(error -> log.warn("persona: 写 game-note 失败 game={}: {}", game, error.toString()))
                .then();
    }

    @Override
    public Mono<String> state() {
        return client.get()
                .uri(base + "/state")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT);
    }
}
