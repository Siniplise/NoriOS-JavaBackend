package net.siniplise.noriosbackend.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siniplise.noriosbackend.app.SessionRuntime;
import net.siniplise.noriosbackend.contract.NoriPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * `/nori-line` 与 `/nori-spoken`：台词的"取"与"播完回报"。
 *
 * <p>两个都是**破坏性/有状态**的接口，所以都投递到会话线程执行（{@link SessionRuntime}），
 * HTTP 线程只负责把结果搬回去。实测客户端每 ~706ms 轮询一次 `/nori-line`，
 * 一旦并发读串了就会出现"同一句上屏两次"或"取走后新行被覆盖"（Q3）。
 *
 * <p>`since` 参数目前只接受、不参与判断：旧栈用它做去重，但真正的破坏性语义是"取走即清空"，
 * 保留参数是为了客户端兼容。
 */
@RestController
public class NoriLineController {

    private final SessionRuntime session;

    public NoriLineController(SessionRuntime session) {
        this.session = session;
    }

    /** 响应体：`{lines:[{id,text,emotion,qid,qpart,qof}]}`。 */
    public record LinesResponse(List<LineView> lines) {
    }

    /** 客户端能用的最小字段集。 */
    public record LineView(String id, String text, String emotion, String qid, int qpart, int qof) {
    }

    @GetMapping(NoriPaths.LINE)
    public Mono<LinesResponse> line(@RequestParam(name = "since", required = false) String since) {
        return Mono.fromFuture(session.drainLines()).map(queue -> {
            List<LineView> views = new ArrayList<>();
            for (SpeechQueue.Line l : queue) {
                // id 用 qid#qpart：客户端回报时带的是 (qid,qpart)，id 只用于它的内部去重
                views.add(new LineView(l.qid() + "#" + l.qpart(), l.text(), l.emotion(),
                        l.qid(), l.qpart(), l.qof()));
            }
            return new LinesResponse(List.copyOf(views));
        });
    }

    /** 播放回报；`ok` 恒为 true，状态由服务端自己维护（客户端不该关心内部推进结果）。 */
    @PostMapping(NoriPaths.SPOKEN)
    public Mono<Map<String, Object>> spoken(@RequestBody SpokenRequest request) {
        return Mono.fromFuture(session.ackSpoken(request.qid(), request.qpart()))
                .map(outcome -> Map.<String, Object>of("ok", true, "state", outcome.name()));
    }

    /** `{qid,qpart}`。 */
    public record SpokenRequest(String qid, int qpart) {
    }
}
