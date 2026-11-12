package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Actual SQLite/C1/C2/backup, with synthetic model responses and no network. */
class RoleCausalAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var core:CompanionRepository
    private lateinit var ledger:ChatLedger
    private lateinit var database:String
    private var now=1_800_000_000_000L
    @Before fun setup()=runBlocking {
        database="role-causal-${System.nanoTime()}.db";store=Store(context,database)
        core=CompanionRepository(store){now};ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成甲","独立判断"),Friend("b","合成乙","独立判断")),false)
    }
    @After fun close(){store.close();context.deleteDatabase(database)}
    private suspend fun event(input:String,commit:Boolean=true,change:(JSONObject,RoleContext)->Unit={_,_->}):ChatTurn {
        core.beginInput("a");val turn=ledger.begin("a",input,"synthetic")
        val source=core.sourceFor(turn);val context=core.roleContext("a",source)
        val json=JSONObject(RoleModelPayload.json(source.turnId,action=if(context.learning.isEmpty())"listen"else"explore"))
        change(json,context)
        val fact=ExperienceProposal(quote=input,memory=input,claimKind="reported_event")
        val flow=RoleUnderstandingContract.parse(json.toString(),input,fact,context,now,false,false)
        val proposal=fact.copy(understanding=flow)
        assertTrue(core.accept(turn,proposal,core.snapshot("a")){true})
        if(commit){val snapshot=core.snapshot("a")
            val reply=if(flow.usedLearningIds.isEmpty())"合成正式回复：听你说共同解题"else"合成正式回复：我想继续探索共同解题的解法"
            assertTrue(ledger.finishWithMemory(turn.id,turn.attempt,reply,expectedStamp=ledger.context(turn.id).stamp,onCompleted={core.finish(it,turn,snapshot,proposal)}){core.current(store.readableDatabase,snapshot)})
        }else ledger.fail(turn.id,turn.attempt,ChatFailure.NETWORK)
        return ledger.all().single{it.id==turn.id}
    }
    private fun evidence(source:Long,layer:String="fact",message:Long?=null)=JSONObject().put("source",source).put("layer",layer).apply{if(message!=null)put("message",message)}
    private fun learning(id:String,phase:String,evidence:JSONArray)=JSONObject().put("id",id).put("kind","interest").put("scope","共同解题").put("content","我对共同解题中的不同思路产生兴趣").put("phase",phase).put("evidence",evidence)
    private suspend fun experience():Pair<ChatTurn,ChatTurn> {
        val first=event("今天一起解出了第一道逻辑题"){json,c->json.put("learning",learning("","propose",JSONArray().put(evidence(c.current.turnId))))}
        val second=event("今天我独自换了一种解法，也发现了解法的限制，没有请你改变喜好"){json,c->
            json.getJSONObject("appraisal").put("eventRelation","progress")
            json.put("learning",learning(c.learning.single().id,"revise",JSONArray().put(evidence(c.current.turnId)).put(evidence(first.userMessageId,"action",first.assistantMessageId))))
        }
        return first to second
    }
    @Test fun experienceActualResultAutonomousRevisionAndLaterSelectionAreLinked():Unit=runBlocking {
        experience()
        val state=core.roleContext("a",SourceRef("a",0,0));assertEquals("active",state.learning.single().state)
        assertTrue(state.entries.all{it.flow.feedback.isEmpty()}) // no approval/positive reward gate
        val later=event("下一步你自己想聊什么？"){json,c->
            val id=c.learning.single().id;json.put("usedLearningIds",JSONArray().put(id)).put("consideredLearningIds",JSONArray().put(id))
        }
        val current=core.roleContext("a",SourceRef("a",0,0))
        assertEquals("explore",current.entries.last().flow.selectedChoice()!!.action)
        assertEquals(listOf(state.learning.single().id),current.entries.last().action!!.usedLearningIds)
        assertTrue(store.messages().single{it.id==later.assistantMessageId}.text.contains("继续探索"))
        assertTrue(core.roleContext("b",SourceRef("b",0,0)).learning.isEmpty())
    }
    @Test fun withdrawalRemovesDependentJudgmentsButPreservesIndependentNewFact():Unit=runBlocking {
        val (first,second)=experience();core.forget("a",first.userMessageId)
        val current=core.roleContext("a",SourceRef("a",0,0))
        assertTrue(current.learning.isEmpty());assertTrue(current.entries.isEmpty())
        assertTrue(current.facts.any{it.source.turnId==second.userMessageId})
        assertFalse(current.prompt().contains("我对共同解题中的不同思路产生兴趣"))
    }
    @Test fun acceptedInputSurvivesReplyFailureWithoutInventingAnActualResult():Unit=runBlocking {
        val first=event("合成真实输入，但本轮回复失败",commit=false)
        val state=core.roleContext("a",SourceRef("a",0,0))
        assertEquals(first.userMessageId,state.entries.single().source.turnId);assertNull(state.entries.single().action)
        assertTrue(state.actions.isEmpty());assertEquals(1,core.experiences("a").size)
        assertEquals(1,core.recall("a",listOf("合成真实输入"),5).size)
        assertFalse(core.snapshot("a").prompt.contains("回复null已正式保存"))
    }
    @Test fun cachedRevisionAndPauseCanRetryAfterC1WithoutRepeatingLearning():Unit=runBlocking {
        val first=event("今天一起解出了第一道逻辑题"){json,c->json.put("learning",learning("","propose",JSONArray().put(evidence(c.current.turnId))))}
        for(phase in listOf("revise","pause")){
            val input="根据新的实际结果调整共同解题兴趣：$phase"
            var wire=""
            val failed=event(input,commit=false){json,c->
                json.getJSONObject("appraisal").put("eventRelation","progress")
                json.put("learning",learning(c.learning.single().id,phase,JSONArray().put(evidence(c.current.turnId)).put(evidence(first.userMessageId,"action",first.assistantMessageId))))
                wire=json.toString()
            }
            val cached=core.cached(failed)!!
            val projected=core.roleContext("a",SourceRef("a",0,0)).learning.single()
            assertEquals(failed.userMessageId,projected.source.turnId)
            assertEquals(if(phase=="revise")"active"else"paused",projected.state)
            core.beginInput("a")
            val retry=ledger.retry(failed.id,"synthetic")!!
            val retryContext=core.roleContext("a",core.sourceFor(retry))
            assertTrue(retryContext.entries.any{it.source==retryContext.current&&it.flow==cached.understanding})
            val reparsed=RoleUnderstandingContract.parse(wire,input,cached.copy(understanding=null),retryContext,now,false,false)
            // validateAccepted checks an exact dependency SET; serialization/sanitization may
            // enumerate that set differently. Every other field still has exact structural equality.
            assertEquals(cached.understanding!!.copy(dependencies=emptyList()),reparsed.copy(dependencies=emptyList()))
            assertEquals(cached.understanding.dependencies.toSet(),reparsed.dependencies.toSet())
            val snapshot=core.snapshot("a")
            assertTrue(core.accept(retry,cached,snapshot){true})
            assertTrue(ledger.finishWithMemory(retry.id,retry.attempt,"合成重试后的正式回复",expectedStamp=ledger.context(retry.id).stamp,onCompleted={core.finish(it,retry,snapshot,cached)}){core.current(store.readableDatabase,snapshot)})
            val after=core.roleContext("a",SourceRef("a",0,0))
            assertEquals(projected,after.learning.single())
            assertEquals(1,after.entries.count{it.source==retryContext.current})
            assertEquals(1,after.actions.count{it.source==retryContext.current})
            assertEquals(TurnState.COMPLETED,ledger.all().single{it.id==retry.id}.state)
        }
    }
    @Test fun missingDependencyInStoredFlowCannotBecomeUsableState():Unit=runBlocking {
        val turn=event("合成有来源记录")
        val p=core.cached(turn)!!;val broken=p.copy(understanding=p.understanding!!.copy(dependencies=emptyList()))
        store.writableDatabase.execSQL("UPDATE companion_sources SET proposal=? WHERE source_id=?",arrayOf<Any>(Gson().toJson(broken),turn.userMessageId))
        val state=core.roleContext("a",SourceRef("a",0,0));assertTrue(state.entries.isEmpty());assertTrue(state.actions.isEmpty())
        assertEquals(1,state.facts.size)
    }
    @Test fun backupRemapsAllRoleSourceAndActionDependencies():Unit=runBlocking {
        experience()
        val vault=ModelVault(context,"synthetic-role-backup-${System.nanoTime()}",store)
        val backup=BackupRepository(store,vault);val exported=backup.export("a",false)
        val bytes=java.io.ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray()
        val restored=backup.commit(backup.preview(BackupArchive.read(bytes.inputStream()),"ADD",null),true,ChatAttemptLease()).single()
        val state=core.roleContext(restored,SourceRef(restored,0,0))
        assertEquals(2,state.entries.size);assertEquals("active",state.learning.single().state)
        assertTrue(state.entries.flatMap{it.flow.dependencies}.all{it.source.characterId==restored})
        assertTrue(state.entries.last().flow.dependencies.any{it.layer=="action"&&state.actions.any{a->a.replyId==it.messageId}})
    }
    @Test fun proactiveLearningUseIsValidatedPersistedProjectedBackedUpAndWithdrawn():Unit=runBlocking {
        core.setPolicy("a",true,CompanionRuntime.owner)
        val (first,_)=experience()
        val learningId=core.roleContext("a",SourceRef("a",0,0)).learning.single().id
        event("合成有来源的兴趣分享机会"){json,c->
            json.put("future",JSONObject().put("kind","interest_share").put("reason","接续共同解题").put("notBefore",now+900000).put("expiresAt",now+3600000).put("evidence",JSONArray().put(evidence(c.current.turnId))))
        }
        val later=CompanionRepository(store){now+900000};val o=later.claim(later.queued().single().id,CompanionRuntime.owner)!!
        val s=later.snapshot("a");assertEquals(listOf(learningId),s.learning.map{it.id})
        val messages=store.messages().size
        for(bad in listOf(OpportunityDecision("send","接续","不应保存",consideredLearningIds=listOf("learning-missing"),usedLearningIds=listOf("learning-missing"),judgmentKind="inference"),
            OpportunityDecision("send","接续","不应保存",consideredLearningIds=emptyList(),usedLearningIds=listOf(learningId),judgmentKind="inference"))){
            // Strict records reject fabricated influence. The reply boundary keeps valid prose
            // but drops malformed accounting; it has done so since before this 11-item change.
            assertTrue(runCatching{CompanionContracts.decision(Gson().toJson(bad),now+900000,Long.MAX_VALUE,s.learning)}.isFailure)
            val cleaned=CompanionContracts.decisionForReply(Gson().toJson(bad),now+900000,Long.MAX_VALUE,s.learning)
            assertEquals(bad.text,cleaned.text);assertTrue(cleaned.consideredLearningIds.isEmpty());assertTrue(cleaned.usedLearningIds.isEmpty())
        }
        assertEquals(messages,store.messages().size)
        val id=later.conclude(o,CompanionRuntime.owner,OpportunityDecision("send","接续共同解题","合成主动正文：我想继续聊不同解法",consideredLearningIds=listOf(learningId),usedLearningIds=listOf(learningId),judgmentKind="evidence_based"),s){true}!!
        val action=later.roleContext("a",SourceRef("a",0,0)).actions.single{it.replyId==id}
        assertEquals(listOf(learningId),action.usedLearningIds);assertEquals(listOf(learningId),action.consideredLearningIds)
        val material=later.snapshot("a").prompt
        assertTrue(material.contains("合成主动正文：我想继续聊不同解法"))
        assertTrue(material.substringAfter(ExperiencePresentation.REFS).contains("当时使用的变化 $learningId"))
        val backup=BackupRepository(store,ModelVault(context,"synthetic-proactive-backup-${System.nanoTime()}",store));val exported=backup.export("a",false)
        val bytes=java.io.ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray()
        val restored=backup.commit(backup.preview(BackupArchive.read(bytes.inputStream()),"ADD",null),true,ChatAttemptLease()).single()
        assertTrue(core.roleContext(restored,SourceRef(restored,0,0)).actions.any{it.situation=="proactive_interest_share"&&it.usedLearningIds==listOf(learningId)})
        later.forget("a",first.userMessageId)
        val withdrawn=later.roleContext("a",SourceRef("a",0,0))
        assertTrue(withdrawn.learning.isEmpty());assertTrue(withdrawn.actions.none{it.replyId==id})
        assertFalse(later.snapshot("a").prompt.contains(learningId))
    }
    @Test fun invalidLearningReferencesAreCleanedAtActualSubmission():Unit=runBlocking {
        core.setPolicy("a",true,CompanionRuntime.owner);experience()
        val learningId=core.roleContext("a",SourceRef("a",0,0)).learning.single().id
        val invalid=listOf(
            OpportunityDecision("send","接续","第一条正常正文",consideredLearningIds=listOf("learning-missing"),usedLearningIds=listOf("learning-missing"),judgmentKind="inference"),
            OpportunityDecision("send","接续","第二条正常正文",usedLearningIds=listOf(learningId),judgmentKind="inference"))
        for(bad in invalid){
            now+=RelationshipProjector.DAY
            event("合成有来源的独立机会"){json,c->json.put("future",JSONObject().put("kind","interest_share").put("reason","接续共同解题").put("notBefore",now+900000).put("expiresAt",now+3600000).put("evidence",JSONArray().put(evidence(c.current.turnId))))}
            val later=CompanionRepository(store){now+900000}
            val opportunity=later.claim(later.queued().single().id,CompanionRuntime.owner)!!
            val id=later.conclude(opportunity,CompanionRuntime.owner,bad,later.snapshot("a")){true}!!
            assertEquals(bad.text,store.messages().single{it.id==id}.text)
            val action=later.roleContext("a",SourceRef("a",0,0)).actions.single{it.replyId==id}
            assertTrue(action.consideredLearningIds.isEmpty());assertTrue(action.usedLearningIds.isEmpty())
            store.readableDatabase.rawQuery("SELECT considered,used,judgment FROM companion_action_learning WHERE message_id=?",arrayOf(id.toString())).use{c->
                assertTrue(c.moveToFirst());assertEquals("[]",c.getString(0));assertEquals("[]",c.getString(1));assertEquals("",c.getString(2))
            }
        }
    }
    @Test fun actualProactiveInterestAndFollowUpKeepTheirOwnMeaning():Unit=runBlocking {
        core.setPolicy("a",true,CompanionRuntime.owner)
        for(kind in listOf("interest_share","follow_up")){
            // Separate opportunities must not compete for the same synthetic day's contact cap.
            now+=RelationshipProjector.DAY
            val turn=event("合成有来源的主动交流：$kind"){json,c->
                json.put("future",JSONObject().put("kind",kind).put("reason","接续这次真实交流").put("notBefore",now+900000).put("expiresAt",now+3600000).put("evidence",JSONArray().put(evidence(c.current.turnId))))
            }
            val later=CompanionRepository(store){now+900000};val o=later.claim(later.queued().single().id,CompanionRuntime.owner)!!
            val id=later.conclude(o,CompanionRuntime.owner,OpportunityDecision("send","接续有效交流","合成主动正文"),later.snapshot("a")){true}!!
            val action=later.roleContext("a",SourceRef("a",0,0)).actions.single{it.replyId==id}
            assertEquals("proactive_$kind",action.situation)
            assertEquals(if(kind=="interest_share")"role_interest"else"current_request",action.goalOrigin)
            later.stopReminder("a",turn.userMessageId)
            assertTrue(later.roleContext("a",SourceRef("a",0,0)).actions.any{it.replyId==id})
        }
    }
}
