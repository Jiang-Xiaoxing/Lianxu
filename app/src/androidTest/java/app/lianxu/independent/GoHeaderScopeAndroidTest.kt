package app.lianxu.independent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoHeaderScopeAndroidTest {
 private val key="synthetic-key-only"
 private val success="""{"choices":[{"message":{"content":"synthetic answer"},"finish_reason":"stop"}]}"""
 private val official="https://opencode.ai/inference/go/openai/v1"
 private fun config(provider:String="opencode-go",base:String=official)=ModelConfig("scope","Synthetic",provider,base,"synthetic-model")
 private fun request()=TextChatRequest(listOf(WireMessage("user","synthetic request")),128,conversationId=ProviderRequestMetadata.conversation("synthetic-role-a"))
 @Test fun officialCaseTrailingSlashAndExplicit443ReceiveOnlyOpaqueSession()=runBlocking{
  val seen=mutableListOf<ProviderMockConnection>();val client=HttpsTextChatClient(HttpsConnectionFactory{ProviderMockConnection(it,success).also{c->seen+=c}})
  val req=request();for(base in listOf(official,official+"/","HTTPS://OPENCODE.AI:443/inference/go/openai/v1///")){assertTrue(client.complete(config(base=base),key,req) is TextChatResult.Success)}
  assertEquals(3,seen.size);seen.forEach{assertEquals(req.conversationId,it.getRequestProperty("x-opencode-session"));assertEquals("Lianxu (Android; companion-chat)",it.getRequestProperty("User-Agent"));assertFalse(it.instanceFollowRedirects)}
  assertFalse(req.conversationId.contains("synthetic-role-a"));assertFalse(req.conversationId.contains(key));assertEquals(76,req.conversationId.length)
 }
 @Test fun changedGoPresetHostPortAndPathNeverOpenAConnectionAndAreNotRewritten()=runBlocking{
  var opens=0;val client=HttpsTextChatClient(HttpsConnectionFactory{opens++;ProviderMockConnection(it,success)})
  for(base in listOf("https://example.invalid/v1","https://opencode.ai.evil/inference/go/openai/v1","https://evil-opencode.ai/inference/go/openai/v1","https://opencode.ai:8443/inference/go/openai/v1","https://opencode.ai/inference/openai/v1","https://opencode.ai/zen/go/v1","https://opencode.ai/%69nference/go/openai/v1")){
   val c=config(base=base);val result=client.complete(c,key,request()) as TextChatResult.Failure
   assertEquals(base,ChatFailure.GO_ENDPOINT_UNVERIFIED,result.reason);assertTrue(result.reason.display.contains("未发送"));assertEquals(base,c.baseUrl);assertTrue(ProviderRequestMetadata.headers(c,request()).isEmpty())
  };assertEquals(0,opens)
 }
 @Test fun userinfoQueryFragmentAndCleartextAreRejectedBeforeNetwork()=runBlocking{
  var opens=0;val client=HttpsTextChatClient(HttpsConnectionFactory{opens++;ProviderMockConnection(it,success)})
  for(base in listOf("https://u@opencode.ai/inference/go/openai/v1","https://opencode.ai@evil.invalid/inference/go/openai/v1",official+"?x=y",official+"#x","http://opencode.ai/inference/go/openai/v1")){
   assertTrue(client.complete(config(base=base),key,request()) is TextChatResult.Failure);assertTrue(ProviderRequestMetadata.headers(config(base=base),request()).isEmpty())
  };assertEquals(0,opens)
 }
 @Test fun unrelatedAndSimilarCustomTargetsKeepGenericRequestsWithoutSessionHeaders()=runBlocking{
  val seen=mutableListOf<ProviderMockConnection>();val client=HttpsTextChatClient(HttpsConnectionFactory{ProviderMockConnection(it,success).also{c->seen+=c}})
  for(base in listOf("https://example.invalid/v1","https://opencode.ai.evil/inference/go/openai/v1","https://opencode.ai:8443/inference/go/openai/v1","https://opencode.ai/inference/openai/v1"))assertTrue(client.complete(config("custom",base),key,request()) is TextChatResult.Success)
  assertEquals(4,seen.size);seen.forEach{assertNull(it.getRequestProperty("x-opencode-session"));assertNull(it.getRequestProperty("User-Agent"));assertEquals("Bearer $key",it.getRequestProperty("Authorization"));assertFalse(it.instanceFollowRedirects)}
 }
 @Test fun officialGoRedirectStopsAtFirstCertificateVerifiedLoopbackResponse()=runBlocking{
  TextTlsMock(302,"synthetic redirect").use{s->
   val targets=mutableListOf<java.net.URL>();val client=s.routedProviderClient{targets+=it};val req=request()
   val result=client.complete(config(),key,req) as TextChatResult.Failure
   assertEquals(ChatFailure.REDIRECT,result.reason);assertEquals(1,s.calls.get());assertEquals(1,targets.size);assertEquals(official+"/chat/completions",targets.single().toString())
   assertTrue(s.headers.contains("x-opencode-session: "+req.conversationId,true));assertTrue(s.headers.contains("User-Agent: Lianxu (Android; companion-chat)",true))
   // The server advertises a foreign Location. REDIRECT plus one request proves the client did not follow it.
   assertFalse(s.headers.contains("never-follow"))
  }
 }
}
