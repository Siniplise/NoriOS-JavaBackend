package net.siniplise.noriosbackend.game;

import java.net.URI;
import java.time.Duration;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `/game-turn` 的 HTTP 实现。
 *
 * <p>端点就是人格服务（25503）+ {@code /game-turn}，与 `nori_game.js` 的
 * {@code BRAIN_URL} 同一个地址。**这就是"先用 25503"的全部含义**：
 * 她不只会说台词，还会替你下棋、出牌、猜词。
 *
 * <p><b>超时 8 秒</b>（对齐 JS 的 {@code HOLD_MS}）。给得比 persona 的 3 秒宽，
 * 因为这是一次真正的推理（LLM 要读局面 + 产出结构化动作），不是一句文案。
 * 超时不是灾难：调用方应当退回到"本地兜底策略"（JS 侧有 {@code pickFallback} /
 * {@code pickChessFallback}），继续把这一回合走完。
 *
 * <p><b>响应非 2xx 时把 body 也带进异常</b>：25503 出错时往往在 body 里写原因
 * （比如模型 key 缺失），只报状态码会让排查多绕一圈。
 */
@Component
public class HttpGameBrain implements GameBrain {

    private static final Logger log = LoggerFactory.getLogger(HttpGameBrain.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 与 JS 的 {@code HOLD_MS} 一致。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final URI endpoint;
    private final WebClient client;

    /**
     * ★ 必须标 {@code @Autowired}：本类有**两个**构造器，Spring 遇到多构造器时不会自己挑，
     * 结果是 {@code BeanCreationException}，而且报错发生在整个上下文启动阶段 —— 所有
     * {@code @SpringBootTest} 风格的用例会一起红，看着像"全崩了"。这个坑本项目已经踩过第二次。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HttpGameBrain(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        this(configProvider.getIfAvailable(ConfigLoader::load).config().upstreams().persona());
    }

    /**
     * 直接指定人格服务地址（测试/多环境用）。
     *
     * <p>抽出来是为了让"完整链路"能被测：真实 fen → 我们的请求 → 25503 → 解析她选的走法。
     * 那条链路里最容易错的是**请求形状**，而它只有真发出去才能验。
     */
    public HttpGameBrain(String personaBase) {
        this.endpoint = URI.create(personaBase + "/game-turn");
        this.client = WebClient.builder().build();
        log.info("game: 大脑端点 {}", endpoint);
    }

    @Override
    public Mono<GameTurnReply> turn(GameTurnRequest request) {
        long t0 = System.currentTimeMillis();
        return client.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.toBody())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                // 响应是文本：先自己 parse，才能在 ok=false 时给出比"空结果"更有用的错误
                .map(body -> {
                    JsonNode node;
                    try {
                        node = MAPPER.readTree(body);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("大脑回复不是合法 JSON: " + brief(body), e);
                    }
                    GameTurnReply reply = GameTurnReply.parse(node, System.currentTimeMillis() - t0);
                    if (reply == null) {
                        // ok=false 或结构不对：这是"大脑宕了"，不是"没有动作"。
                        throw new IllegalStateException("大脑回复 ok=false: " + brief(body));
                    }
                    log.debug("game: 大脑 {} obey={} acts={} emo={} img={} {}ms",
                            request.game(), reply.obeyed(), reply.actionCount(), reply.emotion(),
                            request.image() != null ? "yes" : "no", reply.ms());
                    return reply;
                })
                .doOnError(e -> log.warn("game: 大脑调用失败 game={} : {}", request.game(), e.toString()));
    }

    /** 日志里只留前 200 字，避免把整个局面回显刷满。 */
    private static String brief(String s) {
        if (s == null) {
            return "(null)";
        }
        String one = s.replace('\n', ' ');
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
