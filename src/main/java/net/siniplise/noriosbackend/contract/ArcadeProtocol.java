package net.siniplise.noriosbackend.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Arcade 通道的 JSON 消息契约（来源：上游 {@code docs/VERIFIED_PROTOCOL.md}，逐条对过线上客户端）。
 *
 * <p>这份清单的用途是**别让服务端发出客户端会静默丢弃的消息**。上游客户端用 Zod {@code .strict()}
 * 解析，缺字段不发错、直接丢包：客户端表现成「发了指令没反应」或「心跳没回应」，排查代价极高。
 *
 * <p>因此这里分两层：
 * <ul>
 *   <li>{@link #CLIENT_TO_SERVER} / {@link #SERVER_TO_CLIENT}：类型白名单，用来快速判断「这条认不认识」；</li>
 *   <li>{@link #missingRequired}：只在文档明确写死的字段上做校验，不猜、不过度约束。</li>
 * </ul>
 */
public final class ArcadeProtocol {

    /** 客户端发给我们的类型（主通道）。{@code open_media} 属于 media 通道。 */
    public static final Set<String> CLIENT_TO_SERVER = Set.of(
            "open_my_web_world",
            "reset_my_web_world",
            "join_world",
            "leave_world",
            "mount_cartridge",
            "unmount_cartridge",
            "dispatch",
            "advance_visibility_fence",
            "ping",
            "event");

    /** media 通道建连后的第一条消息。 */
    public static final String OPEN_MEDIA = "open_media";

    /** 我们发回客户端的类型。 */
    public static final Set<String> SERVER_TO_CLIENT = Set.of(
            "world_joined",
            "world_created",
            "world_left",
            "web_world_reset_ack",
            "runtime_transition",
            "cartridge_mounted",
            "cartridge_mounted_ack",
            "cartridge_unmounted",
            "cartridge_unmounted_ack",
            "visibility_fence_advanced",
            "visibility_fence_advanced_ack",
            "dispatch_ack",
            "event",
            "error",
            "pong");

    /**
     * 文档明确给出字段的消息 → 必填字段。
     *
     * <p>刻意只收「文档里写死了 JSON 结构」的那几个。像 {@code error}、{@code world_left}
     * 文档只说"validated"，没给字段，就不猜——过度校验会把本来能用的消息判死。
     */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            // "serverId is required; now is optional. The field is now, never timestamp."
            "pong", List.of("serverId"),
            "world_joined", List.of("world", "session"),
            "web_world_reset_ack", List.of("worldId"),
            "runtime_transition", List.of("worldId", "cartridgeId", "version", "transition"),
            "cartridge_mounted_ack", List.of("transition", "runtimes"),
            "dispatch_ack", List.of("worldId", "cartridgeId", "requestId", "success",
                    "committed", "committedVersion", "headVersion", "result"));

    /** 消息类型；没有 type 字段返回空串。 */
    public static String typeOf(JsonNode message) {
        if (message == null || !message.isObject()) {
            return "";
        }
        JsonNode type = message.get("type");
        return type == null || !type.isString() ? "" : type.asString();
    }

    /** 是不是我们认识的「服务端→客户端」类型。 */
    public static boolean isKnownServerType(String type) {
        return SERVER_TO_CLIENT.contains(type);
    }

    /**
     * 检查一条待发消息缺哪些必填字段。
     *
     * @return 缺失字段名；空列表表示通过（也包含「该类型没有硬性规则」的情况）
     */
    public static List<String> missingRequired(JsonNode message) {
        List<String> missing = new ArrayList<>();
        List<String> required = REQUIRED.get(typeOf(message));
        if (required == null) {
            return missing;
        }
        for (String field : required) {
            JsonNode value = message.get(field);
            // 上游 strict 解析对 null 也算缺；这里同口径
            if (value == null || value.isNull()) {
                missing.add(field);
            }
        }
        return missing;
    }

    /**
     * 发送前的断言：缺字段直接抛，别让它悄悄发出去。
     *
     * <p>用异常而不是日志，是因为这种错一律是编码 bug，越早炸越好——发出去只会变成
     * 一个"客户端没反应"的悬案。
     */
    public static void requireWellFormed(JsonNode message) {
        List<String> missing = missingRequired(message);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "消息 " + typeOf(message) + " 缺少必填字段 " + missing + "（客户端会静默丢弃）");
        }
    }

    /** pong 的时间字段名固定是 {@code now}，写 {@code timestamp} 客户端认不出来。 */
    public static final String PONG_TIME_FIELD = "now";

    private ArcadeProtocol() {
        throw new AssertionError("no instances");
    }
}
