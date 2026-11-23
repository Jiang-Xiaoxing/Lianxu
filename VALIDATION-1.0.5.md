# 恋叙 1.0.5 正式包验证

- 上一正式版为 v1.0.4（版本号 63）。使用本地留存的 `delivery-1.0.4-20261119/Lianxu-1.0.4.apk` 作为覆盖升级基线。
- 新正式版为 1.0.5（版本号 64），包名为 `app.lianxu.independent`。两版证书 SHA-256 均为 `78e2ff6edc4468a700ce51e68681f03c38b11778918ec251fc3e367561e0c31b`。
- 新 APK 共 18,702,553 字节，SHA-256 为 `109080c19fccc66824b79fc9c703b52cc35ebd519e2c1ef9116f623fe18d1021`。APK Signature Scheme v2/v3 签名与 16 KiB 对齐校验通过，正式 APK 无调试标记（`pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`）。
- APK 内嵌的源码提交为 `c0cab6a3fb9e9fb4ca64db301635d963c4c54229`，`app` 源码树为 `ece1ad5ab364f39d94173cb68cad3048a4f19d32`。发布标签与构建信息见附件 `BUILD-INFO.json`。
- 在 Android 15 / API 35 模拟器上先安装 1.0.4 并冷启动，随后 `adb install -r` 直接覆盖安装 1.0.5：`versionCode` 由 63 升至 64，`firstInstallTime` 保持一致（`2026-11-23 09:42:26`），冷启动正常，设备已安装的 `base.apk` SHA-256 与交付 APK 完全一致。
- 本次 14 个定向 JVM 单测类共 133 项通过，0 失败、0 跳过；Release Lint 为 0 错误、40 条警告。
- 本次发布验证覆盖安装包构建、签名对齐、同证书覆盖升级与定向单测。真实手机、长期后台省电策略及不同服务商的语音/看图效果仍需在真实设备上验证。
