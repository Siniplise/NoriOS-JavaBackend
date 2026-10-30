package net.siniplise.noriosbackend.obs.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志格式的两条硬约定：级别恒为四字符、logger 只留「上一级包 + 类名」。
 *
 * <p>这两件事一旦漂移，日志就没法按列扫读，也没法直接 grep 出 `EROR`——所以用断言钉住。
 */
class LogFormatTest {

    private static final String FQCN = LogFormatTest.class.getName();

    /** 手工造的 LoggingEvent 需要一个 LoggerContext；Marker 方案不再依赖 MDC adapter。 */
    private static LoggerContext newContext() {
        return new LoggerContext();
    }

    @Test
    @DisplayName("级别一律四字符：DEBG / EROR / CRTL 而不是 DEBU / ERRO")
    void levelsAreAlwaysFourChars() {
        assertThat(ShortLevelConverter.abbreviate("TRACE")).isEqualTo("TRCE");
        assertThat(ShortLevelConverter.abbreviate("DEBUG")).isEqualTo("DEBG");
        assertThat(ShortLevelConverter.abbreviate("INFO")).isEqualTo("INFO");
        assertThat(ShortLevelConverter.abbreviate("WARN")).isEqualTo("WARN");
        assertThat(ShortLevelConverter.abbreviate("ERROR")).isEqualTo("EROR");
        assertThat(ShortLevelConverter.abbreviate("FATAL")).isEqualTo("CRTL");

        // 长度不变量：不管是哪个级别（含未知级别、null），都必须正好 4 个字符
        for (String name : List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "CRITICAL", "X", "SOMETHING")) {
            assertThat(ShortLevelConverter.abbreviate(name)).hasSize(4);
        }
        assertThat(ShortLevelConverter.abbreviate(null)).hasSize(4);
    }

    @Test
    @DisplayName("logger 只留上一级包 + 类名，长包根被吃掉")
    void loggerKeepsOnlyParentPackage() {
        assertThat(ShortLoggerConverter.shorten("net.siniplise.noriosbackend.runtime.SessionLoop"))
                .isEqualTo("runtime.SessionLoop");
        assertThat(ShortLoggerConverter.shorten("net.siniplise.noriosbackend.app.NoriBackendApplication"))
                .isEqualTo("app.NoriBackendApplication");
        assertThat(ShortLoggerConverter.shorten("net.siniplise.noriosbackend.config.ConfigLoader"))
                .isEqualTo("config.ConfigLoader");
        // 更深的包也只取最后一级
        assertThat(ShortLoggerConverter.shorten("net.siniplise.noriosbackend.obs.logging.ShortLevelConverter"))
                .isEqualTo("logging.ShortLevelConverter");
        // 边界：没有包名 / 空
        assertThat(ShortLoggerConverter.shorten("ROOT")).isEqualTo("ROOT");
        assertThat(ShortLoggerConverter.shorten("")).isEmpty();
        assertThat(ShortLoggerConverter.shorten(null)).isEmpty();
    }

    @Test
    @DisplayName("转换器接进 logback 事件后输出的就是四字符级别")
    void convertersWorkOnRealEvents() {
        LoggerContext context = newContext();
        var logger = context.getLogger("net.siniplise.noriosbackend.runtime.SessionLoop");

        ShortLevelConverter levelConverter = new ShortLevelConverter();
        levelConverter.setContext(context);
        levelConverter.start();

        ShortLoggerConverter loggerConverter = new ShortLoggerConverter();
        loggerConverter.setContext(context);
        loggerConverter.start();

        LoggingEvent event = new LoggingEvent(
                FQCN, logger, Level.ERROR, "模拟失败", new IllegalStateException("boom"), null);

        assertThat(levelConverter.convert(event)).isEqualTo("EROR");
        assertThat(loggerConverter.convert(event)).isEqualTo("runtime.SessionLoop");
    }

    @Test
    @DisplayName("ERROR + NORI_CRITICAL 标记渲染成 CRTL；普通 ERROR 仍是 EROR")
    void criticalMarkerPromotesErrorToCrtl() {
        LoggerContext context = newContext();
        var logger = context.getLogger("net.siniplise.noriosbackend.save.SaveManager");

        ShortLevelConverter converter = new ShortLevelConverter();
        converter.setContext(context);
        converter.start();

        LoggingEvent critical = new LoggingEvent(FQCN, logger, Level.ERROR,
                "存档写坏", new IllegalStateException("checksum mismatch"), null);
        critical.addMarker(org.slf4j.MarkerFactory.getMarker(Critical.CRITICAL.getName()));
        LoggingEvent plain = new LoggingEvent(FQCN, logger, Level.ERROR, "普通错误", null, null);

        assertThat(converter.convert(critical)).isEqualTo("CRTL");
        assertThat(converter.convert(plain)).isEqualTo("EROR");
    }

    @Test
    @DisplayName("Marker 名字与转换器里的常量不能各写各的")
    void markerNameIsShared() {
        assertThat(Critical.CRITICAL.getName()).isEqualTo(ShortLevelConverter.CRITICAL_MARKER_NAME);
    }
}
