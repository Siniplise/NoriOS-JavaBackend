package net.siniplise.noriosbackend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * 子协议回选：错了会让浏览器**直接断开**，而服务端日志只会说"连接已关闭"——
 * 症状指向上游，实际是自己的握手。所以每条边界都钉死。
 */
class SubProtocolHandshakeServiceTest {

    @Test
    @DisplayName("优先选 arcade.v1（上游只认它）")
    void prefersArcade() {
        assertThat(SubProtocolHandshakeService.pick(List.of("arcade.v1", "ticket.abc")))
                .isEqualTo("arcade.v1");
    }

    @Test
    @DisplayName("arcade.v1 不在第一个也照样选它（顺序不保证）")
    void picksArcadeEvenIfNotFirst() {
        assertThat(SubProtocolHandshakeService.pick(List.of("ticket.abc", "arcade.v1")))
                .isEqualTo("arcade.v1");
    }

    @Test
    @DisplayName("只有 ticket 时退回第一个（不能返回 null，否则浏览器会拒）")
    void fallsBackToFirst() {
        assertThat(SubProtocolHandshakeService.pick(List.of("ticket.abc"))).isEqualTo("ticket.abc");
    }

    @Test
    @DisplayName("客户端没请求任何协议 ⇒ 返回 null（不发该响应头）")
    void nullWhenNoRequested() {
        assertThat(SubProtocolHandshakeService.pick(List.of())).isNull();
        assertThat(SubProtocolHandshakeService.pick(null)).isNull();
    }

    @Test
    @DisplayName("头解析：逗号分隔 + 去空白")
    void parsesHeader() {
        HttpHeaders h = new HttpHeaders();
        h.set("Sec-WebSocket-Protocol", "arcade.v1, ticket.eyJhbGciOi");
        assertThat(WsHeaders.requestedProtocols(h)).containsExactly("arcade.v1", "ticket.eyJhbGciOi");
        assertThat(SubProtocolHandshakeService.pick(WsHeaders.requestedProtocols(h)))
                .isEqualTo("arcade.v1");
    }

    @Test
    @DisplayName("头缺失 ⇒ 空列表，不回选")
    void missingHeader() {
        assertThat(WsHeaders.requestedProtocols(new HttpHeaders())).isEmpty();
        assertThat(SubProtocolHandshakeService.pick(WsHeaders.requestedProtocols(new HttpHeaders())))
                .isNull();
    }
}
