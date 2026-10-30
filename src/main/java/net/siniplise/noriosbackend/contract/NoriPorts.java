package net.siniplise.noriosbackend.contract;

/**
 * 端口表。
 *
 * <h2>为什么默认是 4183 而不是 4173</h2>
 * 旧的 Node 栈（{@code nori_proxy.js}）还在跑任务，4173 被它占着；两个项目并行期
 * Java 必须换一个空档，否则起不来。4183/4184 是查过现有占用后挑的：
 * <ul>
 *   <li>4173 旧反代（Node）</li>
 *   <li>4174 Nori.Web.exe（游戏本体）</li>
 *   <li>4180 {@code nori-freeze-probe.js} 的探针上报口</li>
 *   <li>4199 {@code nori-fresh-start.js} 注释里提到的清档口</li>
 *   <li>8880 TTS（Python / Inworld）</li>
 *   <li>25503 人格（Node {@code n_llm.js}）</li>
 * </ul>
 *
 * <h2>换端口对前端是否安全（2026-10-30 实查结论）</h2>
 * 安全，因为 bundle 是「来源相对」的，4173 只是没有 {@code window} 时的兜底：
 * <pre>
 * assets/env-DaMn6UrE.js       if (window.location) return window.location.origin; return "http://127.0.0.1:4173";
 * assets/NormalApp-Cn6agT0F.js  return `ws://${window.location.host}/api/arcade/web/v1`
 * </pre>
 * {@code index.html} 里出现的两处 4173 只是作者注释文本，不参与运行。
 *
 * <p><b>唯一的真硬编码</b>：{@code nori-prompt-inject.js} 第 15 行
 * {@code var NORI_LLM_BASE = "http://127.0.0.1:4173/v1";}（注释明确说「这里才是真正生效的出口」）。
 * 也就是说：页面从 4183 打开时，WS 和 /nori-* 都会跟着走 4183，
 * 但「浏览器 → LLM 出口」这条仍会打到 4173。并行期要么让 4173 侧继续应答，
 * 要么覆盖 localStorage 里的 baseUrl，要么改这一行（改即动前端，需单独决策）。
 *
 * <h2>配置来源（重要）</h2>
 * 这里只是「出厂默认值 + 已知占用表」，用来给 {@code resources/config/nori-backend.json}
 * 和测试对齐。<b>实际生效的端口不从这里读</b>：唯一事实来源是
 * {@link net.siniplise.noriosbackend.config.NoriConfig#ports()}，由
 * {@link net.siniplise.noriosbackend.config.ConfigLoader} 合并「外部 JSON + 环境变量」后得到。
 * 两处各自解析会让「改了配置却不生效」，属于必须避免的重复。
 */
public final class NoriPorts {

    /** Java 反代入口默认端口。 */
    public static final int GAME_DEFAULT = 4183;

    /** 管理端默认端口（存档 / 记忆 / 人格 / TTS / 端口映射），前端不感知。 */
    public static final int ADMIN_DEFAULT = 4184;

    /** 旧 Node 反代，切换期只读对照用。 */
    public static final int LEGACY_PROXY = 4173;

    /** 游戏本体 Nori.Web.exe，暂时不动。 */
    public static final int GAME_UPSTREAM = 4174;

    /** 冻结探针上报口，前端脚本里写死，别占。 */
    public static final int FREEZE_PROBE = 4180;

    /** 清档入口，前端脚本注释里写死，别占。 */
    public static final int FRESH_START = 4199;

    /** Inworld TTS。 */
    public static final int TTS_UPSTREAM = 8880;

    /** 人格服务（现在还是 Node，Java 版会整体取代）。 */
    public static final int PERSONA_UPSTREAM = 25503;

    private NoriPorts() {
        throw new AssertionError("no instances");
    }
}
