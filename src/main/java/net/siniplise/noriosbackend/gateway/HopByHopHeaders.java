package net.siniplise.noriosbackend.gateway;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * 逐跳（hop-by-hop）头过滤。
 *
 * <p>这些头描述的是**这一跳连接**，不是请求本身。原样转发会让上游误判连接状态：
 * 最典型的是把 `Transfer-Encoding: chunked` 转发给一个已经重新计算长度的请求，
 * 或者把 `Connection: keep-alive` 带过去导致连接复用错乱。
 *
 * <p>{@code Host} 也要重写：上游按自己的 Host 做路由/校验，带上客户端的 Host 会出怪事
 * （实测：录制代理保留客户端 Host 时，上游行为会变）。
 */
public final class HopByHopHeaders {

    private static final Set<String> NAMES = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    /**
     * 请求侧额外要丢的：由客户端库重新计算，带过去会与真实长度冲突。
     *
     * <p>注意 {@code Content-Length} **只丢请求侧**。响应侧如果也丢了、又不给 chunked，
     * 消息就没有边界，Spring 会直接把 body 吞掉——实测症状是「200 但 body 为 null」。
     */
    private static final Set<String> REQUEST_ONLY = Set.of("content-length", "host");

    /** 该不该丢掉这个头。 */
    public static boolean isHopByHop(String name) {
        return NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 复制一组成熟的头，过滤掉逐跳项，并把 Host 改写成上游。 */
    public static HttpHeaders forUpstream(HttpHeaders source, String upstreamAuthority) {
        HttpHeaders out = new HttpHeaders();
        source.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!isHopByHop(lower) && !REQUEST_ONLY.contains(lower)) {
                out.put(name, values);
            }
        });
        out.set(HttpHeaders.HOST, upstreamAuthority);
        return out;
    }

    /** 复制上游回来的头，过滤掉逐跳项（不额外加东西）。 */
    public static HttpHeaders forClient(HttpHeaders source) {
        HttpHeaders out = new HttpHeaders();
        source.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            // 保留 content-length：它是上游真实长度，删了会让响应体失去边界
            if (!isHopByHop(lower) && !"host".equals(lower)) {
                out.put(name, values);
            }
        });
        return out;
    }

    private HopByHopHeaders() {
        throw new AssertionError("no instances");
    }
}
