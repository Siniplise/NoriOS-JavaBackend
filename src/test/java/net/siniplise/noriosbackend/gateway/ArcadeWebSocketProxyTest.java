package net.siniplise.noriosbackend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.siniplise.noriosbackend.app.NoriBackendApplication;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;


/**
 * WS 双向代理的真实验证。
 *
 * <p>三个断言对应三个"错了不会报错、只会表现成怪现象"的点：
 * <ol>
 *   <li><b>子协议带上游</b>：假上游记录它收到的 {@code Sec-WebSocket-Protocol}。
 *       没带上去 → 上游回一个我们没请求过的协议 → 无限重连（实测 75 次）；</li>
 *   <li><b>文本逐字符一致</b>：故意用空白不规范、字段顺序非标准的 JSON，验证不做重序列化；</li>
 *   <li><b>二进制逐字节一致</b>：音频帧差一个字节就是整句噪音。</li>
 * </ol>
 *
 * <p>客户端用 JDK 的 {@code java.net.http.WebSocket} 而不是 Netty 的：Spring 7 服务端不再
 * "回选"子协议（原有 HandshakeHandler 已移除），浏览器容忍这种情况，Netty 客户端却会直接抛
 * "Invalid subprotocol. Actual: null"——那是测试客户端的严格性，不是产品缺陷。
 */
class ArcadeWebSocketProxyTest {

    private static FakeArcadeServer upstream;
    private static int upstreamPort;

    @BeforeAll
    static void startFakeUpstream() {
        // 假上游必须"会选协议"，否则测的不是生产路径
        upstream = new FakeArcadeServer(NoriPaths.WS_MAIN, "arcade.v1");
        upstreamPort = upstream.port();
    }

    @AfterAll
    static void stopFakeUpstream() {
        if (upstream != null) {
            upstream.close();
        }
    }

    private static ConfigurableApplicationContext startApp() {
        ConfigLoader.Loaded loaded = ConfigLoader.load()
                .withGameExe("http://127.0.0.1:" + upstreamPort);
        SpringApplication app = NoriBackendApplication.newApplication(loaded);
        return app.run("--server.port=0");
    }

    @Test
    @DisplayName("WS 透传：子协议带到上游、文本逐字符一致、二进制逐字节一致")
    void proxiesWebSocket() throws Exception {
        try (ConfigurableApplicationContext context = startApp()) {
            String port = context.getEnvironment().getProperty("local.server.port");
            URI uri = URI.create("ws://127.0.0.1:" + port + NoriPaths.WS_MAIN);

            List<Object> received = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch gotThree = new CountDownLatch(3);

            WebSocket socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .subprotocols("arcade.v1", "ticket.test-token")
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            received.add(data.toString());
                            gotThree.countDown();
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            received.add(bytes);
                            gotThree.countDown();
                            ws.request(1);
                            return null;
                        }
                    })
                    .join();

            socket.request(1);

            String text = "{\"type\":\"ping\",  \"pad\" :\"x\"}";
            byte[] binary = {0, 1, 2, (byte) 0xFA, (byte) 0xFF, 7};

            socket.sendText(text, true).join();
            socket.sendBinary(ByteBuffer.wrap(binary), true).join();

            assertThat(gotThree.await(15, TimeUnit.SECONDS))
                    .as("应在 15s 内收到 问候 + 两条回声")
                    .isTrue();
            socket.abort();

            assertThat(upstream.requestedProtocols.get())
                    .as("子协议列表必须原样带给上游")
                    .isEqualTo("arcade.v1,ticket.test-token");

            assertThat(received.get(0)).as("上游协商到的协议").isEqualTo("proto:arcade.v1");

            assertThat(received.get(1))
                    .as("文本帧逐字符一致（不重排、不压缩空白）")
                    .isEqualTo(text);

            assertThat(received.get(2)).as("二进制逐字节一致").isEqualTo(binary);
        }
    }

    @Test
    @DisplayName("媒体通道：没有子协议也能建连（浏览器不一定带）")
    void worksWithoutSubprotocol() {
        try (ConfigurableApplicationContext context = startApp()) {
            String port = context.getEnvironment().getProperty("local.server.port");
            WebSocket socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + NoriPaths.WS_MEDIA),
                            new WebSocket.Listener() { })
                    .join();
            assertThat(socket.isOutputClosed()).isFalse();
            socket.abort();
        }
    }
}
