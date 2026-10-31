package net.siniplise.noriosbackend.gateway;

import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.contract.ArcadeFrame;
import net.siniplise.noriosbackend.speech.MediaSink;
import net.siniplise.noriosbackend.speech.MediaSinkProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * 当前媒体连接的唯一出口。
 *
 * <p>为什么是"唯一"：单用户单窗口，音频只能有一个播放端。多连接同时推帧会让客户端收到
 * 两路交错的 seq——那是最难查的一类音频故障（听着像卡带，其实是两路混在一起）。
 * 所以新连接**顶掉**旧连接，并记一条 WARN。
 *
 * <p>线程：注册发生在 WS 线程，发送发生在会话线程。用 {@link AtomicReference} 交换引用，
 * 只做"拿到当前 sink"这一件事；不做锁，也不持有 WebSocketSession 的其他状态。
 */
@Component
public class MediaHub implements MediaSinkProvider {

    private static final Logger log = LoggerFactory.getLogger(MediaHub.class);

    private final AtomicReference<SessionSink> current = new AtomicReference<>();

    /** 新连接顶掉旧的，返回注册结果。 */
    public MediaSink register(WebSocketSession session) {
        SessionSink sink = new SessionSink(session);
        SessionSink previous = current.getAndSet(sink);
        if (previous != null && previous.isOpen()) {
            log.warn("media: 新连接顶掉旧连接（旧连接未关闭）");
        }
        log.info("media: 已注册音频出口 id={}", session.getId());
        return sink;
    }

    /** 连接关闭时解绑（只有当前仍是自己才清空，避免把新连接误清）。 */
    public void unregister(MediaSink sink) {
        if (current.compareAndSet((SessionSink) sink, null)) {
            log.info("media: 音频出口已解绑");
        }
    }

    @Override
    public MediaSink current() {
        SessionSink sink = current.get();
        return sink != null && sink.isOpen() ? sink : null;
    }

    /** 当前有没有可用的出口。 */
    public boolean hasSink() {
        SessionSink sink = current.get();
        return sink != null && sink.isOpen();
    }

    /** 把一帧推给当前连接；没有连接则丢弃并返回 false。 */
    public boolean broadcast(ArcadeFrame frame) {
        SessionSink sink = current.get();
        if (sink == null || !sink.isOpen()) {
            return false;
        }
        sink.send(frame);
        return true;
    }

    /** 一条媒体连接的发送器。 */
    static final class SessionSink implements MediaSink {
        private final WebSocketSession session;
        private volatile boolean open = true;

        SessionSink(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void send(ArcadeFrame frame) {
            if (!open) {
                return;
            }
            byte[] encoded = frame.encode();
            // 注意：这里只发**帧本身**。complete/seq 等语义都在头里，不要额外包一层 JSON
            session.send(reactor.core.publisher.Mono.just(
                    session.binaryMessage(factory -> factory.wrap(encoded)))).subscribe();
        }

        @Override
        public boolean isOpen() {
            return open && session.isOpen();
        }

        void markClosed() {
            open = false;
        }
    }

    /** 供代理在连接结束时调用。 */
    public void close(MediaSink sink) {
        if (sink instanceof SessionSink s) {
            s.markClosed();
        }
        unregister(sink);
    }
}
