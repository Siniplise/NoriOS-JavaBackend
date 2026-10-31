package net.siniplise.noriosbackend.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上游发送口：给"代理主动发一帧给游戏本体"用。
 *
 * <p><b>为什么要它</b>：反代默认只做透传，但超限手势那四枚 fact 必须由我们**主动注入**
 * （官方链路是客户端发，本地 exe 不落库 ⇒ 只能代理补齐）。JS 侧的对应物是
 * `nori_proxy.js` 里的 `UPS` 集合 + `pickUp()`：挑一个 readyState=1 的连接发出去。
 *
 * <p><b>为什么是"多连接挑一个"而不是"绑一个"</b>：实测同一时刻浏览器会开 4 条 WS 连接
 * （Q27），任何一条断开都不该让注入能力消失。这里用 `CopyOnWriteArrayList`：
 * 注册/注销只在连接建立/断开时发生（低频），发送在热路径上（高频读）——正好是 COW 的适用场景。
 *
 * <p><b>不持有业务状态</b>：这里只负责"发",判断发什么、该不该发，属于 {@code game} 包。
 */
@Component
public final class UpstreamChannels {

    private static final Logger log = LoggerFactory.getLogger(UpstreamChannels.class);

    /** 一条可用的上游连接。 */
    @FunctionalInterface
    public interface Channel {
        /** @return 是否成功交给传输层 */
        boolean send(String text);
    }

    private final CopyOnWriteArrayList<Channel> channels = new CopyOnWriteArrayList<>();

    public void register(Channel channel) {
        if (channel != null) {
            channels.add(channel);
            log.debug("gateway: 上游发送口注册，当前 {} 条", channels.size());
        }
    }

    public void unregister(Channel channel) {
        if (channel != null && channels.remove(channel)) {
            log.debug("gateway: 上游发送口注销，剩余 {} 条", channels.size());
        }
    }

    public int size() {
        return channels.size();
    }

    /**
     * 挑第一条能发出去的发。
     *
     * <p>为什么不广播：这是"代替客户端说一句话"，发 N 份会让上游收到 N 次同样的请求
     * （虽然 {@code manifold.dev.jump.request} 幂等，但日志会变成噪音，且掩盖真正的重发）。
     *
     * @return 至少一条发成功才返回 true
     */
    public boolean send(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<Channel> snapshot = new ArrayList<>(channels);
        for (Channel ch : snapshot) {
            try {
                if (ch.send(text)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("gateway: 这条上游发送口失败，试下一条: {}", e.toString());
            }
        }
        return false;
    }

    /** 测试用：清空。 */
    public void clear() {
        channels.clear();
    }

    /**
     * 把「注入一枚 fact」这件事封成一个闭包，装配层不用关心报文长什么样。
     *
     * <p>报文格式（与 `nori_proxy.js#injectFact` 逐字段一致）：
     * <pre>{@code
     * {"type":"event","channel":"manifold.dev.jump.request",
     *  "payload":{"facts":["gesture.chess"]},"requestId":"<uuid>"}
     * }</pre>
     *
     * <p>为什么手写字符串而不用 JsonMapper：这里的两个变量（fact、requestId）都受控
     * （fact 来自常量表，requestId 是 UUID），没有转义风险；而多绕一次序列化只会让
     * "发出去的到底是什么"更难核对。真要带任意文本时再换成 mapper。
     */
    public static final class InjectorHolder {

        private final UpstreamChannels channels;

        public InjectorHolder(UpstreamChannels channels) {
            this.channels = channels;
        }

        /** @return 是否真的发出去了；没有上游连接时 false（调用方据此不记账） */
        public boolean inject(String fact, String why) {
            if (channels == null || fact == null || fact.isEmpty()) {
                return false;
            }
            String requestId = java.util.UUID.randomUUID().toString();
            String json = "{\"type\":\"event\",\"channel\":\""
                    + net.siniplise.noriosbackend.game.GestureFacts.INJECT_CHANNEL
                    + "\",\"payload\":{\"facts\":[\"" + fact + "\"]},\"requestId\":\""
                    + requestId + "\"}";
            boolean ok = channels.send(json);
            if (!ok) {
                log.debug("gateway: 注入 {} 未发出（无可用上游连接）— {}", fact, why);
            }
            return ok;
        }

        /**
         * 发一段**已经拼好的** JSON 给上游。
         *
         * <p>给帧流驱动用：那边要发的是 {@code dispatch}（带 cmd / expectedHeadVersion），
         * 结构比"注入一枚 fact"复杂得多，让调用方自己序列化更清楚，
         * 也避免这里变成一个"什么报文都拼"的万能工厂。
         */
        public boolean injectRaw(String json) {
            if (channels == null || json == null || json.isBlank()) {
                return false;
            }
            return channels.send(json);
        }
    }
}
