package net.siniplise.noriosbackend.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 冒烟：Spring Boot 4 + WebFlux 真的起得来，管理端点真的答得上。
 *
 * <p>用 {@code server.port=0} 起随机端口，因此不会撞上开发机上正在跑的 4183。
 * 这里刻意不用 {@code @SpringBootTest}：那会把 spring-boot-starter-test 拖进来，
 * 连带把 JUnit 版本换成 Boot 管的那一版，和我们显式锁的 5.14 打架。
 */
class AdminConfigEndpointTest {

    @Test
    @DisplayName("容器能起来，/api/admin/config 返回生效中的配置 JSON")
    void adminConfigEndpointServesEffectiveJson() {
        ConfigLoader.Loaded loaded = ConfigLoader.load();

        SpringApplication app = NoriBackendApplication.newApplication(loaded);
        // 随机端口：测试不抢占开发端口。必须走命令行参数——这台机器的环境里有 SERVER__PORT=8942，
        // 默认属性会被环境变量压过去（见 NoriBackendApplication#withConfigPort 的说明）
        try (ConfigurableApplicationContext context = app.run("--server.port=0")) {
            String port = context.getEnvironment().getProperty("local.server.port");
            assertThat(port).as("WebFlux 起来后应该拿到随机端口").isNotNull();

            String body = WebClient.create("http://127.0.0.1:" + port)
                    .get()
                    .uri("/api/admin/config")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            assertThat(body).isNotNull();
            assertThat(body).contains("\"ports\"").contains("\"game\"");
            // 密钥铁律：管理端点吐出来的只能是环境变量名
            assertThat(body).contains("DEEPSEEK_API_KEY").doesNotContain("sk-");
        }
    }
}
