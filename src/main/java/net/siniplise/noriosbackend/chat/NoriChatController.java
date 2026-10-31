package net.siniplise.noriosbackend.chat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `/v1/chat/completions`：命中固定台词的自己答，其余原样转发人格服务。
 *
 * <p>三条硬约束（都能在 HANDOFF §3.2 找到出处）：
 * <ol>
 *   <li><b>游戏回合放行</b>：`response_format.type == "json_object"` 一律转发；</li>
 *   <li><b>命中不转发</b>：省一次 LLM 调用，且保证台词与剧本一致；</li>
 *   <li><b>响应体必须与 OpenAI 格式兼容</b>：客户端按 `choices[0].message.content` 取值，
 *       少一层就白屏。</li>
 * </ol>
 *
 * <p><b>转发为什么用 writeWith 而不是返回字符串</b>：人格服务可能回 SSE 流
 * （`stream: true` 时）。先缓冲再返回会把"逐字上屏"变成"憋完再炸出来"，
 * 而且 SSE 的 `data:` 分帧会被错误地当成一整个字符串。
 */
@RestController
public class NoriChatController {

    private static final Logger log = LoggerFactory.getLogger(NoriChatController.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ChatGate gate;
    private final URI persona;
    private final org.springframework.web.reactive.function.client.WebClient client;

    public NoriChatController(ObjectProvider<ConfigLoader.Loaded> configProvider, ChatGate gate) {
        ConfigLoader.Loaded loaded = configProvider.getIfAvailable(ConfigLoader::load);
        this.gate = gate;
        this.persona = URI.create(loaded.config().upstreams().persona());
        this.client = org.springframework.web.reactive.function.client.WebClient.builder().build();
        log.info("chat: LLM 出口 {}（命中规则走固定台词）", persona);
    }

    @PostMapping(path = NoriPaths.V1_CHAT_COMPLETIONS)
    public Mono<Void> chat(ServerWebExchange exchange, @RequestBody(required = false) byte[] rawBody) {
        ChatRequestView view = parse(rawBody);
        var hit = gate.match(view);

        if (hit.isEmpty()) {
            return forward(exchange, rawBody);
        }

        ChatGate.Hit h = hit.get();
        log.info("chat: 固定回应 beat={} why={} lines={}", h.beatId(), h.why(), h.lines().size());
        return writeFixedReply(exchange.getResponse(), h);
    }

    /** 解析失败一律当"放行"——解析错是我们的事，不能因此吞掉玩家的请求。 */
    private ChatRequestView parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(new String(rawBody, StandardCharsets.UTF_8));
            return ChatRequestView.parse(root);
        } catch (RuntimeException e) {
            log.warn("chat: 请求体解析失败，按放行处理: {}", e.toString());
            return null;
        }
    }

    /** 固定回应：格式与旧栈 `sendFixed` 完全一致（`chatcmpl-nori-fixed`、content 带 emotion 前缀）。 */
    private Mono<Void> writeFixedReply(ServerHttpResponse response, ChatGate.Hit hit) {
        StringBuilder content = new StringBuilder();
        if (hit.emotion() != null && !hit.emotion().isBlank()) {
            content.append("[emotion:").append(hit.emotion()).append("]\n");
        }
        content.append(String.join("\n", hit.lines()));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content.toString());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "chatcmpl-nori-fixed");
        body.put("object", "chat.completion");
        body.put("created", System.currentTimeMillis() / 1000);
        body.put("model", "nori-fixed");
        body.put("choices", java.util.List.of(choice));
        body.put("usage", Map.of());

        byte[] json = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(json);
        return response.writeWith(Flux.just(buffer));
    }

    /** 原样转发到人格服务（流式透传）。 */
    private Mono<Void> forward(ServerWebExchange exchange, byte[] rawBody) {
        ServerHttpRequest request = exchange.getRequest();
        String pathAndQuery = request.getURI().getRawPath()
                + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());

        return client.post()
                .uri(URI.create(persona + pathAndQuery))
                .headers(headers -> {
                    headers.addAll(exchange.getRequest().getHeaders());
                    headers.remove(HttpHeaders.HOST);
                    headers.remove(HttpHeaders.CONTENT_LENGTH);
                    headers.remove(HttpHeaders.CONNECTION);
                    headers.remove(HttpHeaders.TRANSFER_ENCODING);
                })
                .bodyValue(rawBody == null ? new byte[0] : rawBody)
                .exchangeToMono(response -> {
                    ServerHttpResponse out = exchange.getResponse();
                    out.setStatusCode(response.statusCode());
                    HttpHeaders headers = new HttpHeaders();
                    headers.putAll(response.headers().asHttpHeaders());
                    headers.remove(HttpHeaders.CONTENT_LENGTH);
                    headers.remove(HttpHeaders.TRANSFER_ENCODING);
                    headers.remove(HttpHeaders.CONNECTION);
                    out.getHeaders().addAll(headers);
                    return out.writeWith(response.bodyToFlux(DataBuffer.class));
                })
                .onErrorResume(error -> {
                    log.error("chat: 转发人格服务失败 {}: {}", persona, error.toString());
                    return writeError(exchange, "persona unreachable");
                });
    }

    private Mono<Void> writeError(ServerWebExchange exchange, String message) {
        ServerHttpResponse out = exchange.getResponse();
        if (out.isCommitted()) {
            return Mono.empty();
        }
        out.setStatusCode(HttpStatus.BAD_GATEWAY);
        out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return out.writeWith(Flux.just(out.bufferFactory()
                .wrap(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8))));
    }
}
