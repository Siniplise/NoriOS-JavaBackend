package com.nori.backend.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.nori.backend.contract.NoriPaths;
import com.nori.backend.contract.NoriPorts;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 骨架自检：只验证「构建链是对的」——JDK 25、契约常量可用、跨模块依赖能编译。
 * 业务断言等 §8 第 1 步的 golden 会话录下来之后再写。
 */
class ToolchainSmokeTest {

    @Test
    @DisplayName("编译与测试都用 JDK 25（toolchain 生效）")
    void runsOnJdk25() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
    }

    @Test
    @DisplayName("契约常量与 HANDOFF §2.2 对齐（16 个 HTTP 端点）")
    void contractPathsAreWired() {
        assertThat(NoriPaths.HTTP_ENDPOINTS).hasSize(16).doesNotHaveDuplicates();

        // 三条前端依赖最深的路径，写死名字防止手滑改名
        assertThat(NoriPaths.HTTP_ENDPOINTS)
                .contains("/nori-line", "/nori-tts", "/v1/chat/completions");

        // 顺序上的约束：/nori-tts 与 /v1/* 必须区分开，否则 4173 会把 TTS 请求吃进 LLM 路由
        assertThat(List.of(NoriPaths.LINE, NoriPaths.SPOKEN, NoriPaths.TOUCH))
                .allMatch(p -> p.startsWith("/nori-"));
    }

    @Test
    @DisplayName("WS 通道路径与前端一致")
    void wsPathsMatch() {
        assertThat(NoriPaths.WS_MAIN).isEqualTo("/api/arcade/web/v1");
        assertThat(NoriPaths.WS_MEDIA).isEqualTo("/api/arcade/web/v1/media");
    }

    @Test
    @DisplayName("端口表：默认 4183，且不与任何已占用端口撞车")
    void portsDoNotCollide() {
        // 换 4183 的唯一理由就是 4173 被旧 Node 占着——这条断言把这个理由钉住
        assertThat(NoriPorts.GAME_DEFAULT).isEqualTo(4183);
        assertThat(NoriPorts.GAME_DEFAULT).isNotEqualTo(NoriPorts.LEGACY_PROXY);

        assertThat(List.of(
                        NoriPorts.GAME_DEFAULT,
                        NoriPorts.ADMIN_DEFAULT,
                        NoriPorts.LEGACY_PROXY,
                        NoriPorts.GAME_UPSTREAM,
                        NoriPorts.FREEZE_PROBE,
                        NoriPorts.FRESH_START,
                        NoriPorts.TTS_UPSTREAM,
                        NoriPorts.PERSONA_UPSTREAM))
                .doesNotHaveDuplicates();
    }
}
