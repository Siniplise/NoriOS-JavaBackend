// LLM 出口拦截：/v1/* 命中固定台词、json_object 一律转发（§3.2）
dependencies {
    api(project(":contract"))
    implementation(project(":persona"))
    implementation(project(":obs"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
