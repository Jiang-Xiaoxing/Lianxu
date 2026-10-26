package app.lianxu.independent

import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class DiagnosticTraceState(val trace:String,val span:String,val parent:String?,val purpose:String?)
internal class DiagnosticTrace(private val value:DiagnosticTraceState):ThreadContextElement<DiagnosticTraceState?>,AbstractCoroutineContextElement(Key){
    companion object Key:CoroutineContext.Key<DiagnosticTrace>
    override fun updateThreadContext(context:CoroutineContext):DiagnosticTraceState?=Diagnostics.traceState.get().also{Diagnostics.traceState.set(value)}
    override fun restoreThreadContext(context:CoroutineContext,oldState:DiagnosticTraceState?){Diagnostics.traceState.set(oldState)}
}

/** Best-effort local diagnostics. Logging must never change a business result or call a model. */
internal object Diagnostics {
    internal val traceState=ThreadLocal<DiagnosticTraceState?>()
    @Volatile private var journal:DiagnosticJournal?=null
    private var identity=""
    /** Invoke on Dispatchers.IO during application setup; directory is app-private. */
    @Synchronized fun configure(directory:File,version:String){
        val key=directory.absolutePath
        if(journal!=null&&identity==key)return
        journal?.close();journal=DiagnosticJournal(directory,version);identity=key
        event("diagnostic.session",mapOf("retention_days" to 7,"segments" to 5,"segment_bytes" to 2*1024*1024,"queue_capacity" to 128,"queue_bytes" to 2*1024*1024))
    }
    fun registerSecret(secret:String){journal?.redactor?.register(secret)}
    fun traceId():String=traceState.get()?.trace?:UUID.randomUUID().toString()
    fun event(name:String,data:Map<String,Any?> = emptyMap(),trace:String?=null,elapsed:Long?=null,secrets:List<String> = emptyList()){
        val state=traceState.get()
        runCatching{journal?.record(name,trace?:state?.trace?:UUID.randomUUID().toString(),data+mapOf("span" to state?.span,"parent_span" to state?.parent,"purpose" to state?.purpose),elapsed,secrets)}
    }
    fun failure(name:String,error:Throwable,data:Map<String,Any?> = emptyMap(),trace:String?=null,secrets:List<String> = emptyList()){
        event(name,data+mapOf("error_type" to error.javaClass.name,"error" to error.message,"stack" to error.stackTraceToString()),trace,secrets=secrets)
    }
    fun context(purpose:String,trace:String=UUID.randomUUID().toString()):DiagnosticTrace {
        val old=traceState.get();return DiagnosticTrace(DiagnosticTraceState(old?.trace?:trace,UUID.randomUUID().toString(),old?.span,purpose))
    }
    suspend fun <T> span(name:String,data:Map<String,Any?> = emptyMap(),block:suspend()->T):T {
        val old=traceState.get();val state=DiagnosticTraceState(old?.trace?:UUID.randomUUID().toString(),UUID.randomUUID().toString(),old?.span,old?.purpose?:name.substringBefore('.'))
        return withContext(DiagnosticTrace(state)){
            val start=System.nanoTime();event("$name.start",data)
            try{block().also{event("$name.complete",data,elapsed=(System.nanoTime()-start)/1_000_000)}}
            catch(e:CancellationException){event("$name.cancelled",data,elapsed=(System.nanoTime()-start)/1_000_000);throw e}
            catch(e:Exception){failure("$name.failed",e,data);throw e}
        }
    }
    suspend fun export(detailed:Boolean):ByteArray=withContext(Dispatchers.IO){
        val active=journal?:error("诊断记录尚未初始化，请返回页面后重试")
        event("diagnostic.export",mapOf("detailed" to detailed));active.export(detailed)
    }
    internal fun installForTest(value:DiagnosticJournal?){journal=value;identity=""}
}

/** Covers mocked and HTTPS clients alike; stream chunks are aggregated, not individually enqueued. */
internal class LoggedTextChatClient(private val delegate:TextChatClient):TextChatClient {
    private fun request(config:ModelConfig,key:String,value:TextChatRequest,id:String,stream:Boolean){
        Diagnostics.registerSecret(key)
        Diagnostics.event("network.request",mapOf("request_id" to id,"stream" to stream,"model" to config.modelId,"provider" to config.providerId,"endpoint" to config.baseUrl,"context_limit" to config.contextTokens,"output_limit" to value.outputLimit,"timeout_ms" to value.timeoutMillis,"messages" to value.messages.map{mapOf("role" to it.role,"content" to it.content,"tool_calls" to it.toolCalls.map{c->mapOf("id" to c.id,"name" to c.name,"arguments" to c.arguments)})},"tools" to value.tools),secrets=listOf(key))
    }
    private fun response(id:String,result:TextChatResult,start:Long,key:String,extra:Map<String,Any?> = emptyMap()){
        val value=when(result){
            is TextChatResult.Success->mapOf("status" to "success","text" to result.text,"tool_calls" to result.toolCalls.map{mapOf("id" to it.id,"name" to it.name,"arguments" to it.arguments)})
            is TextChatResult.Failure->mapOf("status" to "failure","reason" to result.reason.name,"http_status" to result.httpStatus)
        }
        Diagnostics.event("network.response",value+extra+mapOf("request_id" to id),elapsed=(System.nanoTime()-start)/1_000_000,secrets=listOf(key))
    }
    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=withContext(Diagnostics.context(Diagnostics.traceState.get()?.purpose?:"network")){
        val id=UUID.randomUUID().toString();val start=System.nanoTime();request(config,key,request,id,false)
        return@withContext try{delegate.complete(config,key,request).also{response(id,it,start,key)}}
        catch(e:CancellationException){Diagnostics.event("network.cancelled",mapOf("request_id" to id));throw e}
        catch(e:Exception){Diagnostics.failure("network.failed",e,mapOf("request_id" to id),secrets=listOf(key));throw e}
    }
    override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=withContext(Diagnostics.context(Diagnostics.traceState.get()?.purpose?:"network")){
        val id=UUID.randomUUID().toString();val start=System.nanoTime();request(config,key,request,id,true)
        var chunks=0;var chars=0;var omitted=false;val text=StringBuilder()
        return@withContext try{
            delegate.stream(config,key,request){chunk->chunks++;chars+=chunk.length;if(!omitted){if(text.length+chunk.length<=32768)text.append(chunk)else{omitted=true;text.clear()}};onChunk(chunk)}
                .also{result->response(id,result,start,key,mapOf("chunks" to chunks,"stream_chars" to chars,"stream_text" to if(result is TextChatResult.Success&&!omitted)text.toString() else "[流式正文已省略]","stream_text_omitted" to (omitted||result !is TextChatResult.Success),"stream_omitted_reason" to if(omitted)"超过正文限额" else if(result !is TextChatResult.Success)"响应未完整成功" else ""))}
        }catch(e:CancellationException){Diagnostics.event("network.cancelled",mapOf("request_id" to id,"chunks" to chunks,"stream_chars" to chars));throw e}
        catch(e:Exception){Diagnostics.failure("network.failed",e,mapOf("request_id" to id,"chunks" to chunks,"stream_chars" to chars),secrets=listOf(key));throw e}
    }
}

internal class LoggedModelDirectoryClient(private val delegate:ModelDirectoryClient):ModelDirectoryClient {
    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult=withContext(Diagnostics.context("model")){
        Diagnostics.registerSecret(key)
        Diagnostics.event("model.directory.start",mapOf("provider" to config.providerId,"endpoint" to config.baseUrl,"model" to config.modelId,"fetch_models" to fetchModels),secrets=listOf(key))
        try{delegate.request(config,key,fetchModels).also{result->Diagnostics.event("model.directory.result",when(result){is ConfigResult.Success->mapOf("status" to "success","models" to result.modelIds);is ConfigResult.Failure->mapOf("status" to "failure","reason" to result.error.name,"http_status" to result.httpStatus)},secrets=listOf(key))}}
        catch(e:CancellationException){Diagnostics.event("model.directory.cancelled");throw e}
        catch(e:Exception){Diagnostics.failure("model.directory.failed",e,secrets=listOf(key));throw e}
    }
}
