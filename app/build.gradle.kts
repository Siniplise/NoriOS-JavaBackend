// 可执行入口。等 §8 第 1 步（录制契约）完成、框架定下来之后，
// 这里才会挂上真正的 HTTP/WS 服务（Netty 原生 或 Spring Boot 4 + WebFlux）。
plugins {
    application
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":gateway"))
    implementation(project(":lifecycle"))
    implementation(project(":chat"))
    implementation(project(":script"))
    implementation(project(":speech"))
    implementation(project(":tts"))
    implementation(project(":touch"))
    implementation(project(":content"))
    implementation(project(":proactive"))
    implementation(project(":persona"))
    implementation(project(":obs"))

    // 日志实现只出现在运行时，域模块只见 slf4j-api
    runtimeOnly(libs.logback.classic)

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

application {
    mainClass = "com.nori.backend.app.NoriBackendApplication"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Djava.net.useSystemProxies=false")
}
