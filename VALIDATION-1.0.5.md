# 恋叙 1.0.5 正式包验证

- 上一正式版为 v1.0.4（版本号 63）。使用本地留存的 `delivery-1.0.4-20261119/Lianxu-1.0.4.apk` 作为覆盖升级基线。
- 新正式版为 1.0.5（版本号 64），包名为 `app.lianxu.independent`。两版证书 SHA-256 均为 `78e2ff6edc4468a700ce51e68681f03c38b11778918ec251fc3e367561e0c31b`。
- 新 APK 共 18,702,553 字节，SHA-256 为 `bdafc8884a97b731cf15eb2727327241f19370728801d5580e16949a7912978a`。APK Signature Scheme v2/v3 签名与 16 KiB 对齐校验通过，正式 APK 无调试标记（`pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`）。
- 构建清单（`BUILD-INFO.json`）记录的编译提交为 `d789655142826ed6721348dac89ef079ecea452d`，`app` 源码树为 `d2fd452bf2fe2b055bd921c3b8daa0f704e3d502`，与发布提交的 `app` 源码树一致。
- 在 Android 15 / API 35 模拟器上先安装 1.0.4 并冷启动，随后 `adb install -r` 直接覆盖安装 1.0.5：`versionCode` 由 63 升至 64，`firstInstallTime` 保持一致，冷启动正常（后续仅调整随包公告文案并保持同证书重签、通过签名与对齐校验）。
- 本次 14 个定向 JVM 单测类共 133 项通过，0 失败、0 跳过；Release Lint 为 0 错误、46 条警告。
- 本次发布验证覆盖安装包构建、签名对齐、同证书覆盖升级与定向单测。真实手机、长期后台省电策略及不同服务商的语音/看图效果仍需在真实设备上验证。
