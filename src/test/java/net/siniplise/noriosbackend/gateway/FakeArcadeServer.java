package net.siniplise.noriosbackend.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试专用的假 Arcade 服务：**会真的选子协议**的回声服务器。
 *
 * <p>为什么不用 reactor-netty 的 `routes().ws(...)`：它不选子协议，而我们的上游客户端
 * 按 Netty 的严格规则会拒收（"Invalid subprotocol. Actual: null"）。真实 Arcade 上游是**会选**的，
 * 所以测试里的上游也必须会选，否则测的根本不是生产路径。
 *
 * <p>用 Netty 自带的 {@link WebSocketServerProtocolHandler}：给它一个协议列表，它负责
 * 握手 + 从列表里挑一个回给客户端，帧的编解码也一并处理。
 */
final class FakeArcadeServer implements AutoCloseable {

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup(1);
    private final Channel channel;
    /** 客户端发来的 `Sec-WebSocket-Protocol` 原文（我们断言"有没有带上去"就靠它）。 */
    final AtomicReference<String> requestedProtocols = new AtomicReference<>();
    /** 实际回给客户端的协议。 */
    final AtomicReference<String> selectedProtocol = new AtomicReference<>();

    FakeArcadeServer(String path, String supportedProtocol) {
        try {
            this.channel = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(64 * 1024));
                            // 抓请求头：WebSocketServerProtocolHandler 之后就拿不到原始 header 了
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                    requestedProtocols.set(req.headers().get("Sec-WebSocket-Protocol"));
                                    ctx.fireChannelRead(req.retain());
                                }
                            });
                            ch.pipeline().addLast(
                                    new WebSocketServerProtocolHandler(path, supportedProtocol, true));
                            ch.pipeline().addLast(new EchoHandler());
                        }
                    })
                    .bind("127.0.0.1", 0)
                    .sync()
                    .channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("假上游启动失败", e);
        }
    }

    int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        channel.close();
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }

    /** 握手完成后先回一句 "proto:<协商结果>"，之后原样回声。 */
    private final class EchoHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete done) {
                selectedProtocol.set(done.selectedSubprotocol());
                ctx.writeAndFlush(new TextWebSocketFrame("proto:" + done.selectedSubprotocol()));
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame text) {
                ctx.writeAndFlush(new TextWebSocketFrame(text.text()));
            } else if (frame instanceof BinaryWebSocketFrame binary) {
                ctx.writeAndFlush(new BinaryWebSocketFrame(binary.content().retain()));
            }
        }
    }
}
