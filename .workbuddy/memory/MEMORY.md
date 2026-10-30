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
- 模块清单锁定 HANDOFF §7.2（12 个域模块 + app），**不要再拆细**；新增模块必须先改 settings 里的注释说明理由。
- `subprojects {}` 里不能直接用 `libs.xxx`（accessor 失效），要在根作用域先取本地变量。

## 技术选型现状

- 已定：Netty 4.2 系（gateway/tts/persona 的 HTTP/WS）、Jackson 2.22（只解析不改写帧）、SLF4J + Logback、JUnit 5 + AssertJ。
- **未定**：HTTP/WS 框架（Netty 原生 vs Spring Boot 4 + WebFlux）。HANDOFF 建议的 Boot 3 **不支持 Java 25**，若要 Boot 必须上 4.x。
- **未定**：状态持久化。HANDOFF §7.1 建议 SQLite(WAL)；`sqlite-jdbc` 已在版本目录登记但没挂到任何模块。

## 代码纪律（来自 HANDOFF §6，换语言也要保留）

- WS 文本帧**不得 JSON 重序列化**（字段顺序与未知字段敏感）；二进制音频帧按 frameOffset 零拷贝透传，`blockId` 是句子序号，错位即全崩。
- API KEY 只从环境变量读，缺了显式抛错，不静默降级。
- 写文件：先读旧 → 算新 → 写 tmp → 原子替换；关键标记不得减少。
- 本地请求不走系统代理（Clash 会绕 127.0.0.1）。

## 下一步（按 HANDOFF §8）

第 1 步录制 golden 契约（WS 帧 + 全部 `/nori-*`）→ 只读镜像 GET → script/speech/tts 离线可测 → touch/spoken → WS 影子对比 24h → 下线 Node。
**在第 1 步完成前不写业务代码。**
