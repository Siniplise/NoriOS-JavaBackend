package net.siniplise.noriosbackend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.app.NoriBackendApplication;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `/v1/chat/completions` 的三条分流规则。
 *
 * <p>这里最关键的一条不是"命中要答"，而是**游戏回合必须放行**：
 * `response_format.type == "json_object"` 时如果被拦掉，游戏会解析失败——
 * 表现为"她突然不说话/游戏卡住"，而且日志里看不出任何异常。
 */
class NoriChatControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HttpServer persona;
    private static String personaBase;
    private static final AtomicInteger personaCalls = new AtomicInteger();
    private static final AtomicReference<String> personaSawBody = new AtomicReference<>();

    @BeforeAll
    static void startFakePersona() throws IOException {
        persona = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        persona.createContext("/", exchange -> {
            personaCalls.incrementAndGet();
            personaSawBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = ("{\"id\":\"from-persona\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"人格的答复\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        persona.start();
        personaBase = "http://127.0.0.1:" + persona.getAddress().getPort();
    }

    @AfterAll
    static void stopFakePersona() {
        if (persona != null) {
            persona.stop(0);
        }
    }

    private static Path fixtureTable() throws IOException {
        String json = new String(NoriChatControllerTest.class
                .getResourceAsStream("/script/fixture-beats.json").readAllBytes(), StandardCharsets.UTF_8);
        Path file = Files.createTempFile("nori-beats-", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String start() throws IOException {
        ConfigLoader.Loaded loaded = ConfigLoader.load()
                .withGameExe("http://127.0.0.1:1")
                .withPersona(personaBase)
                .withScriptLinesFile(fixtureTable().toString());
        SpringApplication app = NoriBackendApplication.newApplication(loaded);
        ConfigurableApplicationContext context = app.run("--server.port=0");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    private static JsonNode post(String base, String json) {
        String body = WebClient.create(base).post()
                .uri(NoriPaths.V1_CHAT_COMPLETIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
                .block(Duration.ofSeconds(15));
        return MAPPER.readTree(body);
    }

    @Test
    @DisplayName("命中固定台词：直接答，不走人格服务；content 带 [emotion:] 前缀")
    void hitReturnsFixedReplyWithoutCallingPersona() throws IOException {
        String base = start();
        personaCalls.set(0);

        JsonNode body = post(base, """
                {"model":"deepseek-flash","messages":[{"role":"user","content":"我摸摸尾巴"}]}
                """);

        assertThat(body.get("id").asString()).isEqualTo("chatcmpl-nori-fixed");
        String content = body.at("/choices/0/message/content").asString();
        assertThat(content).startsWith("[emotion:").contains("……");
        assertThat(body.at("/choices/0/finish_reason").asString()).isEqualTo("stop");
        assertThat(personaCalls.get()).as("命中时不允许调人格服务（省一次 LLM，也保证台词一致）").isZero();
    }

    @Test
    @DisplayName("游戏回合（json_object）即使文本命中，也必须转发给人格服务")
    void jsonTurnAlwaysForwards() throws IOException {
        String base = start();
        personaCalls.set(0);

        JsonNode body = post(base, """
                {"model":"deepseek-flash","response_format":{"type":"json_object"},
                 "messages":[{"role":"user","content":"我摸摸尾巴"}]}
                """);

        assertThat(personaCalls.get()).as("游戏回合必须真的打到上游").isEqualTo(1);
        assertThat(body.get("id").asString()).isEqualTo("from-persona");
        assertThat(personaSawBody.get()).contains("摸摸尾巴").contains("json_object");
    }

    @Test
    @DisplayName("普通聊天：原样转发，请求体不被重建")
    void normalChatForwards() throws IOException {
        String base = start();
        personaCalls.set(0);

        String json = """
                {"model":"deepseek-flash","temperature":0.33,"messages":[{"role":"user","content":"今天天气不错"}]}
                """;
        JsonNode body = post(base, json);

        assertThat(personaCalls.get()).isEqualTo(1);
        assertThat(body.get("id").asString()).isEqualTo("from-persona");
        assertThat(personaSawBody.get())
                .as("透传的 body 必须原样，连 temperature 都在")
                .contains("\"temperature\":0.33")
                .contains("今天天气不错");
    }

    @Test
    @DisplayName("不可解析的请求体：放行而不是吞掉")
    void malformedBodyStillForwards() throws IOException {
        String base = start();
        personaCalls.set(0);

        WebClient.create(base).post().uri(NoriPaths.V1_CHAT_COMPLETIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("not-a-json")
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
                .block(Duration.ofSeconds(15));

        assertThat(personaCalls.get()).as("解析失败是我们的问题，不能因此吞请求").isEqualTo(1);
    }
}
