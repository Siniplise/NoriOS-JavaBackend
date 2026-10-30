package com.nori.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nori.backend.contract.NoriPorts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 配置层的行为契约。
 *
 * <p>重点不是覆盖率，而是三条「管理器接上来之后一定会踩」的边界：
 * 只写部分字段不能把其它默认值清空；字段名拼错必须报出来；密钥绝不能落进 JSON。
 */
class ConfigLoaderTest {

    /** 没有任何环境变量的世界。 */
    private static final Function<String, String> NO_ENV = name -> null;

    private final Path missing = Path.of("build", "definitely-missing-nori-config.json");

    @Test
    @DisplayName("没有本机文件时，出厂默认值可用且类型正确")
    void defaultsAreUsable() {
        ConfigLoader.Loaded loaded = ConfigLoader.load(missing, NO_ENV);

        assertThat(loaded.config().ports().game()).isEqualTo(NoriPorts.GAME_DEFAULT);
        assertThat(loaded.config().ports().admin()).isEqualTo(NoriPorts.ADMIN_DEFAULT);
        assertThat(loaded.config().memory().store()).isEqualTo("sqlite");
        assertThat(loaded.config().tts().parallel()).isEqualTo(3);
        assertThat(loaded.config().speech().dedupeMs()).isEqualTo(2_500);
        assertThat(loaded.config().touch().gateEnabled()).isTrue();
        assertThat(loaded.unknownKeys()).isEmpty();
        assertThat(loaded.source()).startsWith("classpath:");
        // 管理器拿到的就是这一份完整 JSON
        assertThat(loaded.effectiveJson()).contains("\"game\"").contains("\"recallTopK\"");
    }

    @Test
    @DisplayName("本机文件只覆盖指定字段，其余默认值不丢（深合并而非替换）")
    void externalFileMergesInsteadOfReplacing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nori-backend.json");
        Files.writeString(file, "{\"ports\":{\"game\":5000}}", StandardCharsets.UTF_8);

        ConfigLoader.Loaded loaded = ConfigLoader.load(file, NO_ENV);

        assertThat(loaded.config().ports().game()).isEqualTo(5_000);
        assertThat(loaded.config().ports().admin()).isEqualTo(NoriPorts.ADMIN_DEFAULT);
        assertThat(loaded.config().tts().parallel()).isEqualTo(3);
        assertThat(loaded.source()).isEqualTo(file.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("字段名拼错会被点名（但仍然保留，不静默丢弃）")
    void unknownKeysAreReported(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nori-backend.json");
        Files.writeString(file, "{\"ports\":{\"game\":5001},\"typoField\":true}", StandardCharsets.UTF_8);

        ConfigLoader.Loaded loaded = ConfigLoader.load(file, NO_ENV);

        assertThat(loaded.unknownKeys()).containsExactly("typoField");
        assertThat(loaded.effectiveJson()).contains("typoField");
    }

    @Test
    @DisplayName("环境变量能覆盖，且上游只写 host:port 时自动补 http://")
    void envOverridesApply() {
        Map<String, String> env = Map.of(
                "NORI_PORT", "6000",
                "NORI_TTS_PARALLEL", "5",
                "NORI_PET_GATE", "off",
                "NORI_LLM_UPSTREAM", "127.0.0.1:26000");

        ConfigLoader.Loaded loaded = ConfigLoader.load(missing, env::get);

        assertThat(loaded.config().ports().game()).isEqualTo(6_000);
        assertThat(loaded.config().tts().parallel()).isEqualTo(5);
        assertThat(loaded.config().touch().gateEnabled()).isFalse();
        assertThat(loaded.config().upstreams().persona()).isEqualTo("http://127.0.0.1:26000");
    }

    @Test
    @DisplayName("环境变量写错类型时显式报错，并带上是哪个变量")
    void invalidEnvValueFailsLoudly() {
        assertThatThrownBy(() -> ConfigLoader.load(missing, Map.of("NORI_PORT", "abc")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORI_PORT");

        assertThatThrownBy(() -> ConfigLoader.load(missing, Map.of("NORI_PET_GATE", "maybe")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORI_PET_GATE");
    }

    @Test
    @DisplayName("端口撞车直接拒绝，不进入「起来一半」的状态")
    void duplicatePortsAreRejected() {
        assertThatThrownBy(() -> ConfigLoader.load(missing, Map.of("NORI_ADMIN_PORT", "4183")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("相同");
    }

    @Test
    @DisplayName("JSON 里出现真密钥立刻拒绝启动")
    void secretValueInJsonIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nori-backend.json");
        Files.writeString(file,
                "{\"secrets\":{\"deepseekKeyEnv\":\"sk-1234567890abcdefghijklmnop\"}}",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ConfigLoader.load(file, NO_ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥值");
    }

    @Test
    @DisplayName("数字字段被写成字符串时当场报错，而不是悄悄变 0")
    void wrongTypeIsNotSilentlyCoerced(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nori-backend.json");
        Files.writeString(file, "{\"ports\":{\"game\":\"4183\"}}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ConfigLoader.load(file, NO_ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ports.game")
                .hasMessageContaining("整数");
    }

    @Test
    @DisplayName("生效配置的 JSON 里只有环境变量名，没有任何密钥值")
    void effectiveJsonCarriesNoSecretValues() {
        ConfigLoader.Loaded loaded = ConfigLoader.load(missing, NO_ENV);
        String json = loaded.effectiveJson();

        assertThat(json).contains("DEEPSEEK_API_KEY");
        assertThat(json).doesNotContain("sk-");
    }

    @Test
    @DisplayName("密钥缺失时 SecretRef 显式抛错，不返回空串")
    void secretRefFailsLoudly() {
        assertThatThrownBy(() -> SecretRef.resolve(NO_ENV, "DEEPSEEK_API_KEY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");

        assertThat(SecretRef.isPresent(NO_ENV, "DEEPSEEK_API_KEY")).isFalse();
        assertThat(SecretRef.mask("sk-1234567890")).isEqualTo("sk-1***(13)");
    }
}
