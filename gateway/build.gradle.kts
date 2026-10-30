// WS 双向代理 + 二进制音频帧（§2.3 / §2.4）
// 铁律：文本帧不得 JSON 重序列化；二进制帧按 frameFor 结构零拷贝透传。
dependencies {
    api(project(":contract"))
    implementation(project(":obs"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
}
