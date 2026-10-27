# 模型容量与生成预算

当前阶段：后端能力、存储、预算与估算已接线；下述新配置自动建议和高级设置入口仍待用户确认本次预览后实装，不能视为已经可用的界面。

上下文容量用于本地装载检查，不会作为请求字段发给厂商。聊天和记忆整理各有生成上限；对于默认启用推理的已核实模型，该上限包括推理与正文，不保证留下同等长度的可见回答，也不要求每次生成到上限。

## 默认值与已有配置

仅对官方厂商、明确模型 ID 和官方端点的组合应用能力记录，不按模型名前缀猜测。新配置在用户尚未手填预算时跟随模型建议；手填后停止自动调整。已有加密配置原值保留；旧记录没有记忆整理字段时，沿用该记录原输出上限。只有明确使用建议并保存才修改已有配置，获取目录不修改预算或当前模型。

| 已核实模型 | 上下文 | 模型生成上限 | 新配置聊天 / 整理建议 |
| --- | --- | --- | --- |
| 官方 MiMo `mimo-v2.6-flash`、`mimo-v2.6-pro`、`mimo-v2.6-pro-ultraspeed` | 1,000,000 | 131,072，含推理 | 16,384 / 32,768 |
| 官方 DeepSeek `deepseek-flash`、`deepseek-v4-pro`；官方确认的两个 V4 Flash 旧别名 | 1,000,000 | 393,216，含推理 | 16,384 / 32,768 |
| 官方 OpenAI `gpt-4o-mini` / `gpt-4o-mini-2024-07-18` | 128,000 | 16,384 | 4,096 / 8,192 |
| 官方 OpenAI `gpt-4.1` / `gpt-4.1-2025-04-14` | 1,047,576 | 32,768 | 4,096 / 8,192 |
| 官方 OpenAI `gpt-5-mini` / `gpt-5-mini-2025-08-07` | 400,000 | 128,000，含推理 | 16,384 / 32,768 |

核对日期为2026-10-27。MiMo/DeepSeek文档标为1M，按十进制1,000,000保守填写，不推断二进制边界。OpenAI上述旧模型可能已弃用；容量记录不表示服务仍可用，不替用户切换新模型。其他厂商、未知模型、自定义或改过的地址不套用这些能力；新配置保留16,384 / 2,048 / 2,048并允许按文档手填。

已知组合校验官方容量与生成上限；未知组合没有虚构的8192厂商上限，但仍受应用393,216生成上限、2,000,000上下文上限及生成必须小于上下文的保护。建议值是应用为当前用途选择的起点，不是厂商默认值或最优效果保证。

## 估算与边界

本地按文字类型估算：常用汉字每字1.2、ASCII字母数字每字符0.5、常见标点1、空白0.2；其他文字与扩展字符保守按UTF-8字节计。结果再加25%余量和每条12单位开销。它不是厂商tokenizer，不保证对所有罕见字、代码、随机串都构成数学上界。服务仍可能拒绝超长请求；失败保留原话，不自动换模型或重复收费重试。

近期40轮、每批20轮整理、相关记忆15条、核心独立保持不变。不增加提前整理或自动删原话。工具定义/结果仍参与工具循环检查，回查配额仍独立限制。旧回查计数不重置，旧核心文本和历史不迁移。记忆整理预算用于整批请求及完整回执；预算不足仍失败并保留原批次。

本次只调整预算，不宣称完成所有厂商的推理工具协议。现有传输不回传 `reasoning_content`；部分厂商的推理工具连续调用要求此字段，不能靠增大生成上限解决。角色内部判断提示不是厂商思考开关。真实服务、超长生成的延迟/费用和真机效果须另行验收，本次只使用合成数据。

官方依据：[MiMo模型](https://mimo.mi.com/models/en-US/mimo-v2.6-pro-ultraspeed)、[MiMo请求与生成上限](https://mimo.mi.com/docs/en-US/api/chat/openai-api)、[DeepSeek模型与容量](https://api-docs.deepseek.com/quick_start/pricing/)、[DeepSeek生成参数](https://api-docs.deepseek.com/api/create-chat-completion/)、[DeepSeek推理工具要求](https://api-docs.deepseek.com/guides/thinking_mode/)、[GPT-4o mini](https://developers.openai.com/api/docs/models/gpt-4o-mini)、[GPT-4.1](https://developers.openai.com/api/docs/models/gpt-4.1)、[GPT-5 mini](https://developers.openai.com/api/docs/models/gpt-5-mini)、[OpenAI生成上限](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。
