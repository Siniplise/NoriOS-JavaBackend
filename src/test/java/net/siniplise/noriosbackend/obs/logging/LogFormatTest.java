package net.siniplise.noriosbackend.obs.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志格式的两条硬约定：级别恒四字符、logger 只留「上一级包 + 类名」。
 *
 * <p>这两件事一旦漂移，日志就没法按列扫读，也没法 grep 出 {@code EROR}——所以用断言钉住。
 * 这套断言在「logback → Log4j2」换框架时直接暴露了差异（%logger{2} 的语义就靠它验证）。
 */
class LogFormatTest {

    @Test
    @DisplayName("级别一律四字符：DEBG / EROR / CRTL 而不是 DEBU / ERRO")
    void levelsAreAlwaysFourChars() {
        assertThat(ShortLevelConverter.abbreviate("TRACE")).isEqualTo("TRCE");
        assertThat(ShortLevelConverter.abbreviate("DEBUG")).isEqualTo("DEBG");
        assertThat(ShortLevelConverter.abbreviate("INFO")).isEqualTo("INFO");
        assertThat(ShortLevelConverter.abbreviate("WARN")).isEqualTo("WARN");
        assertThat(ShortLevelConverter.abbreviate("ERROR")).isEqualTo("EROR");
        assertThat(ShortLevelConverter.abbreviate("FATAL")).isEqualTo("CRTL");

        // 长度不变量：含未知级别与 null，都必须正好 4 个字符
        for (String name : List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL",
                "CRITICAL", "X", "SOMETHING")) {
            assertThat(ShortLevelConverter.abbreviate(name)).hasSize(4);
        }
        assertThat(ShortLevelConverter.abbreviate(null)).hasSize(4);
    }

    @Test
    @DisplayName("原生 FATAL 与「ERROR + 严重标记」都渲染成 CRTL，普通 ERROR 仍是 EROR")
    void criticalHasTwoSources() {
        Marker critical = MarkerManager.getMarker(Critical.MARKER_NAME);
        Marker other = MarkerManager.getMarker("SOMETHING_ELSE");

        assertThat(ShortLevelConverter.render(Level.FATAL, null)).isEqualTo("CRTL");
        assertThat(ShortLevelConverter.render(Level.ERROR, critical)).isEqualTo("CRTL");
        assertThat(ShortLevelConverter.render(Level.ERROR, null)).isEqualTo("EROR");
        assertThat(ShortLevelConverter.render(Level.ERROR, other))
                .as("别的标记不能把错误抬成 CRTL")
                .isEqualTo("EROR");
        assertThat(ShortLevelConverter.render(Level.WARN, critical))
                .as("标记只对 ERROR 生效")
                .isEqualTo("WARN");
    }

    @Test
    @DisplayName("插件渲染进 pattern：%LVL4 输出四字符，%logger{2} 只留上一级包")
    void patternRendersAsConfigured() {
        PatternLayout layout = PatternLayout.newBuilder()
                .setPattern("%LVL4|%logger{2}|%msg")
                .build();

        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("net.siniplise.noriosbackend.runtime.SessionLoop")
                .setLevel(Level.ERROR)
                .setMarker(MarkerManager.getMarker(Critical.MARKER_NAME))
                .setMessage(new SimpleMessage("任务失败"))
                .build();

        assertThat(layout.toSerializable(event)).isEqualTo("CRTL|runtime.SessionLoop|任务失败");
    }
}
