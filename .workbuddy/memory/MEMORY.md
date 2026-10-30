# MEMORY.md · NoriOS-JavaBackend（项目长期笔记）

> 跨会话必读。上限 3000 字符，只放稳定约定，不放下次就会过期的临时状态。

## 项目定位

用 Java 重写 Nori 的后端（原 4173 `nori_proxy.js` + 25503 人格 + 8880 TTS 那个栈）。
**前端一行不改**：MF-Dust 发行包 + 8 个注入脚本是唯一不可变契约。
需求与契约的唯一权威是 `NoriOSNew/HANDOFF.md`（2026-10-30 版）；本目录只是新工程，**不要回写主工作区**。

## 隔离铁律

- 本工程在 `C:\Users\sinip\WorkBuddy\NoriOS-JavaBackend`。
- `NoriOSNew` 是主工作区，**用户可能正跑任务**：只读不写，绝不 `clean`/覆盖/改文件。
- 两个项目并行推进，互不共享构建缓存目录以外的任何东西。

## 构建约定

- Gradle **9.8.2**（wrapper 已入库），`distributionUrl` 指向腾讯镜像 + 带 sha256 校验。
- **所有仓库走腾讯镜像**（用户指定）：`https://mirrors.cloud.tencent.com/nexus/repository/{maven-public,gradle-plugins}/`；
  `repositoriesMode = FAIL_ON_PROJECT_REPOS`，禁止子项目自带 repositories。
- JDK **25**（toolchain 写死 25，`major version: 69`）。不是 HANDOFF 建议的 21，用户改的。
- 包根 **`net.siniplise.noriosbackend`**（2026-10-30 用户改，别再回 `com.nori.backend`）；`group` 同名。
- **单模块 + 按功能分包**（2026-10-30 用户拍板，推翻最初 13 个子项目的做法）。
  包地图在 `build.gradle.kts` 头注释里，新代码先对号入座；**不要再拆回 Gradle 子项目**。

## 配置层（管理器就靠它）

- 唯一事实来源：JSON。三层优先级 = 出厂默认（`resources/config/nori-backend.json`）
  → 本机覆盖（`./config/nori-backend.json`，深合并，只写要改的）→ 环境变量（`NORI_*`）。
- `ConfigLoader.Loaded#effectiveJson()` = 生效中的完整配置，未来管理器读它渲染、写回本机文件、重启生效。
- 加字段固定四步：JSON 默认值 → `NoriConfig` record → `ConfigLoader.BINDINGS`（要 env 覆盖时）→ `validate`（要范围约束时）。
- **端口不再在代码里解析**：`NoriPorts` 只放默认值与已知占用表，生效值只从 `NoriConfig.ports()` 读，避免两处各解析一遍。
- 密钥：JSON 里只有 `secrets.*KeyEnv` 变量名；值只能走 `SecretRef`（env，缺失即抛）；
  JSON 里出现疑似真 key（`sk-` 开头或长度 > 60）直接拒绝启动。

## 线程模型

- 每会话一个 `SessionLoop`（虚拟线程 + Mailbox 串行队列），会话内状态变更/帧下发全部排队执行，
  顺序由入队顺序唯一决定，不靠锁。这是「帧序与 blockId 不错位」的唯一保证。
- loop 之间只用 `Mailbox` 通信；**不要在 loop 内同步等同一个 loop**（自锁）。
- `Mailbox.offer` 满了返回 false，由调用方显式拒绝——不阻塞、不静默丢弃。

## 端口

- 4183 = Java 反代入口默认（避开还在跑的旧 Node 4173）；4184 = 管理端。
- 已占用：4173 旧反代 / 4174 exe / 4180 冻结探针 / 4199 清档 / 8880 TTS / 25503 人格。
- 前端换端口是安全的：`assets/env-*.js`、`NormalApp-*.js` 都优先 `window.location.origin|host`，4173 仅兜底；
  `index.html` 里两处 4173 是注释。**唯一真硬编码**：`nori-prompt-inject.js:15` 的 `NORI_LLM_BASE`（改它=动前端）。

## 技术选型现状

- 已定：Netty 4.2 系（HTTP/WS 入口与上游客户端）、Jackson 2.22（只解析不改写帧）、
  SLF4J + Logback、JUnit 5 + AssertJ、sqlite-jdbc（`memory.store` 起步值）。
- **未定**：HTTP/WS 框架（Netty 原生 vs Spring Boot 4 + WebFlux）。HANDOFF 建议的 Boot 3 **不支持 Java 25**，若要 Boot 必须上 4.x。
- **范围已扩**（2026-10-30 用户定）：不是只换 4173，而是整体后端 —— 含三层记忆、存档、25503 人格、TTS，多线程 + 子线程通信。

## 日志约定（2026-10-30 用户定）

- 格式（`resources/logback.xml`）：`%d{MM-dd HH:mm:ss.SSS} %lvl4 [%thread] %logger4:%line - %msg%n%ex`
  实际样子：`10-30 21:11:58.726 CRTL [main] logging.LogFormatDemo:35 - 消息` + 下一行起是堆栈。
- **级别恒四字符**：TRCE / DEBG / INFO / WARN / EROR / CRTL。`%.4level` 只会截成 DEBU/ERRO，做不到，必须自定义转换器。
- **logger 只留「上一级包.类名」**：`%logger{0}` 会重名、全量包名（27 字符）挤掉半行，取最后一级包名是折中。
- **CRTL 的来源**：logback 1.5 没有 FATAL，用 `log.error(Critical.CRITICAL, ...)` 的 Marker 表达。
  **不要用 MDC + 辅助方法包一层**——多一层栈会把 `%line` 指到辅助类里，排查时最有用的一行就丢了。
- logback 1.5 的坑：`conversionRule` 属性名是 `class`（写 `converterClass` 会 deprecation 刷屏）；
  `ILoggingEvent.getMarker()`、`Marker.contains(String)` 已过时，用 `getMarkerList()` + `getName()`。
- 依赖：logback-classic 是 `compileOnly + runtimeOnly`（只有 obs.logging 的转换器需要），业务包只见 slf4j-api。
- 自检：`gradlew run --args="--demo-log"` 打五种级别 + 一条带堆栈的异常。

## 代码纪律（来自 HANDOFF §6，换语言也要保留）

- WS 文本帧**不得 JSON 重序列化**（字段顺序与未知字段敏感）；二进制音频帧按 frameOffset 零拷贝透传，`blockId` 是句子序号，错位即全崩。
- API KEY 只从环境变量读，缺了显式抛错，不静默降级。
- 写文件：先读旧 → 算新 → 写 tmp → 原子替换；关键标记不得减少。
- 本地请求不走系统代理（Clash 会绕 127.0.0.1）。

## 下一步（按 HANDOFF §8）

第 1 步录制 golden 契约（WS 帧 + 全部 `/nori-*`）→ 只读镜像 GET → script/speech/tts 离线可测 → touch/spoken → WS 影子对比 24h → 下线 Node。
**在第 1 步完成前不写业务代码。**
