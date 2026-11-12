package app.lianxu.independent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** In-memory HTTPS transport: runs the real encoder/parser without DNS, sockets, credentials or billable requests. */
internal class ProviderMockConnection(url:URL,private val response:String,private val mime:String="application/json",private val code:Int=200):HttpsURLConnection(url){
 val sent=ByteArrayOutputStream()
 override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
 override fun getCipherSuite()="synthetic"
 override fun getLocalCertificates():Array<Certificate>?=null
 override fun getServerCertificates():Array<Certificate> = emptyArray()
 override fun getPeerPrincipal():Principal?=null
 override fun getLocalPrincipal():Principal?=null
 override fun getOutputStream()=sent
 override fun getInputStream()=ByteArrayInputStream(response.toByteArray())
 override fun getResponseCode()=code
 override fun getHeaderField(name:String?)=if(name.equals("Content-Type",true))mime else null
}

@RunWith(AndroidJUnit4::class)
class ProviderExpansionAndroidTest {
 private val key="synthetic-provider-key-only"
 private val success="""{"choices":[{"message":{"content":"synthetic answer"},"finish_reason":"stop"}]}"""
 private fun config(id:String,model:String="synthetic-model")=ProviderPresets.get(id).let{ModelConfig("synthetic","Synthetic",id,it.base,model)}
 private fun request()=TextChatRequest(listOf(WireMessage("user","synthetic request")),128)
 private fun definitions()=listOf("agent_history_search","agent_memory_read").map{mapOf<String,Any>("type" to "function","function" to mapOf("name" to it,"parameters" to mapOf("type" to "object")))}
 private fun event(delta:JSONObject=JSONObject(),finish:String?=null)= "data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish?:JSONObject.NULL))).toString()+"\n\n"
 private fun fragment(index:Int,id:String?=null,name:String?=null,args:String?=null):JSONObject=JSONObject().put("index",index).apply{
  if(id!=null)put("id",id).put("type","function")
  put("function",JSONObject().apply{if(name!=null)put("name",name);if(args!=null)put("arguments",args)})
 }
 private fun delta(vararg p:JSONObject)=JSONObject().put("tool_calls",JSONArray(p.toList()))
 private fun toolStream(name:String="agent_history_search",args:String="{\"query\":\"synthetic\"}",done:Boolean=true)=event(delta(fragment(0,"call_a",name,args)))+event(finish="tool_calls")+(if(done)"data: [DONE]\n\n" else "")
 @Test fun newChatPresetsSendExactNormalizedEndpointAndBearer()=runBlocking{
  for(id in listOf("gemini","tokenhub-cn","tokenhub-intl","qianfan","stepfun-cn","stepfun-intl","moonshot-cn","opencode-go")){
   lateinit var connection:ProviderMockConnection
   val c=config(id).copy(baseUrl=config(id).baseUrl+"/")
   val client=HttpsTextChatClient(HttpsConnectionFactory{url->ProviderMockConnection(url,success).also{connection=it}})
   assertTrue(id,client.complete(c,key,request()) is TextChatResult.Success)
   assertEquals(config(id).baseUrl+"/chat/completions",connection.url.toString());assertEquals("Bearer $key",connection.getRequestProperty("Authorization"))
   val sent=JSONObject(connection.sent.toString("UTF-8"));assertEquals("synthetic-model",sent.getString("model"));assertEquals("synthetic request",sent.getJSONArray("messages").getJSONObject(0).getString("content"))
  }
 }
 @Test fun documentedTextEmbeddingPresetsUseNumericArrayAndNeverForceDimensions()=runBlocking{
  val samples=mapOf("gemini" to "gemini-embedding-001","tokenhub-cn" to "kinfra-text-embedding-0.6b","tokenhub-intl" to "kinfra-text-embedding-4b","qianfan" to "embedding-v1","zhipu-cn" to "embedding-3","silicon-cn" to "Qwen/Qwen3-Embedding-4B")
  for((id,model) in samples){lateinit var connection:ProviderMockConnection
   val client=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{url->ProviderMockConnection(url,"""{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""").also{connection=it}})
   val c=config(id,model);val result=client.embed(ModelCredential(c,key,"synthetic"),listOf("a","b"),1000)
   assertEquals(c.baseUrl+"/embeddings",connection.url.toString());assertEquals("Bearer $key",connection.getRequestProperty("Authorization"));val body=JSONObject(connection.sent.toString("UTF-8"));assertFalse(body.has("dimensions"));assertEquals(2,body.getJSONArray("input").length());assertArrayEquals(doubleArrayOf(1.0,0.0),result[0],0.0)
  }
 }
 @Test fun multimodalSpecificProtocolsFailBeforeAnyConnectionOpens()=runBlocking{
  var opens=0;val client=HttpsMemoryEmbeddingClient(HttpsConnectionFactory{opens++;ProviderMockConnection(it,"{}")})
  for(c in listOf(config("ark-cn","doubao-embedding-vision-251215"),config("tokenhub-cn","kinfra-vl-embedding-2b"),config("bailian-cn","qwen3-vl-embedding"),config("ark-cn","doubao-embedding-vision-251215").copy(providerId="custom"))){try{client.embed(ModelCredential(c,key,"s"),listOf("synthetic"),1000);fail()}catch(_:IllegalArgumentException){}}
  assertEquals(0,opens)
 }
 @Test fun goHeadersStayStableOnlyAtTheVerifiedTargetAndEditedConfigIsPreserved()=runBlocking{
  val seen=mutableListOf<ProviderMockConnection>();val client=HttpsTextChatClient(HttpsConnectionFactory{url->ProviderMockConnection(url,success).also{seen+=it}})
  val c=config("opencode-go");val a=request().copy(conversationId=ProviderRequestMetadata.conversation("role-a"))
  client.complete(c,key,a);client.complete(c,key,a.copy(messages=listOf(WireMessage("user","second"))));client.complete(c,key,request().copy(conversationId=ProviderRequestMetadata.conversation("role-b")))
  assertEquals(seen[0].getRequestProperty("x-opencode-session"),seen[1].getRequestProperty("x-opencode-session"));assertNotEquals(seen[0].getRequestProperty("x-opencode-session"),seen[2].getRequestProperty("x-opencode-session"))
  val original="https://example.invalid/user-original-route";val edited=c.copy(baseUrl=original);val result=client.complete(edited,key,a)
  assertEquals(ChatFailure.GO_ENDPOINT_UNVERIFIED,(result as TextChatResult.Failure).reason);assertEquals(3,seen.size);assertEquals(original,edited.baseUrl);assertTrue(ProviderRequestMetadata.headers(edited,a).isEmpty())
 }
 @Test fun goStreamAssemblesInterleavedToolFragmentsWithoutEmittingArgumentsOrReasoning()=runBlocking{
  val sse=event(JSONObject().put("reasoning_content","hidden synthetic reasoning"))+event(delta(fragment(1,"call_b","agent_memory_","{\"query\":"),fragment(0,"call_a","agent_history_","{\"query\":")))+event(delta(fragment(0,name="search",args="\"garden\"}"),fragment(1,name="read",args="\"tea\"}")))+event(finish="tool_calls")+"data: [DONE]\n\n"
  val emitted=StringBuilder();val client=HttpsTextChatClient(HttpsConnectionFactory{ProviderMockConnection(it,sse,"text/event-stream")})
  val result=client.stream(config("opencode-go"),key,request().copy(tools=definitions())){emitted.append(it)} as TextChatResult.Success
  assertEquals(listOf("call_a","call_b"),result.toolCalls.map{it.id});assertEquals("agent_history_search",result.toolCalls[0].name);assertEquals("garden",JSONObject(result.toolCalls[0].arguments).getString("query"));assertEquals("",emitted.toString());assertFalse(result.text.contains("reasoning"))
 }
 @Test fun malformedDisabledTruncatedAndOversizedStreamCallsNeverBecomeExecutableResults()=runBlocking{
  val cases=listOf(Triple(toolStream(done=false),definitions(),ChatFailure.TRUNCATED),Triple(toolStream(),emptyList(),ChatFailure.TOOLS_UNAVAILABLE),Triple(toolStream("unknown_tool"),definitions(),ChatFailure.FORMAT),Triple(toolStream(args="{"),definitions(),ChatFailure.FORMAT),Triple(toolStream(args="x".repeat(16001)),definitions(),ChatFailure.STREAM_LIMIT),Triple(event(delta(fragment(8,"ninth","agent_memory_read","{}")))+event(finish="tool_calls")+"data: [DONE]\n\n",definitions(),ChatFailure.STREAM_PROTOCOL),Triple(event(finish="length")+"data: [DONE]\n\n",definitions(),ChatFailure.TRUNCATED))
  for((sse,tools,expected) in cases){val client=HttpsTextChatClient(HttpsConnectionFactory{ProviderMockConnection(it,sse,"text/event-stream")});val result=client.stream(config("opencode-go"),key,request().copy(tools=tools)){};assertEquals("SSE case tools=${tools.size}, characters=${sse.length}",expected,(result as TextChatResult.Failure).reason)}
 }
 @Test fun goToolResponseAndNextAnswerKeepSessionAcrossTheFullMockProtocol()=runBlocking{
  val seen=mutableListOf<ProviderMockConnection>();var count=0;val final=event(JSONObject().put("content","synthetic final"))+event(finish="stop")+"data: [DONE]\n\n"
  val client=HttpsTextChatClient(HttpsConnectionFactory{url->ProviderMockConnection(url,if(count++==0)toolStream() else final,"text/event-stream").also{seen+=it}})
  val first=request().copy(tools=definitions(),conversationId=ProviderRequestMetadata.conversation("one-role"));val a=client.stream(config("opencode-go"),key,first){} as TextChatResult.Success
  val next=first.copy(messages=first.messages+WireMessage("assistant",a.text,a.toolCalls)+WireMessage("tool","{\"results\":[]}",toolCallId=a.toolCalls.single().id,name=a.toolCalls.single().name));val emitted=StringBuilder();val b=client.stream(config("opencode-go"),key,next){emitted.append(it)} as TextChatResult.Success
  assertEquals("synthetic final",b.text);assertEquals(b.text,emitted.toString());assertTrue(b.toolCalls.isEmpty());assertEquals(seen[0].getRequestProperty("x-opencode-session"),seen[1].getRequestProperty("x-opencode-session"));val wire=JSONObject(seen[1].sent.toString("UTF-8")).getJSONArray("messages");assertEquals("call_a",wire.getJSONObject(2).getString("tool_call_id"))
 }
 @Test fun oldEncryptedConfigurationRoundTripsWithoutChangingBaseOrKey(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="providers-preserve-${System.nanoTime()}";val vault=ModelVault(context,namespace)
  val original=ModelConfig("old","Saved original","moonshot","https://example.invalid/my-original/v1","custom-model",16384,3333)
  try{vault.save(original,key);val reopened=ModelVault(context,namespace)
   assertEquals(original,reopened.list().single());assertEquals(key,reopened.secret(original.id));assertEquals("https://api.moonshot.ai/v1",ProviderPresets.get("moonshot").base)
  }finally{vault.delete(original.id)}
 }
}
