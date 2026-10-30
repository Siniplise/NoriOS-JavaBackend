package net.siniplise.noriosbackend.obs.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志格式自检：五种级别各打一条，外加一条带堆栈的异常。
 *
 * <p>用途只有一个——换机器、改 log4j2.xml、升级 Log4j2 之后，跑一次就能肉眼确认
 * 「四字符级别 + 上一级包.类名:行号 + 堆栈」没被破坏。等 gateway 落地、有真实日志可看之后可以删。
 *
 * <p>为什么放在 obs 里而不是 app 里：它要临时改 root 级别（否则 DEBG 被过滤，演示不完整），
 * 而 Log4j2 的 API 只应该在观测层出现，业务包只见 slf4j。
 */
public final class LogFormatDemo {

    private static final Logger log = LoggerFactory.getLogger(LogFormatDemo.class);

    public static void run() {
        Level original = LogManager.getRootLogger().getLevel();
        try {
            Configurator.setRootLevel(Level.DEBUG);
            log.debug("DEBG 示例：调试细节（临时把 root 调到 DEBUG 才看得见）");
        } finally {
            Configurator.setRootLevel(original);
        }

        log.info("INFO 示例：正常链路");
        log.warn("WARN 示例：可恢复，比如 ack 丢了走 9s 兜底");
        log.error("EROR 示例：需要人看，下面是随附的 Exception 与全量堆栈",
                new IllegalStateException("模拟 TTS 上游返回 502"));
        log.error(Critical.CRITICAL, "CRTL 示例（ERROR + 标记）：必须停机处理");

        // Log4j2 才有的原生 FATAL：走 log4j2 API 直接就是 CRTL
        LogManager.getLogger(LogFormatDemo.class).fatal("CRTL 示例（原生 FATAL）：这条等价于上面的严重错误");

        log.info("级别集合：TRCE / DEBG / INFO / WARN / EROR / CRTL —— 固定四字符");
    }

    private LogFormatDemo() {
        throw new AssertionError("no instances");
    }
}
