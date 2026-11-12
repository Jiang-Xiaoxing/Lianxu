package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** 当前感觉：每笔小幅调整、按小事/大事各自变淡、按笔撤回。 */
class RelationshipFeelingTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.STEADY.parameters
    private fun cursor()=RelationCursor(RelationStart.FRIENDS,p)
    private fun row(id:Long,at:Long=0)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")

    @Test fun smallThingsHalveInTwoWeeksBigThingsInTwoMonthsKnotsStay(){
        val small=cursor();small.apply(row(1).copy(p=.6));val gain=small.trust-.5
        small.advance(14*day);assertEquals(.5+gain/2,small.trust,1e-9)
        val big=cursor();big.apply(row(1).copy(p=.9));val bigGain=big.trust-.5
        big.advance(60*day);assertEquals(.5+bigGain/2,big.trust,1e-9)
        val broken=cursor();broken.apply(row(1).copy(v=.6));val tension=broken.state().tension;val loss=.5-broken.trust
        assertTrue(tension>0);assertTrue(broken.impacts.getValue(row(1).source).lasting)
        broken.advance(60*day);assertEquals(tension,broken.state().tension,0.0);assertEquals(.5-loss/2,broken.trust,1e-9)
        val gap=cursor();gap.apply(row(1).copy(disappointment=.2,importance="高",gap="中"));assertTrue(gap.impacts.getValue(row(1).source).lasting)
        val minor=cursor();minor.apply(row(1).copy(disappointment=.2,importance="中",gap="中"));assertFalse(minor.impacts.getValue(row(1).source).lasting)
    }
    @Test fun retractingTakesBackWhatIsLeftIncludingDecay(){
        val only=cursor();only.apply(row(2,3*day).copy(p=.6));only.advance(20*day)
        val both=cursor();both.apply(row(1).copy(p=.3));both.apply(row(2,3*day).copy(p=.6));both.advance(20*day)
        val first=both.impacts.getValue(row(1).source)
        assertTrue(first.trust>0)
        both.retract(row(1).source)
        // 第二笔当时按含第一笔的信任饱和，所以只看第一笔剩下的量被准确减掉。
        val expectedTrust=.5+both.impacts.getValue(row(2).source).trust
        assertEquals(expectedTrust,both.trust,1e-12);assertFalse(row(1).source in both.impacts)
        assertTrue(both.moments.none{it.source==row(1).source})
        assertEquals(.5+only.impacts.getValue(row(2).source).trust,only.trust,1e-12)
    }
    @Test fun retractingAnExplanationGivesTheOldGrudgeBack(){
        val c=cursor();val broken=row(1).copy(v=.6);c.apply(broken)
        val before=c.state();val impact=c.impacts.getValue(broken.source)
        val explained=row(2,3600_000).copy(explains=broken.source,excuse="工作忙");c.apply(explained)
        assertTrue(c.state().tension<before.tension);assertTrue(broken.source !in c.state().ledger)
        c.retract(explained.source)
        assertEquals(impact.tension,c.impacts.getValue(broken.source).tension,1e-12)
        assertTrue(broken.source in c.state().ledger);assertTrue(c.state().explanations.isEmpty());assertNull(c.excuses["工作忙"])
    }
    @Test fun retractingARepairAndAFulfilmentUndoesOnlyThatEntry(){
        val c=cursor();val broken=row(1).copy(v=.6);c.apply(broken)
        val promise=row(2).copy(promise=true);c.apply(promise)
        val knot=c.impacts.getValue(broken.source).tension
        val done=row(3,day).copy(c=.6,fulfills=promise.source,repairs=broken.source,repairCompleted=true);c.apply(done)
        assertEquals(knot*.5,c.impacts.getValue(broken.source).tension,1e-12);assertEquals(done.source,c.promises.getValue(promise.source).fulfilledBy)
        c.retract(done.source)
        assertEquals(knot,c.impacts.getValue(broken.source).tension,1e-12);assertNull(c.promises.getValue(promise.source).fulfilledBy);assertTrue(c.repairPairs.isEmpty())
        c.retract(promise.source);assertTrue(c.promises.isEmpty())
    }
    @Test fun lateEntryCountsFromItsOwnTime(){
        val c=cursor();c.advance(28*day)
        c.apply(row(1).copy(p=.6))
        val fresh=cursor();fresh.apply(row(1).copy(p=.6))
        assertEquals(fresh.impacts.getValue(row(1).source).trust*.25,c.impacts.getValue(row(1).source).trust,1e-12)
        assertEquals(0.0,c.state().happy,1e-6)
    }
    @Test fun momentsUseFixedReactionsWithDirection(){
        val c=cursor()
        c.apply(row(1,0).copy(p=.6));c.apply(row(2,day).copy(disappointment=.2,gap="中",importance="中"));c.apply(row(3,2*day).copy(category="普通信息"))
        c.apply(row(4,3*day).copy(p=.9));c.apply(row(5,4*day).copy(v=.6))
        assertEquals(listOf("broken_big","moved","gap","warm"),c.moments.map{it.reaction})
        assertEquals(listOf(false,true,false,true),c.moments.map{it.positive})
        assertEquals(c.moments,c.state().moments)
    }
    @Test fun codecKeepsFeelingUndoAndMoments(){
        val c=cursor();c.apply(row(1).copy(v=.6));c.apply(row(2,3600_000).copy(explains=row(1).source,excuse="工作忙"));c.apply(row(3,day).copy(p=.6))
        val raw=RelationSnapshotCodec.encode(c)
        val back=RelationSnapshotCodec.decode(raw,RelationStart.FRIENDS,p)!!
        assertEquals(c.state(),back.state());assertEquals(c.undo,back.undo);assertEquals(raw,RelationSnapshotCodec.encode(back))
        back.retract(row(2).source);c.retract(row(2).source);assertEquals(c.state(),back.state())
    }
    private fun feel(saved:RelationFeelingSaved?,rows:List<RelationInput>,now:Long)=
        RelationFeeling.update("r",saved,p,p,RelationStart.FRIENDS,rows,rows.map{it.at},emptyList(),emptyList(),now)
    @Test fun savedFeelingOnlyAddsNewEntriesAndMatchesAFreshCount(){
        val rows=listOf(row(1,0).copy(p=.6),row(2,day).copy(v=.6),row(3,2*day).copy(p=.3))
        val first=feel(null,rows.take(2),day);assertTrue(first.rebuilt)
        val second=feel(first.saved,rows,2*day);assertFalse(second.rebuilt);assertEquals(1,second.added);assertEquals(0,second.retracted)
        val cold=feel(null,rows,2*day)
        val a=second.cursor.state();val b=cold.cursor.state()
        listOf(a.trust to b.trust,a.closeness to b.closeness,a.tension to b.tension,a.disappointment to b.disappointment,a.happy to b.happy).forEach{(x,y)->assertEquals(y,x,1e-12)}
        val same=feel(second.saved,rows,2*day);assertFalse(same.changed)
    }
    @Test fun deletedOrRegeneratedEntryIsRetractedAlone(){
        val rows=listOf(row(1,0).copy(p=.6),row(2,day).copy(v=.6),row(3,2*day).copy(p=.3))
        val saved=feel(null,rows,3*day).saved
        val removed=feel(saved,rows.filter{it.source.turnId!=2L},3*day)
        assertEquals(1,removed.retracted);assertEquals(0,removed.added);assertEquals(0.0,removed.cursor.state().tension,0.0)
        assertTrue(removed.cursor.moments.none{it.source.turnId==2L})
        val kept=feel(saved,rows,3*day).cursor
        assertEquals(kept.impacts.getValue(row(3).source).trust,removed.cursor.impacts.getValue(row(3).source).trust,0.0)
        // 重新生成：同一条消息换了版本，只撤旧的一笔、按原来的时间记新的一笔。
        val regenerated=rows.map{if(it.source.turnId==2L)it.copy(source=SourceRef("r",2,2),v=null,p=.3)else it}
        val redo=feel(saved,regenerated,3*day)
        assertEquals(1,redo.retracted);assertEquals(1,redo.added);assertEquals(0.0,redo.cursor.state().tension,0.0)
        assertTrue(redo.cursor.impacts.getValue(SourceRef("r",2,2)).trust>0)
        val disputed=feel(saved,rows.map{if(it.source.turnId==2L)it.copy(status="disputed")else it},3*day)
        assertEquals(1,disputed.retracted);assertEquals(0.0,disputed.cursor.state().tension,0.0)
    }
    @Test fun withdrawnMessageAlsoTakesBackItsContact(){
        val rows=listOf(row(1,0).copy(p=.6))
        fun feelWith(saved:RelationFeelingSaved?,contacts:List<Long>,now:Long)=
            RelationFeeling.update("r",saved,p,p,RelationStart.FRIENDS,rows,contacts,emptyList(),emptyList(),now)
        fun stateAt(r:RelationFeelingResult,now:Long)=r.cursor.copy().also{it.advance(now)}.state()
        val both=feelWith(null,listOf(0L,2*day),2*day)
        assertFalse(feelWith(both.saved,listOf(0L,2*day),2*day).changed)
        // 第二次联系那条消息被撤回：当没发生过，想念照常上涨，和从来没有那次联系一样。
        // 想念按存下时刻分段往前走，和一口气走到底只差分段带来的一点点（千分之一以内）。
        val withdrawn=feelWith(both.saved,listOf(0L),3*day);val never=feelWith(null,listOf(0L),3*day);val kept=feelWith(both.saved,listOf(0L,2*day),3*day)
        val a=stateAt(withdrawn,3*day);val b=stateAt(never,3*day)
        assertTrue(withdrawn.changed);assertEquals(0L,withdrawn.saved.lastContact)
        assertEquals(b.missing,a.missing,1e-3);assertEquals(b.lastContact,a.lastContact);assertTrue(a.missing>stateAt(kept,3*day).missing)
        assertEquals(b.trust,a.trust,1e-12);assertEquals(b.closeness,a.closeness,1e-12)
        // 之后再联系照常记上。
        val later=feelWith(withdrawn.saved,listOf(0L,4*day),4*day);assertEquals(4*day,stateAt(later,4*day).lastContact)
        // 旧存档没有联系记录：不去猜，只补记下来。
        val old=feelWith(both.saved.copy(contactPrint=null),listOf(0L,2*day),2*day);assertTrue(old.changed);assertNotNull(old.saved.contactPrint)
    }
    @Test fun otherEngineVersionStartsOver(){
        val rows=listOf(row(1,0).copy(p=.6))
        val saved=feel(null,rows,day).saved.copy(engine=1)
        assertTrue(feel(saved,rows,day).rebuilt)
    }
    @Test fun momentSentencesAreFixedTemplates(){
        val warm=RelationMoment(SourceRef("r",1,1),0,"warm",true)
        assertEquals("你说「谢谢你陪我加班」，她心里暖了一下",RelationMomentText.sentence(warm,"谢谢你陪我加班"))
        assertEquals("你说「今天加班不能陪你了，…」，她有点失落",RelationMomentText.sentence(warm.copy(reaction="gap",positive=false),"今天加班不能陪你了，对不起"))
        assertEquals("你们聊得很好，她心里暖了一下",RelationMomentText.sentence(warm,null))
        assertEquals("她主动找你没等到回复，有点失落",RelationMomentText.sentence(warm.copy(reaction="ignored",positive=false),"任何"))
    }
}
