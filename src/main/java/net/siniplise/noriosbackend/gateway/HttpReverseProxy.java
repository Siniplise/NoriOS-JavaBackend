package net.siniplise.noriosbackend.gateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP 反向代理：除管理端外的所有请求原样转给游戏本体（默认 4174）。
 *
 * <h2>为什么是 WebFilter 而不是 RouterFunction</h2>
 * 先用 {@code RouterFunction + ServerResponse.body(fromDataBuffers(...))} 写过一版，
 * 结果**请求永远不返回**（15s 超时）：{@code exchangeToMono} 的 Mono 要等响应写完才完成，
 * 而响应体又要等这个 Mono 完成才被消费——互相等。改成 filter 里直接
 * {@code response.writeWith(flux)} 才是流式转发的正路：{@code writeWith} 的 Mono
 * 就是「写完」这件事本身，没有第二层语义。
 *
 * <h2>为什么必须流式</h2>
 * golden 里 465 个路径，绝大多数是静态资源（字体、m3 纹理、音频）。全缓冲会把首屏拖慢，
 * 10MB+ 的资源还会把堆顶爆。
 *
 * <h2>线程模型</h2>
 * 无共享可变状态；上游地址在构造时读一次（配置只读）。每请求一条 reactor 管道，不需要锁。
 */
@Component
public class HttpReverseProxy implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HttpReverseProxy.class);

    private final URI upstream;
    private final WebClient client;

    public HttpReverseProxy(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        ConfigLoader.Loaded loaded = configProvider.getIfAvailable(ConfigLoader::load);
        this.upstream = URI.create(loaded.config().upstreams().gameExe());
        // 自己建而不是注入 Builder：Boot 4 把 webclient 自动配置拆成独立 starter，
        // 当前依赖组合下没有 WebClient.Builder bean（实测启动即 UnsatisfiedDependency）。
        this.client = WebClient.builder().build();
        log.info("gateway: HTTP 反代目标 {}", upstream);
    }

    @Override
    public int getOrder() {
        // 排在最后：让管理端等注解控制器优先拿到机会（这里也主动放行它们的路径）
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();

        // 管理端与 Boot 错误页放行给下游 handler
        if (path.startsWith("/api/admin") || path.equals("/error")) {
            return chain.filter(exchange);
        }
        if (isOwnEndpoint(path)) {
            return fail(exchange, HttpStatus.NOT_IMPLEMENTED,
                    "{\"error\":\"not implemented yet\",\"path\":\"" + path + "\"}");
        }
        return forward(exchange);
    }

    private Mono<Void> forward(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        URI uri = request.getURI();
        String target = upstream + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        var spec = client.method(request.getMethod())
                .uri(URI.create(target))
                .headers(headers -> headers.addAll(
                        HopByHopHeaders.forUpstream(request.getHeaders(), upstream.getAuthority())));

        // GET/HEAD 不带请求体：塞一个空 body 会让上游收到 chunked 编码的"空体"，
        // 少数实现会因此挂住等数据（我们自己的假上游就踩过）。
        var pending = hasBody(request.getMethod())
                ? spec.body(BodyInserters.fromDataBuffers(request.getBody()))
                : spec;

        return pending.exchangeToMono(response -> {
                    ServerHttpResponse out = exchange.getResponse();
                    out.setStatusCode(response.statusCode());
                    out.getHeaders().addAll(HopByHopHeaders.forClient(response.headers().asHttpHeaders()));
                    return out.writeWith(response.bodyToFlux(DataBuffer.class));
                })
                .onErrorResume(error -> {
                    log.error("gateway: 转发 {} 失败: {}", target, error.toString());
                    return fail(exchange, HttpStatus.BAD_GATEWAY,
                            "{\"error\":\"upstream unreachable\",\"upstream\":\"" + upstream + "\"}");
                });
    }

    /** 直接写一个 JSON 响应（响应头若已提交就只能放弃——此时再写会触发二次响应）。 */
    private Mono<Void> fail(ServerWebExchange exchange, HttpStatus status, String json) {
        ServerHttpResponse out = exchange.getResponse();
        if (out.isCommitted()) {
            return Mono.empty();
        }
        out.setStatusCode(status);
        out.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = out.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return out.writeWith(Flux.just(buffer));
    }

    private static boolean hasBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE;
    }

    /** `/nori-*` 与 `/v1/*` 是我们的业务端点，不走上游。 */
    static boolean isOwnEndpoint(String path) {
        return path.startsWith("/nori-")
                || path.equals(NoriPaths.V1_CHAT_COMPLETIONS)
                || path.startsWith("/v1/");
    }
}
