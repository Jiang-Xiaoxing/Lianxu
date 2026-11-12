package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.*
class ContactFailuresTest {
 @Test fun config(){assertEquals(ContactFailureKind.MODEL_CONFIG,ContactFailures.classify(CompanionModelFailure(ChatFailure.MISSING_CONFIG)))}
 @Test fun network(){listOf(java.net.UnknownHostException(),java.net.ConnectException(),java.net.NoRouteToHostException(),CompanionModelFailure(ChatFailure.NETWORK)).forEach{assertEquals(ContactFailureKind.NETWORK,ContactFailures.classify(it))}}
 @Test fun timeoutsAndOtherIoErrorsAreNotCalledNetwork(){listOf(java.net.SocketTimeoutException(),java.io.IOException("Connection reset"),CompanionModelFailure(ChatFailure.TIMEOUT),CompanionModelFailure(ChatFailure.RESPONSE_BROKEN),CompanionModelFailure(ChatFailure.TLS)).forEach{assertEquals(it.toString(),ContactFailureKind.OTHER,ContactFailures.classify(it))}}
 @Test fun detailIsTheRawErrorWithKeysMasked(){
  val key="sk-synthetic-contact-123456789"
  val provider=ContactFailures.detail(CompanionModelFailure(ChatFailure.PAYMENT,"HTTP 402 Payment Required\n{\"error\":\"no balance\"}"),listOf(key))
  assertEquals("HTTP 402 Payment Required\n{\"error\":\"no balance\"}",provider)
  assertEquals("IOException: Connection reset",ContactFailures.detail(java.io.IOException("Connection reset")))
  assertEquals("SocketTimeoutException: timeout",ContactFailures.detail(Exception(java.net.SocketTimeoutException("timeout")).cause!!))
  val leaked=ContactFailures.detail(IllegalStateException("bad key $key"),listOf(key))
  assertTrue(leaked,leaked.startsWith("IllegalStateException: bad key"));assertFalse(leaked.contains(key))
  assertEquals("MISSING_CONFIG: "+ChatFailure.MISSING_CONFIG.display,ContactFailures.detail(CompanionModelFailure(ChatFailure.MISSING_CONFIG)))
 }
 @Test fun response(){listOf(ChatFailure.SERVER,ChatFailure.UNAUTHORIZED,ChatFailure.STRUCTURED_FORMAT).forEach{assertEquals(ContactFailureKind.MODEL_RESPONSE,ContactFailures.classify(CompanionModelFailure(it)))}}
 @Test fun wrapped(){assertEquals(ContactFailureKind.NETWORK,ContactFailures.classify(Exception(java.net.UnknownHostException())));assertEquals(ContactFailureKind.OTHER,ContactFailures.classify(Exception(java.io.IOException())))}
 @Test fun other(){assertEquals(ContactFailureKind.OTHER,ContactFailures.classify(IllegalStateException()))}
 @Test fun backupRemappingPreservesOptionalFailureFields(){
   val remap=BackupRemapper("a","b",mapOf("o" to "p"),emptyMap())
   for(extra in listOf("",",\"fail_kind\":\"NETWORK\",\"fail_at\":123")){
     val row=com.google.gson.JsonParser.parseString("{\"id\":\"o\",\"role\":\"a\"$extra}")
     val mapped=remap.json(row).asJsonObject
     assertEquals("p",mapped["id"].asString)
     assertEquals(extra.isNotEmpty(),mapped.has("fail_kind"))
     if(extra.isNotEmpty()){assertEquals("NETWORK",mapped["fail_kind"].asString);assertEquals(123,mapped["fail_at"].asInt)}
   }
 }
}
