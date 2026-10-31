package net.siniplise.noriosbackend.app;

import net.siniplise.noriosbackend.gateway.MediaHub;
import net.siniplise.noriosbackend.tts.TtsClient;
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

    @Bean
    public SessionRuntime sessionRuntime(TtsClient tts, MediaHub media) {
        return new SessionRuntime("main", tts, media);
    }
}
