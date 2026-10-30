// 前端契约 DTO（HANDOFF §2.2 HTTP 端点 / §2.3 WS 协议 / §2.4 二进制帧）
// 契约优先（§7.3）：端点签名集中在这里，改动即编译期报错。
dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
}
