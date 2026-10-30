package net.siniplise.noriosbackend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 消息必填字段的守门规则。
 *
 * <p>这些规则存在的唯一理由：上游客户端用 Zod {@code .strict()} 解析，缺字段<b>静默丢弃</b>。
 * 丢了之后的现象是「发了指令没反应」或者「心跳没回应」，现场没有任何报错——所以必须在发送前拦。
 */
class ArcadeProtocolTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    @DisplayName("pong 少 serverId 要报出来；时间字段叫 now，不叫 timestamp")
    void pongRequiresServerId() {
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"pong\"}")))
                .containsExactly("serverId");

        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"pong\",\"serverId\":\"s1\"}")))
                .isEmpty();

        // 上游 schema: { type, serverId, now? } —— 写成 timestamp 客户端认不出来
        assertThat(ArcadeProtocol.PONG_TIME_FIELD).isEqualTo("now");
    }

    @Test
    @DisplayName("dispatch_ack 必须带齐 8 个字段")
    void dispatchAckRequiresFullSet() {
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"dispatch_ack\",\"worldId\":\"w\"}")))
                .containsExactlyInAnyOrder("cartridgeId", "requestId", "success",
                        "committed", "committedVersion", "headVersion", "result");

        String full = """
                {"type":"dispatch_ack","worldId":"w","cartridgeId":"chat","requestId":"r",
                 "success":true,"committed":true,"committedVersion":1,"headVersion":1,"result":{}}
                """;
        assertThat(ArcadeProtocol.missingRequired(json(full))).isEmpty();
    }

    @Test
    @DisplayName("cartridge_mounted_ack 必须带 transition 与 runtimes")
    void mountAckRequiresTransitionAndRuntimes() {
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"cartridge_mounted_ack\"}")))
                .containsExactlyInAnyOrder("transition", "runtimes");
    }

    @Test
    @DisplayName("null 值按缺字段处理（与上游 strict 解析同口径）")
    void nullCountsAsMissing() {
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"pong\",\"serverId\":null}")))
                .containsExactly("serverId");
    }

    @Test
    @DisplayName("文档没写字段的类型不瞎猜，一律放行")
    void unknownTypesAreNotOverConstrained() {
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"error\"}"))).isEmpty();
        assertThat(ArcadeProtocol.missingRequired(json("{\"type\":\"whatever_new\"}"))).isEmpty();
    }

    @Test
    @DisplayName("requireWellFormed 直接抛，并点名缺哪个字段")
    void requireWellFormedThrows() {
        assertThatThrownBy(() -> ArcadeProtocol.requireWellFormed(json("{\"type\":\"pong\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pong")
                .hasMessageContaining("serverId");
    }

    @Test
    @DisplayName("类型清单：客户端 10 种、服务端 15 种，且 open_media 单列")
    void typeSets() {
        assertThat(ArcadeProtocol.CLIENT_TO_SERVER).hasSize(10)
                .contains("open_my_web_world", "dispatch", "advance_visibility_fence", "ping", "event")
                .doesNotContain(ArcadeProtocol.OPEN_MEDIA);

        assertThat(ArcadeProtocol.SERVER_TO_CLIENT).hasSize(15)
                .contains("world_joined", "runtime_transition", "dispatch_ack", "pong", "error");

        assertThat(ArcadeProtocol.isKnownServerType("pong")).isTrue();
        assertThat(ArcadeProtocol.isKnownServerType("nope")).isFalse();
        assertThat(ArcadeProtocol.typeOf(json("{\"type\":\"pong\",\"serverId\":\"x\"}"))).isEqualTo("pong");
        assertThat(ArcadeProtocol.typeOf(null)).isEmpty();
        assertThat(ArcadeProtocol.typeOf(json("{\"noType\":1}"))).isEmpty();
    }
}
