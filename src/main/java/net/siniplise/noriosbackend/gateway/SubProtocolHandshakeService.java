package net.siniplise.noriosbackend.gateway;

import java.util.List;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 握手时**替浏览器回选子协议**。
 *
 * <p><b>为什么必须自己写</b>：Spring WebFlux 的 {@link HandshakeWebSocketService} 不提供选协议的钩子，
 * 默认不发 {@code Sec-WebSocket-Protocol} 响应头。而浏览器（RFC 6455 客户端行为）在请求里列了子协议、
 * 服务端却一个都没选时，会**直接判定握手失败并关闭连接**。
 *
 * <p>实测症状很有迷惑性：服务端日志是
 * {@code AbortedException: Connection has been closed BEFORE send operation}——
 * 看起来像"上游连不上"，实际是**浏览器侧已断**，导致我们的 {@code sendObject} 在发送前发现连接没了。
 * 用 ws 客户端直连我们自己的端口，报的是 {@code Server sent no subprotocol}，一句话就能定位。
 *
 * <p>而 {@code nori_proxy.js} 用 {@code wss.handleUpgrade} 会自动回选，所以旧栈没这个毛病——
 * 这类"旧栈能跑、新栈不行"的差异，最该先怀疑的就是握手细节。
 *
 * <p>选谁：优先 {@code arcade.v1}（上游只认它），否则退回浏览器列表里的第一个。
 * 客户端一个都没请求时返回 {@code null}（不发该头，符合规范）。
 */
public final class SubProtocolHandshakeService extends HandshakeWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(SubProtocolHandshakeService.class);

    /** 上游唯一支持的业务子协议。 */
    static final String ARCADE = "arcade.v1";

    @Override
    public Mono<Void> handleRequest(ServerWebExchange exchange, WebSocketHandler handler) {
        var request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        List<String> requested = WsHeaders.requestedProtocols(headers);
        String selected = pick(requested);
        if (selected != null) {
            log.debug("ws: 回选子协议 {}（客户端请求 {}）", selected, requested);
        } else {
            log.debug("ws: 客户端未请求子协议，不发送该响应头");
        }
        HandshakeInfo info = new HandshakeInfo(
                request.getURI(), headers, exchange.getPrincipal(), selected);
        return getUpgradeStrategy().upgrade(exchange, handler, selected, () -> info);
    }

    /**
     * 选一个要回给浏览器的子协议。
     *
     * @param requested 客户端请求的列表（可能为空）
     * @return 选中的协议；客户端没请求任何协议时返回 {@code null}
     */
    static String pick(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        // 上游只认 arcade.v1：即使它不在第一个，也优先选它。
        // （前端通常发 "arcade.v1, ticket.<token>"，但顺序不保证，不能只取 [0]。）
        for (String p : requested) {
            if (ARCADE.equalsIgnoreCase(p)) {
                return p;
            }
        }
        return requested.get(0);
    }

    /** 便于单测：媒体通道与主通道共用同一套规则，这里只暴露判定以便断言。 */
    static boolean isMediaPath(String path) {
        return path != null && path.startsWith(NoriPaths.WS_MEDIA);
    }
}
