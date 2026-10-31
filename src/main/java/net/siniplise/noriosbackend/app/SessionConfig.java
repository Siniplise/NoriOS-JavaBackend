package net.siniplise.noriosbackend.app;

import java.nio.file.Files;
import java.nio.file.Path;
import net.siniplise.noriosbackend.config.ConfigLoader;
import net.siniplise.noriosbackend.chat.ChatGate;
import net.siniplise.noriosbackend.gateway.MediaHub;
import net.siniplise.noriosbackend.script.ScriptTable;
import net.siniplise.noriosbackend.tts.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话运行时的容器装配。
 *
 * <p>当前是**单会话**（本地单用户，一个游戏窗口）。多会话要改的地方集中在这里：
 * 把单例换成 `Map<sessionId, SessionRuntime>` + 过期回收，其余代码不用动——
 * 所有业务都通过 SessionRuntime 的方法进去，没有全局可变状态。
 */
@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    /**
     * 台词表：从配置里的 {@code script.linesFile} 读。
     *
     * <p>读不到**不阻断启动**——但在日志里喊一声。理由：台词是内容，用户可能正在改；
     * 让整个后端因为一个 JSON 语法错误起不来，代价太大。空表的表现是「她不主动说话」，
     * 配合这条 WARN 足够定位。
     */
    @Bean
    public ScriptTable scriptTable(ConfigLoader.Loaded loaded) {
        Path file = Path.of(loaded.config().script().linesFile());
        if (!Files.isRegularFile(file)) {
            log.warn("script: 台词表不存在 {}，台词系统以空表运行（不会主动说话）", file.toAbsolutePath());
            return ScriptTable.empty();
        }
        try {
            return ScriptTable.load(file);
        } catch (RuntimeException e) {
            log.error("script: 台词表解析失败 {}，以空表运行: {}", file, e.toString());
            return ScriptTable.empty();
        }
    }

    @Bean
    public ChatGate chatGate(ScriptTable table) {
        return new ChatGate(table);
    }

    @Bean
    public SessionRuntime sessionRuntime(TtsClient tts, MediaHub media, ScriptTable table) {
        return new SessionRuntime("main", tts, media, table);
    }
}
