# 恋叙 1.0.7 正式包验证

- 上一正式版为 v1.0.6（版本号 65）。使用本地留存的 `delivery-1.0.6-20261124/Lianxu-1.0.6.apk` 作为覆盖升级基线。
- 新正式版为 1.0.7（版本号 66），包名为 `app.lianxu.independent`。两版证书 SHA-256 均为 `78e2ff6edc4468a700ce51e68681f03c38b11778918ec251fc3e367561e0c31b`。
- 新 APK 共 18,833,625 字节，SHA-256 为 `cb1837abe9d07698b1d268d633b134c90206e6a6ece8e445c26393273c5d4bae`。APK Signature Scheme v2/v3 签名与 16 KiB 对齐校验通过，正式 APK 无调试标记（`pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`）。
- 构建清单（`BUILD-INFO.json`）记录的编译提交为 `2514fa9acce250f8868650178ac211e5642ebe71`，`app` 源码树为 `36ecdea66cdc15282e31460ba91875054e371538`，与发布提交的 `app` 源码树完全一致。
- 在 Android 15 / API 35 模拟器上先安装 1.0.6 并冷启动，随后 `adb install -r` 直接覆盖安装 1.0.7：`versionCode` 由 65 升至 66，`versionName` 为 `1.0.7`，`firstInstallTime` 保持一致（`2026-11-27 05:38:54`），冷启动正常、无异常崩溃。
- 全量 JVM 单元测试 1703/1703 全部通过，0 失败、0 跳过；Release Lint 为 0 错误、49 条警告；更新页、启动提示与元数据模拟器界面测试 13/13 全绿零跳过。
- 本次发布验证覆盖安装包构建、签名与 16 KiB 对齐、同证书 1.0.6→1.0.7 覆盖升级、全量 JVM 单测与定向界面测试。真实手机、长期后台省电策略、Android 8–9 实机存储权限弹窗及真实服务商长期表现仍需在真实设备上验证。
