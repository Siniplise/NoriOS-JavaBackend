package net.siniplise.noriosbackend.game.driver;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 每个卡带的 {@code headVersion} 账本。
 *
 * <p><b>为什么必须自己记</b>：上游的 {@code dispatch} 带 {@code expectedHeadVersion}，
 * 版本不对时它会**静默丢弃**——不报错、不回 ack、什么都不发生。
 * 我们替她发动作时如果版本是陈的，她的棋就走了个寂寞，而日志里看不出任何异常。
 *
 * <p><b>两个来源都要吃</b>：
 * <ul>
 *   <li>{@code runtime_transition.version} —— 上游每次推进都会广播当前版本；</li>
 *   <li>{@code dispatch_ack.headVersion} —— 我们（或玩家）发命令后上游的确认。</li>
 * </ul>
 * 只吃一个都会漏：只吃 transition 会在"刚发完命令、新 transition 还没来"的空档里落后；
 * 只吃 ack 则收不到玩家操作推进的版本。
 *
 * <p>取**最大值**，不回退：广播副本乱序到达时，旧版本不能把 head 拉回去
 * （JS 侧也踩过这个：head 回退到上局末尾版本，导致所有指令被拒）。
 */
public final class GameHeads {

    private final Map<String, Integer> heads = new LinkedHashMap<>();

    /** 从一条上游消息里更新（只认 {@code runtime_transition} 和 {@code dispatch_ack}）。 */
    public void observe(JsonNode msg) {
        if (msg == null || !msg.isObject()) {
            return;
        }
        String type = text(msg, "type");
        String cart = text(msg, "cartridgeId");
        if (cart == null || cart.isBlank()) {
            return;
        }
        Integer version = null;
        if ("runtime_transition".equals(type)) {
            version = intOrNull(msg, "version");
            // 有的实现把 version 放在 transition 里面
            if (version == null) {
                JsonNode tr = msg.get("transition");
                version = (tr != null && tr.isObject()) ? intOrNull(tr, "version") : null;
            }
        } else if ("dispatch_ack".equals(type)) {
            version = intOrNull(msg, "headVersion");
        }
        if (version != null) {
            bump(cart, version);
        }
    }

    /** 只升不降。 */
    public void bump(String cartridgeId, int version) {
        if (cartridgeId == null) {
            return;
        }
        heads.merge(cartridgeId, version, Math::max);
    }

    /** 当前 head；没见过就返回 {@code null}（调用方应当放弃发命令，而不是猜一个 0）。 */
    public Integer head(String cartridgeId) {
        return heads.get(cartridgeId);
    }

    /** 新局时清掉（版本会从头开始，留着旧值会让新局所有指令被拒）。 */
    public void reset(String cartridgeId) {
        if (cartridgeId != null) {
            heads.remove(cartridgeId);
        }
    }

    public void clear() {
        heads.clear();
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v.asString();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }
}
