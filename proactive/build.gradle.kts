// N3 主动触发：游戏事件 → 本地 LLM 一句 → pushGameLine，带节流与 N3_BUSY 互斥（§3.8）
dependencies {
    api(project(":contract"))
    implementation(project(":persona"))
    implementation(project(":obs"))
}
