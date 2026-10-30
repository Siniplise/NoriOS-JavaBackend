// Nori 后端 · Gradle 根设置（单模块）
// 镜像策略：全部走腾讯云镜像（用户指定），不直连外网。
//   - 插件解析：gradle-plugins（plugins.gradle.org 的镜像）
//   - 依赖解析：maven-public（central 合并镜像）
// 结构决策（2026-10-30）：单模块 + 按功能分包，不拆 Gradle 子项目。
// 理由：这是单机单用户的系统，万行级代码；12 个域拆成子项目只会增加「这个类放哪」的心智负担。

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
    // 依赖只在这里声明，禁止子项目各写一遍 repositories（防止镜像被绕过）
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
