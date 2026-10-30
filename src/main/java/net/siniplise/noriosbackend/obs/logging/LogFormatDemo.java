package net.siniplise.noriosbackend.obs.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志格式自检：把五种级别各打一条，外加一条带堆栈的异常。
 *
 * <p>用途只有一个——换机器、改 logback.xml、升级 logback 之后，跑一次就能肉眼确认
 * 「四字符级别 + 上一级包.类名:行号 + 堆栈」没被破坏。等 gateway 落地、有真实日志可看之后可以删。
 *
 * <p>为什么放在 obs 里而不是 app 里：它要临时改 root 级别（否则 DEBG 被过滤，演示不完整），
 * 而 logback 的 API 只应该在观测层出现，业务包只见 slf4j。
 */
public final class LogFormatDemo {

    private static final Logger log = LoggerFactory.getLogger(LogFormatDemo.class);

    public static void run() {
        LoggerContext context = loggerContext();
        Level original = rootLevel(context);
        try {
            setRootLevel(context, Level.DEBUG);
            log.debug("DEBG 示例：调试细节（临时把 root 调到 DEBUG 才看得见）");
        } finally {
            setRootLevel(context, original);
        }

        log.info("INFO 示例：正常链路");
        log.warn("WARN 示例：可恢复，比如 ack 丢了走 9s 兜底");
        log.error("EROR 示例：需要人看，下面是随附的 Exception 与全量堆栈",
                new IllegalStateException("模拟 TTS 上游返回 502"));
        log.error(Critical.CRITICAL, "CRTL 示例：必须停机处理（存档写坏 / 记忆库损坏 / 密钥缺失这类）");

        log.info("级别集合：TRCE / DEBG / INFO / WARN / EROR / CRTL —— 固定四字符");
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static ch.qos.logback.classic.Logger root(LoggerContext context) {
        return context.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    private static Level rootLevel(LoggerContext context) {
        return root(context).getLevel();
    }

    private static void setRootLevel(LoggerContext context, Level level) {
        root(context).setLevel(level);
    }

    private LogFormatDemo() {
        throw new AssertionError("no instances");
    }
}
