package net.siniplise.noriosbackend.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 配置加载：默认值（classpath） → 外部 JSON 覆盖 → 环境变量覆盖 → 校验。
 *
 * <h2>三层优先级（低 → 高）</h2>
 * <ol>
 *   <li>{@code src/main/resources/config/nori-backend.json}：出厂默认值，随包发。</li>
 *   <li>{@code ./config/nori-backend.json}：本机覆盖，未来的管理器就改这个文件。</li>
 *   <li>环境变量：容器/临时排障用，只覆盖少数关键项（见 {@link #BINDINGS}）。</li>
 * </ol>
 *
 * <h2>为什么「合并」而不是「替换」</h2>
 * 外部文件只写要改的字段即可，升级加了新字段不会因为本机文件太旧而丢默认值；
 * 同时把外部文件里出现、默认值里没有的键收集成 {@code unknownKeys}——
 * 管理器保存时拼错字段名，能立刻看见，而不是静默不生效。
 *
 * <h2>给管理器留的口子</h2>
 * {@link Loaded#effectiveJson()} 就是「当前真正生效的全部配置」的 JSON 快照：
 * 管理器读它渲染表单、写回 {@code ./config/nori-backend.json}、重启后生效。
 * 密钥永远不在此 JSON 里，只有 {@code secrets.*KeyEnv} 这样的环境变量名。
 */
public final class ConfigLoader {

    /** 本机覆盖文件（相对工作目录）。 */
    public static final String WORKING_FILE = "config/nori-backend.json";

    /** 出厂默认值（classpath）。 */
    public static final String CLASSPATH_DEFAULT = "/config/nori-backend.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // 容忍未知字段：管理器或新版本可能写进我们还不认识的键。这些键由 merge() 保留，
            // 并在 unknownKeys 里被点名——用「报告」而不是「启动即失败」来处理，比直接炸更可控。
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** 环境变量 → JSON 路径。布尔项接受 on/off/true/false/1/0（HANDOFF 的写法是 on/off）。 */
    private static final List<Binding> BINDINGS = List.of(
            new Binding("NORI_PORT", "ports.game"),
            new Binding("NORI_ADMIN_PORT", "ports.admin"),
            new Binding("NORI_LLM_UPSTREAM", "upstreams.persona"),
            new Binding("NORI_LLM_BASE_URL", "llm.baseUrl"),
            new Binding("NORI_LLM_MODEL", "llm.model"),
            new Binding("NORI_LLM_ROUTE", "llm.routeEnabled"),
            new Binding("NORI_TTS_PARALLEL", "tts.parallel"),
            new Binding("NORI_TTS_GAP_MS", "tts.gapMs"),
            new Binding("NORI_TTS_VOICE", "tts.voice"),
            new Binding("NORI_LINE_DEDUPE_MS", "speech.dedupeMs"),
            new Binding("NORI_SPEECH_FALLBACK_MS", "speech.fallbackMs"),
            new Binding("NORI_PET_GATE", "touch.gateEnabled"),
            new Binding("NORI_PET_SURFACE_GATE", "touch.requirePatSurface"),
            new Binding("NORI_RUN_RESET", "script.resetOnNewRun"),
            new Binding("NORI_MEMORY_STORE", "memory.store"));

    /**
     * 加载结果：类型化视图 + 生效中的 JSON 快照（文本与树两份）+ 拼错的键 + 来源。
     *
     * <p>为什么同时给文本和树：文本给日志/CLI 打印，树给管理端点直接返回——
     * WebFlux 里返回字符串再标 {@code application/json} 很可能被当成「字符串」序列化，
     * 出去是一坨带转义引号的内容，管理器根本没法当对象解析。
     */
    public record Loaded(
            NoriConfig config,
            String effectiveJson,
            JsonNode effectiveTree,
            List<String> unknownKeys,
            String source) {

        public boolean hasUnknownKeys() {
            return !unknownKeys.isEmpty();
        }

        /** 换一个游戏上游（不动配置文件）。影子对比与网关测试都靠它换个目标。 */
        public Loaded withGameExe(String gameExe) {
            var u = config.upstreams();
            var next = new NoriConfig(config.ports(),
                    new NoriConfig.Upstreams(gameExe, u.legacyProxy(), u.tts(), u.persona()),
                    config.secrets(), config.llm(), config.tts(), config.script(),
                    config.speech(), config.touch(), config.memory(), config.save(), config.obs());
            return new Loaded(next, effectiveJson, effectiveTree, unknownKeys, source);
        }
    }

    private record Binding(String env, String path) {
    }

    /** 按默认约定加载：工作目录的 config/ + 进程环境变量。 */
    public static Loaded load() {
        return load(Path.of(WORKING_FILE), System::getenv);
    }

    /**
     * 可注入版本（测试用）。
     *
     * @param external 本机覆盖文件，不存在则跳过
     * @param env 环境变量读取函数，测试里传 Map::get
     */
    public static Loaded load(Path external, Function<String, String> env) {
        ObjectNode effective = defaults();
        List<String> unknownKeys = new ArrayList<>();
        String source = "classpath:" + CLASSPATH_DEFAULT;

        if (external != null && Files.isRegularFile(external)) {
            String text;
            try {
                text = Files.readString(external, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读不出配置文件: " + external, e);
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(text);
            } catch (JacksonException e) {
                throw new IllegalStateException("配置文件不是合法 JSON: " + external, e);
            }
            if (!node.isObject()) {
                throw new IllegalStateException(external + " 的根节点必须是 JSON 对象");
            }
            merge(effective, (ObjectNode) node, "", unknownKeys);
            source = external.toAbsolutePath().toString();
        }

        applyEnv(effective, env);
        validate(effective);

        NoriConfig config;
        try {
            config = MAPPER.treeToValue(effective, NoriConfig.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("配置绑定失败（字段类型不匹配？）", e);
        }

        return new Loaded(config, pretty(effective), effective, List.copyOf(unknownKeys), source);
    }

    /** 出厂默认值的可变副本。 */
    public static ObjectNode defaults() {
        byte[] bytes;
        try (InputStream in = ConfigLoader.class.getResourceAsStream(CLASSPATH_DEFAULT)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少默认配置: " + CLASSPATH_DEFAULT);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读不出默认配置", e);
        }
        JsonNode node = MAPPER.readTree(bytes);
        if (!node.isObject()) {
            throw new IllegalStateException("默认配置根节点必须是 JSON 对象");
        }
        return (ObjectNode) node;
    }

    // ── 合并 ────────────────────────────────────────────────────────────────

    private static void merge(ObjectNode base, ObjectNode override, String prefix, List<String> unknownKeys) {
        // properties() 而不是 fields()：后者在 Jackson 2.2x 已标记过时
        for (Map.Entry<String, JsonNode> entry : override.properties()) {
            String key = entry.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            JsonNode incoming = entry.getValue();
            JsonNode existing = base.get(key);

            if (existing == null) {
                // 默认值里没有这个键：仍然保留（管理器可能已经写了新字段），但必须报出来
                unknownKeys.add(path);
                base.set(key, incoming.deepCopy());
                continue;
            }
            if (incoming.isObject() && existing.isObject()) {
                merge((ObjectNode) existing, (ObjectNode) incoming, path, unknownKeys);
            } else {
                base.set(key, incoming.deepCopy());
            }
        }
    }

    // ── 环境变量覆盖 ────────────────────────────────────────────────────────

    private static void applyEnv(ObjectNode effective, Function<String, String> env) {
        for (Binding binding : BINDINGS) {
            String raw = env.apply(binding.env());
            if (raw == null || raw.isEmpty()) {
                continue; // 空值不覆盖：HANDOFF 里 NORI_PET_SURFACE_GATE 空字符串就表示「关」，交给默认值
            }
            setAtPath(effective, binding, raw.trim());
        }
    }

    private static void setAtPath(ObjectNode root, Binding binding, String raw) {
        String[] segments = binding.path().split("\\.");
        ObjectNode parent = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode child = parent.get(segments[i]);
            if (!(child instanceof ObjectNode)) {
                throw new IllegalStateException(
                        "配置路径不存在: " + binding.path() + "（环境变量 " + binding.env() + "）");
            }
            parent = (ObjectNode) child;
        }
        String leaf = segments[segments.length - 1];
        JsonNode current = parent.get(leaf);
        if (current == null) {
            throw new IllegalStateException(
                    "配置字段不存在: " + binding.path() + "（环境变量 " + binding.env() + "）");
        }

        if (current instanceof IntNode) {
            try {
                parent.put(leaf, Integer.parseInt(raw));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        binding.env() + " 需要整数，实际是: " + raw, e);
            }
        } else if (current instanceof BooleanNode) {
            parent.put(leaf, parseBoolean(binding.env(), raw));
        } else if (current instanceof StringNode) {
            parent.put(leaf, normalizeUrl(binding.path(), raw));
        } else {
            throw new IllegalStateException("不支持的配置类型: " + binding.path());
        }
    }

    private static boolean parseBoolean(String env, String raw) {
        String v = raw.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "1", "on", "true", "yes", "y" -> true;
            case "0", "off", "false", "no", "n" -> false;
            default -> throw new IllegalStateException(
                    env + " 需要布尔值（on/off/true/false/1/0），实际是: " + raw);
        };
    }

    /** 上游地址允许只写 host:port（HANDOFF 的 NORI_LLM_UPSTREAM 就是这种写法），补上 scheme。 */
    private static String normalizeUrl(String path, String raw) {
        if (path.startsWith("upstreams.") && !raw.contains("://")) {
            return "http://" + raw;
        }
        return raw;
    }

    // ── 校验 ────────────────────────────────────────────────────────────────

    private static void validate(ObjectNode root) {
        checkPort(root, "ports.game");
        checkPort(root, "ports.admin");
        int game = root.at("/ports/game").asInt();
        int admin = root.at("/ports/admin").asInt();
        if (game == admin) {
            throw new IllegalStateException("ports.game 与 ports.admin 不能相同: " + game);
        }

        checkMin(root, "tts.parallel", 1);
        checkMin(root, "tts.gapMs", 0);
        checkMin(root, "speech.fallbackMs", 1_000);
        checkMin(root, "speech.dedupeMs", 0);
        checkMin(root, "memory.recallTopK", 1);
        checkMin(root, "save.maxSlots", 1);
        checkMin(root, "llm.timeoutMs", 1_000);

        String store = root.at("/memory/store").asString();
        if (!List.of("sqlite", "postgres").contains(store)) {
            throw new IllegalStateException("memory.store 只能是 sqlite 或 postgres，实际是: " + store);
        }

        // 密钥铁律的最后一道闸：JSON 里只允许出现环境变量名，出现疑似真 key 立刻拒绝启动
        for (String field : List.of("/secrets/deepseekKeyEnv", "/secrets/inworldKeyEnv")) {
            String value = root.at(field).asString("");
            if (value.startsWith("sk-") || value.length() > 60) {
                throw new IllegalStateException(
                        field + " 里存的是密钥值，不是环境变量名——立刻停手，把 key 移回环境变量");
            }
        }
    }

    private static void checkPort(ObjectNode root, String path) {
        int port = intAt(root, path);
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException(path + " 超出范围 (1-65535): " + port);
        }
    }

    private static void checkMin(ObjectNode root, String path, int min) {
        int value = intAt(root, path);
        if (value < min) {
            throw new IllegalStateException(path + " 不能小于 " + min + "，实际是: " + value);
        }
    }

    /** 严格取整数：管理器把数字字段写成字符串（"4183"）时必须当场报错，不能悄悄变成 0。 */
    private static int intAt(ObjectNode root, String path) {
        JsonNode node = root.at("/" + path.replace('.', '/'));
        if (!(node instanceof IntNode)) {
            throw new IllegalStateException(path + " 必须是整数，实际是: " + node);
        }
        return node.intValue();
    }

    private static String pretty(JsonNode node) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /** 只读视图：给管理器表格/日志用，避免外部直接改内部树。 */
    public static Map<String, Object> describe(Loaded loaded) {
        return Map.of(
                "source", loaded.source(),
                "unknownKeys", loaded.unknownKeys(),
                "gamePort", loaded.config().ports().game(),
                "adminPort", loaded.config().ports().admin());
    }

    private ConfigLoader() {
        throw new AssertionError("no instances");
    }
}
