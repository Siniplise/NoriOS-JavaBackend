package net.siniplise.noriosbackend.obs.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;

/**
 * {@code %LVL4}：把级别渲染成<b>固定四个字符</b>。
 *
 * <p>Log4j2 自带的 {@code %level} / {@code %-5level} 只能补空格或截断，
 * {@code DEBUG} 出来是 {@code DEBUG} 或 {@code DEBU}，给不出本项目约定的 {@code DEBG}。
 * 所以写一个插件转换器：
 *
 * <pre>
 * TRACE → TRCE      DEBUG → DEBG      INFO  → INFO
 * WARN  → WARN      ERROR → EROR      FATAL → CRTL
 * </pre>
 *
 * <p>四个字符的好处是终端里能按列扫读，grep {@code EROR} 也不会误伤。
 * 未登记的级别一律「截前四位 + 空格补齐」，长度恒为 4，不破坏对齐。
 *
 * <h2>CRTL 有两条来源（Log4j2 比 logback 好的地方）</h2>
 * <ol>
 *   <li><b>原生 FATAL</b>：Log4j2 有 FATAL 级别，{@code log.fatal(...)} 直接就是 CRTL；</li>
 *   <li><b>ERROR + {@link Critical#CRITICAL} 标记</b>：业务代码走 slf4j API（没有 fatal 方法），
 *       用标记表达「这条要人立刻处理」。</li>
 * </ol>
 *
 * <p>插件在这里靠 {@code log4j2.xml} 的 {@code packages="net.siniplise.noriosbackend.obs.logging"}
 * 扫出来（运行时扫描），不依赖注解处理器生成 plugin cache。
 */
@Plugin(name = "NoriShortLevelConverter", category = "Converter")
@ConverterKeys({"LVL4"})
public final class ShortLevelConverter extends LogEventPatternConverter {

    private static final ShortLevelConverter INSTANCE = new ShortLevelConverter();

    private ShortLevelConverter() {
        super("LVL4", "level");
    }

    /** Log4j2 通过反射找这个工厂方法。 */
    public static ShortLevelConverter newInstance(final String[] options) {
        return INSTANCE;
    }

    /** 级别名 → 四字符写法（纯函数，方便单测）。 */
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

    /** 渲染一个事件：FATAL，或「ERROR + 严重标记」，都出 CRTL。 */
    public static String render(Level level, Marker marker) {
        boolean marked = marker != null && Critical.MARKER_NAME.equals(marker.getName());
        if (marked && level == Level.ERROR) {
            return "CRTL";
        }
        return abbreviate(level.name());
    }

    private static String pad(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.length() >= 4) {
            return upper.substring(0, 4);
        }
        return (upper + "    ").substring(0, 4);
    }

    @Override
    public void format(LogEvent event, StringBuilder toAppendTo) {
        toAppendTo.append(render(event.getLevel(), event.getMarker()));
    }
}
