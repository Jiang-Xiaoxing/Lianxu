package app.lianxu.independent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FilterInputStream
import java.io.BufferedReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Transport never retries or redirects; optional tool definitions are supplied by the caller. */
class HttpsTextChatClient(
    private val factory:HttpsConnectionFactory=HttpsConnectionFactory{it.openConnection() as HttpsURLConnection},
    private val timeoutMillis:Int?=null,
):TextChatClient {
    companion object {private val workers=Executors.newFixedThreadPool(4){r->Thread(r,"lianxu-text-http").apply{isDaemon=true}}}
    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult =
        withTimeoutOrNull((timeoutMillis?:request.timeoutMillis).toLong()){awaitReply(config,key,request,timeoutMillis?:request.timeoutMillis)}?:TextChatResult.Failure(ChatFailure.TIMEOUT)
    override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=coroutineScope{
        val chunks=Channel<String>(Channel.RENDEZVOUS)
        val consumer=launch{for(chunk in chunks)onChunk(chunk)}
        val result=try{withTimeoutOrNull((timeoutMillis?:request.timeoutMillis).toLong()){
            awaitReply(config,key,request,timeoutMillis?:request.timeoutMillis){chunk->runBlocking{chunks.send(chunk)}}
        }?:TextChatResult.Failure(ChatFailure.TIMEOUT)}finally{chunks.close()}
        consumer.join();result
    }
    private suspend fun awaitReply(config:ModelConfig,key:String,request:TextChatRequest,budget:Int,onChunk:((String)->Unit)?=null):TextChatResult = suspendCancellableCoroutine {continuation->
        Diagnostics.registerSecret(key);val diagnosticTrace=Diagnostics.traceId()
        if(ConfigRules.validate(config,key)!=null||!ModelTokenPolicy.acceptsRequest(config,request.outputLimit)){continuation.resume(TextChatResult.Failure(ChatFailure.INVALID_CONFIG));return@suspendCancellableCoroutine}
        val connection=AtomicReference<HttpsURLConnection?>(null)
        val deadline=System.nanoTime()+budget*1_000_000L
        val task=workers.submit {
            var c:HttpsURLConnection?=null
            val result=try {
                if(!continuation.isActive)return@submit
                val active=factory.open(URL(config.baseUrl.trimEnd('/')+"/chat/completions"));c=active;connection.set(active)
                if(!continuation.isActive)return@submit
                val provider=ProviderPresets.get(config.providerId)
                active.instanceFollowRedirects=false;active.connectTimeout=minOf(budget,15000);active.readTimeout=budget;active.requestMethod="POST";active.doOutput=true
                active.setRequestProperty(provider.authHeader,if(provider.authHeader=="Authorization")"Bearer $key" else key)
                active.setRequestProperty("Content-Type","application/json; charset=utf-8");active.setRequestProperty("Accept",if(onChunk==null)"application/json" else "text/event-stream, application/json")
                val messages=JSONArray();request.messages.forEach{m->messages.put(JSONObject().put("role",m.role).put("content",m.content).apply{
                    if(m.toolCalls.isNotEmpty())put("tool_calls",JSONArray().apply{m.toolCalls.forEach{call->put(JSONObject().put("id",call.id).put("type","function").put("function",JSONObject().put("name",call.name).put("arguments",call.arguments)))}})
                    if(m.role=="tool"){put("tool_call_id",m.toolCallId);put("name",m.name)}
                })}
                val body=JSONObject().put("model",config.modelId).put("messages",messages).put("stream",onChunk!=null)
                if(request.tools.isNotEmpty())body.put("tools",JSONArray(request.tools)).put("tool_choice","auto")
                body.put(if(config.providerId in setOf("openai","mimo"))"max_completion_tokens" else "max_tokens",request.outputLimit)
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                Diagnostics.event("http.request.body",mapOf("method" to "POST","endpoint" to config.baseUrl.trimEnd('/')+"/chat/completions","body" to body.toString()),trace=diagnosticTrace,secrets=listOf(key))
                require(bytes.size<=2*1024*1024);active.setFixedLengthStreamingMode(bytes.size)
                active.outputStream.use{it.write(bytes)};bytes.fill(0)
                val status=active.responseCode
                Diagnostics.event("http.response",mapOf("status" to status,"content_type" to active.contentType,"stream" to (onChunk!=null)),trace=diagnosticTrace,secrets=listOf(key))
                val errorBody=if(status !in 200..299)diagnosticErrorBody(active,deadline,diagnosticTrace,key) else ""
                if(status !in 200..299)TextChatResult.Failure(if(request.tools.isNotEmpty()&&status in setOf(400,422)&&explicitToolsUnsupported(errorBody))ChatFailure.UNSUPPORTED_TOOLS else when(status){401->ChatFailure.UNAUTHORIZED;403->ChatFailure.FORBIDDEN;429->ChatFailure.RATE_LIMIT;in 300..399->ChatFailure.REDIRECT;in 500..599->ChatFailure.SERVER;else->ChatFailure.REJECTED},status)
                else if(onChunk!=null&&active.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream",true)){
                    active.inputStream.use{input->
                        val limited=object:FilterInputStream(input){var count=0
                            override fun read():Int{val n=super.read();if(n>=0&&++count>2*1024*1024)throw StreamRejected(ChatFailure.FORMAT);return n}
                            override fun read(b:ByteArray,off:Int,len:Int):Int{val n=`in`.read(b,off,len);if(n>0){count+=n;if(count>2*1024*1024)throw StreamRejected(ChatFailure.FORMAT)};return n}
                        }
                        readSse(limited.bufferedReader(),onChunk){
                            if(!continuation.isActive)throw StreamRejected(ChatFailure.INTERRUPTED)
                            val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)throw SocketTimeoutException();active.readTimeout=minOf(budget,remaining)
                        }
                    }
                }else {
                    val response=active.inputStream.use {input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(output.size()<=2*1024*1024){
                            val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)throw SocketTimeoutException()
                            active.readTimeout=minOf(budget,remaining)
                            if(!continuation.isActive)return@submit
                            val n=input.read(buffer,0,minOf(buffer.size,2*1024*1024+1-output.size()));if(n<0)break;output.write(buffer,0,n)
                        };output.toByteArray()}
                    if(response.size>2*1024*1024)TextChatResult.Failure(ChatFailure.FORMAT) else parse(String(response,Charsets.UTF_8),key,request.tools.map(::toolNameOf).toSet()).also{if(it is TextChatResult.Success)onChunk?.invoke(it.text)}
                }
            }catch(e:SocketTimeoutException){Diagnostics.failure("http.timeout",e,trace=diagnosticTrace,secrets=listOf(key));TextChatResult.Failure(ChatFailure.TIMEOUT)}
            catch(e:StreamRejected){Diagnostics.failure("http.stream_failed",e,mapOf("reason" to e.reason.name),trace=diagnosticTrace,secrets=listOf(key));TextChatResult.Failure(e.reason)}
            catch(e:SSLException){Diagnostics.failure("http.tls_failed",e,trace=diagnosticTrace,secrets=listOf(key));TextChatResult.Failure(ChatFailure.TLS)}
            catch(e:org.json.JSONException){Diagnostics.failure("http.format_failed",e,trace=diagnosticTrace,secrets=listOf(key));TextChatResult.Failure(ChatFailure.FORMAT)}
            catch(e:Exception){Diagnostics.failure("http.network_failed",e,trace=diagnosticTrace,secrets=listOf(key));TextChatResult.Failure(ChatFailure.NETWORK)}
            finally{connection.getAndSet(null)?.disconnect();c?.disconnect()}
            if(continuation.isActive)continuation.resume(result)
        }
        continuation.invokeOnCancellation{task.cancel(true);connection.getAndSet(null)?.let{open->Thread({open.disconnect()},"lianxu-text-close").apply{isDaemon=true}.start()}}
    }
    private fun diagnosticErrorBody(connection:HttpsURLConnection,deadline:Long,trace:String,key:String):String {
        var complete=false
        val bytes=runCatching{connection.errorStream?.use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(2048)
            while(out.size()<16384){val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)break;connection.readTimeout=minOf(2000,remaining);val n=input.read(buffer,0,minOf(buffer.size,16384-out.size()));if(n<0){complete=true;break};out.write(buffer,0,n)}
            out.toByteArray()
        }?:ByteArray(0)}.getOrElse{Diagnostics.failure("http.error_body.unavailable",it,trace=trace,secrets=listOf(key));ByteArray(0)}
        return String(bytes,Charsets.UTF_8).also{Diagnostics.event("http.error_body",mapOf("body" to if(!complete)"[错误响应未完整读取或达到限额，正文已省略]" else it,"limited" to !complete),trace=trace,secrets=listOf(key))}
    }
    private class StreamRejected(val reason:ChatFailure):Exception()
    /** Only choice zero content is emitted; provider reasoning and tool deltas never become facts. */
    private fun readSse(reader:BufferedReader,chunk:(String)->Unit,beforeRead:()->Unit):TextChatResult {
        val data=StringBuilder();var finished=false;var done=false
        fun event(){
            if(data.isEmpty())return
            val raw=data.toString().removeSuffix("\n");data.clear()
            if(done)throw StreamRejected(ChatFailure.FORMAT)
            if(raw=="[DONE]"){if(!finished)throw StreamRejected(ChatFailure.TRUNCATED);done=true;return}
            val obj=JSONObject(raw);if(obj.has("error"))throw StreamRejected(ChatFailure.REJECTED)
            val choices=obj.optJSONArray("choices")?:throw StreamRejected(ChatFailure.FORMAT);if(choices.length()==0)return
            val choice=choices.getJSONObject(0);if(choice.optInt("index",0)!=0)throw StreamRejected(ChatFailure.FORMAT)
            val delta=choice.optJSONObject("delta")?:JSONObject()
            if(delta.optJSONArray("tool_calls")?.length()?.let{it>0}==true||!delta.isNull("function_call"))throw StreamRejected(ChatFailure.TOOLS_UNAVAILABLE)
            val content=delta.opt("content");if(content is String&&content.isNotEmpty()){if(finished)throw StreamRejected(ChatFailure.FORMAT);chunk(content)}
            if(!choice.isNull("finish_reason")){val reason=choice.getString("finish_reason");if(reason !in setOf("stop","end_turn"))throw StreamRejected(if(reason=="length")ChatFailure.TRUNCATED else ChatFailure.FORMAT);finished=true}
        }
        while(true){beforeRead();val line=reader.readLine()?:break
            if(line.isEmpty())event() else if(line.startsWith("data:")){data.append(line.substring(5).removePrefix(" ")).append('\n');if(data.length>256*1024)throw StreamRejected(ChatFailure.FORMAT)}
        }
        event();return if(done&&finished)TextChatResult.Success("stream-complete") else TextChatResult.Failure(ChatFailure.TRUNCATED)
    }
    internal fun explicitToolsUnsupported(raw:String):Boolean = runCatching {
        val error=JSONObject(raw).optJSONObject("error")?:return false
        val parameter=error.optString("param").lowercase()
        val code=error.optString("code").lowercase()
        val message=error.optString("message").lowercase()
        (parameter=="tools"&&code in setOf("unsupported_parameter")) ||
            (Regex("model.{0,100}(?:does not support|doesn’t support|cannot support) (?:the )?(?:tools?|function calling)\\b|(?:tools?|function calling) (?:is|are) not supported (?:by|for|with) (?:this |the )?model").containsMatchIn(message))
    }.getOrDefault(false)
    internal fun parse(raw:String,key:String,allowedTools:Set<String> = emptySet()):TextChatResult {
        val choice=JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?:return TextChatResult.Failure(ChatFailure.FORMAT)
        val message=choice.optJSONObject("message")?:return TextChatResult.Failure(ChatFailure.FORMAT)
        if(!message.isNull("function_call"))return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
        val calls=message.optJSONArray("tool_calls")
        if(calls!=null&&calls.length()>0){
            if(allowedTools.isEmpty())return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
            if(choice.optString("finish_reason")!="tool_calls"||calls.length()>8)return TextChatResult.Failure(ChatFailure.FORMAT)
            val parsed=(0 until calls.length()).map {i->
                val c=calls.getJSONObject(i);val f=c.getJSONObject("function");val id=c.getString("id");val name=f.getString("name");val args=f.getString("arguments")
                if(c.optString("type")!="function"||!id.matches(Regex("[A-Za-z0-9_-]{1,160}"))||name !in allowedTools||args.length>16000||args.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
                JSONObject(args);ChatToolCall(id,name,args)
            }
            if(parsed.map{it.id}.distinct().size!=parsed.size)return TextChatResult.Failure(ChatFailure.FORMAT)
            val content=(message.opt("content") as? String).orEmpty().replace(Regex("(?is)<think>.*?</think>"),"").trim()
            if(content.contains(key)||content.contains("<think>",true)||content.length>100000)return TextChatResult.Failure(ChatFailure.FORMAT)
            return TextChatResult.Success(content,parsed)
        }
        val reason=choice.optString("finish_reason")
        if(reason=="length")return TextChatResult.Failure(ChatFailure.TRUNCATED)
        if(reason !in setOf("stop","end_turn"))return TextChatResult.Failure(ChatFailure.FORMAT)
        // Some compatible providers embed thinking in content. Never show or retain it as dialogue.
        val text=message.opt("content") as? String ?:return TextChatResult.Failure(ChatFailure.FORMAT)
        val visible=text.replace(Regex("(?is)<think>.*?</think>"),"").trim()
        if(visible.contains("<think>",true)||visible.isBlank()||visible.length>100000||visible.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
        return TextChatResult.Success(visible)
    }
}
