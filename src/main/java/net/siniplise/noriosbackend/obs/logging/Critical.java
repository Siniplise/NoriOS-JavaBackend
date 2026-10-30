package net.siniplise.noriosbackend.obs.logging;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * CRTL 级别：用 slf4j 的 Marker 表达「这条要人立刻看」。
 *
 * <p>logback 1.5 没有 FATAL 级别，所以 CRTL 不是一个真的 Level，而是
 * 「ERROR + {@link #CRITICAL} 标记」，由 {@link ShortLevelConverter} 渲染成四字符 {@code CRTL}。
 *
 * <p><b>为什么不用 MDC + 辅助方法</b>：那样会多一层调用栈，{@code %line} 会报到辅助方法内部，
 * 而不是业务代码那一行——排查时等于把最有用的信息弄丢了。Marker 是 slf4j 原生能力，
 * 调用点仍然直接写 {@code log.error(...)}，行号天然正确：
 *
 * <pre>{@code
 * log.error(Critical.CRITICAL, "存档校验失败，拒绝写入", ex);   // 终端显示 CRTL
 * }</pre>
 *
 * <p>什么时候用：重试也不会好的故障——存档写坏、记忆库损坏、密钥缺失、状态机不一致。
 * 普通的「上游 502」用 {@code log.error} 就够了。
 */
public final class Critical {

    /** 严重标记。名字固定为 {@code NORI_CRITICAL}，转换器按这个名字识别。 */
    public static final Marker CRITICAL = MarkerFactory.getMarker("NORI_CRITICAL");

    /** 便捷方法：不写 Marker 也能打出 CRTL。注意行号会指向这里，不如直接用 {@link #CRITICAL}。 */
    public static void log(Logger logger, String message, Throwable throwable) {
        logger.error(CRITICAL, message, throwable);
    }

    private Critical() {
        throw new AssertionError("no instances");
    }
}
