package com.nori.backend.app;

import com.nori.backend.contract.NoriPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 骨架入口。
 *
 * <p>当前只做三件事：确认跑在 JDK 25 上、确认各模块依赖链装配正常、把下一步指向
 * HANDOFF.md §8 第 1 步（录制契约）。<b>真正的 4173 反代还没实现</b>，
 * 所以这里不监听任何端口、不假装能转发。
 */
public final class NoriBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(NoriBackendApplication.class);

    public static void main(String[] args) {
        String feature = String.valueOf(Runtime.version().feature());
        log.info("nori-backend skeleton | JVM {} ({})", Runtime.version(), System.getProperty("java.vm.name"));

        if (!"25".equals(feature)) {
            // 宁可启动即失败，也不要悄悄跑在别的 JDK 上（与「缺 key 显式报错」同一原则）
            throw new IllegalStateException("expect JDK 25, but got " + feature);
        }

        log.info("contract loaded: {} 个 HTTP 端点 + 2 条 WS 通道", NoriPaths.HTTP_ENDPOINTS.size());
        log.info("下一步：HANDOFF §8 第 1 步 —— 抓到可回放的 golden 会话，再动业务代码。");
    }

    private NoriBackendApplication() {
        throw new AssertionError("no instances");
    }
}
