// TTS 客户端：/tts（整段 WAV@48k）与 /tts.stream（PCM@32k 分块）、预取时机（§3.4）
dependencies {
    api(project(":contract"))
    implementation(project(":obs"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
}
