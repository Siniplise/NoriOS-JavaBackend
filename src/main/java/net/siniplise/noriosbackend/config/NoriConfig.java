package net.siniplise.noriosbackend.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 全部可配置项的类型化视图。
 *
 * <p>字段与 {@code resources/config/nori-backend.json} 一一对应；JSON 是事实来源，
 * 这里是「代码能编译期发现拼错」的那一层。加字段的顺序固定：先改 JSON 默认值，
 * 再改这个 record，最后在 {@link ConfigLoader} 的环境变量映射表里登记（如果需要 env 覆盖）。
 *
 * <p>注意 {@link Secrets}：这里只存<b>环境变量名</b>，不存值。
 * JSON 会被未来的管理器读出来展示/编辑，任何真 key 出现在 JSON 里都视为泄漏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoriConfig(
        Ports ports,
        Upstreams upstreams,
        Secrets secrets,
        Llm llm,
        Tts tts,
        Script script,
        Speech speech,
        Touch touch,
        Memory memory,
        Save save,
        Obs obs) {

    /** 本机监听端口：game = 反代入口（4183，避开旧 Node 的 4173），admin = 管理器。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ports(int game, int admin) {
    }

    /** 上游地址：游戏本体 / 旧 Node 反代（只读对照）/ TTS / 人格。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Upstreams(String gameExe, String legacyProxy, String tts, String persona) {
    }

    /** 密钥只存环境变量名，永不存值。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Secrets(String deepseekKeyEnv, String inworldKeyEnv) {
    }

    /** LLM 出口：baseUrl / model / 超时 / 是否接管 /v1/*（对应 NORI_LLM_ROUTE）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Llm(String baseUrl, String model, int timeoutMs, boolean routeEnabled) {
    }

    /** TTS：并发闸门、句间留白、预取开关、默认音色。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tts(int parallel, int gapMs, boolean prefetch, String voice) {
    }

    /** 台词表与状态文件；resetOnNewRun 对应 NORI_RUN_RESET。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Script(String linesFile, String stateFile, boolean resetOnNewRun) {
    }

    /** 语音队列：9s 兜底、内容级去重窗口。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Speech(int fallbackMs, int dedupeMs) {
    }

    /** 摸头闸门总开关 / 是否要求 pat-surface。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Touch(boolean gateEnabled, boolean requirePatSurface) {
    }

    /** 记忆：sqlite 起步，pgvector 后续；recallTopK = 一次召回取几张卡。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Memory(String store, String path, int recallTopK) {
    }

    /** 存档：目录、自动存档、槽位上限。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Save(String dir, boolean autoSave, int maxSlots) {
    }

    /** 观测：日志目录、是否 JSON 结构化输出。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Obs(String logDir, boolean jsonLogs) {
    }
}
