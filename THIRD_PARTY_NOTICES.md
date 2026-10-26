# 依赖与许可记录

项目代码采用GNU General Public License version 3 only（GPL-3.0-only），全文见根LICENSE；另有声明的第三方材料继续适用各自原许可证。已核实的新增组件与适配范围见docs/REUSE-PROVENANCE.md。本文件不为第三方代码改变许可证，不代表完整法律审计。以下依赖按构建声明引用，不包含完整SDK或依赖分发包。

| 依赖 | 使用 | 上游许可 |
|---|---|---|
| Android SDK / Emulator | 编译、专用模拟器测试 | Android SDK License；系统镜像含 AOSP 第三方 NOTICE（仅开发环境） |
| Android Gradle Plugin 9.3.2 | 构建 | Apache-2.0 |
| Gradle 9.8.0 / wrapper | 构建 | Apache-2.0 |
| Kotlin / Compose compiler 2.4.20 | 语言与编译 | Apache-2.0 |
| AndroidX Compose BOM 2026.09.00；Material3 / UI / Foundation / Runtime | UI | Apache-2.0 |
| AndroidX Activity Compose 1.13.0 | Activity | Apache-2.0 |
| AndroidX Lifecycle 2.10.0 | ViewModel、生命周期 | Apache-2.0 |
| kotlinx.coroutines（传递依赖） | 异步任务 | Apache-2.0 |
| AndroidX Test / Compose UI test | 测试 | Apache-2.0 |
| JUnit 4.13.2 | 单元测试 | EPL-1.0 |
| Hamcrest（JUnit 传递测试依赖） | 测试断言 | BSD-3-Clause |
| Temurin OpenJDK 21 | 本地开发 JDK | GPL-2.0 with Classpath Exception |

上游：https://android.googlesource.com/platform/frameworks/support/ 、https://github.com/JetBrains/kotlin 、https://github.com/Kotlin/kotlinx.coroutines 、https://github.com/gradle/gradle 、https://github.com/junit-team/junit4 、https://github.com/hamcrest/JavaHamcrest 。

无第三方图片或概念图人物资产打包。0.5新增Google Fonts Ma Shan Zheng“恋叙”两字子集，SIL Open Font License 1.1；完整版权/许可在app/src/main/assets/licenses/MaShanZheng-OFL.txt，来源见docs/BRAND-FONT.md。头像与导航图形由Compose绘制，正文仍用系统字体。开发字形核对工具FontTools（MIT）与Brotli（MIT）仅在任务工具目录，不打包APK。构建仓库仅声明Google Maven、Maven Central、Gradle Plugin Portal。


## 随源码保留的第三方材料

- **Ma Shan Zheng字体子集**：Copyright 2018 The Ma Shan Zheng Project Authors；SIL Open Font License 1.1，全文见app/src/main/assets/licenses/MaShanZheng-OFL.txt，官方来源https://github.com/google/fonts/tree/main/ofl/mashanzheng。
- **Microsoft Recognizers-Text测试资料**：固定版本da7edcff59f669b2a460ab9d400e36298f0d658e；Copyright (c) Microsoft Corporation；MIT全文见app/src/test/resources/licenses/Recognizers-Text-MIT.txt。292条上游选题的文件、下标及处理理由保存在timewords资源。112条补充题与61条独立固定回归题不冒充微软原题。来源https://github.com/microsoft/Recognizers-Text/tree/da7edcff59f669b2a460ab9d400e36298f0d658e。
- **Gradle wrapper**：gradlew、gradlew.bat、gradle/wrapper/gradle-wrapper.jar及配置，基线9.8.0。Gradle分发的原LICENSE与NOTICE保存在licenses/Gradle-LICENSE.txt和licenses/Gradle-NOTICE.txt；保留wrapper脚本原版权/Apache声明。本仓库不附带完整Gradle分发、JDK或SDK。
- **直接复用的用户贡献链**：来源仓库当前许可证元数据为GPL-3.0，详见docs/REUSE-PROVENANCE.md。维护者为本项目代码选定GPL-3.0-only；源仓库整体许可不代替独立新增文件的来源核对，不据此宣称所有内容可在任意许可下使用。

此源码整理不包含APK、签名私钥、模型凭据、数据库、真实聊天、缓存或内部交接报告。应用图标采用既有用户授权生成资源；本次未新增素材或完成商标审查。
