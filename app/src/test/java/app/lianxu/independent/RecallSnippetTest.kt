package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecallSnippetTest {
 private val gson=Gson()
 private val full="abcdefghijklmnopqrstuvwxyz"
 private fun snippet(body:String=full,start:Int=3,end:Int=9)=mapOf<String,Any>("message_id" to "m1","source_revision" to stableDigest(body),"snippet" to body.substring(start,end),"text_offset" to start,"next_text_offset" to end)
 private fun result(row:Map<String,Any>)=mapOf<String,Any>("success" to true,"results" to listOf(row))
 private fun tool(value:Map<String,Any>)=mapOf<String,Any>("role" to "tool","name" to "agent_history_search","content" to gson.toJson(value))
 @Test fun snippetMetadataIsAnExactVerifiableSourceRange()=runBlocking {
  val text="a".repeat(400)+"needle"+"b".repeat(400)
  val reader=object:AgentRecallReader{
   override suspend fun isAvailable(sessionId:String)=true
   override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int)=emptyList<AgentRecallCandidate>()
   override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int)=listOf(AgentHistoryMessage("m1","user",text,"2026-10-30"))
   override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int)=emptyList<AgentHistoryMessage>()
  }
  val r=AgentRecallToolHandler("a",reader,true).execute("agent_history_search",mapOf("query" to "needle"))
  val row=(r["results"] as List<*>).single() as Map<*,*>;val start=(row["text_offset"] as Number).toInt();val end=(row["next_text_offset"] as Number).toInt()
  assertEquals(stableDigest(text),row["source_revision"]);assertEquals(text.substring(start,end),row["snippet"]);assertTrue(end-start<=260)
 }
 @Test fun duplicateSnippetAcrossRequestsIsRemovedButFullReadIsNot()=runBlocking {
  var saved:String?=null;val g=CompanionRecallBudget({CompanionRecallSettings()},{saved},{saved=it},adaptive=true)
  g.prepare("one",emptyList(),buildAgentRecallToolDefinitions(),32000,2000)
  val first=g.read("one"){result(snippet())};assertEquals(1,(first["results"] as List<*>).size)
  g.prepare("two",listOf(tool(first)),buildAgentRecallToolDefinitions(),32000,2000)
  val repeated=g.read("two"){result(snippet())};assertTrue((repeated["results"] as List<*>).isEmpty());assertNotEquals(true,repeated["recall_stopped"])
  val read=g.read("two"){mapOf("success" to true,"messages" to listOf(mapOf("message_id" to "m1","source_revision" to stableDigest(full),"content" to full,"text_offset" to 0)))}
  assertEquals(full,((read["messages"] as List<*>).single() as Map<*,*>)["content"])
 }
 @Test fun unseenSnippetRangesAndEditedSourcesArePreserved()=runBlocking {
  var saved:String?=null;val g=CompanionRecallBudget({CompanionRecallSettings()},{saved},{saved=it},adaptive=true)
  g.prepare("one",listOf(tool(result(snippet()))),buildAgentRecallToolDefinitions(),32000,2000)
  val overlap=g.read("one"){result(snippet(start=6,end=12))};val row=(overlap["results"] as List<*>).single() as Map<*,*>
  assertEquals("jkl",row["snippet"]);assertEquals(9,(row["text_offset"] as Number).toInt());assertEquals(12,(row["next_text_offset"] as Number).toInt())
  val edited=g.read("one"){result(snippet(body="ABC"+full.substring(3)))};assertEquals(1,(edited["results"] as List<*>).size)
 }
 @Test fun legacyOrUnverifiableSnippetsAreNeverSilentlyDiscarded()=runBlocking {
  var saved:String?=null;val g=CompanionRecallBudget({CompanionRecallSettings()},{saved},{saved=it},adaptive=true)
  val old=snippet()-"source_revision";g.prepare("one",listOf(tool(result(old))),buildAgentRecallToolDefinitions(),32000,2000)
  assertEquals(1,(g.read("one"){result(old)}["results"] as List<*>).size)
  val decorated=snippet()+("snippet" to "…defghi…")
  assertEquals(1,(g.read("one"){result(decorated)}["results"] as List<*>).size)
 }
 @Test fun alreadyVisibleFullBodySuppressesOnlyMatchingSearchPreview()=runBlocking {
  var saved:String?=null;val g=CompanionRecallBudget({CompanionRecallSettings()},{saved},{saved=it},adaptive=true)
  val original=mapOf<String,Any>("role" to "user","content" to full,CompanionRecallBudget.SOURCE_META to mapOf("id" to "m1","revision" to stableDigest(full)))
  g.prepare("one",listOf(original),buildAgentRecallToolDefinitions(),32000,2000)
  assertTrue((g.read("one"){result(snippet())}["results"] as List<*>).isEmpty())
 }
 @Test fun activeReadUsesPhysicalContextInsteadOfLegacyQuotaOrFifteenPercent()=runBlocking {
  var saved:String?=gson.toJson(CompanionRecallUsage(CompanionRecallSettings(1,256,128),rounds=100,tokens=100000))
  val g=CompanionRecallBudget({CompanionRecallSettings()},{saved},{saved=it},adaptive=true)
  val prepared=g.prepare("one",emptyList(),buildAgentRecallToolDefinitions(),32000,1000);assertTrue(prepared.tools.isNotEmpty())
  val body="记".repeat(6000)
  val r=g.read("one"){mapOf("success" to true,"messages" to listOf(mapOf("message_id" to "m1","source_revision" to stableDigest(body),"content" to body)))}
  assertEquals(body,((r["messages"] as List<*>).single() as Map<*,*>)["content"])
  assertFalse(r.containsKey("tokens_left"));assertFalse(r.containsKey("rounds_left"));assertNotEquals(true,r["recall_stopped"])
 }

}
