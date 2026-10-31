package net.siniplise.noriosbackend.obs.logging;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 兜底守卫：**任何未预期的异常都必须留下痕迹，绝不允许静默。**
 *
 * <h2>为什么需要它</h2>
 * 前端通信链路上的异常如果没人接住，后果是"功能悄悄失效"——
 * 2026-10-31 就踩过：一个 {@code ArrayNode.asString()} 抛在帧处理里，
 * 被上层 catch 成 {@code debug}（而 root level 是 INFO），于是日志**一片安静**，
 * 现象是"她完全不动"，排查了两轮才找到。
 *
 * <p>开发期的原则是：**宁可满屏报错，也不要一点动静都没有。**
 *
 * <h2>用 ERROR 还是 CRTL</h2>
 * 按项目既有语义（见 {@link Critical}）：
 * <ul>
 *   <li><b>CRTL</b>（{@code error + Critical.CRITICAL}）—— 重试也不会好的故障，通常是**代码 bug**。
 *       兜底接住的 {@code Throwable} 正属此类：它是"程序员没预料到的情况"，不是网络抖动。</li>
 *   <li><b>ERROR</b> —— 可恢复的失败（上游 502、连接断了、一条帧格式不对）。</li>
 * </ul>
 * 所以这里用 CRTL，并且在消息里明确写"已隔离、继续运行"，避免读日志的人误以为进程要挂。
 *
 * <h2>为什么不用 try/catch 直接写</h2>
 * 可以，但调用点会多出五行样板；而且"忘了加"是常态。集中成一个工具，
 * 至少让人在 review 时能看见"这里做了兜底"。
 */
public final class FatalGuard {

    private static final Logger log = LoggerFactory.getLogger(FatalGuard.class);

    private FatalGuard() {
    }

    /**
     * 执行一段会碰前端通信的逻辑；任何 {@code Throwable} 都被隔离并记成 CRTL。
     *
     * @param where 出问题时的定位标识（写清"哪个入口、处理什么"）
     * @param body  正文
     * @return 是否正常完成（{@code false} 表示被兜底接住了）
     */
    public static boolean run(String where, Runnable body) {
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            log.error(Critical.CRITICAL,
                    "{} 抛出未处理异常（已隔离，本次数据丢弃，连接/会话继续）", where, t);
            return false;
        }
    }

    /**
     * 同上，但有返回值。
     *
     * @param fallback 兜底时返回什么（通常是 {@code null} / {@code false} / 空列表）
     */
    public static <T> T call(String where, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Throwable t) {
            log.error(Critical.CRITICAL,
                    "{} 抛出未处理异常（已隔离，本次数据丢弃，连接/会话继续）", where, t);
            return fallback;
        }
    }
}
