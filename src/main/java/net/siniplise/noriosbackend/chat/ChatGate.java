package net.siniplise.noriosbackend.chat;

import java.util.List;
import java.util.Optional;
import net.siniplise.noriosbackend.script.ScriptBeat;
import net.siniplise.noriosbackend.script.ScriptTable;

/**
 * 决定一条聊天请求是「照常转发」还是「用固定台词答掉」。
 *
 * <p>三条来自 HANDOFF §3.2 的硬规则：
 * <ol>
 *   <li><b>游戏回合必须放行</b>：`response_format.type == "json_object"` ⇒ 无论文本多像，
 *       一律转发。拦了游戏会解析失败——这是"她突然变傻"背后最常见的原因；</li>
 *   <li><b>命中即不转发</b>：直接回固定台词，不花一次 LLM 调用；</li>
 *   <li><b>台词从台词表来</b>：不在这里硬编码字符串，避免"改台词要改代码"。</li>
 * </ol>
 *
 * <p>规则表刻意做得很窄（现在是"摸尾巴/摸后背"两种说法）。宽泛的关键词匹配会把正常聊天
 * 也吃掉——那比漏拦糟糕得多，因为玩家会觉得"她怎么只会说这一句"。
 */
public final class ChatGate {

    /** 命中结果：要答的内容。 */
    public record Hit(String beatId, String emotion, List<String> lines, String why) {
    }

    /** 触发规则：一段文本 + 一个 beatId + 说明。 */
    private record Rule(String beatId, List<String> keywords, String why) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("touch_tail", List.of("摸尾巴", "摸摸尾巴", "摸一下尾巴"), "explicit tail touch"),
            new Rule("touch_head", List.of("摸头", "摸摸头", "摸摸脑袋"), "explicit head touch"));

    private final ScriptTable table;

    public ChatGate(ScriptTable table) {
        this.table = table;
    }

    /**
     * 判断是否命中。
     *
     * @return 命中则返回该说什么；未命中返回 empty（调用方转发上游）
     */
    public Optional<Hit> match(ChatRequestView view) {
        if (view == null || view.jsonTurn()) {
            // 游戏回合：一律放行
            return Optional.empty();
        }
        String text = view.lastUserText();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (text.contains(keyword)) {
                    return hit(rule);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Hit> hit(Rule rule) {
        ScriptBeat beat = table.byId(rule.beatId());
        if (beat == null || !beat.hasLines()) {
            // 台词表里没有这个词条 ⇒ 宁可不拦：拦了却没台词，等于把玩家的话吞掉
            return Optional.empty();
        }
        return Optional.of(new Hit(beat.id(), beat.emotion(), beat.lines(), rule.why()));
    }
}
