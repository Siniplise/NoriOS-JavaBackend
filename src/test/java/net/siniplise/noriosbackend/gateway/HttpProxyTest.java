package net.siniplise.noriosbackend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.app.NoriBackendApplication;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP 反代的真实验证：起一个假上游 + 起完整应用，用 WebClient 当浏览器打过去。
 *
 * <p>为什么不用 mock：反代最容易错的不是"有没有转发"，而是**头/体/状态码在链路上被改坏**。
 * 那种错只有真跑一遍 HTTP 才看得见。
 *
 * <p>用的端口都是 0（随机），不会碰开发机上正在跑的 4174。
 */
class HttpProxyTest {

    private static HttpServer upstream;
    private static String upstreamBase;
    private static final AtomicReference<String> lastRequest = new AtomicReference<>();

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String seen = exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " host=" + exchange.getRequestHeaders().getFirst("Host")
                    + " x-test=" + exchange.getRequestHeaders().getFirst("x-test")
                    + " body=" + body;
            lastRequest.set(seen);

            // 如果我们的 /nori-* 被误转发到上游，这里会返回 500，测试立刻暴露
            if (exchange.getRequestURI().getRawPath().startsWith("/nori-")) {
                byte[] leaked = "leaked-to-upstream".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, leaked.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(leaked);
                }
                return;
            }
            byte[] payload = ("echo:" + exchange.getRequestURI().getRawPath()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-upstream", "yes");
            exchange.getResponseHeaders().add("Connection", "keep-alive"); // 逐跳头，必须被过滤掉
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private static String startAppAndProxy(String fakeUpstream) {
        ConfigLoader.Loaded loaded = ConfigLoader.load().withGameExe(fakeUpstream);
        SpringApplication app = NoriBackendApplication.newApplication(loaded);
        ConfigurableApplicationContext context = app.run("--server.port=0");
        // 关掉时回收，避免测试之间抢端口（随机端口但仍要释放）
        Runtime.getRuntime().addShutdownHook(new Thread(context::close, "test-close"));
        String port = context.getEnvironment().getProperty("local.server.port");
        return "http://127.0.0.1:" + port;
    }

    @Test
    @DisplayName("GET 透传：状态码、响应体、上游头都在；逐跳头被过滤；Host 被改写成上游")
    void proxiesGet() {
        String proxy = startAppAndProxy(upstreamBase);

        var response = WebClient.create(proxy)
                .get()
                .uri("/assets/app.js?v=1")
                .header("x-test", "hello")
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .map(body -> new Object[] {resp.statusCode().value(), body,
                                resp.headers().asHttpHeaders()}))
                .block(Duration.ofSeconds(15));

        assertThat(response).isNotNull();
        assertThat(response[0]).isEqualTo(200);
        assertThat((String) response[1]).isEqualTo("echo:/assets/app.js");

        var headers = (org.springframework.http.HttpHeaders) response[2];
        assertThat(headers.getFirst("x-upstream")).isEqualTo("yes");
        assertThat(headers.getFirst("connection"))
                .as("逐跳头不能回给客户端").isNull();

        assertThat(lastRequest.get())
                .as("查询串、自定义头要带到上游")
                .contains("GET /assets/app.js?v=1")
                .contains("x-test=hello");
        assertThat(lastRequest.get())
                .as("Host 必须改写成上游，不能是客户端那个端口")
                .contains("host=127.0.0.1:" + upstream.getAddress().getPort());
    }

    @Test
    @DisplayName("POST 透传：请求体原样到达上游")
    void proxiesPost() {
        String proxy = startAppAndProxy(upstreamBase);

        String body = "{\"text\":\"你好\"}";
        var response = WebClient.create(proxy)
                .post()
                .uri("/api/arcade/ws-ticket")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class))
                .block(Duration.ofSeconds(15));

        assertThat(response).isEqualTo("echo:/api/arcade/ws-ticket");
        assertThat(lastRequest.get()).contains("POST /api/arcade/ws-ticket").contains("body=" + body);
    }

    @Test
    @DisplayName("/nori-* 由控制器处理，不会被转发到上游")
    void ownEndpointsAreNotForwarded() {
        String proxy = startAppAndProxy(upstreamBase);

        var result = WebClient.create(proxy)
                .get()
                .uri("/nori-lifestat")
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .map(body -> new Object[] {resp.statusCode().value(), body}))
                .block(Duration.ofSeconds(15));

        assertThat(result).isNotNull();
        assertThat(result[0]).as("上游对 /nori-* 会回 500，能返回 200 说明没被转发").isEqualTo(200);
        assertThat((String) result[1]).contains("phase");
    }

    @Test
    @DisplayName("管理端仍然由 @RestController 处理（没被 catch-all 抢走）")
    void adminEndpointStillWorks() {
        String proxy = startAppAndProxy(upstreamBase);

        String body = WebClient.create(proxy)
                .get()
                .uri("/api/admin/config")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(15));

        assertThat(body).contains("\"ports\"").contains("\"upstreams\"");
    }

    @Test
    @DisplayName("上游不可达时回 502 且带可读原因，不是 500 堆栈")
    void unreachableUpstreamReturns502() {
        // 一个肯定没人监听的端口
        String proxy = startAppAndProxy("http://127.0.0.1:1");

        var result = WebClient.create(proxy)
                .get()
                .uri("/whatever")
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .map(body -> new Object[] {resp.statusCode().value(), body}))
                .block(Duration.ofSeconds(15));

        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo(502);
        assertThat((String) result[1]).contains("upstream unreachable");
    }
}
