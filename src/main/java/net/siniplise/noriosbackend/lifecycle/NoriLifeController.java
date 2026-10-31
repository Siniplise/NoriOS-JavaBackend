package net.siniplise.noriosbackend.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.siniplise.noriosbackend.app.SessionRuntime;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * `/nori-lifestat` 与 `/nori-touch`：生命周期快照与摸头。
 *
 * <p>两者都读同一份会话状态，所以都走 {@link SessionRuntime}（会话线程）。
 *
 * <p>摸头的语义按 HANDOFF §3.5：先过 `petGate` 四类闸门；
 * 被拦时返回 `{ok:true, fired:false, blocked:<原因>}` 且**不写 fact、不上屏、不给表情**——
 * 注意 `ok` 仍是 true：这是"闸门拦住了"，不是"请求失败"。
 */
@RestController
public class NoriLifeController {

    private final SessionRuntime session;

    public NoriLifeController(SessionRuntime session) {
        this.session = session;
    }

    /** 生命周期快照。字段名对齐旧栈，方便新旧对照。 */
    @GetMapping(NoriPaths.LIFESTAT)
    public Mono<Map<String, Object>> lifeStat() {
        return Mono.fromFuture(session.snapshot()).map(snap -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("on", !"idle".equals(snap.phase()));
            body.put("phase", snap.phase());
            body.put("cart", snap.cartridge());
            body.put("ended", snap.ended());
            body.put("seq", snap.seq());
            body.put("petGate", snap.petGate());
            body.put("facts", snap.facts().size());
            return body;
        });
    }

    /** 摸头：`{part:'head'|'tail'}`。 */
    @PostMapping(NoriPaths.TOUCH)
    public Mono<Map<String, Object>> touch(@RequestBody(required = false) TouchRequest request) {
        String part = request == null || request.part() == null ? "head" : request.part();
        // 注意：Mono.fromFuture 遇到 null 会变成"空完成"，而不是发出 null。
        // petGate() 用 null 表示"放行"，直接透传会让整个响应变成 200 但无 body（实测踩过）。
        return Mono.fromFuture(session.petGate().thenApply(Optional::ofNullable)).flatMap(gate -> {
            String blocked = gate.orElse(null);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("part", part);
            if (blocked != null) {
                body.put("fired", false);
                body.put("blocked", blocked);
                return Mono.just(body);
            }
            // 放行：跑台词引擎（fact → beat → 入队），台词由客户端轮询 /nori-line 取走
            return Mono.fromFuture(session.onFact("touch." + part)).map(queued -> {
                body.put("fired", true);
                body.put("fact", "touch." + part);
                body.put("emotion", "smile");
                body.put("queued", queued);
                return body;
            });
        });
    }

    /** `{part}`；缺省按 head 处理（前端老版本可能不带体）。 */
    public record TouchRequest(String part) {
    }
}
