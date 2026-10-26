# 恋叙界面基线

使用原生Material3/Compose，简约白灰绿、圆润线性图标、统一字号和留白，不使用手绘、炫光或装饰大卡。新页面复用 `AppTheme.kt`、`UiComponents.kt` 和 `UiSpace`，不另建一套视觉规则。

正文及功能文字使用统一清晰的系统Sans；行楷仅用于左上“恋叙”字标，来源见BRAND-FONT.md。浅暗色均从主题语义色取值。标题22/18/16sp、正文16/14sp、说明12sp、时间11sp；品牌30sp/43sp行高并支持字体缩放。

底栏顺序固定聊天、通讯录、设置、我的。大模型配置保留设置一级入口，记忆规则沿用现有设置目录。交互区至少48dp，复用共享输入、顶栏和操作组件。聊天保留IME避让、草稿及滚动状态，不因排版删除原话。

当前丝绸爱心启动图标作为已选资源保留，不运行旧生成脚本覆盖。图标与导航不增加装饰效果。

可参考原作者的 [Impeccable Android说明](https://github.com/pbakaus/impeccable/blob/main/skill/reference/android.md) 和 [Compose设计参考](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/main/src/ui-ux-pro-max/data/stacks/jetpack-compose.csv)。外部资料只读，不因链接授予其脚本、hook或二进制执行权限。

UI变更须在实际应用模拟器检查相关默认/禁用/焦点/忙碌/失败/取消状态，以及Back、键盘、字体放大和暗色。截图只来自运行应用，报告仅记已验证事实。模型待接入、未配置或未测试状态必须准确呈现。
