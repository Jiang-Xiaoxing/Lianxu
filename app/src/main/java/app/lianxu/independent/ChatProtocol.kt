package app.lianxu.independent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Wire order is supplied by the confirmed prompt assembler, never inferred by the HTTP client. */
data class ChatToolCall(val id:String,val name:String,val arguments:String)
/** Ephemeral provider protocol state. Never dialogue, memory, diagnostics or backup. */
class ProviderContinuation internal constructor(@Transient private val reasoning:String) {
    internal val tokenCost get()=ConservativeTokens.tokens(reasoning)
    internal fun wireValue()=reasoning
    override fun toString()="ProviderContinuation([omitted])"
}
data class WireMessage(val role:String,val content:String,val toolCalls:List<ChatToolCall> = emptyList(),val toolCallId:String="",val name:String="",val memoryIds:List<String> = emptyList(),val sourceMessageId:String="",
    /** Length of the program-added time head in front of a history message; the original text starts after it. */
    val sourcePrefix:Int=0,val capabilityRange:IntRange?=null,val turnContext:Boolean=false,@Transient val continuation:ProviderContinuation?=null) {
    init {require(role in setOf("system","user","assistant","tool"));require(content.isNotBlank()||(role=="assistant"&&toolCalls.isNotEmpty()));require(role!="tool"||toolCallId.isNotBlank())}
}
data class TextChatRequest(val messages:List<WireMessage>,val outputLimit:Int=2048,val timeoutMillis:Int=120000,val tools:List<Map<String,Any>> = emptyList(),val conversationId:String=java.util.UUID.randomUUID().toString(),val jsonObject:Boolean=false) {
    init {require(conversationId.matches(Regex("[A-Za-z0-9_-]{1,160}")));require(messages.isNotEmpty());require(outputLimit in 1..ModelTokenPolicy.MAX_OUTPUT);require(timeoutMillis in 1..300000)}
}
enum class ChatFailure(val display:String) {
    MISSING_CONFIG("请先在设置的大模型配置中选择聊天模型。"),
    GO_ENDPOINT_UNVERIFIED(ProviderRequestMetadata.GO_UNVERIFIED_NOTICE),
    INVALID_CONFIG("模型配置不完整，请检查服务地址、模型和密钥。"),
    UNAUTHORIZED("认证失败，请检查服务商、地域和 API Key。"),
    FORBIDDEN("服务拒绝访问，请检查模型权限。"),
    PAYMENT("服务账户余额不足（HTTP 402），请充值或更换服务商后重试。"),
    RATE_LIMIT("请求受限，请稍后手动重试。"),
    TIMEOUT("回复超时，可手动重试。"),
    TLS("HTTPS 证书校验失败。"),REDIRECT("服务返回重定向，已停止且没有向新地址转发密钥。"),
    NETWORK("网络连不上，可手动重试。"),SERVER("服务暂时不可用，可稍后重试。"),
    REJECTED("服务未接受请求，请检查模型 ID 和兼容接口。"),
    FORMAT("服务没有返回可用的文本回复。"),STREAM_LIMIT("服务一次回的内容太长，超过了读取上限，本次没有采用；原消息保留，可手动重试。"),STREAM_PROTOCOL("服务返回的不是可解析的事件流，本次没有采用；原消息保留，可手动重试。"),STRUCTURED_FORMAT("输入理解未通过结构校验，有限重试已结束；原消息已保留，可手动重试。"),
    REPLY_TEXT_INVALID("回复文字缺失、为空、类型错误或超过长度限制，有限重试已结束；原消息已保留，可手动重试。"),
    STORAGE("本地保存失败，本轮未完成。已保存的原消息保留；若输入仍在输入框，请保留后重试。"),TRUNCATED("回复未完整生成，原消息已保留。请检查输出预留或模型后重试。"),
    UNSUPPORTED_TOOLS("模型明确不支持工具调用。"),
    TOOLS_UNAVAILABLE("服务请求了尚未启用的工具，本次没有执行任何工具。"),
    INTERRUPTED("上次请求因应用退出而中断，没有自动重发。"),
    SOURCE_CHANGED("对话来源已变化，本次回复没有写入。"),
    CONTEXT_NEEDS_MEMORY("聊天记录太长，上下文放不下了，请先完成总结记忆；原话没有删除。"),
    MEMORY_CAPACITY("记忆模型装不下完整总结记忆批次。请选择容量更大的记忆模型，或在记忆规则中调整每批轮数；本批没有缩小或推进。"),
    CONTEXT_CAPACITY("模型装不下完整上下文。请选择更大容量模型，或在记忆规则中主动调整近期轮数与相关条数；未自动减少数量或截断核心。"),
    MISSING_MEMORY_MODEL("原话需要总结记忆，请先在统一设置选择记忆模型；原消息已保留。"),
    MEMORY_FAILED("总结记忆尚未成功，未截断原话。请到设置的记忆查看并重试。"),
    MEMORY_PAYMENT("记忆服务余额不足（HTTP 402），本轮没有发出。充值或更换记忆模型后，在聊天里点重试；原话没有删除。"),
    MEMORY_STREAM_LIMIT("记忆服务这次回的内容太长，超过了读取上限，本轮没有发出。原话没有删除，可在聊天里点重试。"),
    MEMORY_STREAM_PROTOCOL("记忆服务返回的不是正常的事件流，本轮没有发出。原话没有删除，可在聊天里点重试。"),
    MEMORY_RETRY("上一批记忆总结没有完成。确认余额和记忆模型后，点这条消息的重试；原话没有删除。"),
    CORE_CAPACITY("固定提示与核心记忆超出容量。请到记忆核对核心条目并选择改为普通，或选择更大容量模型；未截断人工核心。调整维护目标不会增加模型容量。"),
    PROMPT_UNCONFIRMED("提示词运行规格尚未确认，本阶段不能发送。"),
    RESPONSE_BROKEN("服务已经开始回复，但读到一半连接断了，本次没有采用；原消息保留，可手动重试。"),
    UNKNOWN("出了点意外，本轮没有完成；原消息保留，可手动重试。")
}
/** 只有真连不上服务商（找不到地址、连接被拒、没有路由）才叫「网络连不上」；超时和其他错误照原样显示报错。 */
internal fun unreachable(e:Throwable):Boolean {
    var x:Throwable?=e;val seen=mutableSetOf<Throwable>()
    while(x!=null&&seen.add(x)){
        if(x is java.net.UnknownHostException||x is java.net.ConnectException||x is java.net.NoRouteToHostException||x is java.net.PortUnreachableException)return true
        x=x.cause
    }
    return false
}
/** A blocked chat must name the real cause: balance, auth, capacity or a generic summary failure. */
internal fun memoryFailureFor(code:String):ChatFailure=when(code){
    "NO_MEMORY_MODEL"->ChatFailure.MISSING_MEMORY_MODEL
    "CAPACITY","TURN_TOO_LARGE"->ChatFailure.MEMORY_CAPACITY
    "MODEL_PAYMENT"->ChatFailure.MEMORY_PAYMENT
    "MODEL_STREAM_LIMIT"->ChatFailure.MEMORY_STREAM_LIMIT
    "MODEL_STREAM_PROTOCOL"->ChatFailure.MEMORY_STREAM_PROTOCOL
    "MANUAL_RETRY"->ChatFailure.MEMORY_RETRY
    else->ChatFailure.MEMORY_FAILED
}
sealed interface TextChatResult {    data class Success(val text:String,val toolCalls:List<ChatToolCall> = emptyList(),@Transient val continuation:ProviderContinuation?=null,val displayReasoning:String=""):TextChatResult
    /** detail: what the provider actually said (HTTP status plus body) or the low-level error, keys masked. */
    data class Failure(val reason:ChatFailure,val httpStatus:Int?=null,val detail:String=""):TextChatResult
}
/** Native provider fields only; neither event is a completed/committed reply. */
sealed interface TextStreamEvent {
    val text:String
    data class Content(override val text:String):TextStreamEvent
    data class Reasoning(override val text:String):TextStreamEvent
}
interface TextChatClient {
    suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult
    /** Mocks and non-stream transports can supply one complete chunk; no second request is made. */
    suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult {
        val result=complete(config,key,request);if(result is TextChatResult.Success)onChunk(result.text);return result
    }
    /** A complete-only client performs exactly one request, with no synthetic reasoning. */
    suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
        currentCoroutineContext().ensureActive()
        val result=complete(config,key,request)
        currentCoroutineContext().ensureActive()
        if(result is TextChatResult.Success){
            if(result.displayReasoning.isNotEmpty())onEvent(TextStreamEvent.Reasoning(result.displayReasoning))
            currentCoroutineContext().ensureActive()
            if(result.text.isNotEmpty())onEvent(TextStreamEvent.Content(result.text))
        }
        currentCoroutineContext().ensureActive()
        return result
    }
}
enum class ModelPurpose(val key:String,val title:String) {
    EMBEDDING("embedding","记忆向量（可选）"),CHAT("chat","聊天"),MEMORY("memory","总结记忆"),AUX_VISION("aux-vision","辅助视觉")
}
