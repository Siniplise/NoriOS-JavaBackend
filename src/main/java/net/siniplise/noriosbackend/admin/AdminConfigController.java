package net.siniplise.noriosbackend.admin;

import net.siniplise.noriosbackend.config.ConfigLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * 管理端：只给运维/管理器用，前端不感知（HANDOFF §2 的 8 个注入脚本不会碰这些路径）。
 *
 * <p>第一个端点是 {@code GET /api/admin/config}：返回「当前真正生效的全部配置」JSON。
 * 管理器的读写契约就在这里——读它渲染表单，写 {@code ./config/nori-backend.json}，
 * 重启后生效。密钥不在里面，只有 {@code secrets.*KeyEnv} 这样的环境变量名。
 *
 * <p>返回 {@code Mono} 而不是直接返回字符串：WebFlux 下所有响应都走响应式管道，
 * 这里暂不涉及阻塞 IO，但接口签名先摆正，免得后面接存储时又改一遍。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final ConfigLoader.Loaded loaded;

    public AdminConfigController(ObjectProvider<ConfigLoader.Loaded> configProvider) {
        // 正常情况下由 main() 注册；万一没注册（比如单测直接起容器），自己读一份兜底
        this.loaded = configProvider.getIfAvailable(ConfigLoader::load);
    }

    /** 生效中的完整配置。 */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> effectiveConfig() {
        // 返回 JSON 树而不是字符串：WebFlux 里 String 配 application/json 会被当成字符串值序列化，
        // 出去是带转义引号的一坨，管理器没法当对象读
        return Mono.just(loaded.effectiveTree());
    }

    /** 配置来源与拼错的字段名，管理器保存前可以先探一眼。 */
    @GetMapping(value = "/config/meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Meta> configMeta() {
        return Mono.just(new Meta(loaded.source(), loaded.unknownKeys()));
    }

    /** 管理端返回的元信息。 */
    public record Meta(String source, java.util.List<String> unknownKeys) {
    }
}
