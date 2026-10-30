package net.siniplise.noriosbackend.obs.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 把日志级别压成<b>固定四个字符</b>，供 logback pattern 里的 {@code %lvl4} 使用。
 *
 * <p>为什么不用现成的 {@code %.4level}：它只会从左边截断，DEBUG 变 {@code DEBU}、
 * ERROR 变 {@code ERRO}，既不好认也不符合本项目约定。这里的映射是：
 *
 * <pre>
 * TRACE → TRCE      DEBUG → DEBG      INFO  → INFO
 * WARN  → WARN      ERROR → EROR      FATAL → CRTL
 * </pre>
 *
 * <p>四个字符的好处是终端里列能对齐，扫一眼就能抓到 EROR 那一行。
 * 未登记的级别一律「取前四位 + 空格补齐」，保证长度仍是 4，绝不会把对齐搞乱。
 */
public class ShortLevelConverter extends ClassicConverter {

    /** 严重标记的名字，与 {@link Critical#CRITICAL} 必须一致。 */
    public static final String CRITICAL_MARKER_NAME = "NORI_CRITICAL";

    /** 日志级别在终端里的四字符写法。 */
    public static String abbreviate(String levelName) {
        if (levelName == null || levelName.isEmpty()) {
            return "????";
        }
        return switch (levelName.toUpperCase(java.util.Locale.ROOT)) {
            case "TRACE" -> "TRCE";
            case "DEBUG" -> "DEBG";
            case "INFO" -> "INFO";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR" -> "EROR";
            case "FATAL", "CRITICAL" -> "CRTL";
            default -> pad(levelName);
        };
    }

    private static String pad(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.length() >= 4) {
            return upper.substring(0, 4);
        }
        return (upper + "    ").substring(0, 4);
    }

    @Override
    public String convert(ILoggingEvent event) {
        // ERROR + NORI_CRITICAL 标记 ⇒ CRTL。logback 没有 FATAL，这是 CRTL 的唯一来源。
        if (event.getLevel() == ch.qos.logback.classic.Level.ERROR && isCritical(event)) {
            return "CRTL";
        }
        return abbreviate(event.getLevel().toString());
    }

    private static boolean isCritical(ILoggingEvent event) {
        // 用 getMarkerList()：logback 1.5 起 getMarker() 与 Marker.contains(String) 都已过时
        var markers = event.getMarkerList();
        if (markers == null) {
            return false;
        }
        for (var marker : markers) {
            if (CRITICAL_MARKER_NAME.equals(marker.getName())) {
                return true;
            }
        }
        return false;
    }
}
