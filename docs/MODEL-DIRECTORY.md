# 获取模型

在模型配置页填写密钥后，点击“获取模型”读取当前服务的目录。列表可以搜索；获取成功不会自动更换模型，点选才填入草稿，点击顶部“保存”后才写入配置。手动填写模型 ID 始终保留。

加载期间可以取消；连续点击不会重复发起同一请求。修改配置、返回、应用转入后台或切换厂商后，旧请求和旧列表失效。修改服务地址或切换厂商会清空当前输入的密钥；保存的密钥也只在原厂商、原地址未变时沿用。请求使用 HTTPS，不跟随重定向，不会自动换地址、换地域或探测其他主机。

目录获取只读模型列表，不发送聊天，不开通模型，不自动保存配置。厂商目录可能同时列出语音、向量或其他模型，出现于列表不等于支持当前文字聊天，也不保证账户有生成权限。连接测试与聊天生成验收分开。

## 已核对的厂商接口

以下记录核对日期为2026-10-27。文档支持与实际账户可用性分开；本次开发只用合成数据与本机模拟服务验证，没有使用用户真实密钥或调用付费聊天。

| 预设 | 列表请求 | 认证与说明 |
| --- | --- | --- |
| 硅基流动中国大陆 | 当前根地址 `/models?sub_type=chat` | Bearer；[官方说明](https://api-docs.siliconflow.cn/docs/api/models-get)。 |
| DeepSeek | 当前根地址 `/models` | Bearer；默认根地址不带 `/v1`；[官方说明](https://api-docs.deepseek.com/api/list-models/)。 |
| OpenAI | 当前 `/v1` 根地址下的 `/models` | Bearer；[官方说明](https://developers.openai.com/api/reference/resources/models/methods/list)。 |
| OpenRouter | 当前 `/api/v1` 根地址下的 `/models` | Bearer；目录可能公开可读，认证测试另外访问 `/key`；[官方说明](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)。 |
| 小米 MiMo | 当前 `/v1` 根地址下的 `/models` | 沿用 `api-key` 请求头；[官方说明](https://mimo.mi.com/docs/en-US/api/model/list-models)。 |
| Moonshot / Kimi 国际 | 当前 `/v1` 根地址下的 `/models` | Bearer；不混用国内站或订阅密钥；[官方说明](https://platform.kimi.ai/docs/api/list-models)。 |
| MiniMax 中国大陆 | 当前 `/v1` 根地址下的 `/models` | Bearer；不混用国际站密钥；[官方说明](https://platform.minimax.cn/docs/api-reference/models/openai/list-models)。 |
| MiniMax 国际 | 当前 `/v1` 根地址下的 `/models` | Bearer；不混用国内站密钥；[官方说明](https://platform.minimax.io/docs/api-reference/models/openai/list-models)。 |
| 自定义 OpenAI 兼容 | 尝试用户当前根地址下的 `/models` | Bearer；只有点击获取才尝试，服务不支持或格式不兼容时继续手填。 |

## 仍需手填的预设

- **百炼北京**：官方目录为业务空间专属主机下的 `/api/v1/models`，且响应需要分页；不能在现有 `dashscope.aliyuncs.com/compatible-mode/v1` 后直接拼 `/models`。本版尚未适配专属目录，不猜业务空间、不自动改主机或向新主机发送密钥。[官方说明](https://help.aliyun.com/zh/model-studio/list-models)
- **火山方舟北京**：官方管理面 `ListFoundationModels` 仅支持 Access Key 签名，与现有推理 API Key 不同。本版不索取管理凭据，手填已开通的 Model ID 或 Endpoint ID。[官方说明](https://docs.volcengine.com/docs/ark/list-foundation-models-api?lang=zh)
- **智谱中国大陆**：已核对官方公开文档索引和兼容说明，尚未核实标准 API Key 的聊天模型目录接口。本版保留手填，不能仅凭聊天兼容就声明支持获取列表。[官方索引](https://docs.bigmodel.cn/llms.txt)、[兼容说明](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction)

## 失败时

认证失败、权限拒绝、限流、超时、重定向和不兼容分别显示简明提示，不展示原始服务端错误或密钥。只有用户再次点击才重试；取消和失败都保留原有模型字段。空列表或搜索无结果时仍可手填。

目录响应限制为1 MiB；最多保留500个去重后的安全模型 ID，单个不超过200字符。搜索只在本次列表中进行，不发网络请求。密钥不会作为选项显示，日志沿用写盘前脱敏。
