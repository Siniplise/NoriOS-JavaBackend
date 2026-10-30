package net.siniplise.noriosbackend.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * WS 升级相关的纯函数：子协议解析、上游头过滤。
 *
 * <p>单独抽出来是因为这两件事都**错一次就整条链路连不上**，而且症状很误导人：
 * <ul>
 *   <li>子协议没带上去 → 上游选了一个协议、客户端没请求过 → "Server sent a subprotocol but none
 *       was requested" → 客户端无限重连（实测重连 75 次）；</li>
 *   <li>握手头没剔掉 → 上游按我们的 {@code sec-websocket-key} 算 accept → 握手直接失败。</li>
 * </ul>
 * 抽成纯函数后可以单测，不用每次靠"连一次试试"。
 */
public final class WsHeaders {

    /** 客户端请求里的子协议列表，形如 {@code arcade.v1, ticket.<token>}。 */
    public static List<String> requestedProtocols(HttpHeaders headers) {
        String raw = headers.getFirst("Sec-WebSocket-Protocol");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    /** 这些头由客户端库重新生成，带上去会与真实握手冲突。 */
    private static final Set<String> HANDSHAKE_ONLY = Set.of(
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-accept",
            "sec-websocket-protocol", // 通过 WebsocketClientSpec.protocols 传，不走头
            "upgrade",
            "connection",
            "host",
            "content-length");

    /** 转发给上游的升级头（不含子协议，子协议走 spec）。 */
    public static HttpHeaders forUpstream(HttpHeaders source) {
        HttpHeaders out = new HttpHeaders();
        source.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HANDSHAKE_ONLY.contains(lower)) {
                out.put(name, values);
            }
        });
        return out;
    }

    private WsHeaders() {
        throw new AssertionError("no instances");
    }
}
