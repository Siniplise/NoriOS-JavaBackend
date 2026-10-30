// 语音编排：单队列串行、bubblesFor 分句、9s 兜底、qid 单调（§3.4）
dependencies {
    api(project(":contract"))
    implementation(project(":tts"))
    implementation(project(":persona"))
    implementation(project(":obs"))
}
