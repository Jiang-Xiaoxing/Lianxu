package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationshipRepairTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long,at:Long=0)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")
    private val first=row(1).copy(v=.9)
    private val second=row(2).copy(b=.6)
    private val promise=row(3).copy(promise=true)
    private fun seeded()=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(first);it.apply(second);it.apply(promise)}
    private fun fulfilled(target:SourceRef?=null,at:Long=0)=row(4,at).copy(c=.6,fulfills=promise.source,repairs=target,repairCompleted=true)

    @Test fun unrelatedFulfillmentKeepsEveryGrudgeAndStillImprovesTheRelationship(){
        val c=seeded();val before=c.state();val impacts=c.impacts.toMap()
        c.apply(fulfilled())
        assertEquals(before.tension,c.state().tension,0.0)
        for(ref in listOf(first.source,second.source))assertEquals(impacts.getValue(ref).tension,c.impacts.getValue(ref).tension,0.0)
        assertTrue(c.trust>before.trust);assertTrue(c.closeness>before.closeness);assertTrue(c.state().happy>before.happy)
        assertEquals(fulfilled().source,c.promises.getValue(promise.source).fulfilledBy)
    }
    @Test fun relatedFulfillmentOnlyHalvesItsTargetWithTheSamePositiveBenefits(){
        val targeted=seeded();val unrelated=seeded();val a=targeted.impacts.getValue(first.source).tension;val b=targeted.impacts.getValue(second.source).tension
        targeted.apply(fulfilled(first.source));unrelated.apply(fulfilled())
        assertEquals(a*.5,targeted.impacts.getValue(first.source).tension,1e-12)
        assertEquals(b,targeted.impacts.getValue(second.source).tension,0.0)
        assertEquals(unrelated.trust,targeted.trust,0.0);assertEquals(unrelated.closeness,targeted.closeness,0.0)
        assertEquals(unrelated.state().happy,targeted.state().happy,0.0)
        assertEquals("↓",targeted.causes.getValue("tension").direction)
    }
    @Test fun aNewPromiseOrAnInvalidFulfillmentDoesNotRepairAnything(){
        for(action in listOf(row(4).copy(promise=true,repairs=first.source),fulfilled(first.source).copy(repairCompleted=false),
            fulfilled(first.source).copy(repairCompleted=false),fulfilled(first.source).copy(fulfills=SourceRef("r",99,1)))){
            val c=seeded();val before=c.state().tension;c.apply(action);assertEquals(before,c.state().tension,0.0)
        }
    }
    @Test fun missingForeignFutureAndWrongVersionTargetsDoNotTouchAnyGrudge(){
        for(target in listOf(SourceRef("r",99,1),SourceRef("other",1,1),SourceRef("r",4,1),first.source.copy(version=2))){
            val c=seeded();val before=c.state().tension;c.apply(fulfilled(target));assertEquals(before,c.state().tension,0.0)
            assertEquals(fulfilled().source,c.promises.getValue(promise.source).fulfilledBy)
        }
    }
    @Test fun oneFulfilledPromiseCannotRepairTwiceOrBeRedirectedToAnotherGrudge(){
        val c=seeded();c.apply(fulfilled(first.source));val before=c.state()
        c.apply(fulfilled(second.source).copy(source=SourceRef("r",5,1)))
        assertEquals(before.tension,c.state().tension,0.0);assertEquals(before.trust,c.trust,0.0)
    }
    @Test fun unseenUncertainOrNonGrudgeReferencesDropOnlyTheRepair(){
        val c=seeded()
        val cases=listOf(fulfilled(first.source) to setOf(3L),fulfilled(promise.source) to setOf(1L,3L),
            fulfilled(first.source).copy(repairCompleted=false) to setOf(1L,3L),fulfilled(first.source).copy(repairCompleted=false,fulfills=null) to setOf(1L),
            fulfilled() to setOf(3L))
        for((action,seen) in cases){
            val checked=RelationshipRecall.validate(action,c.state(),seen){_,_->}
            assertNull(checked.repairs);assertEquals(action.c,checked.c)
            if(action.fulfills==promise.source)assertEquals(promise.source,checked.fulfills)
        }
        val checked=RelationshipRecall.validate(fulfilled(first.source),c.state(),setOf(1L,3L)){_,_->fail()}
        assertEquals(first.source,checked.repairs)
    }
    @Test fun parsingAndRecallExposeARepairTargetWithoutGivingTheModelScores(){
        val action=RelationInputContract.parse(JsonParser.parseString("""{"消息":"4","信息类别":"关于关系","P":"无","C":"中","V":"无","B":"无","兑现约定":"3","修复心结":"1","弥补已完成":true}"""),SourceRef("r",4,1),0,0,listOf(first,second,promise))!!
        assertEquals(first.source,action.repairs);assertTrue(RelationInputContract.validStored(action))
        val events=RelationshipRecall.recent(listOf(first,second,promise),seeded().state(),0){"合成原话"}
        assertEquals(true,events.single{it["消息"]==1L}["可修复心结"])
        assertTrue(RelationInputContract.instruction(4).contains("关联不确定就省略"))
        assertFalse(Gson().toJson(events).contains("tension"))
    }
    @Test fun naturalDecayContinuesForEachEventBeforeAndAfterTargetedRepair(){
        val a=seeded();val b=seeded();val time=5*day
        a.apply(fulfilled(first.source,time));b.apply(fulfilled(null,time))
        assertEquals(b.impacts.getValue(first.source).tension*.5,a.impacts.getValue(first.source).tension,1e-12)
        assertEquals(b.impacts.getValue(second.source).tension,a.impacts.getValue(second.source).tension,1e-12)
        val repaired=a.impacts.getValue(first.source).tension;val untouched=a.impacts.getValue(second.source).tension
        a.advance(12*day);b.advance(12*day)
        // 心结在弥补前后都不自己变淡。
        assertEquals(repaired,a.impacts.getValue(first.source).tension,0.0);assertEquals(untouched,a.impacts.getValue(second.source).tension,0.0)
        assertEquals(b.impacts.getValue(first.source).tension*.5,a.impacts.getValue(first.source).tension,1e-12)
    }
    @Test fun partialExplanationAndApologyKeepTheRemainingGrudgeAddressableThroughCompaction(){
        val c=seeded();c.apply(row(4).copy(explains=first.source,releaseKind="有原因",excuse="工作忙",formatVersion=2,explanationPolicy=1))
        c.apply(row(5).copy(previous=first.source,correction="道歉和好"));c.advance(3*RelationshipProjector.HOUR);c.compact()
        assertTrue(first.source in c.state().repairable)
        val restored=RelationSnapshotCodec.decode(RelationSnapshotCodec.encode(c),RelationStart.FRIENDS,p)!!
        val before=restored.impacts.getValue(first.source).tension
        restored.apply(fulfilled(first.source,3*RelationshipProjector.HOUR).copy(source=SourceRef("r",6,1)))
        assertEquals(before*.5,restored.impacts.getValue(first.source).tension,1e-12)
    }
    @Test fun coldReplayAndIncrementalSnapshotsAgreeAndWithdrawnSourcesCannotKeepRepairing(){
        val store=MemoryRelationSnapshots();val cache=RelationProjectionCache(store)
        val rows=listOf(first,second,promise,fulfilled(first.source))
        fun project(c:RelationProjectionCache,values:List<RelationInput>)=c.project(p,values,7*day,RelationStart.FRIENDS,emptyList())
        project(cache,rows.take(3));val actual=project(cache,rows)
        assertEquals(actual,project(RelationProjectionCache(store),rows));assertEquals(actual,project(RelationProjectionCache(),rows))
        for(changed in listOf(rows.map{if(it.source==first.source)it.copy(status="disputed")else it},
            rows.map{if(it.source==fulfilled().source)it.copy(status="disputed")else it},
            rows.map{if(it.source==first.source)it.copy(source=it.source.copy(version=2))else it})){
            assertEquals(project(RelationProjectionCache(),changed),project(cache,changed))
        }
        val reference=RelationCursor(RelationStart.FRIENDS,p,compact=false);rows.forEach{reference.apply(it)};reference.advance(7*day);reference.compact()
        assertEquals(actual,reference.state())
    }
    @Test fun repairReferencesSurviveCurrentBackupIdRemapping(){
        val remapped=BackupRemapper("r","copy",emptyMap(),mapOf(1L to 101L,3L to 103L,4L to 104L)).json(Gson().toJsonTree(fulfilled(first.source)))
        val restored=Gson().fromJson(remapped,RelationInput::class.java)
        assertEquals(SourceRef("copy",101,1),restored.repairs);assertEquals(SourceRef("copy",103,1),restored.fulfills)
        assertTrue(RelationInputContract.validStored(restored))
    }
    @Test fun invalidOrOmittedParsedRepairKeepsTheFulfillment(){
        for(target in listOf("999","\"拿不准\"","null")){
            val raw="""{"消息":"4","信息类别":"关于关系","P":"无","C":"中","V":"无","B":"无","兑现约定":"3","修复心结":$target}"""
            val parsed=RelationInputContract.parse(JsonParser.parseString(raw),SourceRef("r",4,1),0,0,listOf(first,second,promise)){_,_->}!!
            val checked=RelationshipRecall.validate(parsed,seeded().state(),setOf(1L,3L)){_,_->}
            assertNull(checked.repairs);assertEquals(.6,checked.c!!,0.0);assertEquals(promise.source,checked.fulfills)
            assertTrue(RelationInputContract.validStored(checked))
        }
    }
    @Test fun replySubmissionKeepsValidRepairButBlocksRepetitionAndHypotheticalClaims()=runBlocking{
        val current=SourceRef("r",4,1)
        val config=ModelConfig("synthetic","合成","custom","https://example.invalid","synthetic",64000,2048)
        val context=RoleContext("r",current,(listOf(first,second,promise).map{it.source}+current).associateBy{it.turnId},emptyList(),emptyList(),emptyList(),emptyList(),
            relationInputs=listOf(first,second,promise),relationRecent=listOf(mapOf("消息" to 1L),mapOf("消息" to 3L)),relationState=seeded().state())
        for((claim,event,allowed) in listOf(Triple("reported_event","new_event",true),Triple("reported_event","repetition",false),Triple("hypothesis","new_event",false))){
            var calls=0
            val raw="""{"appraisal":{"event":"补看电影","meaning":"兑现","certainty":"reported","relevance":"回应","attribution":"user_report","situation":"chat","eventRelation":"$event","uncertainty":[],"evidence":[{"source":4,"layer":"fact"}]},"关系输入":{"消息":"4","信息类别":"关于关系","P":"无","C":"中","V":"无","B":"无","兑现约定":"3","修复心结":"1","弥补已完成":true}}"""
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("合成回复")}}
            val reply=RolePlanner(client).respond(config,"synthetic","合成当前原话",ExperienceProposal(claimKind=claim),context,0,TextChatRequest(listOf(WireMessage("user","合成当前原话"))),{true},{0},false){request,record->
                record?.invoke(raw)
                client.complete(config,"synthetic",request)
            }
            assertEquals(1,calls);assertEquals("合成回复",reply.text)
            assertEquals(if(allowed)first.source else null,reply.relationInput!!.repairs)
            assertEquals(if(allowed)promise.source else null,reply.relationInput.fulfills)
            assertTrue(RelationInputContract.validStored(reply.relationInput))
        }
    }
}
