package net.siniplise.noriosbackend.gateway;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.net.URI;
import java.util.List;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import net.siniplise.noriosbackend.speech.MediaSink;
import net.siniplise.noriosbackend.obs.logging.Critical;
import net.siniplise.noriosbackend.obs.logging.FatalGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

/**
 * Arcade WebSocket 双向代理：`/api/arcade/web/v1`（文本）与 `.../media`（二进制音频）。
 *
 * <h2>三条铁律</h2>
 * <ol>
 *   <li><b>文本帧原样透传</b>：不 parse、不 stringify。前端对字段顺序与未知字段敏感（HANDOFF §2.3）。
 *       这里只在"要不要记日志/喂生命周期"时读一眼，转发的永远是原始字符。</li>
 *   <li><b>子协议必须带上游</b>：客户端请求 `arcade.v1, ticket.<token>`，用
 *       {@link WebsocketClientSpec#protocols} 传。不传 → 上游选了一个我们没请求过的协议 →
 *       "Server sent a subprotocol but none was requested" → 客户端无限重连（实测 75 次）。</li>
 *   <li><b>升级请求不能走 HTTP 反代</b>：见 {@link HttpReverseProxy} 里的 Upgrade 判断。</li>
 * </ol>
 *
 * <h2>为什么按"帧"而不是"字节流"桥接</h2>
 * 媒体通道的语义单位是**帧**（每帧 48 字节头 + PCM）。按字节泵会丢掉帧边界，
 * 一旦上游把两帧合进一个 TCP 段，浏览器侧就再也切不回来。所以下行用
 * {@link WebsocketInbound#receiveFrames()}、上行逐条 {@link WebSocketMessage} 映射成帧。
 *
 * <h2>零拷贝</h2>
 * 当前**复制字节**：Spring 的 payload 是 {@link DataBuffer}，转 Netty 帧时读出来。
 * 代价是每帧一次拷贝（音频帧 11KB，实测可接受）；收益是不会踩"buffer 已回收还在用"（Q6）。
 * 等确认吞吐是瓶颈再换 retain。
 */
@Component
public class ArcadeWebSocketProxy implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ArcadeWebSocketProxy.class);

    /** 单帧上限：音频块 11KB。留足余量，但不无限大（防内存放大）。 */
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private final URI upstream;
    private final HttpClient httpClient;
    private final MediaHub mediaHub;
    private final net.siniplise.noriosbackend.app.SessionRuntime session;
    private final UpstreamChannels channels;

    public ArcadeWebSocketProxy(ObjectProvider<ConfigLoader.Loaded> configProvider, MediaHub mediaHub,
                                net.siniplise.noriosbackend.app.SessionRuntime session,
                                UpstreamChannels channels) {
        ConfigLoader.Loaded loaded = configProvider.getIfAvailable(ConfigLoader::load);
        this.upstream = URI.create(loaded.config().upstreams().gameExe());
        this.httpClient = HttpClient.create();
        this.mediaHub = mediaHub;
        this.session = session;
        this.channels = channels;
        log.info("gateway: WS 反代目标 {}", upstream);
    }

    @Override
    public Mono<Void> handle(WebSocketSession browser) {
        HandshakeInfo info = browser.getHandshakeInfo();
        String path = info.getUri().getRawPath()
                + (info.getUri().getRawQuery() == null ? "" : "?" + info.getUri().getRawQuery());
        List<String> protocols = WsHeaders.requestedProtocols(info.getHeaders());

        var spec = WebsocketClientSpec.builder().maxFramePayloadLength(MAX_FRAME_BYTES);
        if (!protocols.isEmpty()) {
            // 注意：reactor-netty 这个 API 收的是**逗号分隔的单个字符串**，不是 varargs
            spec.protocols(String.join(",", protocols));
        }

        // 媒体通道额外登记为"音频出口"：台词合成后由会话线程直接推帧给这个连接。
        // 上游仍然照常转发（上游若也发媒体帧，两边内容不会冲突——实测上游一个都不发）。
        final MediaSink registered = path.startsWith(NoriPaths.WS_MEDIA)
                ? mediaHub.register(browser) : null;

        String upstreamUrl = "ws://" + upstream.getAuthority() + path;
        log.debug("ws: 升级 {} -> {} protocols={}", path, upstreamUrl, protocols);

        return httpClient
                // 注意命名空间：reactor-netty 的 headers 回调给的是 Netty 的 HttpHeaders，
                // 不是 Spring 的；所以逐个 add，不能 addAll(Spring HttpHeaders)
                .headers(h -> WsHeaders.forUpstream(info.getHeaders())
                        .forEach((name, values) -> h.set(name, values)))
                .websocket(spec.build())
                .uri(upstreamUrl)
                .connect()
                .flatMap(conn -> {
                    // Connection.inbound() 的静态类型是 NettyInbound，WS 场景下实际实现是 WebsocketInbound
                    WebsocketInbound inbound = (WebsocketInbound) conn.inbound();
                    WebsocketOutbound outbound = (WebsocketOutbound) conn.outbound();

                    // ── 主动注入通道 ────────────────────────────────────────────────
                    // 用途：超限手势那四枚 fact 必须由代理**主动发一帧**给上游（客户端不发）。
                    // 做法：把注入帧并进**同一个**出站序列（WebsocketOutbound 只接受一个出站流，
                    // 另开一次 sendObject().subscribe() 会与透传的那条打架）。
                    //
                    // ★ 只对业务通道开放：媒体通道发文本帧没有意义，它自己已是音频出口。
                    // ★ 早期这里被判为"不可用"，是因为当时**子协议没回选**——浏览器握手即断，
                    //   纯透传也报 AbortedException。子协议修好后（见 SubProtocolHandshakeService）
                    //   才能公平地评估这个写法。
                    final Sinks.Many<WebSocketFrame> injected = path.startsWith(NoriPaths.WS_MEDIA)
                            ? null
                            : Sinks.many().unicast().onBackpressureBuffer();
                    final UpstreamChannels.Channel channel = injected == null
                            ? null
                            : text -> injected.tryEmitNext(new TextWebSocketFrame(text)).isSuccess();
                    if (channel != null) {
                        channels.register(channel);
                    }

                    Mono<Void> down = browser.send(
                            inbound.receiveFrames()
                                    .handle((frame, sink) -> {
                                        // 旁路观察：只读，不阻塞转发；异常一律吞掉，绝不能影响帧流
                                        if (frame instanceof TextWebSocketFrame text && session != null) {
                                            session.observeFrame(text.text())
                                                    .exceptionally(e -> {
                                                        log.warn("ws: 生命周期观察失败（不影响转发）: {}", e.toString());
                                                        return false;
                                                    });
                                        }
                                        WebSocketMessage message = toBrowserMessage(browser, frame);
                                        if (message != null) {
                                            sink.next(message);
                                        }
                                    }));

                    // 浏览器上行（`browser.receive()` 全流程只订阅这一次）
                    Flux<WebSocketFrame> fromBrowser = browser.receive()
                            .<WebSocketFrame>handle((message, sink) -> {
                                WebSocketFrame frame;
                                try {
                                    frame = toUpstreamFrame(message);
                                } catch (Throwable t) {
                                    log.error(Critical.CRITICAL,
                                            "ws: 上行帧转换失败（这一帧被丢弃，玩家操作可能没生效）", t);
                                    return;
                                }
                                if (frame != null) {
                                    // 旁路观察**上行**文本帧：你画我猜的笔画只在这里出现
                                    // （服务端不回传"这张画长什么样"）。只读，失败不影响转发。
                                    if (frame instanceof TextWebSocketFrame text && session != null) {
                                        FatalGuard.run("ws 上行观察", () -> session.observeUpstreamFrame(text.text()));
                                    }
                                    sink.next(frame);
                                }
                            });
                    // 单源 = 浏览器上行；有注入时把两条并成一条（merge 保持各自内部顺序）
                    Flux<WebSocketFrame> toUpstream = injected == null
                            ? fromBrowser
                            : Flux.merge(fromBrowser, injected.asFlux());

                    // sendObject 负责把 WebSocketFrame 交给 WS 编码器；send() 只收 ByteBuf，不能用
                    Mono<Void> up = outbound.sendObject(toUpstream).then();
                    // 任一方向结束就收整条连接：半开的 WS 会让对端以为还活着
                    return Mono.when(down, up).doFinally(signal -> {
                        if (channel != null) {
                            channels.unregister(channel);
                            injected.tryEmitComplete();
                        }
                        conn.dispose();
                    });
                })
                .doFinally(signal -> {
                    if (registered != null) {
                        mediaHub.close(registered);
                    }
                })
                .onErrorResume(error -> {
                    log.error("ws: 连上游失败 {}: {}", upstreamUrl, error.toString());
                    return browser.close();
                });
    }

    /**
     * 浏览器 → 上游。
     *
     * <p>控制帧（ping/pong）不转发：它们是**这一跳**的保活语义，跨跳转发会让两端的心跳互相打架；
     * 业务层本来就有 JSON `ping`（golden 里 48 次），不依赖 WS 层心跳。
     */
    private WebSocketFrame toUpstreamFrame(WebSocketMessage message) {
        return switch (message.getType()) {
            case TEXT -> new TextWebSocketFrame(message.getPayloadAsText());
            case BINARY -> new BinaryWebSocketFrame(Unpooled.wrappedBuffer(readBytes(message.getPayload())));
            default -> null;
        };
    }

    /** 上游 → 浏览器；同样是文本/二进制。 */
    private WebSocketMessage toBrowserMessage(WebSocketSession browser, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            return browser.textMessage(text.text());
        }
        if (frame instanceof BinaryWebSocketFrame binary) {
            byte[] bytes = new byte[binary.content().readableBytes()];
            binary.content().getBytes(binary.content().readerIndex(), bytes);
            return browser.binaryMessage(factory -> factory.wrap(bytes));
        }
        return null;
    }

    private static byte[] readBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return bytes;
    }

    /** 供测试：把一串消息按 UTF-8 编成帧，用于验证"不改写"这条约束。 */
    static Flux<WebSocketFrame> textFrames(String... texts) {
        return Flux.fromArray(texts).map(TextWebSocketFrame::new);
    }
}
