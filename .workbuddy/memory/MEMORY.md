# MEMORY.md · NoriOS-JavaBackend（项目长期笔记）

> 跨会话必读。上限 3000 字符，只放稳定约定。

## 定位与隔离

用 Java 重写 Nori 后端（4173 `nori_proxy.js` + 25503 人格 + 8880 TTS 那一栈）。
**前端一行不改**：MF-Dust 发行包 + 8 个注入脚本是唯一不可变契约。权威需求是 `NoriOSNew/HANDOFF.md`。
本工程在 `NoriOS-JavaBackend`；**`NoriOSNew` 是主工作区，只读不写**（用户可能正跑任务）。

## 构建

- Gradle **9.8.2**（wrapper 入库），发行版与所有依赖**全走腾讯镜像**；`FAIL_ON_PROJECT_REPOS` 禁止子项目自带仓库。
- JDK **25**（toolchain 写死，`major version 69`）。包根 **`net.siniplise.noriosbackend`**，`group` 同名。
- **单模块 + 按功能分包**（推翻 13 子项目）。包地图在 `build.gradle.kts` 头注释，别再拆回子项目。
- **构建交给 IDEA**：不反复跑 Gradle，避免抢 `~/.gradle/caches/journal-1.lock`。

## 选型

- **Spring Boot 4.1.2 + WebFlux**。Boot 3 不支持 Java 25（class file 69），要用 Boot 只能 4。
  **已在 IDEA 里 build 通过**（2026-10-30 23:44），装配成立，不用再怀疑这套组合。
- **Jackson 3**（`tools.jackson.*`，Boot 4 默认；注解仍在 `com.fasterxml.jackson.annotation`）。
- **Log4j2** 由 `spring-boot-starter-log4j2` 提供，版本归 Boot BOM；
  必须**全局** exclude `spring-boot-starter-logging`（Boot 4 把它挂在 `spring-boot-starter` 下，只排 webflux 那条边无效）。
- 持久化 sqlite-jdbc；测试 JUnit 5 + AssertJ，**不引** `spring-boot-starter-test`（会把 JUnit 版本带偏）。

## Boot 4 的坑

- 入口类在 `app` 子包，兄弟包扫不到 → `@SpringBootApplication(scanBasePackages = "net.siniplise.noriosbackend")`。
- `@SpringBootApplication` 类**不能**写「私有构造 + AssertionError」：Spring 会实例化它。
- 本机环境有 **`SERVER__PORT=8942`**，松弛绑定会认成 `server.port` 且优先级高于默认属性 →
  端口走命令行参数（`withConfigPort`），否则「配置 4183 实际起 8942」。
- `annotationProcessor` 不继承 `implementation` 的 BOM，必须单独 `annotationProcessor(platform(bootBom))`。
- WebFlux 返回 `String` + `application/json` 会被当字符串序列化 → 管理端点返回 `JsonNode`。

## 配置层（管理器就靠它）

- 唯一事实来源 JSON，三层：出厂默认（`resources/config/nori-backend.json`）→ 本机覆盖（`./config/nori-backend.json`，深合并）
  → 环境变量（`NORI_*`）。`Loaded` 同时给 `effectiveJson()`（文本）与 `effectiveTree()`（树）。
- 加字段四步：JSON 默认值 → `NoriConfig` record → `ConfigLoader.BINDINGS` → `validate`。
- 端口只从 `NoriConfig.ports()` 读；`NoriPorts` 仅默认值与占用表（避免两处各解析）。
- 密钥只有 `secrets.*KeyEnv` 变量名，值走 `SecretRef`（env，缺失即抛）；JSON 里疑似真 key 直接拒绝启动。

## 线程模型

- 每会话一个 `SessionLoop`（虚拟线程 + Mailbox 串行队列），顺序由入队顺序决定，不靠锁 —— 这是「帧序与 blockId 不错位」的唯一保证。
- loop 之间只用 `Mailbox`；**不要在 loop 内同步等同一个 loop**（自锁）。`offer` 满了返回 false，由调用方显式拒绝。

## 端口

- 4183 = 反代入口默认（避开旧 Node 4173），4184 = 管理端。
- 已占：4173 旧反代 / 4174 exe / 4180 冻结探针 / 4199 清档 / 8880 TTS / 25503 人格。
- 前端换端口安全：`assets/env-*.js`、`NormalApp-*.js` 优先 `window.location.origin|host`。
  **唯一真硬编码**：`nori-prompt-inject.js:15` 的 `NORI_LLM_BASE`（改它=动前端）。

## 日志

- `%d{MM-dd HH:mm:ss.SSS} %LVL4 [%thread] %logger{2}:%line - %msg%n%ex`
- 级别恒四字符 TRCE/DEBG/INFO/WARN/EROR/CRTL（自定义插件）；logger 用内置 `%logger{2}`。
- **`%lvl4` 不能用**：`%l` 是内置行号别名，会渲染成 `vl4`，键名必须 `%LVL4`。
- CRTL 两种来源：原生 `log.fatal` 与 `log.error(Critical.CRITICAL, ...)` 的 Marker。
  **别用 MDC + 包装方法**——多一层栈把 `%line` 指到辅助类，行号就废了。

## 代码纪律（HANDOFF §6）

- WS 文本帧**不得 JSON 重序列化**；二进制帧按 frameOffset 零拷贝，`blockId` 错位即全崩。
- API KEY 只从环境变量读，缺失显式抛；写文件先读旧→算新→tmp→原子替换；本地请求不走系统代理。

## 下一步（HANDOFF §8）

录 golden 契约 → 只读镜像 GET → script/speech/tts 离线可测 → touch/spoken → WS 影子对比 24h → 下线 Node。
**第 1 步完成前不写业务代码。**
