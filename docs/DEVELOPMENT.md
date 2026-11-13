# 开发与测试

普通用户无需编译，请从 [README 的下载入口](../README.md#开始使用)安装 APK。

需要兼容的 JDK 与 Android SDK。当前工程使用 JDK 21 运行 Gradle，Java 编译目标为 17；SDK 和版本定义见 [app/build.gradle.kts](../app/build.gradle.kts)。自行设置 `ANDROID_HOME`，或在本机创建不提交的 `local.properties`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`gradlew` 是项目自带的 Gradle 启动脚本；`assembleDebug` 生成调试安装包，`testDebugUnitTest` 运行 JVM 单元测试，`lintDebug` 进行 Android 静态检查。首次构建可能下载构建依赖；这条命令不会安装应用或调用聊天模型。Windows 使用 `gradlew.bat`，Linux/macOS 首次解包后可能需要给 `gradlew` 执行权限。

调试包名为 `app.lianxu.independent.companionstage1`，与不带调试后缀的包分开保存数据。测试只应在无真实资料的专用模拟器中运行。本机 HTTPS 测试需要合成证书，仓库不提供私钥：

```sh
mkdir -p app/src/androidTest/assets
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 3650 -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 -storetype PKCS12 -keystore app/src/androidTest/assets/localhost-test.p12 -storepass synthetic-test-only -keypass synthetic-test-only -noprompt
```

`mkdir -p` 创建测试资源目录；`keytool -genkeypair` 生成 RSA 测试密钥和证书，`-keystore` 指定输出文件，`-storepass` 与 `-keypass` 指定公开的测试密码。证书只用于 `localhost` 和 `127.0.0.1` 的回环测试，不应用于真实服务，也不应提交生成的私钥文件。

完整设备测试还包括需要外部交付夹具、指定升级阶段或真实模型配置的用例；缺少条件时不能计作通过。真实服务商测试应单独明确授权，不要把全部设备测试直接接到存有个人资料的手机上。

