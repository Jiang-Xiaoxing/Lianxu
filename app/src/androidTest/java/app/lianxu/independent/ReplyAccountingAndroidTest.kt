package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Isolated SQLite + fake transport. Never opens the user's database or calls a provider. */
class ReplyAccountingAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var core:CompanionRepository
    private lateinit var vault:ModelVault
    @Before fun setup()=runBlocking {
        name="batch-review-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        store.add(listOf(Friend("a","合成甲","角色设定"),Friend("b","合成乙","角色设定")),false)
        vault=ModelVault(context,name);vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid","fake",128000,4096),"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,"fake")
    }
    @After fun close(){vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}
    private suspend fun closed(role:String="a",text:String="合成用户原话",spoken:String="确实显示的正文"):ChatTurn {
        val ledger=ChatLedger(store);val t=ledger.begin(role,text,"fake")
        assertTrue(ledger.finish(t.id,t.attempt,spoken));return ledger.all().single{it.id==t.id}
    }
    private suspend fun failedPrefix():ChatTurn {
        val ledger=ChatLedger(store);val t=ledger.begin("a","合成用户原话","fake")
        assertTrue(ledger.recordSpoken(t.id,t.attempt,"已说出的第一段🙂",AppMutationGate.epoch()))
        ledger.fail(t.id,t.attempt,ChatFailure.NETWORK);return ledger.all().single{it.id==t.id}
    }
    private suspend fun finishRetry(t:ChatTurn):ChatTurn {
        val ledger=ChatLedger(store);val retry=requireNotNull(ledger.retry(t.id,"fake"));val snapshot=core.snapshot("a")
        assertTrue(ledger.finishWithMemory(retry.id,retry.attempt,"已说出的第一段🙂\n\n重试补充的正文",onCompleted={core.finishReply(it,retry,snapshot)}))
        return ledger.all().single{it.id==t.id}
    }
    private suspend fun claim(role:String="a")=repo.tx{db->ReplyAccountingQueue.claim(db,role,repo.memoryRules(db).first.reviewRounds)}
    private fun output(request:TextChatRequest):String {
        val rows=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
        return """{"records":[${rows.joinToString{ """{"source":${it.asJsonObject["source"]},"record":null}""" }}]}"""
    }
    private fun recorded(source:Long):com.google.gson.JsonObject = JsonParser.parseString(RoleModelPayload.json(source)).asJsonObject.apply{
        add("experience",JsonParser.parseString("""{"quote":"合成用户原话","memory":"有原话依据的合成经历","emotion":"unknown","valence":0,"relationship":"none","control":"none","targets":[],"reconsider":null,"claimKind":"interaction","controlIntent":"none","requestQuote":""}"""))
        add("关系输入",JsonParser.parseString("""{"消息":"$source","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"}"""))
    }
    private fun positiveOutput(request:TextChatRequest):String {
        val rows=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
        return """{"records":[${rows.joinToString{val id=it.asJsonObject["source"].asLong;"""{"source":$id,"record":${recorded(id)}}"""}}]}"""
    }
    private fun processor(reply:suspend(TextChatRequest)->TextChatResult)=ReplyAccounting(store,ModelAccess(vault),object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=reply(request)
    })
    @Test fun unverifiedLegacyFeelingKeepsNewRoundsPendingWithoutCallingTheModel()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val before=core.relationship("a").state
        repo.tx{db->val saved=requireNotNull(RelationFeeling.read(db,"a"));RelationFeeling.write(db,"a",saved.copy(attributionIncomplete=true))}
        val turn=closed(text="旧状态待核对时的新原话",spoken="已经显示的新回复")
        var calls=0
        assertFalse(processor{calls++;TextChatResult.Success(output(it))}.drain("a"));assertEquals(0,calls)
        val view=core.relationship("a");assertTrue(view.attributionIncomplete);assertEquals(before,view.state)
        assertEquals(RelationshipPrompt.UNVERIFIED,view.modelMaterial);assertFalse(view.modelMaterial.contains("信任="))
        val pending=repo.read{ReplyAccountingQueue.pending(it,"a")};assertEquals(listOf(turn.userMessageId),pending.map{it.source.turnId})
        val batch=repo.read{requireNotNull(ReplyAccountingQueue.batch(it,"a"))}
        val frozen=repo.read{ReplyAccountingQueue.frozen(it,"a",batch)}
        assertFalse(core.finishReviewBatch(batch,frozen,frozen.associate{it.source.turnId to null},core.snapshot("a"),System.currentTimeMillis()){true})
        assertEquals(pending,repo.read{ReplyAccountingQueue.pending(it,"a")})
        assertEquals(TurnState.COMPLETED,ChatLedger(store).all().single().state)
        assertTrue(store.messages().any{it.mine&&it.text=="旧状态待核对时的新原话"})
        assertTrue(store.messages().any{!it.mine&&it.text=="已经显示的新回复"})
        store.close();store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        assertTrue(core.relationship("a").attributionIncomplete)
        assertEquals(pending,repo.read{ReplyAccountingQueue.pending(it,"a")})
        assertEquals(batch,repo.read{ReplyAccountingQueue.batch(it,"a")})
    }
    @Test fun attributionFailureDuringCommitRollsBackRecordsAndKeepsTheRetryPending()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1))
        val first=failedPrefix()
        assertTrue(processor{TextChatResult.Success(positiveOutput(it))}.drain("a"))
        val view=core.relationship("a")
        repo.tx{db->
            val saved=requireNotNull(RelationFeeling.read(db,"a"))
            val cursor=requireNotNull(RelationSnapshotCodec.decode(saved.cursor,view.start,view.parameters))
            cursor.advance(System.currentTimeMillis()+40*RelationshipProjector.DAY);cursor.compact()
            assertTrue(cursor.impacts.keys.any{it.turnId<0})
            RelationFeeling.write(db,"a",saved.copy(cursor=RelationSnapshotCodec.encode(cursor),merged=null,
                fulfillmentSources=null,archivedUndo=null,unverifiableSources=null,unattributedMerged=null))
        }
        finishRetry(first)
        assertFalse(core.relationship("a").attributionIncomplete)
        fun proposal()=store.readableDatabase.rawQuery("SELECT proposal FROM companion_sources WHERE role='a' AND source_id=?",arrayOf(first.userMessageId.toString())).use{assertTrue(it.moveToFirst());it.getString(0)}
        val originalProposal=proposal();val originalFeeling=repo.read{requireNotNull(RelationFeeling.read(it,"a"))}
        var calls=0
        val processor=processor{calls++;TextChatResult.Success(output(it))}
        assertFalse(processor.drain("a"));assertEquals(1,calls)
        assertEquals(originalProposal,proposal())
        val saved=repo.read{requireNotNull(RelationFeeling.read(it,"a"))}
        assertTrue(saved.attributionIncomplete);assertEquals(originalFeeling.cursor,saved.cursor);assertEquals(originalFeeling.ledger,saved.ledger)
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").any{row->row.turn.id==first.id&&row.turn.attempt==2}})
        assertNotNull(repo.read{ReplyAccountingQueue.batch(it,"a")})
        assertFalse(processor.drain("a"));assertEquals(1,calls)
        assertTrue(store.messages().any{it.text.contains("重试补充的正文")})
    }
    @Test fun oversizedBatchPreservesOrderAndFrozenTailAcrossFailureAndRestart()=runBlocking {
        val spoken="实际显示".repeat(5000)
        val turns=(1..5).map{closed(spoken=spoken)}
        val committed=mutableListOf<Long>();var calls=0
        fun ids(request:TextChatRequest):List<Long>{
            val data=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
            data.forEach{assertEquals(spoken,it.asJsonObject["已实际说过的正文"].asString)}
            return data.map{it.asJsonObject["source"].asLong}
        }
        fun records(chunk:List<Long>)="""{"records":[${chunk.joinToString{ """{"source":$it,"record":${recorded(it)}}""" }}]}"""
        assertFalse(processor{request->
            val chunk=ids(request);assertTrue(chunk.size<5);calls++
            if(calls==1){committed.addAll(chunk);TextChatResult.Success(records(chunk))}else TextChatResult.Failure(ChatFailure.NETWORK)
        }.drain("a"))
        assertEquals(2,calls);assertTrue(committed.isNotEmpty());assertTrue(committed.size<5)
        val pendingBefore=repo.read{ReplyAccountingQueue.pending(it,"a").map{row->row.source.turnId}}
        assertEquals(turns.map{it.userMessageId}.drop(committed.size),pendingBefore)
        // The frozen remainder is already due; changing the next batch threshold must not strand it.
        repo.saveMemoryRules(MemoryRules(reviewRounds=9))
        store.close();store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        val resume=processor{request->val chunk=ids(request);committed.addAll(chunk);TextChatResult.Success(records(chunk))}
        assertTrue(resume.drain("a"))
        assertEquals(turns.map{it.userMessageId},committed);assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
        assertNull(repo.read{ReplyAccountingQueue.batch(it,"a")})
        assertEquals(9,repo.memoryRules().first.reviewRounds)
        assertEquals(5,repo.read{db->db.rawQuery("SELECT count(*) FROM companion_sources WHERE role='a' AND committed=1",null).use{it.moveToFirst();it.getInt(0)}})
        val inputs=core.relationship("a").inputs;assertEquals(turns.map{it.userMessageId},inputs.map{it.source.turnId})
        assertTrue(inputs.all{(it.p?:0.0)>0});assertTrue(resume.drain("a"));assertEquals(inputs,core.relationship("a").inputs)
        assertEquals(turns.map{it.userMessageId},committed)
    }
    @Test fun singleOversizedRoundStaysPendingWithoutCallingOrDroppingContent()=runBlocking {
        repo.saveMemoryRules(MemoryRules(reviewRounds=1));val spoken="不能删的原话".repeat(30000)
        val turn=closed(spoken=spoken);var calls=0
        assertFalse(processor{calls++;TextChatResult.Success(output(it))}.drain("a"))
        assertEquals(0,calls);assertEquals(spoken,ChatLedger(store).spoken(turn.id))
        val batch=repo.read{ReplyAccountingQueue.batch(it,"a")!!};assertEquals(1,batch.members.size)
        assertEquals(listOf(turn.userMessageId),repo.read{ReplyAccountingQueue.pending(it,"a").map{row->row.source.turnId}})
        assertFalse(processor{calls++;TextChatResult.Success(output(it))}.drain("a"));assertEquals(0,calls)
        assertEquals(batch,repo.read{ReplyAccountingQueue.batch(it,"a")})
    }
    @Test fun withdrawnFrozenTailIsExcludedAfterAPartialCapacityCommit()=runBlocking {
        val turns=(1..5).map{closed(spoken="实际显示".repeat(5000))};var calls=0
        assertFalse(processor{request->calls++;if(calls==1)TextChatResult.Success(positiveOutput(request))else TextChatResult.Failure(ChatFailure.NETWORK)}.drain("a"))
        val firstInputs=core.relationship("a").inputs;assertTrue(firstInputs.isNotEmpty());assertTrue(firstInputs.size<5)
        val withdrawn=turns.last().userMessageId;core.forget("a",withdrawn)
        val resumed=mutableListOf<Long>()
        assertTrue(processor{request->
            val ids=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray.map{it.asJsonObject["source"].asLong}
            assertFalse(withdrawn in ids);resumed.addAll(ids);TextChatResult.Success(positiveOutput(request))
        }.drain("a"))
        assertEquals(turns.dropLast(1).map{it.userMessageId},core.relationship("a").inputs.map{it.source.turnId})
        assertTrue(firstInputs.none{it.source.turnId in resumed});assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
        assertEquals("合成用户原话",store.messages().single{it.id==withdrawn}.text)
    }
    @Test fun retryAppendedSpeechCannotEnterFrozenSplitTailOrDoubleCountItsRelation()=runBlocking {
        val spoken="实际显示".repeat(5000);repeat(4){closed(spoken=spoken)}
        val ledger=ChatLedger(store);val fifth=ledger.begin("a","合成用户原话","fake")
        assertTrue(ledger.recordSpoken(fifth.id,1,spoken,AppMutationGate.epoch()));ledger.fail(fifth.id,1,ChatFailure.NETWORK)
        var calls=0
        assertFalse(processor{request->calls++;if(calls==1)TextChatResult.Success(positiveOutput(request))else TextChatResult.Failure(ChatFailure.NETWORK)}.drain("a"))
        val firstSources=core.relationship("a").inputs.map{it.source.turnId};assertTrue(firstSources.isNotEmpty());assertFalse(fifth.userMessageId in firstSources)
        val retry=requireNotNull(ledger.retry(fifth.id,"fake"));val extended=spoken+"\n\n重试后来才说的正文"
        assertTrue(ledger.finish(retry.id,retry.attempt,extended))
        var oldSeen=false
        assertTrue(processor{request->
            val rows=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
            rows.forEach{row->val id=row.asJsonObject["source"].asLong;assertFalse(id in firstSources)
                if(id==fifth.userMessageId){oldSeen=true;assertEquals(spoken,row.asJsonObject["已实际说过的正文"].asString)}}
            TextChatResult.Success(positiveOutput(request))
        }.drain("a"))
        assertTrue(oldSeen);assertEquals(listOf(fifth.userMessageId),repo.read{ReplyAccountingQueue.pending(it,"a").map{row->row.source.turnId}})
        val before=core.relationship("a");assertEquals(5,before.inputs.size)
        repo.saveMemoryRules(MemoryRules(reviewRounds=1));var newSeen=false
        assertTrue(processor{request->
            val row=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray.single().asJsonObject
            assertEquals(fifth.userMessageId,row["source"].asLong);assertEquals(extended,row["已实际说过的正文"].asString);newSeen=true
            TextChatResult.Success(positiveOutput(request))
        }.drain("a"))
        assertTrue(newSeen);val after=core.relationship("a");assertEquals(before.inputs,after.inputs)
        assertEquals(before.state.trust,after.state.trust,1e-5);assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
    }
    @Test fun fifthCompletedTurnFreezesMembershipAndNextMessageStaysOut()=runBlocking {
        repeat(4){closed()};assertNull(claim());val fifth=closed();val batch=requireNotNull(claim())
        assertEquals(5,batch.members.size);assertEquals(fifth.id,batch.members.last().task_id)
        val next=closed();assertEquals(batch,claim());assertFalse(batch.members.any{it.task_id==next.id})
        assertEquals(6,repo.read{ReplyAccountingQueue.pending(it,"a").size})
    }
    @Test fun interruptedUserOnlyInputTravelsWithShownPrefixButDoesNotCount()=runBlocking {
        val ledger=ChatLedger(store);val first=ledger.begin("a","先发的原话","fake")
        ledger.interrupt(first.id,first.attempt,"");repeat(4){closed()};assertNull(claim())
        closed();val batch=requireNotNull(claim());assertEquals(6,batch.members.size)
        assertEquals(first.id,batch.members.first().task_id)
    }
    @Test fun failedBatchPersistsAcrossReopenAndRetriesExactlyThoseMembers()=runBlocking {
        repeat(5){closed()};var calls=0
        assertFalse(processor{calls++;TextChatResult.Failure(ChatFailure.NETWORK)}.drain("a"))
        val first=requireNotNull(claim());closed()
        store.close();store=Store(context,"$name.db");repo=ProcessingRepository(store);core=CompanionRepository(store)
        assertEquals(first,claim())
        assertTrue(processor{calls++;TextChatResult.Success(output(it))}.drain("a"))
        assertEquals(2,calls);assertEquals(1,repo.read{ReplyAccountingQueue.pending(it,"a").size})
        assertTrue(processor{error("Already completed batch must not call model again")}.drain("a"))
    }
    @Test fun newChatAndAnotherRoleDoNotWaitForReview()=runBlocking {
        repeat(5){closed()};val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val worker=async(Dispatchers.Default){processor{started.complete(Unit);release.await();TextChatResult.Success(output(it))}.drain("a")}
        started.await();val next=withTimeout(3000){closed()};repeat(5){closed("b")}
        assertTrue(processor{TextChatResult.Success(output(it))}.drain("b"))
        release.complete(Unit);assertTrue(worker.await())
        assertEquals(listOf(next.id),repo.read{ReplyAccountingQueue.pending(it,"a").map{r->r.turn.id}})
    }
    @Test fun sourceWithdrawalDuringRequestRejectsWholeResultThenRetainsTheBatch()=runBlocking {
        val turns=(1..5).map{closed()};var withdrawn=false
        assertFalse(processor{if(!withdrawn){core.forget("a",turns[0].userMessageId);withdrawn=true};TextChatResult.Success(output(it))}.drain("a"))
        assertNotNull(claim());assertEquals(5,repo.read{ReplyAccountingQueue.pending(it,"a").size})
        assertTrue(processor{request->
            assertFalse(request.messages.last{it.role=="user"}.content.contains("\"source\":${turns[0].userMessageId},"))
            TextChatResult.Success(output(request))
        }.drain("a"))
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
    }
    @Test fun forgetOriginalRemainsStoredButNeitherOldSourceNorRequestEntersReview()=runBlocking {
        val old=closed(text="合成旧事紫色风筝")
        val input="忘记合成旧事紫色风筝，接着聊别的"
        val ledger=ChatLedger(store);val turn=ledger.begin("a",input,"fake")
        val args=org.json.JSONObject().put("action","forget").put("quote",input).put("authorization","current_direct_request").put("targets",org.json.JSONArray().put(old.userMessageId))
        val p=JointControl.parse(args,input,System.currentTimeMillis(),setOf(old.userMessageId),null)
        assertTrue(core.accept(turn,p,core.snapshot("a")){true});val after=core.snapshot("a")
        assertTrue(ledger.finishWithMemory(turn.id,turn.attempt,"好，聊别的",onCompleted={core.finishReply(it,turn,after)}))
        repeat(5){closed()};var calls=0
        assertTrue(processor{request->calls++;assertFalse(request.messages.any{"紫色风筝" in it.content});TextChatResult.Success(output(request))}.drain("a"))
        assertEquals(1,calls);assertEquals(input,store.messages().single{it.id==turn.userMessageId}.text)
        assertTrue(repo.sources("a").none{it.source.turnId in setOf(old.userMessageId,turn.userMessageId)})
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()});assertTrue(core.experiences("a").isEmpty())
    }
    @Test fun revisionChangeRejectsLateBatchWithoutRepeatingOldScore()=runBlocking {
        val turns=(1..5).map{closed()};var changed=false
        assertFalse(processor{request->
            if(!changed){repo.tx{it.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE id=?",arrayOf(turns[0].id))};changed=true}
            TextChatResult.Success(output(request))
        }.drain("a"))
        assertEquals(5,repo.read{ReplyAccountingQueue.pending(it,"a").size})
        assertTrue(processor{TextChatResult.Success(output(it))}.drain("a"))
        assertTrue(core.relationship("a").inputs.isEmpty())
    }
    @Test fun invalidLastRecordRollsBackAllStateAndCompletionMarkers()=runBlocking {
        repeat(5){closed()};val batch=requireNotNull(claim());val items=repo.read{ReplyAccountingQueue.frozen(it,"a",batch)}
        val records=items.associate{it.source.turnId to recorded(it.source.turnId)}.toMutableMap<Long,com.google.gson.JsonObject?>()
        records[items.last().source.turnId]=JsonParser.parseString("{}").asJsonObject
        assertTrue(runCatching{core.finishReviewBatch(batch,items,records,core.snapshot("a"),System.currentTimeMillis()){true}}.isFailure)
        assertEquals(batch,claim());assertEquals(5,repo.read{ReplyAccountingQueue.pending(it,"a").size})
        assertTrue(core.experiences("a").isEmpty());assertTrue(core.relationship("a").inputs.isEmpty())
    }
    @Test fun repeatedWakeupCannotPublishTheSameRelationshipInputTwice()=runBlocking {
        repeat(5){closed()};var requests=0
        val p=processor{request->
            requests++
            val rows=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
            TextChatResult.Success("""{"records":[${rows.joinToString{val id=it.asJsonObject["source"].asLong;"""{"source":$id,"record":${recorded(id)}}"""}}]}""")
        }
        assertTrue(p.drain("a"));assertEquals(5,core.relationship("a").inputs.size)
        val before=core.relationship("a").inputs
        assertTrue(p.drain("a"));assertEquals(before,core.relationship("a").inputs);assertEquals(1,requests)
    }
    @Test fun retryDuringReviewPreservesTheFrozenPrefixAndLeavesNewSpeechPending()=runBlocking {
        repeat(4){closed()};val fifth=failedPrefix();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val task=async(Dispatchers.Default){processor{request->
            val body=request.messages.last{it.role=="user"}.content
            assertTrue(body.contains("已说出的第一段"));assertFalse(body.contains("重试补充的正文"))
            entered.complete(Unit);release.await();TextChatResult.Success(output(request))
        }.drain("a")}
        entered.await();finishRetry(fifth);release.complete(Unit);assertTrue(task.await())
        assertEquals(listOf(fifth.id),repo.read{ReplyAccountingQueue.pending(it,"a").map{r->r.turn.id}})
        repeat(4){closed()}
        assertTrue(processor{request->assertTrue(request.messages.last{it.role=="user"}.content.contains("重试补充的正文"));TextChatResult.Success(output(request))}.drain("a"))
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
    }
    @Test fun retryAfterPublishedReviewUpdatesTheSameSourceInsteadOfAddingAnotherScore()=runBlocking {
        repeat(4){closed()};val fifth=failedPrefix();var calls=0
        val p=processor{request->
            calls++;val rows=JsonParser.parseString(request.messages.last{it.role=="user"}.content).asJsonArray
            TextChatResult.Success("""{"records":[${rows.joinToString{val id=it.asJsonObject["source"].asLong;"""{"source":$id,"record":${recorded(id)}}"""}}]}""")
        }
        assertTrue(p.drain("a"));assertEquals(5,core.relationship("a").inputs.size)
        finishRetry(fifth);repeat(4){closed()};assertTrue(p.drain("a"))
        val rows=core.relationship("a").inputs
        assertEquals(9,rows.size);assertEquals(1,rows.count{it.source.turnId==fifth.userMessageId});assertEquals(2,calls)
        assertTrue(p.drain("a"));assertEquals(rows,core.relationship("a").inputs);assertEquals(2,calls)
    }
    @Test fun retryWithoutNewDisplayedTextSkipsAnotherReviewRequest()=runBlocking {
        repeat(4){closed()};val fifth=failedPrefix()
        assertTrue(processor{TextChatResult.Success(output(it))}.drain("a"))
        val ledger=ChatLedger(store);val retry=requireNotNull(ledger.retry(fifth.id,"fake"));ledger.fail(retry.id,retry.attempt,ChatFailure.NETWORK)
        assertTrue(processor{error("Unchanged actual dialogue must not be reviewed twice")}.drain("a"))
        assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
    }
    @Test fun successfulContactSurvivesReplyRetryAndIsNotRunAgainByReview()=runBlocking {
        core.setPolicy("a",true,CompanionRuntime.owner)
        val ledger=ChatLedger(store);val t=ledger.begin("a","过会儿再聊聊进展","fake");val source=core.sourceFor(t);val now=System.currentTimeMillis()
        val raw="""{"future":{"kind":"follow_up","reason":"回头聊进展","notBefore":${now+1000},"evidence":[{"source":${source.turnId},"layer":"fact"}]}}"""
        val action=ImmediateContact.parse(raw,"过会儿再聊聊进展",core.roleContext("a",source),now)
        assertEquals(true,core.contact(t,action,core.snapshot("a"),now){true}["success"])
        assertEquals(now+1000,core.queued().single().notBefore);assertEquals(Long.MAX_VALUE,core.queued().single().expiresAt)
        ledger.fail(t.id,t.attempt,ChatFailure.TIMEOUT);val retry=requireNotNull(ledger.retry(t.id,"fake"))
        core.contact(retry,action,core.snapshot("a"),now){true};assertEquals(1,core.queued().size)
        assertTrue(ledger.finish(retry.id,retry.attempt,"已安排再考虑"));repeat(4){closed()}
        val before=core.queued().single();assertTrue(processor{TextChatResult.Success(output(it))}.drain("a"))
        assertEquals(listOf(before),core.queued());assertTrue(repo.read{ReplyAccountingQueue.pending(it,"a").isEmpty()})
    }
    @Test fun invalidContactEvidenceCannotCreateAnOpportunityAfterSanitization()=runBlocking {
        core.setPolicy("a",true,CompanionRuntime.owner)
        val t=ChatLedger(store).begin("a","过会儿聊进展","fake");val source=core.sourceFor(t);val now=System.currentTimeMillis()
        val raw="""{"future":{"kind":"follow_up","reason":"回头聊进展","notBefore":${now+900000},"expiresAt":${now+3600000},"evidence":[{"source":${source.turnId},"layer":"fact"}]}}"""
        val good=ImmediateContact.parse(raw,"过会儿聊进展",core.roleContext("a",source),now)
        val invalid=listOf(RoleEvidence(SourceRef("a",999999,1)))
        val bad=good.copy(flow=good.flow!!.copy(future=good.flow.future!!.copy(evidence=invalid),dependencies=invalid))
        assertTrue(runCatching{core.contact(t,bad,core.snapshot("a"),now){true}}.isFailure)
        assertTrue(core.queued().isEmpty());assertNull(core.cached(t))
    }
}
