# 恋叙 · Lianxu

一个正在开发的原生 Android AI 伴侣应用，让你创建自己的角色，在持续的对话中保留各自的记忆与关系。

你决定角色是谁、怎样表达，也可以查看和修正它记住的内容。恋叙使用你自行配置的模型服务，界面采用暖黄星月配色，支持深色模式。

**Android 8.0+ · 自备模型 API · 开发测试阶段 · GPL-3.0-only**

## 现在可以做什么

- **创建角色，分别聊天。** 新安装从空列表开始，不自动添加演示角色。设置名字、介绍与角色提示词；每位角色拥有独立的会话、记忆和关系记录。文字发送支持取消，失败后可手动重试。聊天与通讯录列表提供同一角色的删除入口；确认后删除该角色本机资料，两处同步移除，建议先备份。
- **管理角色的记忆。** 查看原话、整理后的记忆与待确认内容，编辑或锁定重要条目。来源发生变化时，需要确认如何处理受影响的记忆。
- **让旧话题有机会被想起。** 聊天会参考核心记忆和相关记忆；默认保留近期 40 个完整回合，每批整理 20 回合，相关记忆最多 15 条。整理不会删除原始聊天记录。
- **调整表达与关系。** 按角色设置自定义表达、附加提示和初始关系背景，查看六维关系状态。关系自动评估和内部判断提示默认关闭，可按需配置。
- **从聊过的话里挑选语气参考。** 新建角色聊满 20 个完整回合后，会邀请你使用聊天模型挑选 2–3 轮原话；确认后才调用。示例只参考语气，近期原话窗口满后停用并保留记录。升级前已有角色不会自动加入这个流程。
- **自行选择模型和记忆工具。** 统一管理模型配置，并按聊天、记忆整理等用途选择模型。记忆回查和当场写记忆可以分别开启，默认均关闭，调用额度可调整。
- **完整备份并排查问题。** 在设置的“备份与诊断”导出一个角色的全部资料，或备份全部角色、设置及模型配置；全部导出需另选是否携带 API 密钥。导入先选新增/覆盖并预览范围。本机诊断可导出简版或详细版，详细内容可能包含聊天和提示词，请妥善保管。范围与限制见 [说明](docs/BACKUP-DIAGNOSTICS.md)。

## 怎样开始使用

### 安装

测试 APK 仅通过 [GitHub Releases](https://github.com/Jiang-Xiaoxing/Lianxu/releases) 提供。下载时请核对发布说明中的版本、源提交和签名兼容范围；也可以按下方说明自行构建。

v0.18 测试版统一为暖黄星月界面，补充用户与角色人设表单、记忆星空、独立关系入口和随包更新说明，并修复无模型配置时全量恢复失败。版本码18沿用v0.15–v0.17本机调试签名；v0.14使用不同签名，不能直接覆盖安装，有旧数据时请先备份，勿贸然卸载。设备实测范围以本版发布说明为准。

### 配置并开始聊天

1. 打开 **设置 → 大模型配置 → 添加服务商**，填写自己的 API Key。支持目录的服务可点击 **获取模型** 后搜索、点选；也可以手填模型 ID。获取不会自动更换当前模型，点选只填入草稿，保存后才生效。使用自定义服务时，在高级设置中填写 HTTPS 服务地址。文字聊天使用 OpenAI 兼容接口，目录支持范围见 [获取模型说明](docs/MODEL-DIRECTORY.md)。
2. 保存后，在用途选择中指定 **聊天** 模型。可另选 **记忆整理** 模型；未单独指定时，记忆整理沿用聊天模型。高级设置可分别填写聊天和记忆整理生成上限；已核对的厂商、模型和官方端点提供建议，旧配置保留原值，明确应用建议并保存后才修改。推理模型的生成上限包含推理，详情见[模型预算](docs/MODEL-TOKEN-BUDGETS.md)。
3. 在 **聊天** 或 **通讯录** 页点击右上角 **＋ → 创建角色**，填写名字、角色介绍和提示词，再点 **创建角色** 进入聊天。
4. 想调整记忆或关系时，进入 **设置 → 记忆** 或 **设置 → 关系**；想调整角色说话方式时，从角色资料进入 **编辑角色设定**。

保存模型配置不会发起请求；测试连接、获取模型、发送消息，以及运行已启用的处理任务时才会调用相应服务。模型服务可能收费，请按自己的账户额度使用。

### 数据保存在哪里

角色、聊天与记忆保存在应用的本机数据库中，API Key 使用 Android Keystore 保护。**本机保存不等于离线推理**：调用模型时，会把该请求需要的角色设定、对话或记忆内容发送给你配置的服务商。旧聊天示例的挑选会另行说明发送范围，并征求你的同意。

## 当前开发阶段

恋叙仍在迭代，已有合成数据、模拟接口与 Android 模拟器验证；尚未完成真实模型服务、真实手机和完整设备适配验收，不保证所有兼容接口都能正常工作。

目前的边界：

- **搜索 AI 仍是本地演示**，不是联网寻找或生成角色。
- 图片与附件理解、主动聊天、世界书尚未接入；视觉模型配置入口不代表视觉功能已经可用。
- 旧数据迁移暂停。现阶段请使用测试数据体验，不要把它作为重要资料的唯一保存位置。

问题与建议可以提交到 [Issues](https://github.com/Jiang-Xiaoxing/Lianxu/issues)。请说明复现步骤、设备与应用版本，并移除密钥、聊天隐私和其他个人信息。

## 开发与构建

项目使用 Kotlin、Jetpack Compose / Material3 和本机 SQLite。开发规范见 [AGENTS.md](AGENTS.md)，实现说明见 [架构](docs/ARCHITECTURE.md)、[记忆模块](docs/MEMORY-PORT.md) 与 [界面基线](docs/UI-DESIGN.md)。代码变更通过分支与 PR 评审，测试结果按实际执行情况记录。

<details>
<summary>从源码构建 APK</summary>

需要 Git、完整 JDK 21（含 `javac`）和 Android SDK 37。仓库使用 Gradle wrapper 9.8.0、AGP 9.3.2、Kotlin Compose 插件 2.4.20，Java 编译目标为 17。

```sh
git clone https://github.com/Jiang-Xiaoxing/Lianxu.git
cd Lianxu
```

设置 `ANDROID_HOME`，或在项目根目录创建本机专用的 `local.properties`，填写 `sdk.dir`。不要提交个人路径或凭据。

Linux / macOS：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`，为 debug 签名的测试安装包。可将 APK 传到测试设备安装，或使用 Android SDK 的 `adb`：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

覆盖安装要求签名一致；请勿为了安装而贸然卸载存有重要数据的旧版本。

</details>

<details>
<summary>运行开发检查与设备测试</summary>

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

设备测试仅在无真实数据的专用模拟器运行。TLS 模拟测试需要先生成本机合成证书；以下密码是测试源码公开常量，不是真实凭据：

```sh
mkdir -p app/src/androidTest/assets
keytool -J-Dkeystore.pkcs12.legacy -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 3650 -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 -storetype PKCS12 -keystore app/src/androidTest/assets/localhost-test.p12 -storepass synthetic-test-only -keypass synthetic-test-only -noprompt
./gradlew :app:connectedDebugAndroidTest
```

`keystore.pkcs12.legacy` 仅用于让合成测试证书兼容较旧 Android，不修改应用的 TLS 校验。已有该证书时无需重复生成。证书与生成报告均不纳入源码；不得提交真实密钥、证书、数据库、聊天数据或模型配置。

</details>

## 项目创作与协作

**创作者：Jiang-Xiaoxing**

**AI 协作：饭团（OpenAI AI 助手）**，协助设计、开发与测试。

## 来源与许可

除另有明确声明的第三方材料外，本项目代码按 **GNU General Public License version 3 only（SPDX: GPL-3.0-only）** 分发；完整条款见 [LICENSE](LICENSE)，不包含“或任何后续版本”的额外授权。该选择由项目维护者作出，不以来源仓库整体许可证代替逐项来源判断。已核实的日期引擎原提交、修正及其他复用范围见 [复用记录](docs/REUSE-PROVENANCE.md)。

第三方字体、测试数据和 Gradle wrapper 各自保留原许可证与署名，见 [第三方记录](THIRD_PARTY_NOTICES.md)。不为不拥有的代码改变许可证，不声称完成法律审计或商标清查。
