package net.siniplise.noriosbackend.tts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import net.siniplise.noriosbackend.config.ConfigLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * TTS 客户端：请求体、WAV 解码、重采样、错误分类。
 *
 * <p>用假上游而不是真打 8880：真 TTS 每次都要花钱、还要看服务起没起，
 * 而这里要验的是"我们发对了、解得对、错了能分辨"，全都与真实合成无关。
 */
class HttpTtsClientTest {

    private static HttpServer upstream;
    private static String base;
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static volatile byte[] nextResponse;
    private static volatile int nextStatus = 200;

    @BeforeAll
    static void startFakeTts() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/tts", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = nextResponse;
            exchange.sendResponseHeaders(nextStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        upstream.start();
        base = "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    /** 造一个 48kHz 单声道 16bit 的 WAV，1 秒。 */
    private static byte[] wav48k(int samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(48_000)
                .putInt(96_000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            b.putShort((short) (Math.sin(2 * Math.PI * 440 * i / 48_000.0) * 8000));
        }
        return b.array();
    }

    private static HttpTtsClient clientFor(String upstreamBase) {
        ConfigLoader.Loaded loaded = ConfigLoader.load();
        // 换成假上游（withGameExe 只改游戏上游，所以这里直接改配置里的 tts 地址）
        ConfigLoader.Loaded custom = replaceTts(loaded, upstreamBase);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("loaded", custom);
        ObjectProvider<ConfigLoader.Loaded> provider = factory.getBeanProvider(ConfigLoader.Loaded.class);
        return new HttpTtsClient(provider);
    }

    private static ConfigLoader.Loaded replaceTts(ConfigLoader.Loaded loaded, String ttsUrl) {
        var cfg = loaded.config();
        var up = cfg.upstreams();
        var next = new net.siniplise.noriosbackend.config.NoriConfig(cfg.ports(),
                new net.siniplise.noriosbackend.config.NoriConfig.Upstreams(
                        up.gameExe(), up.legacyProxy(), ttsUrl, up.persona()),
                cfg.secrets(), cfg.llm(), cfg.tts(), cfg.script(), cfg.speech(),
                cfg.touch(), cfg.memory(), cfg.save(), cfg.obs());
        return new ConfigLoader.Loaded(next, loaded.effectiveJson(), loaded.effectiveTree(),
                loaded.unknownKeys(), loaded.source());
    }

    @Test
    @DisplayName("合成：48k WAV → 32k 采样，样本数按 2/3 收缩；请求体带 text 与 voice")
    void synthesizesAndResamples() {
        nextStatus = 200;
        nextResponse = wav48k(48_000); // 1 秒
        HttpTtsClient client = clientFor(base);

        short[] pcm = client.synthesize32k("你好呀", "nori-voice-1").block(java.time.Duration.ofSeconds(10));

        assertThat(pcm).isNotNull();
        assertThat(pcm.length).as("48k 1 秒 → 32k 1 秒").isEqualTo(32_000);

        String body = lastBody.get();
        assertThat(body).contains("\"text\":\"你好呀\"").contains("\"voice\":\"nori-voice-1\"");
    }

    @Test
    @DisplayName("上游 5xx：抛带状态码与上游原话的异常，不静默返回空音频")
    void reportsUpstreamError() {
        nextStatus = 502;
        nextResponse = "tts error connect ECONNREFUSED".getBytes(StandardCharsets.UTF_8);
        HttpTtsClient client = clientFor(base);

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.synthesize32k("测试").block(java.time.Duration.ofSeconds(10)));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("502").contains("ECONNREFUSED");
    }

    @Test
    @DisplayName("上游回的不是 WAV：解码阶段显式失败")
    void rejectsNonWav() {
        nextStatus = 200;
        nextResponse = "{\"oops\":true}".getBytes(StandardCharsets.UTF_8);
        HttpTtsClient client = clientFor(base);

        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.synthesize32k("测试").block(java.time.Duration.ofSeconds(10)));

        assertThat(error).isNotNull();
        assertThat(error).hasMessageContaining("WAV");
    }

    @Test
    @DisplayName("空文本不发起请求（避免无意义的上游调用与计费）")
    void rejectsBlankText() {
        HttpTtsClient client = clientFor(base);
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.synthesize32k("  ").block(java.time.Duration.ofSeconds(5)));
        assertThat(error).isInstanceOf(IllegalArgumentException.class);
    }
}
