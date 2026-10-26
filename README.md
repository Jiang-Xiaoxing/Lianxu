# 恋叙 · Lianxu

开发中的原生 Android AI 伴侣项目，当前版本标识为 `0.14-memory-counts-test`。这是源码开发基线，不是成熟正式版，也不包含 Release 或 APK。

界面使用 Kotlin、Jetpack Compose / Material3；聊天、角色、记忆与关系按角色隔离保存。文字聊天调用用户自行配置的 HTTPS OpenAI 兼容接口，配置保存在本机。搜索 AI 仍为本地演示；视觉附件和主动聊天未开放。没有真实服务商或真实手机验收结论。

记忆保留近期40个完整轮次，超过后整理最早20轮；相关记忆默认去重后最多15条，核心独立。已接入有证据的最多2跳关联，评分后保留8入口，评分采用百分制。向量缓存可选，向量用途未配置时不发送嵌入请求。来源、版本、人工/锁定、失败重试及最终回复/工具记忆事务保护必须保留。

记忆模块支持：

1. 主动回查与当场写记忆各自默认关闭；仅明确不支持工具的首次请求可受限重试一次，不自动改开关。
2. 来源变更按受影响集合整体保留文字或逻辑作废，旧证据始终失效，人工与锁定项保留复核。
3. 显式生日年度元数据支持未知年份与2月29日，不批量重解释旧记录、不新增提醒。
4. 回查额度默认3轮/总4000 Token/单次1500，可在全局设置调整并验证边界；自动召回默认15条不变。

2026-10-26验证记录：550项单元测试通过（0失败、0错误、0跳过），assembleDebug、lintDebug及compileDebugAndroidTestKotlin通过；lint为0错误、2项既有提示（Gson可更新、v26资源目录冗余）。9项相关合成设备集成测试及UI默认值断言已编译，设备测试与UI截图未运行。未验证真实API、真实设备及完整UI状态。

## 本地构建

需要兼容的JDK和Android SDK。当前工具链使用JDK21完整开发包（需含javac）、Gradle9.8.0、AGP9.3.2、Kotlin2.4.20、compile/target SDK37、minSdk26，Java目标17。

自行设置 `ANDROID_HOME` 或在本机创建被Git忽略的 `local.properties`，不要提交个人路径或凭据。Linux/macOS解包后先 `chmod +x gradlew`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

设备测试只在无真实数据的专用模拟器运行。仓库不包含任何私钥，TLS测试需要先生成仅供本机mock的合成证书；以下密码是测试源码公开常量，不是真实凭据：

```sh
mkdir -p app/src/androidTest/assets
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 3650 -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 -storetype PKCS12 -keystore app/src/androidTest/assets/localhost-test.p12 -storepass synthetic-test-only -keypass synthetic-test-only -noprompt
./gradlew :app:connectedDebugAndroidTest
```

合成证书被Git忽略，不得上传真实证书、API Key、登录凭据、数据库、聊天数据或模型配置。

## 开发与贡献

开发规范见 [AGENTS.md](AGENTS.md)，实现说明见 [架构](docs/ARCHITECTURE.md) 和 [记忆模块](docs/MEMORY-PORT.md)。贡献使用独立分支和PR评审，不直接合并；报告实际运行的检查及其限制。

旧数据迁移暂停。不得提交数据库、聊天数据、模型配置、凭据或个人路径。单元报告生成于 `app/build/test-results/testDebugUnitTest`，lint报告生成于 `app/build/reports/lint-results-debug.html`；生成报告不纳入源码。

## 来源与许可

除另有明确声明的第三方材料外，本项目代码按 **GNU General Public License version 3 only（SPDX: GPL-3.0-only）** 分发；完整条款见 [LICENSE](LICENSE)，不包含“或任何后续版本”的额外授权。该选择由项目维护者作出，不以来源仓库整体许可证代替逐项来源判断。已核实的日期引擎原提交、修正及其他复用范围见 [复用记录](docs/REUSE-PROVENANCE.md)。

第三方字体、测试数据和Gradle wrapper各自保留原许可证与署名，见 [第三方记录](THIRD_PARTY_NOTICES.md)。不为不拥有的代码改变许可证，不声称完成法律审计或商标清查。
