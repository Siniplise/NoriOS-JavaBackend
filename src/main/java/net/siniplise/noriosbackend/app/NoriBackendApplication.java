package net.siniplise.noriosbackend.app;

import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.config.NoriConfig;
import net.siniplise.noriosbackend.contract.NoriPaths;
import net.siniplise.noriosbackend.runtime.SessionLoop;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 骨架入口。
 *
 * <p>现在做四件事：确认 JDK 25、加载并校验配置、打印生效中的契约与端口、跑一次线程底座自检。
 * <b>真正的反代还没实现</b>——这里不监听任何端口、不假装能转发。
 *
 * <p>{@code --print-config} 会吐出「当前生效的全部配置」JSON（管理器以后就吃这份）；
 * 默认不打印，免得日志里刷一屏。
 *
 * <p>下一步是 HANDOFF §8 第 1 步：抓一次完整会话录成 golden，再动手写 gateway。
 */
public final class NoriBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(NoriBackendApplication.class);

    public static void main(String[] args) throws Exception {
        checkJdk();

        ConfigLoader.Loaded loaded = ConfigLoader.load();
        NoriConfig cfg = loaded.config();

        log.info("config: source={} unknownKeys={}", loaded.source(), loaded.unknownKeys());
        log.info("ports: game={} admin={}", cfg.ports().game(), cfg.ports().admin());
        log.info("upstreams: exe={} tts={} persona={} (legacy proxy {} 仅对照)",
                cfg.upstreams().gameExe(), cfg.upstreams().tts(), cfg.upstreams().persona(),
                cfg.upstreams().legacyProxy());
        log.info("contract: {} 个 HTTP 端点 + 2 条 WS 通道", NoriPaths.HTTP_ENDPOINTS.size());
        log.info("tunables: tts.parallel={} speech.dedupeMs={} speech.fallbackMs={} petGate={}",
                cfg.tts().parallel(), cfg.speech().dedupeMs(), cfg.speech().fallbackMs(),
                cfg.touch().gateEnabled());

        if (Arrays.asList(args).contains("--print-config")) {
            System.out.println(loaded.effectiveJson());
            return;
        }

        // 线程底座自检：串行性 + 子线程通信 + 优雅停机
        try (SessionLoop loop = new SessionLoop("smoke")) {
            CompletableFuture<Integer> answer = loop.submit(() -> 6 * 7);
            log.info("session loop answered: {}", answer.get(2, TimeUnit.SECONDS));
        }

        log.info("下一步：HANDOFF §8 第 1 步 —— 抓到可回放的 golden 会话，再动业务代码。");
    }

    private static void checkJdk() {
        String feature = String.valueOf(Runtime.version().feature());
        log.info("nori-backend skeleton | JVM {} ({})", Runtime.version(), System.getProperty("java.vm.name"));
        if (!"25".equals(feature)) {
            // 宁可启动即失败，也不要悄悄跑在别的 JDK 上（与「缺 key 显式报错」同一原则）
            throw new IllegalStateException("expect JDK 25, but got " + feature);
        }
    }

    private NoriBackendApplication() {
        throw new AssertionError("no instances");
    }
}
