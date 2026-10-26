# 记忆模块实现与验证边界

全局记忆规则提供主动回查、当场写记忆两个独立开关，默认均关闭。关闭的功能不发送工具定义；工具执行与最终写入校验冻结的规则版本及权限。只有首个工具请求被服务明确拒绝为不支持工具时，最多以无工具请求重试一次；认证、限额、网络、格式及未启用工具错误均不触发回退，不自动更改开关。带工具调用的响应保留可见文本。

来源变更沿用原有按整个轮次证据计算的受影响集合，整体保留文字或整体作废。两者均使旧证据与候选失效，保留文字不恢复来源。作废只逻辑撤回非人工、非锁定记忆；人工与锁定项继续保留及复核。无受影响记忆或候选时直接保存原话，不显示影响确认。

显式 `date_type=birthday` 连通工具、批处理、存储与人工编辑，年度月日使用 `annualMonthDay=MM-DD`。无出生年份时 `eventDate=null`，2月29日按年度月日保留，不平移、不猜年份；已知年份按真实日历验证。不批量重解释旧记录，不增加提醒。普通正文编辑保留原日期。

回查额度默认3轮、总4000 Token、单次1500，可在原设置修改；轮数1–100，总额256–64000，单次128至总额，均为整数。自动相关召回仍默认15条，核心独立；40/20轮、有证据的2跳/评分后8入口、百分制、未知日期空及失败事务保护保持。

工具链使用JDK21完整开发包（包含javac）、Gradle9.8.0、AGP9.3.2、Kotlin2.4.20、compile/target SDK37、minSdk26。通过 `ANDROID_HOME` 或被Git忽略的 `local.properties` 指定SDK，不提交个人路径或凭据。Linux/macOS首次运行先 `chmod +x gradlew`。启动及合成TLS测试准备见 [README](../README.md)。

构建与验证命令：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain
```

2026-10-26验证记录：上述检查通过。550项单元测试，0失败、0错误、0跳过；lint为0错误、2项既有提示（Gson版本、v26资源目录）。相关4项纯逻辑单元测试已运行；9项合成设备集成测试与UI默认值断言已编译但未运行。单元XML生成于 `app/build/test-results/testDebugUnitTest`，lint报告生成于 `app/build/reports/lint-results-debug.xml`。

上述记录不包含 `connectedDebugAndroidTest`、UI状态验收或截图，也不包含真实设备或真实API验证。设备测试仅在无真实数据的专用模拟器按README命令运行。旧数据迁移暂停，不批量重解释已有记录。
