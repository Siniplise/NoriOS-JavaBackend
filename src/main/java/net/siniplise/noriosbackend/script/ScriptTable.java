package net.siniplise.noriosbackend.script;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 台词表：从 `nori_script_lines.json` 读出来，按 fact 建索引。
 *
 * <p>刻意**不**把表打进 classpath：台词是**内容**，用户随时会改（那份 JSON 是唯一真源）。
 * 打进 jar 会让"改了台词没生效"变成一个要靠重新打包才能解决的谜。
 *
 * <p>解析只读必要字段，`_doc` / `_meta` / `label` 里的说明性文本一律忽略——
 * 那些是给人看的，不是契约。
 */
public final class ScriptTable {

    private static final Logger log = LoggerFactory.getLogger(ScriptTable.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final List<ScriptBeat> beats;
    private final Map<String, List<ScriptBeat>> byFact;

    private ScriptTable(List<ScriptBeat> beats) {
        this.beats = List.copyOf(beats);
        Map<String, List<ScriptBeat>> index = new LinkedHashMap<>();
        for (ScriptBeat beat : this.beats) {
            for (String fact : beat.when()) {
                index.computeIfAbsent(fact, k -> new ArrayList<>()).add(beat);
            }
        }
        this.byFact = Map.copyOf(index);
        log.info("script: 载入 {} 个 beat，覆盖 {} 个 fact", this.beats.size(), this.byFact.size());
    }

    public static ScriptTable empty() {
        return new ScriptTable(List.of());
    }

    /** 从文件读（不存在就抛——静默空表会让"她不说话"变成哑谜）。 */
    public static ScriptTable load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalStateException("台词表不存在: " + file);
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读不出台词表: " + file, e);
        }
    }

    /** 从 classpath 读（测试与打包内置用）。 */
    public static ScriptTable loadFromClasspath(String resource) {
        try (InputStream in = ScriptTable.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少台词表: " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读不出 classpath 台词表: " + resource, e);
        }
    }

    /** 解析 JSON 文本。 */
    public static ScriptTable parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        JsonNode beatsNode = root.isArray() ? root : root.get("beats");
        if (beatsNode == null || !beatsNode.isArray()) {
            throw new IllegalStateException("台词表缺少 beats 数组");
        }
        List<ScriptBeat> parsed = new ArrayList<>();
        for (JsonNode node : beatsNode) {
            parsed.add(toBeat(node));
        }
        return new ScriptTable(parsed);
    }

    private static ScriptBeat toBeat(JsonNode node) {
        JsonNode after = node.get("after");
        String sentinel = after == null ? null : textOrNull(after.get("sentinel"));
        int delayMs = after == null || after.get("delayMs") == null ? 0 : after.get("delayMs").asInt(0);

        List<String> lines = new ArrayList<>();
        JsonNode linesNode = node.get("lines");
        if (linesNode != null && linesNode.isArray()) {
            for (JsonNode line : linesNode) {
                if (line.isString() && !line.asString().isBlank()) {
                    lines.add(line.asString());
                }
            }
        }

        List<String> when = new ArrayList<>();
        JsonNode whenNode = node.get("when");
        if (whenNode != null && whenNode.isArray()) {
            for (JsonNode w : whenNode) {
                if (w.isString()) {
                    when.add(w.asString());
                }
            }
        }

        JsonNode pickNode = node.get("pick");
        Integer pick = pickNode == null || pickNode.isNull() ? null : pickNode.asInt();

        return new ScriptBeat(
                textOrNull(node.get("id")),
                textOrNull(node.get("label")),
                node.get("act") == null ? 0 : node.get("act").asInt(0),
                when,
                textOrNull(node.get("emotion")),
                node.get("lineGapMs") == null ? 0 : node.get("lineGapMs").asInt(0),
                lines,
                node.get("repeatable") != null && node.get("repeatable").asBoolean(false),
                pick,
                textOrNull(node.get("memory")),
                node.get("memoryImportance") == null ? 0.8 : node.get("memoryImportance").asDouble(0.8),
                sentinel,
                delayMs);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isString() ? null : node.asString();
    }

    /** 全部 beat（只读）。 */
    public List<ScriptBeat> beats() {
        return beats;
    }

    /** 某个 fact 可能触发的 beat（按表内顺序）。 */
    public List<ScriptBeat> forFact(String fact) {
        return byFact.getOrDefault(fact, List.of());
    }

    /** 按 id 找（测试与排障用）。 */
    public ScriptBeat byId(String id) {
        return beats.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }
}
