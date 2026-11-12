package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RelationshipBasisTest {
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系")
    private val hurt=row(1).copy(v=.9)
    private val other=row(2).copy(b=.6)
    private fun repair(id:Long,basis:SourceRef?=null,target:SourceRef=hurt.source)=row(id).copy(repairCompleted=true,repairs=target,repairBasis=basis)
    private fun cursor()=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(hurt);it.apply(other)}
    private fun parse(id:Long,extra:String,prior:List<RelationInput>)=RelationInputContract.parse(JsonParser.parseString("""{"消息":"$id","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无","弥补已完成":true,"修复心结":"1"$extra}"""),SourceRef("r",id,1),0,0,prior){_,_->}!!
    @Test fun samePairAcrossMessagesIsOnceButNewActionsAndOtherTargetsCanRepair(){
        val c=cursor();val first=repair(3);val a=c.impacts.getValue(hurt.source).tension;val b=c.impacts.getValue(other.source).tension
        c.apply(first)
        val duplicate=parse(4,",\"弥补依据\":3",listOf(hurt,other,first))
        val checked=RelationshipRecall.validate(duplicate,c.state(),setOf(1L,3L),setOf(hurt.source,other.source,first.source)){_,_->}
        assertNull(checked.repairs);assertTrue(RelationInputContract.validStored(checked));c.apply(checked);assertEquals(a*.5,c.impacts.getValue(hurt.source).tension,1e-12)
        c.apply(repair(5));assertEquals(a*.25,c.impacts.getValue(hurt.source).tension,1e-12)
        c.apply(repair(6,first.source,other.source));assertEquals(b*.5,c.impacts.getValue(other.source).tension,1e-12)
        assertEquals(3,c.repairPairs.size)
    }
    @Test fun explicitInvalidBasisNeverDefaultsToSelfAndPositiveInputSurvives(){
        val action=row(3).copy(category="普通信息")
        for(raw in listOf("null","{}","999","5","\"unknown\"")){
            val c=cursor();val before=c.state().tension
            val parsed=parse(4,",\"弥补依据\":$raw",listOf(hurt,other,action))
            val checked=RelationshipRecall.validate(parsed,c.state(),setOf(1L,3L),setOf(hurt.source,action.source)){_,_->}
            assertNull(checked.repairs);assertTrue(RelationInputContract.validStored(checked));assertFalse(checked.repairCompleted);assertEquals(.3,checked.p!!,0.0)
            c.apply(checked);assertEquals(before,c.state().tension,0.0);assertTrue(c.repairPairs.isEmpty())
        }
        for(basis in listOf(SourceRef("foreign",3,1),SourceRef("r",3,2),SourceRef("r",5,1))){
            val checked=RelationshipRecall.validate(repair(4,basis),cursor().state(),setOf(1L,3L),setOf(hurt.source,action.source)){_,_->}
            assertNull(checked.repairs);assertTrue(RelationInputContract.validStored(checked))
        }
        val unseen=RelationshipRecall.validate(repair(4,action.source),cursor().state(),setOf(1L),setOf(action.source)){_,_->}
        assertNull(unseen.repairs)
        val valid=RelationshipRecall.validate(repair(4,action.source),cursor().state(),setOf(1L,3L),setOf(action.source)){_,_->fail()}
        assertNotNull(valid.repairs)
    }
    @Test fun snapshotRoundTripAndColdReplayPreservePairsAndSourceWithdrawalRebuildsThem(){
        val basis=row(3).copy(category="普通信息")
        val rows=listOf(hurt,other,basis,repair(4,basis.source),repair(5,basis.source))
        val store=MemoryRelationSnapshots();val cache=RelationProjectionCache(store)
        fun project(c:RelationProjectionCache,r:List<RelationInput>)=c.project(p,r,7*RelationshipProjector.DAY,RelationStart.FRIENDS,emptyList())
        val state=project(cache,rows)
        assertEquals(1,state.repairPairs.size)
        assertEquals(state,project(RelationProjectionCache(store),rows));assertEquals(state,project(RelationProjectionCache(),rows))
        val encoded=RelationSnapshotCodec.encode(cache.forecast()!!)
        val restored=RelationSnapshotCodec.decode(encoded,RelationStart.FRIENDS,p)!!
        val before=restored.state().tension;restored.apply(repair(6,basis.source));assertEquals(before,restored.state().tension,0.0)
        for(changed in listOf(rows.filter{it.source!=basis.source},rows.map{if(it.source==basis.source)it.copy(source=basis.source.copy(version=2))else it},rows.map{if(it.source==basis.source)it.copy(status="disputed")else it})){
            val result=project(cache,changed);assertTrue(result.repairPairs.isEmpty());assertEquals(project(RelationProjectionCache(),changed),result)
        }
        val withdrawn=rows.filter{it.source.turnId!=4L}
        assertEquals(1,project(cache,withdrawn).repairPairs.size) // The later valid claim may now book the pair.
        assertEquals(project(RelationProjectionCache(),withdrawn),project(cache,withdrawn))
    }
    @Test fun remappingPreservesBothRefsAndInvalidBasisOnRestoreOnlyDropsRepair(){
        val action=repair(4,SourceRef("r",3,1)).copy(c=.6,fulfills=other.source)
        val mapped=BackupRemapper("r","copy",emptyMap(),mapOf(1L to 101L,2L to 102L,3L to 103L,4L to 104L)).json(Gson().toJsonTree(action))
        val remapped=Gson().fromJson(mapped,RelationInput::class.java)
        assertEquals(SourceRef("copy",103,1),remapped.repairBasis);assertEquals(SourceRef("copy",101,1),remapped.repairs)
        for(mode in listOf("revision","deleted","blocked")){
            val root=JsonParser.parseString("""{"roles":[{"character":{"id":"r"},"tables":{"messages":[{"id":1,"body":"旧事"},{"id":2,"body":"约定"},{"id":3,"body":"旧行动"},{"id":4,"body":"已完成"}],"chat_turns":[{"user_message_id":1,"source_revision":1},{"user_message_id":2,"source_revision":1},{"user_message_id":3,"source_revision":1},{"user_message_id":4,"source_revision":1}],"message_state":[],"companion_blocks":[],"companion_sources":[]},"preferences":{}}]}""").asJsonObject
            val tables=root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject
            when(mode){"revision"->tables["chat_turns"].asJsonArray[2].asJsonObject.addProperty("source_revision",2);"deleted"->tables["message_state"].asJsonArray.add(JsonParser.parseString("""{"message_id":3,"deleted":1}"""));else->tables["companion_blocks"].asJsonArray.add(JsonParser.parseString("""{"source_id":3}"""))}
            val saved=JsonObject().apply{addProperty("source_id",4);addProperty("revision",1);addProperty("proposal",Gson().toJson(ExperienceProposal(relationInput=action)))}
            tables["companion_sources"].asJsonArray.add(saved)
            assertEquals(0,RelationshipBackup.sanitize(root).skipped)
            val cleaned=Gson().fromJson(saved["proposal"].asString,ExperienceProposal::class.java).relationInput!!
            assertNull(cleaned.repairs);assertNull(cleaned.repairBasis);assertFalse(cleaned.repairCompleted)
            assertEquals(action.c,cleaned.c);assertEquals(action.fulfills,cleaned.fulfills)
        }
    }
    @Test fun oldCompletedActionsCanBeRecalledWithReadableSourceWithoutInventingSemantics(){
        val c=cursor();val action=repair(3);c.apply(action)
        assertFalse(RelationshipRecall.matches(action,c.state(),"负面"))
        assertFalse(RelationshipRecall.matches(action,c.state(),"约定"))
        assertTrue(RelationshipRecall.matches(action,c.state(),"行动"))
        val item=RelationshipRecall.event(action,c.state(),"已经补看电影")
        assertEquals(3L,item["消息"]);assertEquals("已经补看电影",item["内容"]);assertEquals(true,item["可引用行动来源"])
        assertFalse(item.containsKey("tension"));assertFalse(item.containsKey("弥补已完成"))
    }
    @Test fun defaultCurrentBasisIsNotASemanticDuplicateDetector(){
        val c=cursor();val before=c.state().tension
        c.apply(repair(3));c.apply(repair(4)) // Model says these are distinct real actions; program cannot infer otherwise.
        assertEquals(2,c.repairPairs.size);assertTrue(c.state().tension<before)
    }
}
