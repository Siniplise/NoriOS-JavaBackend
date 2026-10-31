package net.siniplise.noriosbackend.persona;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 人格客户端：字段名、transient 语义、错误不炸链路。
 *
 * <p>重点在 `transient`：它是"只上屏不进记忆"的开关。
 * 漏发/发错的表现是「记忆卡越积越多」，几周后才发现——所以必须在这里钉死。
 */
class HttpPersonaClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static volatile int status = 200;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static HttpPersonaClient client() {
        ConfigLoader.Loaded loaded = ConfigLoader.load().withPersona(base);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("loaded", loaded);
        return new HttpPersonaClient(factory.getBeanProvider(ConfigLoader.Loaded.class));
    }

    @Test
    @DisplayName("首次触发：带 memory 与 importance，不带 transient")
    void firstTimeCarriesMemory() {
        status = 200;
        client().writeScriptLine(ScriptLineWrite.firstTime(
                "touch_head", "happy", List.of("头发都被你揉乱啦。"), "他摸了摸我的头。", 0.9))
                .block(Duration.ofSeconds(5));

        assertThat(lastPath.get()).isEqualTo("/nori-scriptline");
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.get("beat").asString()).isEqualTo("touch_head");
        assertThat(body.get("emotion").asString()).isEqualTo("happy");
        assertThat(body.get("memory").asString()).isEqualTo("他摸了摸我的头。");
        assertThat(body.get("importance").asDouble()).isEqualTo(0.9);
        assertThat(body.has("transient")).as("首次不能带 transient，否则记忆卡永远建不起来").isFalse();
        assertThat(body.at("/lines/0").asString()).isEqualTo("头发都被你揉乱啦。");
    }

    @Test
    @DisplayName("重复触发：transient=true 且不带 memory（只上屏）")
    void repeatIsTransient() {
        status = 200;
        client().writeScriptLine(ScriptLineWrite.repeatOnly(
                "touch_head", "happy", List.of("再摸就要收费啦。")))
                .block(Duration.ofSeconds(5));

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.get("transient").asBoolean()).as("重复触发必须只上屏").isTrue();
        assertThat(body.has("memory")).as("重复触发不能带 memory，否则又建一张卡").isFalse();
    }

    @Test
    @DisplayName("game-note：只记不答，字段与旧栈一致")
    void gameNote() {
        status = 200;
        client().gameNote("chess", 3).block(Duration.ofSeconds(5));

        assertThat(lastPath.get()).isEqualTo("/game-note");
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.get("game").asString()).isEqualTo("chess");
        assertThat(body.get("dropped").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("上游 500：Mono 出错但不阻塞调用方（旁路语义）")
    void upstreamErrorPropagatesAsMonoError() {
        status = 500;
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() ->
                client().writeScriptLine(ScriptLineWrite.repeatOnly("touch_head", "happy", List.of("x")))
                        .block(Duration.ofSeconds(5)));
        assertThat(error).isNotNull();
        status = 200;
    }
}
