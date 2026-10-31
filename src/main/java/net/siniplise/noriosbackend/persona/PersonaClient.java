package net.siniplise.noriosbackend.persona;

import reactor.core.publisher.Mono;

/**
 * 人格服务的薄客户端。
 *
 * <p>刻意只做"发得出去、错误可分辨"，不做重试与缓存：
 * 记忆写入是**旁路**——失败不该影响台词上屏（调用方必须 `onErrorResume` 掉）。
 * 在这里加自动重试反而危险：重试成功会写两张卡，失败会拖长会话线程的等待。
 */
public interface PersonaClient {

    /** `POST /nori-scriptline`。 */
    Mono<Void> writeScriptLine(ScriptLineWrite write);

    /** `POST /game-note`：只记不答（换局截断时通报一句）。 */
    Mono<Void> gameNote(String game, int dropped);

    /** `GET /state`：读人格状态（排障/观测用）。 */
    Mono<String> state();
}
