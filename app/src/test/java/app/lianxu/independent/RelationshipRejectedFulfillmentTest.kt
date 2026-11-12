package app.lianxu.independent
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class RelationshipRejectedFulfillmentTest {
 private val p=RelationTemplate.DIRECT.parameters
 private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系")
 private val hurt=row(1).copy(v=.9)
 private val promise=row(2).copy(promise=true)
 private fun parse(rows:List<RelationInput>,claim:String)=RelationInputContract.parse(JsonParser.parseString("""{"消息":"4","信息类别":"关于关系","P":"无","C":"中","V":"无","B":"无","修复心结":"1","弥补已完成":true$claim}"""),SourceRef("r",4,1),0,0,rows){_,_->}!!
 @Test fun rejectedClaimsNeverBecomeUnpromisedRepairsThroughTheWholePipeline(){
  for(mode in listOf("fulfilled","missing","foreign","version","unseen","disputed","malformed")){
   val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(hurt);c.apply(promise)
   if(mode=="fulfilled")c.apply(row(3).copy(c=.6,fulfills=promise.source,repairs=hurt.source,repairCompleted=true))
   val prior=when(mode){"foreign"->promise.copy(source=SourceRef("other",2,1));"version"->promise.copy(source=promise.source.copy(version=2));"disputed"->promise.copy(status="disputed");else->promise}
   val claim=when(mode){"missing"->"99";"malformed"->"{}";else->"2"}
   val parsed=parse(listOf(hurt,prior),",\"兑现约定\":$claim")
   val checked=RelationshipRecall.validate(parsed,c.state(),if(mode=="unseen")setOf(1L)else setOf(1L,2L)){_,_->}
   assertNull(mode,checked.fulfills);assertNull(mode,checked.repairs);assertFalse(mode,checked.repairCompleted)
   val before=c.state().tension;c.apply(checked);assertEquals(mode,before,c.state().tension,0.0)
   val again=RelationshipRecall.validate(checked,c.state(),setOf(1L,2L)){_,_->};assertNull(again.repairs)
  }
 }
 @Test fun genuineAbsentPromiseStillRepairsAndOtherEventsKeepDecaying(){
  val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(hurt);val other=row(2).copy(b=.6);c.apply(other)
  val a=c.impacts.getValue(hurt.source).tension;val b=c.impacts.getValue(other.source).tension
  val checked=RelationshipRecall.validate(parse(listOf(hurt,other),""),c.state(),setOf(1L)){_,_->}
  assertNotNull(checked.repairs);c.apply(checked)
  assertEquals(a*.5,c.impacts.getValue(hurt.source).tension,1e-12);assertEquals(b,c.impacts.getValue(other.source).tension,0.0)
  c.advance(7*RelationshipProjector.DAY);assertEquals(b,c.impacts.getValue(other.source).tension,0.0)
 }
}