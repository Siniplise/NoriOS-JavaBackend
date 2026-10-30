// 人格服务客户端（25503）：/state /memories /log /reset /reload /act /game-turn …
// 现在的 25503 还是 Node，本模块只做客户端；后续可整体替换（§3.6）
dependencies {
    api(project(":contract"))
    implementation(project(":obs"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
