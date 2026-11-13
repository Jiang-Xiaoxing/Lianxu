package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyAccountingTest {
    private val source=SourceRef("a",9,1)
    private val context=RoleContext("a",source,mapOf(9L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,4096)
    private fun items(count:Int=5)=(0 until count).map{i->
        val id=9L+i*2
        ReplyAccountingQueue.Item(ChatTurn("t$i","a",id,id+1,TurnState.COMPLETED,1,"fake",null),SourceRef("a",id,1),"用户原话$i","确实显示的正文$i",1000L+i,"COMPLETED")
    }
    private fun emptyBatch(rows:List<ReplyAccountingQueue.Item>)="""{"records":[${rows.joinToString{ """{"source":${it.source.turnId},"record":null}""" }}]}"""
    @Test fun defaultIsFiveAndExistingSettingsUpgradeWithoutChangingSummary(){
        val original=MemoryRules().json().also{it.remove("reviewRounds")}
        val value=MemoryRules.from(original)
        assertEquals(5,value.reviewRounds);assertEquals(20,value.summaryRounds)
        assertEquals(2,MemoryRules.from(value.copy(reviewRounds=2).json()).reviewRounds)
        assertFalse(value.copy(reviewRounds=0).valid())
    }
    @Test fun triggerCountsSpokenTurnsAndLeavesLaterRowsForNextBatch(){
        assertNull(ReviewBatchTrigger.prefix(listOf(true,true,false,true,true),5))
        assertEquals(6,ReviewBatchTrigger.prefix(listOf(true,true,false,true,true,true,true),5))
        assertEquals(2,ReviewBatchTrigger.prefix(listOf(false,true,true),1))
    }
    @Test fun capacitySplitsKeepEveryFrozenMemberAndExistingTailInOrder(){
        val members=items().map{ReplyAccountingQueue.Member(it.turn.id,it.source.version,it.turn.attempt,it.state,stableDigest(it.input),it.spoken.length,stableDigest(it.spoken),it.readSources)}
        val full=ReplyAccountingQueue.Batch("batch",members)
        val first=full.smaller()!!;val second=first.smaller()!!
        assertEquals(members,first.members+first.remaining);assertEquals(members,second.members+second.remaining)
        assertEquals(2,first.members.size);assertEquals(1,second.members.size);assertNull(second.smaller())
        assertEquals(full.id,first.id);assertEquals(members,full.members)
    }
    @Test fun singleOversizedRoundFailsBeforeCallingModelWithoutTruncating():Unit=runBlocking {
        val row=items(1).single().copy(spoken="原样保留".repeat(20000));var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;error("must not call")}}
        val error=runCatching{ReviewBatchModel(client).review(config,"fake",listOf(row),context,"甲",200000){true}}.exceptionOrNull()
        assertEquals(ChatFailure.CONTEXT_CAPACITY,(error as CompanionModelFailure).failure)
        assertEquals(0,calls);assertEquals("原样保留".repeat(20000),row.spoken)
    }
    @Test fun failedAndRecoveredShownTurnsAreEligibleImmediately(){
        val row=items(1).single()
        listOf("FAILED","CANCELLED","INTERRUPTED").forEach{assertTrue(ReplyAccountingQueue.ready(row.copy(state=it)))}
        assertFalse(ReplyAccountingQueue.ready(row.copy(state="PENDING")))
    }
    @Test fun retryTailNeverEntersAnAlreadyFrozenBatch(){
        val row=items(1).single().copy(spoken="已经说了🙂",state="FAILED")
        val member=ReplyAccountingQueue.Member(row.turn.id,1,1,"FAILED",stableDigest(row.input),row.spoken.length,stableDigest(row.spoken),emptyList())
        val later=row.copy(turn=row.turn.copy(attempt=2),spoken=row.spoken+"\n\n重试后来才说的",state="COMPLETED")
        val frozen=ReplyAccountingQueue.freeze(later,member)
        assertEquals(row.spoken,frozen.spoken);assertEquals(1,frozen.turn.attempt);assertEquals("FAILED",frozen.state)
        assertEquals("stale",ReplyAccountingQueue.freeze(later.copy(input="被更正的原话"),member).state)
        assertEquals("stale",ReplyAccountingQueue.freeze(later.copy(source=row.source.copy(version=2)),member).state)
        assertEquals("stale",ReplyAccountingQueue.freeze(later.copy(spoken="已经改掉"),member).state)
    }
    @Test fun finishingOldReviewCannotConsumeThePendingRetry(){
        val retry=ReplyAccountingQueue.Marker(1,2,"pending")
        val afterOld=retry.finished(1,"old-prefix")
        assertEquals("pending",afterOld.state);assertEquals(2,afterOld.attempt);assertEquals("old-prefix",afterOld.reviewedHash)
        val afterNew=afterOld.finished(2,"new-prefix")
        assertEquals("done",afterNew.state);assertEquals("new-prefix",afterNew.reviewedHash)
    }
    @Test fun batchKeepsOneReadStatePerActionAndItsOriginalReferences():Unit=runBlocking{
        val rows=items(1).map{it.copy(input="第一条原文 第二条原文")}
        val a=RoleActualAction(SourceRef("a",1,2),"listen","chat",2,"第一条原文",emptyList(),emptyList(),"current_request",100000L,null)
        val b=a.copy(source=SourceRef("a",3,4),replyId=4,reply="第二条原文",readAt=null)
        val view=context.copy(knownSources=context.knownSources+mapOf(1L to a.source,3L to b.source),actions=listOf(a,b))
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            val text=request.messages.joinToString("\n"){it.content}
            listOf(a,b).forEach{action->
                val line=ExperiencePresentation.receipt(action.replyId,action.readAt,200000)
                assertEquals(1,Regex.fromLiteral(line).findAll(text).count())
                assertTrue(text.contains(action.reply))
                assertTrue(text.lines().any{it.startsWith("- [回复 ${action.replyId}；来源${ExperiencePresentation.reference(action.source)}]")&&action.reply in it})
            }
            assertEquals(1,Regex.fromLiteral(ExperiencePresentation.MESSAGE_STATE_LABEL).findAll(text).count())
            assertFalse(text.contains("；已读"));assertFalse(text.contains("；未读"))
            return TextChatResult.Success(emptyBatch(rows))
        }}
        assertEquals(1,ReviewBatchModel(client).review(config,"fake",rows,view,"合成角色",200000){true}.size)
    }
    @Test fun fiveTurnsUseOnePrivateModelRequest():Unit=runBlocking{
        val rows=items();val requests=mutableListOf<TextChatRequest>()
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            requests+=request;return TextChatResult.Success(emptyBatch(rows),displayReasoning="不应出现在聊天里的回顾思考")
        }}
        val result=ReviewBatchModel(client).review(config,"fake",rows,context,"你就是甲",200000){true}
        assertEquals(1,requests.size);assertEquals(rows.map{it.source.turnId}.toSet(),result.keys);assertTrue(result.values.all{it==null})
        val request=requests.single();assertTrue(request.jsonObject);assertTrue(request.tools.isEmpty())
        val text=request.messages.joinToString("\n"){it.content}
        rows.forEach{assertTrue(text.contains(it.input));assertTrue(text.contains(it.spoken))}
        assertTrue(text.contains("不得执行或重新安排"));assertTrue(text.contains("记忆总结保持独立"));assertFalse(text.contains("[companion.reply.v1]"))
    }
    @Test fun coverageAndOrderAreMandatory(){
        val rows=items(2)
        assertTrue(runCatching{ReviewBatchModel.parse(emptyBatch(rows.take(1)),rows,200000)}.isFailure)
        assertTrue(runCatching{ReviewBatchModel.parse(emptyBatch(rows.reversed()),rows,200000)}.isFailure)
        assertEquals(2,ReviewBatchModel.parse(emptyBatch(rows),rows,200000).size)
    }
    @Test fun invalidBatchIsRetriedAsAWholeRatherThanMarkedDone():Unit=runBlocking{
        val rows=items();var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;return TextChatResult.Success(if(calls==1)emptyBatch(rows.take(1))else emptyBatch(rows))
        }}
        assertEquals(5,ReviewBatchModel(client).review(config,"fake",rows,context,"甲",200000){true}.size)
        assertEquals(2,calls)
    }
    @Test fun networkFailureLeavesNoCommitCandidate():Unit=runBlocking{
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Failure(ChatFailure.NETWORK)}}
        val e=runCatching{ReviewBatchModel(client).review(config,"fake",items(),context,"甲",200000){true}}.exceptionOrNull()
        assertEquals(ChatFailure.NETWORK,(e as CompanionModelFailure).failure);assertEquals(1,calls)
    }
    @Test fun sourceChangeDuringRequestRejectsReturnedBatch():Unit=runBlocking{
        var active=true
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{active=false;return TextChatResult.Success(emptyBatch(items()))}}
        val e=runCatching{ReviewBatchModel(client).review(config,"fake",items(),context,"甲",200000){active}}.exceptionOrNull()
        assertEquals(ChatFailure.SOURCE_CHANGED,(e as CompanionModelFailure).failure)
    }
    @Test fun contactAllowsShortWaitAndRejectsPastTimeOrUnknownEvidence(){
        val now=200000L
        fun payload(delay:Long,src:Long=9)="""{"future":{"kind":"follow_up","reason":"回头聊聊进展","notBefore":${now+delay},"expiresAt":${now+delay+60000},"evidence":[{"source":$src,"layer":"fact"}]}}"""
        assertNotNull(ImmediateContact.parse(payload(1000),"过会儿聊",context,now).flow!!.future)
        assertTrue(runCatching{ImmediateContact.parse(payload(-1),"过会儿聊",context,now)}.isFailure)
        assertTrue(runCatching{ImmediateContact.parse(payload(900000,99),"过会儿聊",context,now)}.isFailure)
        assertEquals("安排联系",ChatToolJournal.start(ChatToolCall("c",ImmediateContact.NAME,"{}"),1,"").title)
    }
    @Test fun backupKeepsTheMarkerBoundToTheRemappedTurn(){
        val mapped=BackupRemapper("a","b",mapOf("old-turn" to "new-turn"),emptyMap()).preferenceKey(ReplyAccountingQueue.key("old-turn"))
        assertEquals(ReplyAccountingQueue.key("new-turn"),mapped)
    }
    @Test fun twoWorkersSerializeButFastInputsAndAnotherRoleDoNotWait():Unit=runBlocking{
        val rows=mutableListOf("1","2");val committed=mutableListOf<String>();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val record:suspend(String)->Boolean={id->if(id=="1"){started.complete(Unit);release.await()};committed+=id;rows.remove(id);true}
        val first=async{AccountingDrain.run("race-a",{rows.firstOrNull()},record)};started.await()
        val duplicate=async{AccountingDrain.run("race-a",{rows.firstOrNull()},record)}
        // A fresh input and its answer progress while the previous accounting request is suspended.
        val answer=async{rows+="3";"新消息已回复"}
        assertEquals("新消息已回复",withTimeout(1000){answer.await()})
        var other=true
        assertTrue(withTimeout(1000){AccountingDrain.run("race-b",{if(other)"other"else null},{other=false;true})})
        assertTrue(committed.isEmpty());release.complete(Unit);assertTrue(first.await());assertTrue(duplicate.await())
        assertEquals(listOf("1","2","3"),committed)
    }
    @Test fun failureRetainsHeadAndRecoveryCountsItOnlyOnce():Unit=runBlocking{
        val rows=mutableListOf("1","2");val saved=mutableListOf<String>()
        assertFalse(AccountingDrain.run("failure-a",{rows.firstOrNull()},{false}));assertEquals(listOf("1","2"),rows)
        val record:suspend(String)->Boolean={saved+=it;rows.remove(it);true}
        assertTrue(AccountingDrain.run("failure-a",{rows.firstOrNull()},record))
        assertTrue(AccountingDrain.run("failure-a",{rows.firstOrNull()},record));assertEquals(listOf("1","2"),saved)
    }
    @Test fun cancellationBeforeCommitLeavesWorkForTheNextWorker():Unit=runBlocking{
        val rows=mutableListOf("1");val entered=CompletableDeferred<Unit>()
        val old=launch{AccountingDrain.run("cancel-a",{rows.firstOrNull()},{entered.complete(Unit);awaitCancellation()})}
        entered.await();old.cancelAndJoin();assertEquals(listOf("1"),rows)
        assertTrue(AccountingDrain.run("cancel-a",{rows.firstOrNull()},{rows.remove(it);true}));assertTrue(rows.isEmpty())
    }
}
