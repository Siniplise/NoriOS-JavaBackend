package net.siniplise.noriosbackend.obs.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 把 logger 名压成「上一级包 + 类名」，供 logback pattern 里的 {@code %logger4} 使用。
 *
 * <pre>
 * net.siniplise.noriosbackend.runtime.SessionLoop   → runtime.SessionLoop
 * net.siniplise.noriosbackend.app.NoriBackendApplication → app.NoriBackendApplication
 * net.siniplise.noriosbackend.obs.logging.ShortLevelConverter → logging.ShortLevelConverter
 * </pre>
 *
 * <p>为什么不是 {@code %logger{0}}（只留类名）：本项目同名类不少（每个包都有
 * {@code package-info}、将来的 {@code Service}/{@code Client} 会重名），只留类名分不清是谁；
 * 而全量包名（{@code net.siniplise.noriosbackend.} 共 27 个字符）又把行宽吃掉一半。
 * 取最后一级包名是这两者之间的平衡。
 *
 * <p>行号由 pattern 里的 {@code %line} 负责；注意它会取调用栈，日志量大时有成本，
 * 本项目是单机单用户，这点开销可以忽略。
 */
public class ShortLoggerConverter extends ClassicConverter {

    public static String shorten(String loggerName) {
        if (loggerName == null || loggerName.isEmpty()) {
            return "";
        }
        int lastDot = loggerName.lastIndexOf('.');
        if (lastDot < 0) {
            return loggerName; // 无包名的 logger（比如 "ROOT"）原样返回
        }
        int prevDot = loggerName.lastIndexOf('.', lastDot - 1);
        String parentPackage = prevDot >= 0
                ? loggerName.substring(prevDot + 1, lastDot)
                : loggerName.substring(0, lastDot);
        String className = loggerName.substring(lastDot + 1);
        return parentPackage.isEmpty() ? className : parentPackage + "." + className;
    }

    @Override
    public String convert(ILoggingEvent event) {
        return shorten(event.getLoggerName());
    }
}
