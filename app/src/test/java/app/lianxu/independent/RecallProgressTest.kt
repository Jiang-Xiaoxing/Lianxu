package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

class RecallProgressTest {
 @Test fun duplicateCallIsLocalAndDifferentCallCanStillRun(){
  val p=RecallProgress();assertTrue(p.begin("tool:q"));assertFalse(p.begin("tool:q"))
  assertEquals(true,p.blockedResult()["query_duplicate"]);assertNotEquals(true,p.blockedResult()["recall_stopped"])
  assertNull(p.stopped);assertTrue(p.begin("tool:other"));assertTrue(p.begin("other-tool:q"))
 }
 @Test fun emptyDuplicateResultsAndOrdinaryErrorsAllowAnotherDirection(){
  for(r in listOf(mapOf("success" to true,"results" to emptyList<String>()),mapOf("success" to true,"visible_duplicates" to 2),mapOf("success" to false,"error" to "invalid_arguments"))){
   val p=RecallProgress();assertTrue(p.begin("first"));p.accept(r);assertNull(p.stopped);assertTrue(p.begin("other"))
  }
 }
 @Test fun safetyStopCannotBeBypassedByAnotherQueryOrTool(){
  val p=RecallProgress();p.accept(mapOf("success" to true,"recall_stopped" to true,"status" to "quota_exhausted"))
  assertFalse(p.begin("new-tool:new-query"));assertEquals(true,p.blockedResult()["recall_stopped"])
 }
 @Test fun explicitPermissionAndContextFailuresRemainTerminal(){
  for(code in listOf("TOOLS_DISABLED","SOURCE_CHANGED","PERMISSION_DENIED","CONTEXT_CAPACITY","CANCELLED")){
   val p=RecallProgress();p.accept(mapOf("success" to false,"error" to code));assertEquals(code,p.stopped);assertFalse(p.begin("another"))
  }
 }
 @Test fun adaptiveIgnoresLegacyUserCapsButKeepsContextBound()=runBlocking {
  var saved:String?=null;val g=CompanionRecallBudget({CompanionRecallSettings(1,256,128)},{saved},{saved=it},adaptive=true)
  repeat(4){val actor="$it";val prepared=g.prepare(actor,emptyList(),buildAgentRecallToolDefinitions(),32000,2000);assertTrue(prepared.tools.isNotEmpty());val result=g.read(actor){mapOf("success" to true,"messages" to listOf(mapOf("message_id" to "m$it","source_revision" to "v","content" to "new fact $it")))};assertNotEquals(true,result["recall_stopped"])}
  try{g.prepare("full",listOf(mapOf("role" to "user","content" to "x".repeat(200000))),buildAgentRecallToolDefinitions(),4096,2048);fail("actual context must be reported")}catch(_:RecallContextCapacity){}
 }
}
