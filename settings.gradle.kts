// Nori 后端（Java 重写）· Gradle 根设置
// 镜像策略：全部走腾讯云镜像（用户指定），不直连外网。
//   - 插件解析：gradle-plugins（plugins.gradle.org 的镜像）
//   - 依赖解析：maven-public（central + jcenter 合并镜像）
// 说明：本文件与 NoriOSNew 主工作区完全隔离，这里只是另一个独立构建。

pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") {
            name = "tencentGradlePlugins"
        }
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            name = "tencentMavenPublic"
        }
        // 兜底：腾讯镜像缺插件时再打开
        // gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 依赖只在 settings 里声明，禁止子项目各写一遍 repositories（防止镜像被绕过）
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            name = "tencentMavenPublic"
        }
        // 兜底：镜像缺包时才启用（默认关闭，避免静默回源到海外）
        // mavenCentral()
    }
}

rootProject.name = "nori-backend"

// 模块划分直接对齐 HANDOFF.md §7.2（模块化单体，不是微服务）
include(
    "contract",   // 前端契约 DTO：/nori-* 全量（§2.2 / §2.3）
    "gateway",    // WS 双向代理 + 二进制音频帧（§2.3 / §2.4，零拷贝、不重序列化）
    "lifecycle",  // phase / cart / ended、换局清空、petGate（§3.1 / §3.5）
    "chat",       // /v1/* 拦截、固定回应、json_object 排除（§3.2）
    "script",     // beat 表、hook、fire、resetStory、轮换（§3.3）
    "speech",     // 语音队列、分句、9s 兜底、qid/epoch（§3.4）
    "tts",        // /tts 与 /tts.stream 客户端、预取（§3.4）
    "touch",      // 摸头回执、fact 注入（§3.5）
    "content",    // 芯片 / 邮件 / 信号 / 工件闸门（§3.7）
    "proactive",  // N3 主动触发 + 节流（§3.8）
    "persona",    // 对 25503 的客户端（§3.6）
    "obs",        // *stat 观测、debug 包、结构化日志
    "app"         // 可执行入口（后续放 Spring Boot / Netty 启动）
)
