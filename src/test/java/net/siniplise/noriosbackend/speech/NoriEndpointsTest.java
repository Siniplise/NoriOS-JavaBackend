package net.siniplise.noriosbackend.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.app.NoriBackendApplication;
import net.siniplise.noriosbackend.app.SessionRuntime;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `/nori-line`、`/nori-spoken`、`/nori-lifestat`、`/nori-touch` 的端到端验证。
 *
 * <p>这几个端点的坑都在**语义**上而不是语法上：
 * `/nori-line` 是破坏性的（取走即清空）、`/nori-touch` 被闸门拦住时 `ok` 仍是 true、
 * `lifestat` 的 `petGate` 要能反映冷开场。都写成断言。
 */
class NoriEndpointsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Env(ConfigurableApplicationContext context, SessionRuntime session, String base) {
    }

    private static Env start() {
        ConfigLoader.Loaded loaded = ConfigLoader.load().withGameExe("http://127.0.0.1:1");
        SpringApplication app = NoriBackendApplication.newApplication(loaded);
        ConfigurableApplicationContext context = app.run("--server.port=0");
        String port = context.getEnvironment().getProperty("local.server.port");
        return new Env(context, context.getBean(SessionRuntime.class), "http://127.0.0.1:" + port);
    }

    @Test
    @DisplayName("/nori-line 是破坏性的：取走即清空，字段齐全")
    void lineIsDestructive() {
        Env env = start();
        try (env.context) {
            env.session.enqueue(new SpeechQueue.Line("q1", 0, 2, "第一句", "neutral")).join();
            env.session.enqueue(new SpeechQueue.Line("q1", 1, 2, "第二句", "shy")).join();

            JsonNode first = get(env.base, NoriPaths.LINE);
            assertThat(first.at("/lines").size()).isEqualTo(2);
            assertThat(first.at("/lines/0/text").asString()).isEqualTo("第一句");
            assertThat(first.at("/lines/0/qid").asString()).isEqualTo("q1");
            assertThat(first.at("/lines/0/qpart").asInt()).isZero();
            assertThat(first.at("/lines/0/qof").asInt()).isEqualTo(2);
            assertThat(first.at("/lines/0/id").asString()).isEqualTo("q1#0");

            JsonNode second = get(env.base, NoriPaths.LINE);
            assertThat(second.at("/lines").size()).as("第二次必须为空").isZero();
        }
    }

    @Test
    @DisplayName("/nori-spoken 回报在没在播时也返回 ok，不报错")
    void spokenIsIdempotent() {
        Env env = start();
        try (env.context) {
            JsonNode body = post(env.base, NoriPaths.SPOKEN, Map.of("qid", "q1", "qpart", 0));
            assertThat(body.get("ok").asBoolean()).isTrue();
            assertThat(body.get("state").asString()).isEqualTo("IDLE");
        }
    }

    @Test
    @DisplayName("/nori-lifestat 初值是 idle；写进 session.ready 后 petGate 变 coldopen")
    void lifeStatReflectsGates() {
        Env env = start();
        try (env.context) {
            JsonNode idle = get(env.base, NoriPaths.LIFESTAT);
            assertThat(idle.get("phase").asString()).isEqualTo("idle");
            assertThat(idle.get("on").asBoolean()).isFalse();
            assertThat(idle.get("petGate").isNull()).isTrue();

            env.session.observeFact("session.ready").join();

            JsonNode cold = get(env.base, NoriPaths.LIFESTAT);
            assertThat(cold.get("petGate").asString()).as("收到 session.ready 但没过场结束").isEqualTo("coldopen");

            env.session.observeFact("boot.completed").join();
            JsonNode after = get(env.base, NoriPaths.LIFESTAT);
            assertThat(after.get("petGate").isNull()).as("过场结束后放行").isTrue();
        }
    }

    @Test
    @DisplayName("/nori-touch：闸门拦住时 ok 仍为 true、fired=false、带 blocked")
    void touchHonoursGate() {
        Env env = start();
        try (env.context) {
            JsonNode fired = post(env.base, NoriPaths.TOUCH, Map.of("part", "head"));
            assertThat(fired.get("ok").asBoolean()).isTrue();
            assertThat(fired.get("fired").asBoolean()).isTrue();
            assertThat(fired.get("fact").asString()).isEqualTo("touch.head");

            // 注意闸门顺序：session.ready 且没过场结束 ⇒ coldopen 会先短路，
            // 所以要先给 boot.completed，才能验到 corrupt 这一层
            env.session.observeFact("session.ready").join();
            env.session.observeFact("boot.completed").join();
            env.session.observeFact("corrupt.armed").join();

            JsonNode blocked = post(env.base, NoriPaths.TOUCH, Map.of("part", "tail"));
            assertThat(blocked.get("ok").asBoolean()).as("被拦不是失败").isTrue();
            assertThat(blocked.get("fired").asBoolean()).isFalse();
            assertThat(blocked.get("blocked").asString()).isEqualTo("corrupt");
            assertThat(blocked.get("part").asString()).isEqualTo("tail");
        }
    }

    private static JsonNode get(String base, String path) {
        String body = WebClient.create(base).get().uri(path)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
        return MAPPER.readTree(body);
    }

    private static JsonNode post(String base, String path, Map<String, Object> payload) {
        var result = WebClient.create(base).post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> new Object[] {resp.statusCode().value(), body}))
                .block(Duration.ofSeconds(10));
        assertThat(result).isNotNull();
        assertThat((String) result[1])
                .as("POST %s 响应体不能为空（status=%s）", path, result[0])
                .isNotEmpty();
        return MAPPER.readTree((String) result[1]);
    }

    /** 供 IDE 单独跑时排查用。 */
    static List<String> coveredPaths() {
        return List.of(NoriPaths.LINE, NoriPaths.SPOKEN, NoriPaths.LIFESTAT, NoriPaths.TOUCH);
    }
}
