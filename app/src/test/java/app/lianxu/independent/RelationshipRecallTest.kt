package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RelationshipRecallTest {
    private val p=RelationTemplate.STEADY.parameters
    private val source=SourceRef("r",1,1)
    private fun state():RelationState {val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(RelationInput(source,0,0,"关于关系",v=.9));return c.state()}
    @Test fun unseenAndForgottenReferencesNeverRefund(){
        val row=RelationInput(SourceRef("r",2,1),1,0,"关于关系",explains=source)
        val issues=mutableListOf<String>()
        assertNull(RelationshipRecall.validate(row,state(),emptySet()){_,code->issues.add(code)}.explains);assertEquals(listOf("NOT_SEEN"),issues)
        issues.clear();assertNull(RelationshipRecall.validate(row,state().copy(ledger=emptyMap()),setOf(1)){_,code->issues.add(code)}.explains);assertEquals(listOf("NOT_IN_LEDGER"),issues)
        assertEquals(source,RelationshipRecall.validate(row,state(),setOf(1)){_,_->fail()}.explains)
    }
    @Test fun recentInputsStayBoundedAsHistoryGrows(){
        val c=RelationCursor(RelationStart.FRIENDS,p)
        val rows=(1L..10000).map{RelationInput(SourceRef("r",it,1),it*RelationshipProjector.DAY/30,0,"关于关系",v=.9)}
        rows.forEach{c.apply(it);c.compact()};val now=rows.last().at
        val recent=RelationshipRecall.recent(rows,c.state(),now)
        assertTrue(recent.size<=10);assertTrue(com.google.gson.Gson().toJson(recent).toByteArray().size<=900)
        assertTrue(recent.all{it.keys.none{k->k.contains("原话")}})
    }
    @Test fun emptyEventsAreInvisibleAndInvalidModePreservesCurrentInput(){
        val bad=RelationInput(source,0,0,"关于关系",v=.9)
        val rows=listOf(bad)
        assertTrue(RelationshipRecall.recent(rows,state(),1){""}.isEmpty())
        assertEquals(1,RelationshipRecall.recent(rows,state(),1){"原话"}.size)
        val issues=mutableListOf<String>()
        val parsed=RelationInputContract.parse(com.google.gson.JsonParser.parseString("""{"消息":"2","信息类别":"关于关系","P":"中","C":"轻","V":"重","B":"轻","没达到预期":{"期待":"高","重要性":"高","落差":"大"},"指向之前":{"消息":"1","方式":"误会"}}"""),SourceRef("r",2,1),1,0,rows){_,code->issues.add(code)}!!
        assertNull(parsed.previous);assertNull(parsed.explains);assertNull(parsed.correction)
        assertEquals(.6,parsed.p!!,0.0);assertEquals(.3,parsed.c!!,0.0)
        assertEquals(.9,parsed.v!!,0.0);assertEquals(.3,parsed.b!!,0.0)
        assertEquals(.81,parsed.disappointment!!,.000001)
        assertTrue("INVALID_TARGET_OR_MODE" in issues)
        val prompt=RelationInputContract.instruction(2)
        assertFalse(prompt.contains("方式:误会"));assertTrue(prompt.contains("判断:真误会/有原因"))
        val cursor=RelationCursor(RelationStart.FRIENDS,p);cursor.apply(bad)
        val before=cursor.state().trust
        cursor.apply(RelationInput(SourceRef("r",2,1),1,0,"关于关系",previous=source,correction="误会",formatVersion=2))
        assertEquals(before,cursor.state().trust,.000001)
    }
    @Test fun appendedInputHashesOnlyNewSuffix(){
        val cache=RelationProjectionCache();val rows=(1L..10).map{RelationInput(SourceRef("r",it,1),it*1000,0,"普通信息")}
        cache.project(p,rows,10000,RelationStart.FRIENDS,emptyList());val n=cache.fingerprints
        cache.project(p,rows+RelationInput(SourceRef("r",11,1),11000,0,"普通信息"),11000,RelationStart.FRIENDS,emptyList())
        assertEquals(1,cache.fingerprints-n)
    }
}
