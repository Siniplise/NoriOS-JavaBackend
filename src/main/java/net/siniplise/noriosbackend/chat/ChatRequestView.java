package net.siniplise.noriosbackend.chat;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 从 `/v1/chat/completions` 的请求体里抽出我们关心的四件事。
 *
 * <p>为什么只抽这四个：拦截判断只需要「最后一条用户说了什么」「是不是游戏回合」「要不要流式」。
 * 其余字段（temperature、max_tokens…）原样透传，**不解析、不重建**——重建 body 是
 * 最容易引入"看起来一样、其实少了字段"的地方（HANDOFF §2.3 的同一类坑）。
 */
public record ChatRequestView(
        String model,
        boolean stream,
        boolean jsonTurn,
        String lastUserText) {

    /** 从 JSON 请求体解析；解析不了就返回 null（调用方按"放行"处理）。 */
    public static ChatRequestView parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        String model = text(root.get("model"));
        boolean stream = root.get("stream") != null && root.get("stream").asBoolean(false);

        // ★ 游戏回合：response_format.type == "json_object" ⇒ 一律转发，绝不拦截。
        //   拦了游戏会解析失败（HANDOFF §3.2 明确列为硬约束）。
        JsonNode format = root.get("response_format");
        boolean jsonTurn = format != null && format.isObject()
                && "json_object".equals(text(format.get("type")));

        return new ChatRequestView(model, stream, jsonTurn, lastUserMessage(root));
    }

    /** 取最后一条 role=user 的文本内容；支持 content 是字符串或分段数组两种写法。 */
    static String lastUserMessage(JsonNode root) {
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) {
            return "";
        }
        String found = "";
        for (JsonNode message : messages) {
            if (!"user".equals(text(message.get("role")))) {
                continue;
            }
            String text = contentText(message.get("content"));
            if (!text.isBlank()) {
                found = text;
            }
        }
        return found;
    }

    /** content 可能是字符串，也可能是 `[{type:"text",text:"..."}]` 分段。 */
    private static String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : content) {
                String t = text(part.get("text"));
                if (t != null) {
                    parts.add(t);
                }
            }
            return String.join("\n", parts);
        }
        return "";
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isString() ? null : node.asString();
    }
}
