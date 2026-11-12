package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 撤回消息后，关系分旁边「因为哪句话」退回这一项上一条造成变化的消息；没有更早的就留空。 */
class RelationCauseFallbackTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.STEADY.parameters
    private fun cursor()=RelationCursor(RelationStart.FRIENDS,p)
    private fun row(id:Long,at:Long=0)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")

    @Test fun withdrawingTheLatestCauseFallsBackToThePreviousMessageOfThatItem(){
        val c=cursor();c.apply(row(1).copy(p=.6));val first=c.causes.keys.toSet()
        c.apply(row(2,day).copy(p=.6));val second=c.causes.filterValues{it.source==row(2).source}.keys
        assertTrue(first.isNotEmpty());assertTrue(second.isNotEmpty())
        c.retract(row(2).source)
        for(k in second)if(k in first)assertEquals(k,row(1).source,c.causes[k]?.source) else assertNull(k,c.causes[k])
        // 和从头只算第一条的结果一样。
        val fresh=cursor();fresh.apply(row(1).copy(p=.6));fresh.advance(day)
        assertEquals(fresh.causes,c.causes)
    }
    @Test fun withdrawingTheOnlyCauseLeavesTheItemBlank(){
        val c=cursor();c.apply(row(1).copy(p=.6));assertTrue(c.causes.isNotEmpty())
        c.retract(row(1).source)
        assertTrue(c.causes.isEmpty());assertTrue(c.causeHistory.isEmpty())
    }
    @Test fun withdrawingAnOlderCauseKeepsTheLatestOne(){
        val c=cursor();c.apply(row(1).copy(p=.6));c.apply(row(2,day).copy(p=.6))
        val before=c.causes.toMap()
        c.retract(row(1).source)
        assertEquals(before.filterValues{it.source==row(2).source},c.causes.filterValues{it.source==row(2).source})
        assertTrue(c.causes.values.none{it.source==row(1).source})
    }
    @Test fun aNegativeItemFallsBackTooAndKeepsItsDirection(){
        val c=cursor();c.apply(row(1).copy(v=.6));val tension=c.causes.getValue("tension")
        c.apply(row(2,day).copy(v=.6));assertEquals(row(2).source,c.causes.getValue("tension").source)
        c.retract(row(2).source)
        assertEquals(tension,c.causes.getValue("tension"))
    }
    @Test fun snapshotKeepsTheWholeChainSoARestoredCursorFallsBackTheSameWay(){
        val c=cursor();c.apply(row(1).copy(p=.6));c.apply(row(2,day).copy(p=.3));c.apply(row(3,2*day).copy(p=.6))
        val raw=RelationSnapshotCodec.encode(c)
        val back=RelationSnapshotCodec.decode(raw,RelationStart.FRIENDS,p)!!
        assertEquals(c.causeHistory,back.causeHistory);assertEquals(raw,RelationSnapshotCodec.encode(back))
        c.retract(row(3).source);back.retract(row(3).source)
        assertEquals(c.causes,back.causes);assertTrue(back.causes.values.none{it.source==row(3).source})
        c.retract(row(2).source);back.retract(row(2).source)
        assertEquals(c.causes,back.causes)
    }
    @Test fun eachItemRemembersOnlyARecentWindow(){
        val c=cursor();for(i in 1..(RelationCursor.CAUSE_HISTORY+8).toLong())c.apply(row(i,i*day).copy(p=.3))
        assertTrue(c.causeHistory.values.all{it.size<=RelationCursor.CAUSE_HISTORY})
        assertNotNull(RelationSnapshotCodec.decode(RelationSnapshotCodec.encode(c),RelationStart.FRIENDS,p))
    }
}