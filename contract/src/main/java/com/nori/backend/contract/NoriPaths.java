package com.nori.backend.contract;

import java.util.List;

/**
 * 4173 暴露给前端的端点路径（HANDOFF.md §2.2 / §2.3）。
 *
 * <p>这里只放常量，不放逻辑：前端发行包（MF-Dust + 8 个注入脚本）是唯一不可变的契约，
 * 路径拼错就等于契约破坏，所以集中在编译期可见的一处声明。
 *
 * <p>注意 §2.3 的纪律：WS 文本帧可以读，但<b>不得 JSON 重序列化</b>——
 * 前端对字段顺序与未知字段敏感。
 */
public final class NoriPaths {

    // ---- WS（§2.3）----

    /** 全双工文本 JSON：dispatch / dispatch_ack / transition.cmd。 */
    public static final String WS_MAIN = "/api/arcade/web/v1";

    /** 下行二进制音频帧（结构见 §2.4 frameFor）。 */
    public static final String WS_MEDIA = "/api/arcade/web/v1/media";

    // ---- 自有业务 HTTP（§2.2）----

    /** 台词队列。<b>破坏性</b>：取走即清空。 */
    public static final String LINE = "/nori-line";

    /** 播放回报；缺失时走 9s 兜底。 */
    public static final String SPOKEN = "/nori-spoken";

    /** TTS：整段 WAV@48k（不带 stream）或分块 PCM@32k（?stream=1）。 */
    public static final String TTS = "/nori-tts";

    /** 摸头：{@code {"part":"head"|"tail"}}。 */
    public static final String TOUCH = "/nori-touch";

    public static final String LIFESTAT = "/nori-lifestat";
    public static final String HUBSTAT = "/nori-hubstat";
    public static final String SPLICESTAT = "/nori-splicestat";
    public static final String N3STAT = "/nori-n3stat";
    public static final String QSTAT = "/nori-qstat";
    public static final String OBSSTAT = "/nori-obsstat";
    public static final String LLMSTAT = "/nori-llmstat";

    /** 内容通道：?key= 取芯片文本。 */
    public static final String CHIP = "/nori-chip";

    public static final String WORLD_GEN = "/nori-world-gen";

    /** 诊断包（排障入口）。 */
    public static final String DEBUG = "/nori-debug";

    // ---- LLM 出口（OpenAI 兼容，§3.2）----

    public static final String V1_CHAT_COMPLETIONS = "/v1/chat/completions";
    public static final String V1_MODELS = "/v1/models";

    /** 全部 <b>HTTP</b> 端点（不含 WS 与二进制通道），便于做契约自检与路由注册。 */
    public static final List<String> HTTP_ENDPOINTS = List.of(
            LINE, SPOKEN, TTS, TOUCH,
            LIFESTAT, HUBSTAT, SPLICESTAT, N3STAT, QSTAT, OBSSTAT, LLMSTAT,
            CHIP, WORLD_GEN, DEBUG,
            V1_CHAT_COMPLETIONS, V1_MODELS);

    private NoriPaths() {
        throw new AssertionError("no instances");
    }
}
