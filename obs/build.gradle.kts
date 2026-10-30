// 观测层：*stat 计数、结构化日志、/nori-debug 诊断包。
// 刻意不依赖任何业务模块，避免形成环。
dependencies {
    // 日志 API 由根脚本统一加；logback 是运行时实现，只挂在 app 上
}
