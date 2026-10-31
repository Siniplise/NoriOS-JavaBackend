package net.siniplise.noriosbackend.admin;

import net.siniplise.noriosbackend.config.ConfigLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * 管理端：只给运维/管理器用，前端不感知（HANDOFF §2 的 8 个注入脚本不会碰这些路径）。
 *
 * <p>第一个端点是 {@code GET /api/admin/config}：返回「当前真正生效的全部配置」JSON。
 * 管理器的读写契约就在这里——读它渲染表单，写 {@code ./config/nori-backend.json}，
 * 重启后生效。密钥不在里面，只有 {@code secrets.*KeyEnv} 这样的环境变量名。
 *
 * <p>返回 {@code Mono} 而不是直接返回字符串：WebFlux 下所有响应都走响应式管道，
 * 这里暂不涉及阻塞 IO，但接口签名先摆正，免得后面接存储时又改一遍。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final ConfigLoader.Loaded loaded;

    public AdminConfigController(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        // 正常情况下由 main() 注册；万一没注册（比如单测直接起容器），自己读一份兜底
        this.loaded = configProvider.getIfAvailable(ConfigLoader::load);
    }

    /** 生效中的完整配置。 */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> effectiveConfig() {
        // 返回 JSON 树而不是字符串：WebFlux 里 String 配 application/json 会被当成字符串值序列化，
        // 出去是带转义引号的一坨，管理器没法当对象读
        return Mono.just(loaded.effectiveTree());
    }

    /** 配置来源与拼错的字段名，管理器保存前可以先探一眼。 */
    @GetMapping(value = "/config/meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Meta> configMeta() {
        return Mono.just(new Meta(loaded.source(), loaded.unknownKeys()));
    }

    /** 管理端返回的元信息。 */
    public record Meta(String source, java.util.List<String> unknownKeys) {
    }

    /**
     * 上游健康探测（给管理网页用）。
     *
     * <p><b>为什么必须由后端代探</b>：浏览器的 fetch 受同源限制，从 {@code 4183} 探不到
     * {@code 4174/8880/25503}（它们没有 CORS 头）。所以只能在这里探完，把结果交给页面。
     *
     * <p>判据故意宽松：**连得上就算 up**（哪怕返回 404）。因为这几个服务的根路径不一定有内容，
     * 404 只说明"路径不对"，不说明"服务没起"。真正要区分的是"端口没人监听"。
     *
     * <p>全程 800ms 超时、串行探测（最坏 2.4s）：管理页偶尔点一下，不需要为它上并发。
     * 用 {@code boundedElastic} 而不是默认调度器——JDK HttpClient 的 {@code send} 是阻塞的，
     * 放在事件循环线程上会卡住整个应用。
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<HealthReport> health() {
        return Mono.fromCallable(() -> {
            var u = loaded.config().upstreams();
            return new HealthReport(System.currentTimeMillis(), java.util.List.of(
                    probe("游戏 exe", u.gameExe()),
                    probe("TTS", u.tts()),
                    probe("人格 (25503)", u.persona()),
                    probe("旧 Node 代理（对照）", u.legacyProxy())));
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 运行时信息：驱动开关、端口、以及所有 {@code nori.*} 系统属性。
     *
     * <p>为什么单独一个端点：这些值来自 <b>JVM 系统属性</b>（{@code -Dnori.game.driver=on}），
     * 不在配置文件里，所以 {@code /config} 读不到。管理页要显示"她到底开没开"就必须有它。
     *
     * <p>只暴露 {@code nori.*} 前缀，不吐全部系统属性——那里面有路径和用户名，没必要给页面看。
     */
    @GetMapping(value = "/runtime", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RuntimeInfo> runtime() {
        var props = new java.util.TreeMap<String, String>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("nori.")) {
                props.put(k, System.getProperty(k, ""));
            }
        }
        var ports = new java.util.LinkedHashMap<String, Integer>();
        ports.put("game", loaded.config().ports().game());
        ports.put("admin", loaded.config().ports().admin());
        return Mono.just(new RuntimeInfo(
                System.getProperty("nori.game.driver", "off"),
                ports, props));
    }

    /** 运行时信息。 */
    public record RuntimeInfo(String driver, java.util.Map<String, Integer> ports,
                              java.util.Map<String, String> properties) {
    }

    /** 一个上游的探测结果。 */
    public record HealthItem(String name, String url, boolean up, long ms, String note) {
    }

    /** 健康报告。 */
    public record HealthReport(long at, java.util.List<HealthItem> upstreams) {
    }

    private static HealthItem probe(String name, String url) {
        if (url == null || url.isBlank()) {
            return new HealthItem(name, "-", false, 0, "未配置");
        }
        long t0 = System.currentTimeMillis();
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMillis(800))
                    .GET()
                    .build();
            java.net.http.HttpResponse<Void> resp = HTTP.send(req,
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            long ms = System.currentTimeMillis() - t0;
            // 连上了就算 up：4xx 只说明根路径没内容，不代表服务没起
            return new HealthItem(name, url, true, ms, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String note = e.getClass().getSimpleName();
            Throwable cause = e.getCause();
            if (cause != null) {
                note += " / " + cause.getClass().getSimpleName();
            }
            return new HealthItem(name, url, false, ms, note);
        }
    }

    /** 探活用的 HTTP 客户端：**必须连 127.0.0.1 不走系统代理**（本机有代理时会连不上）。 */
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(600))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
}
