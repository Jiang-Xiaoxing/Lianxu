package app.lianxu.independent

/** Enable provider extensions only for the exact documented endpoint/model family. */
internal object StructuredResponsePolicy {
    private fun isSupportedMiMo(config:ModelConfig)=config.baseUrl.trimEnd('/')=="https://api.xiaomimimo.com/v1"&&
        config.modelId in setOf("mimo-v2.6-flash","mimo-v2.6-pro","mimo-v2.6-pro-ultraspeed")

    fun supportsJsonObject(config:ModelConfig)=isSupportedMiMo(config)

    fun supportsReasoningContinuation(config:ModelConfig):Boolean {
        if(isSupportedMiMo(config)) return true
        val endpoint=config.baseUrl.trimEnd('/')
        if(endpoint !in setOf("https://api.deepseek.com","https://api.deepseek.com/v1")) return false
        return config.modelId in setOf("deepseek-reasoner","deepseek-chat") ||
            ModelTokenPolicy.capability("deepseek",endpoint,config.modelId)?.reasoning==true
    }
    // Tool calling and final JSON are separate protocol stages; joint support is not assumed.
    fun jsonMode(config:ModelConfig,request:TextChatRequest)=request.jsonObject&&request.tools.isEmpty()&&supportsJsonObject(config)
}
