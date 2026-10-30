import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion

// ─────────────────────────────────────────────────────────────────────────────
// Nori 后端 · 单模块构建
//
// 版本与选型：
//   JDK 25        —— 用户指定（HANDOFF §7.1 原建议 21，虚拟线程 21 已有，25 是当前 LTS）
//   Gradle 9.8.2  —— wrapper 入库，发行版走腾讯镜像 + sha256 校验
//   框架未定      —— HANDOFF 给的是「Netty 原生 或 Boot 3 + WebFlux」，
//                    但 Boot 3 不支持 class file 69（Java 25），要上就得 Boot 4。
//                    等 §8 第 1 步（录制 golden 契约）之后再定，现在只装依赖不写服务端。
//
// 包地图（按功能分包，不按技术分包；新代码先对号入座再动手）：
//   net.siniplise.noriosbackend
//   ├── app         启动、配置装配、优雅停机
//   ├── contract    前端契约：NoriPaths / DTO（前端一行不改，这里就是边界）
//   ├── gateway     HTTP + WS 入口；WS 双向透传、二进制音频帧按 frameFor 零拷贝
//   ├── runtime     ★线程模型：SessionLoop（每会话串行）+ Mailbox（子线程通信）
//   ├── lifecycle   phase / cart / ended、换局软清空、petGate 四类闸门
//   ├── chat        /v1/* 拦截、固定回应、json_object 一律转发
//   ├── script      14 beat 表、fact→hook→fire、resetStory、轮换
//   ├── speech      单队列串行、bubblesFor 分句、9s 兜底、qid/epoch
//   ├── tts         /tts 与 /tts.stream 客户端、预取；首个 provider 必须对齐 Inworld
//   ├── touch       摸头回执 + fact 注入（down 期间必须无反应）
//   ├── content     芯片 / 邮件 / 信号 / 工件闸门
//   ├── proactive   N3 主动触发 + 节流 + N3_BUSY 互斥
//   ├── persona     取代 25503：prompt 组装、记忆卡、召回编排、/state /memories /reset
//   ├── memory      ★三层记忆：card / emotion / personaState / scene + 召回融合 + scene→card 归档
//   ├── save        ★存档：事件日志 + 快照 + 回放
//   ├── store       持久化抽象：SQLite(WAL) 起步，pgvector 后续
//   ├── admin       /api/admin/*：存档 / 记忆 / 人格 / TTS / 端口 管理端点（前端不感知）
//   └── obs         *stat 指标、结构化日志、/nori-debug
//
// 依赖方向铁律：contract ← 所有业务包；runtime 不依赖任何业务包；
//              业务包之间只允许通过事件（obs/runtime）通信，不允许互相 import 实现类。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    java
    application
}

group = "net.siniplise.noriosbackend"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // 编译与测试都强制 JDK 25，避免落到 PATH 上的其它版本
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

application {
    mainClass = "net.siniplise.noriosbackend.app.NoriBackendApplication"
    applicationName = "nori-backend"
    // 本地请求不走系统代理（Clash 会把 127.0.0.1 也绕一圈，HANDOFF §6）
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Djava.net.useSystemProxies=false")
}

dependencies {
    // JSON（注意 §2.3：WS 帧只解析不改写，禁止整帧重序列化）
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    // HTTP / WS：gateway 的入口、tts 与 persona 的上游客户端
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)

    // 日志：业务代码用 slf4j-api；Log4j2 是底座（obs.logging 的自定义转换器直接用它的 API）。
    // log4j-slf4j2-impl 是 slf4j → log4j2 的 binding，只放运行时，避免业务代码依赖它。
    implementation(libs.slf4j.api)
    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    // 注解处理器生成 META-INF/Log4j2Plugins.dat：让 %LVL4 这类自定义插件在启动时就能被发现，
    // 不必依赖运行期扫包（log4j2.xml 里的 packages 属性只是第二道保险）。
    // 注意 platform 要单独给它：annotationProcessor 不继承 implementation 的 BOM，否则版本解析不到。
    annotationProcessor(platform(libs.log4j.bom))
    annotationProcessor(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)

    // 持久化：HANDOFF §7.1 定的是 SQLite(WAL) 起步（记忆/存档/beat 状态落库）
    implementation(libs.sqlite.jdbc)

    // 测试
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Xlint:all 把「可能有问题」的地方提前暴露；-processing 关掉注解处理器告警噪音
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
    // log4j-core 的注解处理器会检查 GraalVM 元数据参数，不填就刷警告
    options.compilerArgs.addAll(listOf(
        "-Alog4j.graalvm.groupId=net.siniplise.noriosbackend",
        "-Alog4j.graalvm.artifactId=nori-backend",
    ))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "nori-backend",
            "Implementation-Version" to project.version,
        )
    }
}
