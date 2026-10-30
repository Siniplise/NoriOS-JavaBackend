package net.siniplise.noriosbackend.app;

import java.util.Arrays;
import java.util.Map;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.config.NoriConfig;
import net.siniplise.noriosbackend.contract.NoriPaths;
import net.siniplise.noriosbackend.obs.logging.LogFormatDemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 后端入口（Spring Boot 4 + WebFlux）。
 *
 * <p>启动顺序是有讲究的：<b>先读配置、再起容器</b>。
 * 端口和上游地址都来自 {@code config/nori-backend.json}；如果等容器起来再读，
 * 端口冲突这类问题会以「Spring 启动失败」的面目出现，看不出真实原因。
 *
 * <p>配置只解析一次：结果通过 initializer 注册成单例，{@code /api/admin/config} 直接注入。
 * 不允许再调一次 {@code ConfigLoader.load()}——两处各解析一遍就是「改了配置不生效」的来源。
 *
 * <p>开发期留了两个旁路（都不开端口）：
 * {@code --print-config} 打印生效配置后退出；{@code --demo-log} 跑日志格式自检后退出。
 */
// 入口类在 app 包，但业务包（admin / gateway / memory …）都是它的兄弟包，
// 默认「只扫子包」会漏掉它们，所以把扫描根显式抬到 net.siniplise.noriosbackend
@SpringBootApplication(scanBasePackages = "net.siniplise.noriosbackend")
public class NoriBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(NoriBackendApplication.class);

    /** 生效配置在容器里的 bean 名。 */
    public static final String CONFIG_BEAN_NAME = "noriConfigLoaded";

    public static void main(String[] args) {
        checkJdk();

        ConfigLoader.Loaded loaded = ConfigLoader.load();
        logStartup(loaded.config(), loaded);

        if (Arrays.asList(args).contains("--print-config")) {
            System.out.println(loaded.effectiveJson());
            return;
        }
        if (Arrays.asList(args).contains("--demo-log")) {
            LogFormatDemo.run();
            return;
        }

        SpringApplication app = newApplication(loaded);
        app.run(withConfigPort(args, loaded.config().ports().game()));
    }

    /**
     * 把配置里的端口做成<b>命令行参数</b>再启动。
     *
     * <p>为什么不只用 {@code setDefaultProperties}：默认属性是最低优先级，
     * 会被环境变量盖掉。这台机器上就躺着一个 {@code SERVER__PORT=8942}（宿主注入的，
     * Spring 的松弛绑定会认成 {@code server.port}），结果就是「配置文件写 4183，实际起 8942」——
     * 排查这种问题最费时间。命令行参数优先级最高，能压住环境变量。
     *
     * <p>但显式传 {@code --server.port=xxx} 的人应该说了算，所以那种情况我们不加。
     */
    static String[] withConfigPort(String[] args, int port) {
        boolean explicit = Arrays.stream(args).anyMatch(a -> a.startsWith("--server.port="));
        if (explicit) {
            return args;
        }
        String[] merged = Arrays.copyOf(args, args.length + 1);
        merged[args.length] = "--server.port=" + port;
        return merged;
    }

    /**
     * 装配容器：端口与配置注入都在这里。
     * 抽成方法是为了让测试能复用同一套装配逻辑——测试里另写一遍就会和 main 漂移。
     */
    static SpringApplication newApplication(ConfigLoader.Loaded loaded) {
        SpringApplication app = new SpringApplication(NoriBackendApplication.class);
        app.setDefaultProperties(Map.of(
                // 端口来自配置文件（默认 4183，避开还在跑的旧 Node 4173），不写死在这里
                "server.port", String.valueOf(loaded.config().ports().game()),
                "spring.application.name", "nori-backend",
                "spring.main.banner-mode", "off"));
        // 配置只读一次：把已解析的结果塞进容器，admin 端点直接拿
        app.addInitializers(context ->
                context.getBeanFactory().registerSingleton(CONFIG_BEAN_NAME, loaded));
        return app;
    }

    private static void logStartup(NoriConfig cfg, ConfigLoader.Loaded loaded) {
        log.info("config: source={} unknownKeys={}", loaded.source(), loaded.unknownKeys());
        log.info("ports: game={} admin={}", cfg.ports().game(), cfg.ports().admin());
        log.info("upstreams: exe={} tts={} persona={} (legacy proxy {} 仅对照)",
                cfg.upstreams().gameExe(), cfg.upstreams().tts(), cfg.upstreams().persona(),
                cfg.upstreams().legacyProxy());
        log.info("contract: {} 个 HTTP 端点 + 2 条 WS 通道", NoriPaths.HTTP_ENDPOINTS.size());
        log.info("tunables: tts.parallel={} speech.dedupeMs={} speech.fallbackMs={} petGate={}",
                cfg.tts().parallel(), cfg.speech().dedupeMs(), cfg.speech().fallbackMs(),
                cfg.touch().gateEnabled());
    }

    private static void checkJdk() {
        String feature = String.valueOf(Runtime.version().feature());
        log.info("nori-backend | JVM {} ({})", Runtime.version(), System.getProperty("java.vm.name"));
        if (!"25".equals(feature)) {
            // 宁可启动即失败，也不要悄悄跑在别的 JDK 上（与「缺 key 显式报错」同一原则）
            throw new IllegalStateException("expect JDK 25, but got " + feature);
        }
    }

    // 注意：这里故意不写「私有构造 + 抛 AssertionError」那套。@SpringBootApplication 的类
    // 会被 Spring 当成配置类实例化，私有构造会让容器启动直接炸在 no instances 上。
}
