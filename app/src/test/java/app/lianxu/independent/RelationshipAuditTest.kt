package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
class RelationshipAuditTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.STEADY.parameters
    private fun row(id:Long,at:Long)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系",formatVersion=2)
    @Test fun fullRefundOnceWithoutExcuseAndLegacyVoid(){
        val bad=row(1,0).copy(v=.9)
        val release=row(2,10*day).copy(explains=bad.source,releaseKind="真误会",correction="不介意了")
        val c=RelationCursor(RelationStart.FRIENDS,p);c.apply(bad);c.apply(release)
        assertEquals(.5,c.trust,1e-12);assertTrue(c.excuses.isEmpty());assertFalse(bad.source in c.pending)
        val first=c.trust;c.apply(row(3,10*day).copy(explains=bad.source,excuse="工作忙",releaseKind="有原因"));assertEquals(first,c.trust,0.0)
        assertTrue(c.excuses.isEmpty())
        val legacy=release.copy(explains=null,previous=bad.source,correction="误会",formatVersion=1,releaseKind=null)
        val actual=RelationshipProjector.project(p,listOf(bad,legacy),10*day,RelationStart.FRIENDS)
        val baseline=RelationshipProjector.project(p,listOf(legacy),10*day,RelationStart.FRIENDS,contactTimes=listOf(0,10*day))
        assertEquals(baseline,actual)
    }
    @Test fun zeroLossAndVisibility(){
        val c=RelationCursor(RelationStart.FRIENDS,p.copy(sensitivity=0.0));val gap=row(1,0).copy(disappointment=.6)
        c.apply(gap);assertTrue(c.pending.isEmpty())
        val bad=row(2,day).copy(b=.6);c.apply(bad);assertTrue(bad.source in c.pending)
        val issues=mutableListOf<String>();val release=row(3,day).copy(explains=bad.source,releaseKind="真误会")
        assertNull(RelationshipRecall.validate(release,c.state(),emptySet()){_,v->issues.add(v)}.explains);assertEquals(listOf("NOT_SEEN"),issues)
        assertEquals(bad.source,RelationshipRecall.validate(release,c.state(),setOf(2)){_,_->fail()}.explains)
    }
    @Test fun localMidnightRetention(){
        fun time(s:String)=LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows=listOf(1 to time("2026-08-01T10:00"),2 to time("2026-10-01T23:59"),3 to time("2026-10-02T00:01"))+(4..60).map{it to time("2026-11-04T10:00")}
        val kept=RelationSnapshotRetention.positions(rows,time("2026-11-04T20:00"))
        assertTrue(kept.containsAll(listOf(1,2,3)));assertTrue(kept.size<=141)
    }
    @Test fun clarityContentAndFallback(){
        val ref=SourceRef("r",1,1)
        val m=StoredMemory("m","r",MemoryCategory.EXPERIENCE_MEMORY,"全文",gist="大意",trace="残影",evidence=listOf(MemoryEvidence(ref,"user","原话","原话",0,"Asia/Shanghai")))
        for((clarity,expected) in listOf(80.0 to "原话",40.0 to "大意",10.0 to "残影"))assertEquals(expected,RelationshipRecall.content(ref,"原话",listOf(m)){clarity})
        assertEquals("原话",RelationshipRecall.content(ref,"原话",emptyList()){0.0})
    }
}
