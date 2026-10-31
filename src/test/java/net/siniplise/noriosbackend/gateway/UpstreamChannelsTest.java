package net.siniplise.noriosbackend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上游发送口：挑连接、报文格式。
 *
 * <p>重点是**报文必须和 `nori_proxy.js#injectFact` 逐字段一致**——
 * ��个 JSON 一旦写错，上游会静默丢弃（连错误都不给），表现成"fact 注入没反应"。
 */
class UpstreamChannelsTest {

    @Test
    @DisplayName("没有注册任何连接 ⇒ send 返回 false（调用方据此不记账）")
    void noChannel() {
        UpstreamChannels ch = new UpstreamChannels();
        assertThat(ch.size()).isZero();
        assertThat(ch.send("{}")).isFalse();
    }

    @Test
    @DisplayName("挑第一条能发出去的发，且只发一份（不广播）")
    void sendsToOne() {
        UpstreamChannels ch = new UpstreamChannels();
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        ch.register(t -> { a.add(t); return true; });
        ch.register(t -> { b.add(t); return true; });

        assertThat(ch.send("hello")).isTrue();
        assertThat(a).containsExactly("hello");
        assertThat(b).as("不广播：第二条不该收到").isEmpty();
    }

    @Test
    @DisplayName("第一条抛异常 ⇒ 自动试下一条（一条连接坏了不该让注入整体失效）")
    void fallsThroughOnError() {
        UpstreamChannels ch = new UpstreamChannels();
        ch.register(t -> { throw new IllegalStateException("boom"); });
        List<String> ok = new ArrayList<>();
        ch.register(t -> { ok.add(t); return true; });

        assertThat(ch.send("x")).isTrue();
        assertThat(ok).containsExactly("x");
    }

    @Test
    @DisplayName("注销后不再往它发")
    void unregister() {
        UpstreamChannels ch = new UpstreamChannels();
        List<String> a = new ArrayList<>();
        UpstreamChannels.Channel c = t -> { a.add(t); return true; };
        ch.register(c);
        ch.unregister(c);

        assertThat(ch.send("x")).isFalse();
        assertThat(a).isEmpty();
    }

    // ── 报文格式：必须与 nori_proxy.js#injectFact 一致 ──────────────────

    @Test
    @DisplayName("注入报文：channel 是 manifold.dev.jump.request，facts 是数组，带 requestId")
    void injectPayloadShape() {
        List<String> sent = new ArrayList<>();
        UpstreamChannels ch = new UpstreamChannels();
        ch.register(t -> { sent.add(t); return true; });

        UpstreamChannels.InjectorHolder holder = new UpstreamChannels.InjectorHolder(ch);
        assertThat(holder.inject("gesture.chess", "测试")).isTrue();

        assertThat(sent).hasSize(1);
        String json = sent.get(0);
        assertThat(json)
                .contains("\"type\":\"event\"")
                .contains("\"channel\":\"manifold.dev.jump.request\"")
                .contains("\"payload\":{\"facts\":[\"gesture.chess\"]}")
                .containsPattern("\"requestId\":\"[0-9a-f-]{36}\"");
    }

    @Test
    @DisplayName("没有上游连接 ⇒ inject 返回 false，不抛异常")
    void injectWithoutChannel() {
        UpstreamChannels.InjectorHolder holder =
                new UpstreamChannels.InjectorHolder(new UpstreamChannels());
        assertThat(holder.inject("gesture.chess", "无连接")).isFalse();
    }

    @Test
    @DisplayName("null 通道 / 空 fact ⇒ 拒绝，不抛 NPE")
    void injectGuards() {
        assertThat(new UpstreamChannels.InjectorHolder(null).inject("x", "y")).isFalse();
        UpstreamChannels ch = new UpstreamChannels();
        ch.register(t -> true);
        assertThat(new UpstreamChannels.InjectorHolder(ch).inject(null, "y")).isFalse();
        assertThat(new UpstreamChannels.InjectorHolder(ch).inject("", "y")).isFalse();
    }

    @Test
    @DisplayName("每次注入的 requestId 都不同（上游可能按它去重）")
    void uniqueRequestId() {
        List<String> sent = new ArrayList<>();
        UpstreamChannels ch = new UpstreamChannels();
        ch.register(t -> { sent.add(t); return true; });
        UpstreamChannels.InjectorHolder holder = new UpstreamChannels.InjectorHolder(ch);

        holder.inject("a", "1");
        holder.inject("a", "2");

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isNotEqualTo(sent.get(1));
    }
}
