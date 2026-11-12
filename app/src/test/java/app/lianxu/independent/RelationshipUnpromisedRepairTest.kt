package app.lianxu.independent
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class RelationshipUnpromisedRepairTest {
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系")
    private val hurt=row(1).copy(v=.9)
    private val other=row(2).copy(b=.6)
    private fun cursor()=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(hurt);it.apply(other)}
    @Test fun completedRemedyNeedsNoPromiseAndOnlyRepairsItsEvent(){
        val c=cursor();val before=c.impacts.toMap();val trust=c.trust
        val action=row(3).copy(repairs=hurt.source,repairCompleted=true)
        val checked=RelationshipRecall.validate(action,c.state(),setOf(1L)){_,_->fail()}
        assertTrue(RelationInputContract.validStored(checked));c.apply(checked)
        assertEquals(before.getValue(hurt.source).tension*.5,c.impacts.getValue(hurt.source).tension,1e-12)
        assertEquals(before.getValue(other.source).tension,c.impacts.getValue(other.source).tension,0.0)
        assertEquals(trust,c.trust,0.0);assertTrue(c.promises.isEmpty())
        val once=c.state().tension;c.apply(action);assertEquals(once,c.state().tension,0.0)
        val rows=listOf(hurt,other,action)
        assertEquals(RelationshipProjector.project(p,rows,0),RelationshipProjector.project(p,rows+action,0))
    }
    @Test fun unrelatedKindnessFutureCompensationAndUncertaintyDoNotRepair(){
        for(action in listOf(row(3).copy(p=.6),row(3).copy(promise=true,repairs=hurt.source),row(3).copy(repairCompleted=true),row(3).copy(repairs=hurt.source))){
            val c=cursor();val before=c.state().tension
            val checked=RelationshipRecall.validate(action,c.state(),setOf(1L)){_,_->}
            assertNull(checked.repairs);c.apply(checked);assertEquals(before,c.state().tension,0.0)
        }
    }
    @Test fun completedUnpromisedRemedyParsesAndDecaysNormally(){
        val raw="""{"消息":"3","信息类别":"关于关系","P":"无","C":"无","V":"无","B":"无","修复心结":"1","弥补已完成":true}"""
        val action=RelationInputContract.parse(JsonParser.parseString(raw),SourceRef("r",3,1),0,0,listOf(hurt,other))!!
        assertTrue(RelationInputContract.validStored(action));assertNull(action.fulfills)
        val c=cursor();c.apply(action);val before=c.state().tension;c.advance(7*RelationshipProjector.DAY)
        assertEquals(before,c.state().tension,0.0)
    }
}