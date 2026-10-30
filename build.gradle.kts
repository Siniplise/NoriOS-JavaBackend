import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion

// ─────────────────────────────────────────────────────────────────────────────
// Nori 后端 · 单模块构建（Spring Boot 4）
//
// 版本与选型：
//   JDK 25        —— 用户指定
//   Gradle 9.8.2  —— wrapper 入库，发行版走腾讯镜像 + sha256 校验
//   Spring Boot 4.1.2 —— 用户定。HANDOFF 原建议 Boot 3，但 Boot 3 不支持 class file 69（Java 25），
//                        要用 Boot 就只能上 4。取 WebFlux（HANDOFF §7.1 的另一半选项），
//                        WS 帧仍走 Netty 层，将来做零拷贝转发不会被 MVC 挡住。
//   日志          —— Log4j2（不用 Boot 默认的 Logback）：原生 FATAL + AsyncLogger
//
// 包地图（按功能分包，不按技术分包；新代码先对号入座再动手）：
//   net.siniplise.noriosbackend
//   ├── app         启动、Spring 装配、优雅停机
//   ├── admin       /api/admin/*：存档 / 记忆 / 人格 / TTS / 端口 管理端点（前端不感知）
//   ├── config      配置层：JSON 三层来源 + SecretRef
//   ├── contract    前端契约：NoriPaths / NoriPorts / DTO（前端一行不改，这里就是边界）
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
//   └── obs         *stat 指标、结构化日志、/nori-debug（含 obs.logging 的 %LVL4 插件）
//
// 依赖方向铁律：contract ← 所有业务包；runtime 不依赖任何业务包；
//              业务包之间只允许通过事件（obs/runtime）通信，不允许互相 import 实现类。
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    java
    alias(libs.plugins.spring.boot)
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

configurations.configureEach {
    // Boot 4 把 logging starter 挪到了 spring-boot-starter 下面（webflux 不再直接依赖它），
    // 所以只对某一条依赖边做 exclude 是无效的，必须全局排掉。
    // 不排的话 Logback 和 Log4j2 会同时出现在 classpath：slf4j 只认第一个绑定，另一套配置静默失效。
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}

dependencies {
    // Spring Boot 4：BOM 管版本，不写 @ConfigurationProperties 也不用 yaml
    implementation(platform(libs.spring.boot.bom))

    // WebFlux：HTTP + WS 入口（gateway 落地时在这上面接 Netty 的 WebSocket 处理）
    implementation(libs.spring.boot.starter.webflux)
    // Log4j2 的 api / core / slf4j binding 全由这个 starter 带进来，版本跟 Boot BOM 走
    implementation(libs.spring.boot.starter.log4j2)
    implementation(libs.slf4j.api)

    // 注解处理器生成 META-INF/Log4j2Plugins.dat：让 %LVL4 这类自定义插件在启动时就能被发现，
    // 不必依赖运行期扫包（log4j2.xml 里的 packages 属性只是第二道保险）。
    // platform 必须单独给：annotationProcessor 不继承 implementation 的 BOM，
    // 而且这里刻意不写死 log4j 版本 —— 一旦和运行时的 log4j-core 错版，插件缓存可能读不出来。
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.log4j.core)

    // 持久化：HANDOFF §7.1 定的是 SQLite(WAL) 起步（记忆/存档/beat 状态落库）
    implementation(libs.sqlite.jdbc)

    // 测试：JUnit + AssertJ（不引 spring-boot-starter-test，避免它把 JUnit 版本带偏）
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 用 JavaExec 而不是 BootRun 这个具体类：BootRun 继承 JavaExec，
// 这样写不会因为 Boot 4 挪了包名就让整个构建脚本炸掉（脚本一炸，IDEA 同步也跟着失败）
tasks.named<JavaExec>("bootRun") {
    // 本地请求不走系统代理（Clash 会把 127.0.0.1 也绕一圈，HANDOFF §6）
    jvmArgs("-Dfile.encoding=UTF-8", "-Djava.net.useSystemProxies=false")
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
