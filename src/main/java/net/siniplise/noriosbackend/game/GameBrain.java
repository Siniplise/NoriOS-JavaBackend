package net.siniplise.noriosbackend.game;

import reactor.core.publisher.Mono;

/**
 * 游戏大脑：把局面交给 25503 的 `/game-turn`，拿回她要做的事。
 *
 * <p><b>为什么接口层就返回 {@link Mono} 而不是阻塞值</b>：
 * 这个调用要等 LLM 想 1~8 秒。而调用它的地方是每会话唯一的 {@code SessionLoop}（串行）。
 * 一旦在 loop 里同步等，**整条会话（含台词上屏、音频推帧）都会僵住这么久**。
 * 所以这里只发起、不等待；拿到结果后由调用方回到 loop 里应用
 * （{@code reply -> session.onLoop(() -> apply(reply))}）。
 *
 * <p>这与 `nori_game.js` 的 async/await 语义一一对应：
 * JS 那边 `await askBrain(...)` 之所以不卡，是因为它跑在事件循环上、不是线程。
 * Java 这边必须显式用异步表达同一件事。
 */
public interface GameBrain {

    /**
     * 问一次。
     *
     * <p>失败语义：上游返回 {@code ok=false}、超时、网络错误、JSON 解析不了，
     * **一律 {@code Mono.error}**，不返回空结果。理由见 {@link GameTurnReply} 的类注释——
     * "没有动作"和"大脑宕了"必须能被调用方区分开，否则会照着空动作把局面卡死。
     *
     * @return 回复；绝不返回 {@code null}
     */
    Mono<GameTurnReply> turn(GameTurnRequest request);
}
