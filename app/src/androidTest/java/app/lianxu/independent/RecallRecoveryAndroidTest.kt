package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real MemoryTools + local synthetic DB + scripted model; no provider calls or real credentials. */
class RecallRecoveryAndroidTest {
 private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
 private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var db:String
 private val config=ModelConfig("recovery","Synthetic","custom","https://example.invalid/v1","mock",128000,2048)
 @Before fun setup()=runBlocking{db="recall-recovery-${System.nanoTime()}.db";store=Store(c,db);repo=ProcessingRepository(store);store.add(listOf(Friend("a","Synthetic","Synthetic")),false);repo.saveMemoryRules(MemoryRules(proactiveRecall=true))}
 @After fun close(){store.close();c.deleteDatabase(db)}
 private suspend fun source(text:String):SourceDialogue{val l=ChatLedger(store);val t=l.begin("a",text,config.id);assertTrue(l.finish(t.id,t.attempt,"Synthetic source reply"));return repo.sources("a").last()}
 private suspend fun begin():ChatTurn=ChatLedger(store).begin("a","synthetic current question",config.id)
 private suspend fun runtime(t:ChatTurn,active:()->Boolean={true})=MemoryTools(repo,t,"synthetic current question",repo.references("a","synthetic current question",t.userMessageId).copy(valid=emptyList(),pending=emptyList()),active=active)
 private fun request(body:String="synthetic current question")=TextChatRequest(listOf(WireMessage("system","Synthetic rules"),WireMessage("user",body),WireMessage("system","Synthetic time")))
 private fun call(id:String,name:String,args:String)=ChatToolCall(id,name,args)
 private fun ask(vararg calls:ChatToolCall)=TextChatResult.Success("",calls.toList())
 private fun client(block:suspend(TextChatRequest)->TextChatResult)=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{check(key=="synthetic-only");return block(request)}}
 private fun outputs(r:TextChatRequest)=r.messages.filter{it.role=="tool"}.map{JSONObject(it.content)}
 private fun usage(t:ChatTurn)=store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf("memory-recall-usage:${t.id}")).use{if(it.moveToFirst())it.getString(0) else ""}
 private fun reads(r:TextChatRequest)=r.tools.any{toolNameOf(it) in CompanionRecallBudget.readNames}

 @Test fun emptySearchCanBeFollowedByChangedKeywordHit()=runBlocking{
  source("garden source");val t=begin();var n=0
  val result=runtime(t).run(config,"synthetic-only",request(),client{r->when(n++){
   0->ask(call("empty","agent_history_search","{\"query\":\"zznomatchxx\"}"))
   1->{assertEquals(0,outputs(r).last().getJSONArray("results").length());assertTrue(reads(r));ask(call("hit","agent_history_search","{\"query\":\"garden\"}"))}
   else->{assertTrue(outputs(r).last().getJSONArray("results").length()>0);TextChatResult.Success("final")}
  }})
  assertTrue(result is TextChatResult.Success);assertEquals(3,n)
 }
 @Test fun ordinaryReadFailureCanBeFollowedByAnotherTool()=runBlocking{
  repo.manualSave("a",null,"garden preference",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
  val t=begin();var n=0
  val result=runtime(t).run(config,"synthetic-only",request(),client{r->when(n++){
   0->ask(call("bad","agent_history_read","{\"message_id\":\"zznomatchxx\"}"))
   1->{assertFalse(outputs(r).last().getBoolean("success"));assertTrue(reads(r));ask(call("other","agent_memory_read","{\"query\":\"garden\"}"))}
   else->{assertTrue(outputs(r).last().getJSONArray("results").length()>0);TextChatResult.Success("final")}
  }})
  assertTrue(result is TextChatResult.Success);assertEquals(3,n)
 }
 @Test fun exactDuplicateDoesNotExecuteOrChargeAgainAndOtherMethodWorks()=runBlocking{
  val s=source("garden source");val t=begin();var n=0;var firstUsage=""
  val result=runtime(t).run(config,"synthetic-only",request(),client{r->when(n++){
   0->ask(call("one","agent_history_search","{\"query\":\"garden\",\"limit\":5}"))
   1->{firstUsage=usage(t);ask(call("duplicate","agent_history_search","{\"limit\":5,\"query\":\"garden\"}"))}
   2->{assertTrue(outputs(r).last().getBoolean("query_duplicate"));assertEquals(firstUsage,usage(t));assertTrue(reads(r));ask(call("read","agent_history_read","{\"message_id\":\"${s.user.id}\",\"before\":0,\"after\":0}"))}
   else->{assertEquals("garden source",outputs(r).last().getJSONArray("messages").getJSONObject(0).getString("content"));TextChatResult.Success("final")}
  }})
  assertTrue(result is TextChatResult.Success);assertEquals(4,n)
 }
 @Test fun firstEmptyCallDoesNotBlockSecondCallInSameBatch()=runBlocking{
  source("garden source");val t=begin();var n=0
  val result=runtime(t).run(config,"synthetic-only",request(),client{r->if(n++==0)ask(call("empty","agent_history_search","{\"query\":\"zznomatchxx\"}"),call("hit","agent_history_search","{\"query\":\"garden\"}"))else{
   val rows=outputs(r);assertEquals(2,rows.size);assertEquals(0,rows[0].getJSONArray("results").length());assertTrue(rows[1].getJSONArray("results").length()>0);TextChatResult.Success("final")
  }})
  assertTrue(result is TextChatResult.Success);assertEquals(2,n)
 }
 @Test fun duplicateSearchSnippetDoesNotPreventSubsequentFullOriginalRead()=runBlocking{
  val full="p".repeat(350)+"garden"+"z".repeat(350);val s=source(full);val t=begin();var n=0
  val result=runtime(t).run(config,"synthetic-only",request(),client{r->when(n++){
   0->ask(call("lower","agent_history_search","{\"query\":\"garden\"}"))
   1->{assertTrue(outputs(r).last().getJSONArray("results").getJSONObject(0).has("source_revision"));ask(call("upper","agent_history_search","{\"query\":\"GARDEN\"}"))}
   2->{assertEquals(0,outputs(r).last().getJSONArray("results").length());assertTrue(outputs(r).last().getInt("visible_duplicates")>0);assertTrue(reads(r));ask(call("full","agent_history_read","{\"message_id\":\"${s.user.id}\",\"before\":0,\"after\":0}"))}
   else->{assertEquals(full,outputs(r).last().getJSONArray("messages").getJSONObject(0).getString("content"));TextChatResult.Success("final")}
  }})
  assertTrue(result is TextChatResult.Success);assertEquals(4,n)
 }
 @Test fun directAnswerEndsWithoutAnyToolRound()=runBlocking{
  val t=begin();var calls=0;val result=runtime(t).run(config,"synthetic-only",request(),client{calls++;TextChatResult.Success("direct")})
  assertEquals(TextChatResult.Success("direct"),result);assertEquals(1,calls);assertEquals(0,JSONObject(usage(t)).getInt("rounds"))
 }
 @Test fun twelveDifferentEmptyQueriesCanContinuePastOldBoundAndThenAnswer()=runBlocking{
  val t=begin();var calls=0;val result=runtime(t).run(config,"synthetic-only",request(),client{r->
   calls++;assertTrue(reads(r));if(calls<=12){if(calls>1)assertEquals(0,outputs(r).last().getJSONArray("results").length());ask(call("different-$calls","agent_history_search","{\"query\":\"missing-$calls\"}"))}
   else{assertEquals(12,outputs(r).size);TextChatResult.Success("I cannot verify that from these records")}
  })
  assertTrue(result is TextChatResult.Success);assertEquals(13,calls);assertEquals(12,JSONObject(usage(t)).getInt("rounds"))
 }
 @Test fun exactRepeatedCallKeepsOtherToolsAvailableBeyondEightModelSteps()=runBlocking{
  val t=begin();var calls=0;val result=runtime(t).run(config,"synthetic-only",request(),client{r->
   calls++;assertTrue(reads(r));if(calls<=12)ask(call("repeat-$calls","agent_history_search","{\"query\":\"zznomatchxx\"}"))else TextChatResult.Success("uncertain final")
  })
  assertTrue(result is TextChatResult.Success);assertEquals(13,calls);assertEquals(1,JSONObject(usage(t)).getInt("rounds"))
 }
 @Test fun historicalTokenCountersDoNotStopActiveReads()=runBlocking{
  source("garden source");val t=begin();store.writableDatabase.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES(?,?)",arrayOf("memory-recall-usage:${t.id}",Gson().toJson(CompanionRecallUsage(tokens=100000))))
  var calls=0;val result=runtime(t).run(config,"synthetic-only",request(),client{r->if(calls++==0){assertTrue(reads(r));ask(call("read","agent_history_search","{\"query\":\"garden\"}"))}else{assertTrue(outputs(r).last().getJSONArray("results").length()>0);TextChatResult.Success("final")}})
  assertEquals(2,calls);assertTrue(result is TextChatResult.Success)
 }
 @Test fun writeBatchBoundaryRemainsIndependentWhileReadsCanContinue()=runBlocking{
  repo.saveMemoryRules(MemoryRules(proactiveRecall=true,immediateWrite=true));val t=begin();val tools=runtime(t);var calls=0
  val args="{\"op\":\"add\",\"category\":\"STATE_FACT\",\"about\":\"USER\",\"content\":\"synthetic current question\",\"evidence\":[\"S1\"]}"
  val result=tools.run(config,"synthetic-only",request(),client{r->calls++;assertTrue(reads(r));when{
   calls<=7->{assertTrue(r.tools.any{toolNameOf(it)=="agent_memory_update"});ask(call("write-$calls","agent_memory_update",args))}
   calls==8->{assertFalse(r.tools.any{toolNameOf(it)=="agent_memory_update"});ask(call("read","agent_history_search","{\"query\":\"zznomatchxx\"}"))}
   else->{assertFalse(r.tools.any{toolNameOf(it)=="agent_memory_update"});TextChatResult.Success("final")}
  }})
  assertEquals(9,calls);assertTrue(result is TextChatResult.Success);assertTrue(repo.memories("a").isEmpty())
  assertTrue(ChatLedger(store).finishWithMemory(t.id,t.attempt,"final",onCompleted={tools.commit(it)}));assertEquals(1,repo.memories("a").size)
 }
 @Test fun modelNetworkFailureEndsThisAttemptWithoutHiddenRetry()=runBlocking{
  val t=begin();var calls=0;val result=runtime(t).run(config,"synthetic-only",request(),client{calls++;TextChatResult.Failure(ChatFailure.NETWORK)})
  assertEquals(1,calls);assertEquals(ChatFailure.NETWORK,(result as TextChatResult.Failure).reason)
 }
 @Test fun fullContextStopsBeforeCallingModel()=runBlocking{
  val t=begin();var calls=0;val result=runtime(t).run(config.copy(contextTokens=4096,outputTokens=1024),"synthetic-only",request("x".repeat(100000)),client{calls++;TextChatResult.Success("unexpected")})
  assertEquals(0,calls);assertEquals(ChatFailure.CONTEXT_CAPACITY,(result as TextChatResult.Failure).reason)
 }
 @Test fun revokedPermissionOrLeaseStopsQueuedReadBeforeItRuns()=runBlocking{
  for(revokeRules in listOf(false,true)){
   var active=true;val t=begin();var calls=0;val tools=runtime(t){active}
   val result=tools.run(config,"synthetic-only",request(),client{
    calls++;if(revokeRules)repo.saveMemoryRules(MemoryRules(proactiveRecall=false))else active=false
    ask(call("queued","agent_history_search","{\"query\":\"garden\"}"))
   })
   assertEquals(1,calls);assertEquals(ChatFailure.SOURCE_CHANGED,(result as TextChatResult.Failure).reason);assertEquals(0,JSONObject(usage(t)).getInt("rounds"))
   ChatLedger(store).finish(t.id,t.attempt,"synthetic cleanup");repo.saveMemoryRules(MemoryRules(proactiveRecall=true))
  }
 }
 @Test fun cancellationDoesNotContinueOrPersistAToolWrite()=runBlocking{
  val t=begin();val tools=runtime(t);val entered=CompletableDeferred<Unit>();var calls=0
  val job=async{tools.run(config,"synthetic-only",request(),client{calls++;entered.complete(Unit);awaitCancellation()})}
  withTimeout(5000){entered.await()};job.cancelAndJoin();assertTrue(job.isCancelled);assertEquals(1,calls);assertTrue(repo.memories("a").isEmpty())
 }
 @Test fun userCancellationStillStopsASequenceBeyondOldEightSteps()=runBlocking{
  val t=begin();val tools=runtime(t);val entered=CompletableDeferred<Unit>();var calls=0
  val job=async{tools.run(config,"synthetic-only",request(),client{calls++;if(calls==12){entered.complete(Unit);awaitCancellation()}else ask(call("different-$calls","agent_history_search","{\"query\":\"missing-$calls\"}"))})}
  withTimeout(15000){entered.await()};job.cancelAndJoin();assertTrue(job.isCancelled);assertEquals(12,calls);assertTrue(repo.memories("a").isEmpty())
 }
}
