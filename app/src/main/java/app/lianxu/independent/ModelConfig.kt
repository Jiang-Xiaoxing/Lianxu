package app.lianxu.independent

import java.net.URI

data class ProviderPreset(val id:String,val label:String,val base:String,val hint:String,val modelsPath:String?=null,val testPath:String?=modelsPath,val authHeader:String="Authorization",val modelsHint:String="本版尚未适配此服务的模型列表，请手动填写模型 ID。")
object ProviderPresets {
    val all=listOf(
        ProviderPreset("silicon-cn","硅基流动 · 中国大陆","https://api.siliconflow.cn/v1","大陆账户；不要混用国际站 Key。","models?sub_type=chat"),
        ProviderPreset("deepseek","DeepSeek","https://api.deepseek.com","OpenAI 兼容接口；不使用 Anthropic 地址。","models"),
        ProviderPreset("bailian-cn","阿里百炼 · 北京","https://dashscope.aliyuncs.com/compatible-mode/v1","现有北京域名仍可用。可改为控制台业务空间专属域名；新加坡等地域须使用对应 Key。当前手填模型 ID。",modelsHint="当前地址请手填模型 ID；模型列表需要业务空间专属地址，本版尚未适配。"),
        ProviderPreset("ark-cn","火山方舟 · 北京","https://ark.cn-beijing.volces.com/api/v3","填写已开通 Model ID 或推理接入点 Endpoint ID；本版不接管理面签名接口，手填模型。",modelsHint="模型目录需要管理接口鉴权，请手填 Model ID 或 Endpoint ID。"),
        ProviderPreset("zhipu-cn","智谱 · 中国大陆","https://open.bigmodel.cn/api/paas/v4","标准开放平台 Key；不是 Coding Plan 专用地址。当前手填模型 ID。"),
        ProviderPreset("moonshot","Moonshot / Kimi · 国际","https://api.moonshot.ai/v1","国际开放平台账户；不混用国内站或 Kimi Code 订阅 Key。","models"),
        ProviderPreset("openai","OpenAI","https://api.openai.com/v1","官方 API Key，不是 ChatGPT 订阅。模型目录不代表每个模型均支持聊天。","models"),
        ProviderPreset("openrouter","OpenRouter","https://openrouter.ai/api/v1","目录可能公开可读；连接测试额外访问 /key 验证认证。","models","key"),
        ProviderPreset("mimo","小米 MiMo","https://api.xiaomimimo.com/v1","按量 API；Token Plan 地址/Key 另行配置。使用 api-key 鉴权。","models",authHeader="api-key"),
        ProviderPreset("minimax-cn","MiniMax · 中国大陆","https://api.minimax.cn/v1","国内按量平台；不混用国际站或订阅 Key。","models"),
        ProviderPreset("minimax-intl","MiniMax · 国际","https://api.minimax.io/v1","国际按量平台 Key，不混用国内站 Key。","models"),
        ProviderPreset("custom","自定义 OpenAI 兼容","","仅 HTTPS；标准 Bearer 鉴权。可尝试 /models，不支持时请手填。","models")
    )
    fun get(id:String)=all.firstOrNull {it.id==id} ?: all.last()
}
data class ModelConfig(val id:String,val name:String,val providerId:String,val baseUrl:String,val modelId:String,val contextTokens:Int=16384,val outputTokens:Int=2048,val memoryOutputTokens:Int=0) {
    /** Missing/zero is the legacy contract: keep that saved configuration's original output. */
    fun memoryOutputLimit()=if(memoryOutputTokens==0)outputTokens else memoryOutputTokens
}
object ConfigRules {
    fun urlError(value:String):String? = try {
        val u=URI(value)
        when {
            u.scheme!="https" -> "Base URL 必须使用 HTTPS。"
            u.host.isNullOrBlank() || u.port==0 || u.port>65535 -> "请输入有效的服务地址。"
            u.rawUserInfo!=null || u.rawQuery!=null || u.rawFragment!=null -> "地址不能包含账号、密钥、查询参数或片段。"
            u.path.orEmpty().contains("..") || value.any {it.isWhitespace()} || value.contains('{') -> "请填写完整的服务根地址。"
            else -> null
        }
    } catch(_:Exception) {"请输入有效的 HTTPS 地址。"}
    fun validate(config:ModelConfig,key:String):String? = validate(config.name,config.baseUrl,config.modelId,key)
        ?: ModelTokenPolicy.error(config)
    fun validate(name:String,base:String,model:String,key:String):String? = when {
        name.isBlank() -> "请填写服务配置名称。"
        urlError(base)!=null -> urlError(base)
        key.isBlank() -> "请填写 API Key。"
        key.any {it.isWhitespace() || it.code<32} -> "API Key 不能含空格或换行。"
        model.isBlank() -> "请填写或选择模型 ID。"
        else -> null
    }
}
enum class ConfigError(val messageText:String) {
    INVALID("请检查服务地址和 API Key。"), UNAUTHORIZED("认证失败（401），请检查当前地域的 API Key。"), FORBIDDEN("访问被拒绝（403），请检查权限。"),
    RATE_LIMIT("请求受限（429），请稍后手动重试。"), TIMEOUT("连接超时，请稍后重试。"), TLS("证书校验失败，请检查 HTTPS 服务。"),
    REDIRECT("服务返回重定向，已停止，未向新地址转发密钥。"), HTTP("服务返回错误，请检查兼容接口地址。"),
    FORMAT("返回格式不兼容；可手填模型 ID。"), NETWORK("网络连接失败，请检查地址和网络。"),
    UNSUPPORTED("此预设尚未适配无生成的认证/目录测试。可保存并手填模型 ID；连接尚未验证。")
}
sealed interface ConfigResult {
    data class Success(val modelIds:List<String> = emptyList()):ConfigResult
    data class Failure(val error:ConfigError,val httpStatus:Int?=null):ConfigResult
}
interface ModelDirectoryClient { suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult }
