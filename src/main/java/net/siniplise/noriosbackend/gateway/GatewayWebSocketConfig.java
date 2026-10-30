package net.siniplise.noriosbackend.gateway;

import java.util.Map;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * 把两条 WS 通道挂到 {@link ArcadeWebSocketProxy}。
 *
 * <p>order 给 {@link Ordered#HIGHEST_PRECEDENCE}：WS 升级必须**先于**注解控制器与 HTTP 反代被认领，
 * 否则会被当成普通 GET 转给上游（症状是浏览器报 "Unexpected response code: 200"）。
 */
@Configuration
public class GatewayWebSocketConfig {

    @Bean
    public HandlerMapping arcadeWebSocketMapping(ArcadeWebSocketProxy proxy) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        mapping.setUrlMap(Map.<String, WebSocketHandler>of(
                NoriPaths.WS_MAIN, proxy,
                NoriPaths.WS_MEDIA, proxy));
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
