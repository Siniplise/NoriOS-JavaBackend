import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

// Nori 后端 · 根构建脚本
//
// 选型说明（与 HANDOFF.md §7.1 的差异，已确认）：
//   HANDOFF 建议 Java 21 + 虚拟线程；本工程用 Java 25（当前 LTS，虚拟线程早已 GA），
//   Gradle 9.8.2 原生支持 JDK 25 编译与运行。
//   HTTP/WS 框架暂不锁定：HANDOFF 给的是 “Netty 原生 或 Spring Boot 3 + WebFlux”，
//   两者对 Java 25 的支持不同（Boot 3 不支持 class file 69，需 Boot 4）。
//   所以这一版只搭骨架，不引入任何 Web 框架，等 §8 第 1 步（录制契约）之后再定。

plugins {
    base
}

allprojects {
    group = "com.nori.backend"
    version = "0.1.0-SNAPSHOT"
}

// 版本目录在 subprojects {} 里拿不到 accessor（delegate 换成了子项目），先在这里取出本地引用
val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher
val assertjCore = libs.assertj.core
val slf4jApi = libs.slf4j.api

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // 编译与测试都强制用 JDK 25，避免落到 PATH 上的其它版本
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -Xlint:all 会把“可能有问题”的地方提前暴露；-processing 关掉注解处理器告警噪音
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        // 测试基线：JUnit 5 + AssertJ，BOM 统一版本
        "testImplementation"(platform(junitBom))
        "testImplementation"(junitJupiter)
        "testImplementation"(assertjCore)
        "testRuntimeOnly"(junitPlatformLauncher)

        // 日志 API 全模块可见（实现由 app 在运行时提供）
        "implementation"(slf4jApi)
    }
}
