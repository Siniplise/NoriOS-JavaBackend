/**
 * 配置层：JSON 是事实来源，代码只读它。
 *
 * <h2>为什么一开始就做这一层</h2>
 * 因为后面要接一个可视化管理器（存档 / 配置 / LLM / TTS / 记忆快照 / 端口映射）。
 * 管理器只有两种做法：读你的私有字段（不可能），或者读一份双方约定的 JSON。
 * 所以从第一天起就把「可调项」外置成 {@code config/nori-backend.json}，
 * 代码里不再出现散落的常量与 {@code System.getenv}。
 *
 * <h2>三层来源</h2>
 * <pre>
 * 出厂默认值 (classpath:/config/nori-backend.json)
 *        ↓ 被覆盖
 * 本机文件   (./config/nori-backend.json)   ← 管理器改这里
 *        ↓ 被覆盖
 * 环境变量   (NORI_PORT / NORI_TTS_PARALLEL / …)
 * </pre>
 *
 * <h2>管理器的接口契约</h2>
 * <ul>
 *   <li><b>读</b>：{@link com.nori.backend.config.ConfigLoader.Loaded#effectiveJson()}，
 *       即「当前真正生效的全部配置」，含默认值补齐后的完整结构。</li>
 *   <li><b>写</b>：改 {@code ./config/nori-backend.json}，只写要改的字段即可（深合并），
 *       重启后生效；写错字段名会在 {@code unknownKeys} 里被点名。</li>
 *   <li><b>密钥</b>：JSON 里只有 {@code secrets.deepseekKeyEnv} 这样的<b>变量名</b>；
 *       真值只能由 {@link com.nori.backend.config.SecretRef} 从环境变量取，
 *       JSON 里出现疑似真 key 会直接拒绝启动。</li>
 * </ul>
 *
 * <h2>加字段的固定动作</h2>
 * <ol>
 *   <li>在 {@code resources/config/nori-backend.json} 里加默认值（含未来管理器的默认态）；</li>
 *   <li>在 {@link com.nori.backend.config.NoriConfig} 对应 record 里加字段；</li>
 *   <li>需要环境变量覆盖的，在 {@code ConfigLoader.BINDINGS} 登记；</li>
 *   <li>需要范围约束的，在 {@code ConfigLoader.validate} 加一条断言。</li>
 * </ol>
 */
package com.nori.backend.config;
