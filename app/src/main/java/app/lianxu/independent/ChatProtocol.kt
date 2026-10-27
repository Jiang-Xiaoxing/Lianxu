package app.lianxu.independent

/** Wire order is supplied by the confirmed prompt assembler, never inferred by the HTTP client. */
data class ChatToolCall(val id:String,val name:String,val arguments:String)
data class WireMessage(val role:String,val content:String,val toolCalls:List<ChatToolCall> = emptyList(),val toolCallId:String="",val name:String="",val memoryIds:List<String> = emptyList(),val sourceMessageId:String="",val capabilityRange:IntRange?=null,val turnContext:Boolean=false) {
    init {require(role in setOf("system","user","assistant","tool"));require(content.isNotBlank()||(role=="assistant"&&toolCalls.isNotEmpty()));require(role!="tool"||toolCallId.isNotBlank())}
}
data class TextChatRequest(val messages:List<WireMessage>,val outputLimit:Int=2048,val timeoutMillis:Int=45000,val tools:List<Map<String,Any>> = emptyList()) {
    init {require(messages.isNotEmpty());require(outputLimit in 1..ModelTokenPolicy.MAX_OUTPUT);require(timeoutMillis in 1..300000)}
}
enum class ChatFailure(val display:String) {
    MISSING_CONFIG("请先在设置的大模型配置中选择聊天模型。"),
    INVALID_CONFIG("模型配置不完整，请检查服务地址、模型和密钥。"),
    UNAUTHORIZED("认证失败，请检查服务商、地域和 API Key。"),
    FORBIDDEN("服务拒绝访问，请检查模型权限。"),
    RATE_LIMIT("请求受限，请稍后手动重试。"),
    TIMEOUT("回复超时，可手动重试。"),
    TLS("HTTPS 证书校验失败。"),REDIRECT("服务返回重定向，已停止且没有向新地址转发密钥。"),
    NETWORK("网络连接失败，可手动重试。"),SERVER("服务暂时不可用，可稍后重试。"),
    REJECTED("服务未接受请求，请检查模型 ID 和兼容接口。"),
    FORMAT("服务没有返回可用的文本回复。"),TRUNCATED("回复未完整生成，原消息已保留。请检查输出预留或模型后重试。"),
    UNSUPPORTED_TOOLS("模型明确不支持工具调用。"),
    TOOLS_UNAVAILABLE("服务请求了尚未启用的工具，本次没有执行任何工具。"),
    INTERRUPTED("上次请求因应用退出而中断，没有自动重发。"),
    SOURCE_CHANGED("对话来源已变化，本次回复没有写入。"),
    CONTEXT_NEEDS_MEMORY("近期完整轮数已超过设置，请先完成总结记忆；原话没有删除。"),
    MEMORY_CAPACITY("记忆模型装不下完整总结记忆批次。请选择容量更大的记忆模型，或在记忆规则中调整每批轮数；本批没有缩小或推进。"),
    CONTEXT_CAPACITY("模型装不下完整上下文。请选择更大容量模型，或在记忆规则中主动调整近期轮数与相关条数；未自动减少数量或截断核心。"),
    MISSING_MEMORY_MODEL("原话需要总结记忆，请先在统一设置选择记忆模型；原消息已保留。"),
    MEMORY_FAILED("总结记忆尚未成功，未截断原话。请到设置的记忆与关系查看并重试。"),
    CORE_CAPACITY("固定提示与核心记忆超出容量。请到记忆与关系核对核心条目并选择改为普通，或选择更大容量模型；未截断人工核心。调整维护目标不会增加模型容量。"),
    PROMPT_UNCONFIRMED("提示词运行规格尚未确认，本阶段不能发送。")
}
sealed interface TextChatResult {
    data class Success(val text:String,val toolCalls:List<ChatToolCall> = emptyList()):TextChatResult
    data class Failure(val reason:ChatFailure,val httpStatus:Int?=null):TextChatResult
}
interface TextChatClient {
    suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult
    /** Mocks and non-stream transports can supply one complete chunk; no second request is made. */
    suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult {
        val result=complete(config,key,request);if(result is TextChatResult.Success)onChunk(result.text);return result
    }
}
enum class ModelPurpose(val key:String,val title:String) {
    EMBEDDING("embedding","记忆向量（可选）"),CHAT("chat","聊天"),MEMORY("memory","总结记忆"),AUX_VISION("aux-vision","辅助视觉"),RELATIONSHIP("relationship","关系评估")
}
