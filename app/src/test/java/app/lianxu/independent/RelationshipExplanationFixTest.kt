package app.lianxu.independent
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class RelationshipExplanationFixTest {
    private val p=RelationTemplate.STEADY.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系",formatVersion=2,explanationPolicy=1)
    @Test fun remainingDisappointmentOnlyAndLegacy(){
        for(kind in listOf("真误会","有原因")) {
            val c=RelationCursor(RelationStart.FRIENDS,p);val bad=row(1).copy(disappointment=.6)
            c.apply(bad);assertEquals(0.0,c.pending.getValue(bad.source).loss,0.0)
            c.advance(2*RelationshipProjector.DAY);val remaining=c.state().disappointment;val trust=c.trust
            assertTrue(remaining>0);assertTrue(remaining<.6)
            val release=row(2).copy(at=2*RelationshipProjector.DAY,explains=bad.source,releaseKind=kind)
            c.apply(release);assertEquals(trust,c.trust,0.0)
            assertEquals(remaining*(if(kind=="真误会")0.0 else .3),c.state().disappointment,1e-12)
        }
        val old=RelationCursor(RelationStart.FRIENDS,p);old.apply(row(1).copy(disappointment=.6,explanationPolicy=0));assertTrue(old.pending.isEmpty())
        val zero=RelationCursor(RelationStart.FRIENDS,p.copy(sensitivity=0.0));zero.apply(row(1).copy(disappointment=.6));assertTrue(zero.pending.isEmpty())
    }
    @Test fun gapIgnoredAndOldColdReplayStaySeparated(){
        for(input in listOf(row(1).copy(gap="中",importance="高"),row(1).copy(ignored=.1))){
            val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(input)
            assertTrue(input.source in c.pending);assertEquals(0.0,c.pending.getValue(input.source).loss,0.0)
        }
        val gson=com.google.gson.Gson()
        val oldJson=gson.toJsonTree(row(1).copy(disappointment=.6,explanationPolicy=0)).asJsonObject
        oldJson.remove("explanationPolicy")
        val old=gson.fromJson(oldJson,RelationInput::class.java)
        assertEquals(0,old.explanationPolicy)
        val cursor=RelationCursor(RelationStart.FRIENDS,p);cursor.apply(old)
        val before=cursor.state()
        cursor.apply(row(2).copy(explains=null,explanationPolicy=0))
        assertEquals(before.disappointment,cursor.state().disappointment,0.0);assertTrue(cursor.pending.isEmpty())
    }
    @Test fun invalidExplanationsPreserveFieldsAndValidClears(){
        val target=row(1).copy(v=.6,promise=true)
        fun parse(id:Int,judgment:String):Pair<RelationInput,List<String>>{
            val issues=mutableListOf<String>()
            val json="""{"消息":"2","信息类别":"关于关系","P":"轻","C":"轻","V":"中","B":"重","约定":true,"兑现约定":"1","指向之前":{"消息":"1","方式":"道歉和好"},"没达到预期":{"期待":"高","重要性":"高","落差":"大"},"解释":{"事件":"$id","判断":"$judgment"}}"""
            return RelationInputContract.parse(JsonParser.parseString(json),SourceRef("r",2,1),1,0,listOf(target)){_,code->issues.add(code)}!! to issues
        }
        val base=parse(1,"真误会").first
        for((id,kind,code) in listOf(Triple(999,"真误会","INVALID_TARGET"),Triple(1,"坏判断","JUDGMENT_REQUIRED"))){
            val (value,issues)=parse(id,kind);assertTrue(code in issues);assertNull(value.explains)
            assertEquals(base.copy(explains=null,releaseKind=value.releaseKind),value)
        }
        val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(target)
        for((state,seen,code) in listOf(Triple(c.state(),emptySet<Long>(),"NOT_SEEN"),Triple(c.state().copy(ledger=emptyMap()),setOf(1L),"NOT_IN_LEDGER"))){
            val issues=mutableListOf<String>()
            val value=RelationshipRecall.validate(base,state,seen){_,s->issues.add(s)}
            assertTrue(code in issues);assertNull(value.explains)
            assertEquals(base.p,value.p);assertEquals(base.c,value.c);assertEquals(base.v,value.v);assertEquals(base.b,value.b)
            assertEquals(base.disappointment,value.disappointment);assertEquals(base.promise,value.promise)
        }
        val valid=RelationshipRecall.validate(base,c.state(),setOf(1L)){_,_->fail()}
        assertNull(valid.p);assertNull(valid.disappointment);assertFalse(valid.promise);assertEquals("不介意了",valid.correction)
        val missing=RelationCursor(RelationStart.FRIENDS,p);missing.apply(row(2).copy(explains=target.source,p=.6));assertTrue(missing.trust>RelationStart.FRIENDS.trust)
    }
}
