package net.siniplise.noriosbackend.obs.logging;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * CRTL 级别：业务代码走 slf4j API 时的「严重」标记。
 *
 * <p>Log4j2 其实有原生 FATAL，但 slf4j 的 {@code Logger} 没有 {@code fatal()} 方法；
 * 为了不让业务包 import {@code org.apache.logging.log4j.*}，这里用「ERROR + 标记」表达，
 * 由 {@link ShortLevelConverter} 渲染成四字符 {@code CRTL}。
 *
 * <p><b>为什么不用 MDC + 辅助方法</b>：多一层调用栈会让 {@code %line} 报到辅助方法内部，
 * 把排查时最有用的一行弄丢。Marker 是 slf4j 原生能力，调用点直接写，行号天然正确：
 *
 * <pre>{@code
 * log.error(Critical.CRITICAL, "存档校验失败，拒绝写入", ex);   // 终端显示 CRTL
 * }</pre>
 *
 * <p>什么时候用：重试也不会好的故障——存档写坏、记忆库损坏、密钥缺失、状态机不一致。
 * 普通的「上游 502」用 {@code log.error} 就够了。
 */
public final class Critical {

    /** 标记名，转换器按这个名字识别。改这里会同时影响渲染，别单独改另一处。 */
    public static final String MARKER_NAME = "NORI_CRITICAL";

    /** 严重标记。 */
    public static final Marker CRITICAL = MarkerFactory.getMarker(MARKER_NAME);

    private Critical() {
        throw new AssertionError("no instances");
    }
}
