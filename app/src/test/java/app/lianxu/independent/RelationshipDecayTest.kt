package app.lianxu.independent

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Pure formula checks only; integration acceptance remains outstanding. */
class RelationshipDecayTest {
    private val hour=RelationshipProjector.HOUR
    private fun cursor()=RelationCursor(RelationStart.FRIENDS,RelationTemplate.DIRECT.parameters)
    private fun row(id:Long=1,at:Long=0,v:Double?=.6)=RelationInput(SourceRef("r",id,1),at,at/RelationshipProjector.DAY,"关于关系",v=v)
    @Test fun fiveHalfLives(){
        for((half,key) in listOf(12.0 to "happy",6.0 to "unhappy",24.0 to "hurt",36.0 to "low")){
            val c=cursor();c.at=0;c.impacts[row().source]=RelationImpact(happy=.4,disappointment=.4,unhappy=.4,hurt=.4,low=.4)
            c.advance(((2+half)*hour).toLong());val v=c.impacts.getValue(row().source)
            assertEquals(.2,when(key){"happy"->v.happy;"unhappy"->v.unhappy;"hurt"->v.hurt;else->v.low},1e-9)
        }
        // 新做法：失望、信任、亲近是印象，小事14天、大事60天减半，不等2小时。
        for((lasting,days) in listOf(false to 14,true to 60)){
            val c=cursor();c.at=0;c.impacts[row().source]=RelationImpact(disappointment=.4,trust=.1,closeness=-.1,lasting=lasting)
            c.advance(days*RelationshipProjector.DAY);val v=c.impacts.getValue(row().source)
            assertEquals(.2,v.disappointment,1e-9);assertEquals(.05,v.trust,1e-9);assertEquals(-.05,v.closeness,1e-9)
        }
    }
    @Test fun saturationAndOpposition(){
        val c=cursor();c.at=0;c.impacts[SourceRef("r",99,1)]=RelationImpact(happy=.3)
        c.apply(row());assertEquals(.1362,c.state().happy,1e-9)
        repeat(100){c.apply(row(it+2L))};org.junit.Assert.assertTrue(c.state().disappointment<=1)
    }
    @Test fun strengthBeforeClosenessAndPositiveRatio(){
        val positive=cursor();positive.apply(row(v=null).copy(p=.6))
        val negative=cursor();negative.apply(row())
        assertEquals(.1848,positive.state().happy,1e-9)
        assertEquals(.44,positive.state().happy/negative.state().disappointment,1e-9)
    }
    @Test fun gapIgnoresModelExpectation(){
        fun state(expectation:String)=cursor().also{it.apply(row(v=null).copy(disappointment=.27,gap="中",importance="中",expectation=expectation))}.state()
        assertEquals(state("低"),state("高"));assertEquals(.21,state("低").disappointment,1e-9)
        assertEquals(.27,cursor().also{it.apply(row(v=null).copy(disappointment=.27))}.state().disappointment,1e-9)
    }
    @Test fun bigThresholdAndNoGrudgeForGapOrIgnored(){
        for(h in listOf(.249,.25)){
            val c=cursor();c.closeness=1.0;c.apply(row(v=h));assertEquals(if(h<.25)0.0 else .075,c.state().tension,1e-9)
        }
        for(r in listOf(row(v=null).copy(disappointment=1.0),row(v=null).copy(ignored=.2))){
            val c=cursor();c.apply(r);assertEquals(0.0,c.state().tension,1e-9)
        }
    }
    @Test fun floorBothSwitchStatesAndFulfillment(){
        assertEquals(0.0,RelationDecay.grudgeFloor("V",.126,false),1e-9)
        assertEquals(.0126,RelationDecay.grudgeFloor("V",.126,true),1e-9)
        assertEquals(0.0,RelationDecay.grudgeFloor("B",.126,true),1e-9)
        val c=cursor();c.at=0;c.impacts[row().source]=RelationImpact(tension=.126,grudgeFloor=.0126)
        // 心结在弥补前不自己变淡。
        c.advance(365*RelationshipProjector.DAY);assertEquals(.126,c.state().tension,1e-9)
        val promise=row(2,365*RelationshipProjector.DAY,null).copy(promise=true);c.apply(promise)
        c.apply(row(3,promise.at,null).copy(c=.6,fulfills=promise.source,repairs=row().source,repairCompleted=true))
        assertEquals(.0063,c.impacts.getValue(row().source).grudgeFloor,1e-9)
    }
    @Test fun careDoesNotFadeHappyOrGrudge(){
        val c=cursor();c.at=0;c.impacts[row().source]=RelationImpact(happy=.4,tension=.4,disappointment=.4);c.cares.add(0)
        c.advance(9*hour)
        assertEquals(.4*2.0.pow(-7.0/12),c.state().happy,1e-9)
        assertEquals(.4,c.state().tension,1e-9)
        assertEquals(.4*2.0.pow(-9.0/24/14),c.state().disappointment,1e-9)
    }
    @Test fun fiftyCaresStillCapPositiveRewards(){
        val c=cursor();repeat(50){c.apply(row(it+1L,0,null).copy(p=.3))}
        val two=cursor();repeat(2){two.apply(row(it+1L,0,null).copy(p=.3))}
        assertEquals(two.trust,c.trust,1e-9);assertEquals(two.state().happy,c.state().happy,1e-9)
        assertEquals(50,c.cares.size);org.junit.Assert.assertTrue(RelationDecay.careMultiplier(50)<=2)
    }
    @Test fun fulfillmentExampleEAndInvalidReferences(){
        val c=cursor();c.apply(row());val promise=row(2,0,null).copy(promise=true);c.apply(promise)
        val time=5*RelationshipProjector.DAY;c.apply(row(3,time,null).copy(c=.6,fulfills=promise.source,repairs=row().source,repairCompleted=true))
        val expected=.126*.5
        assertEquals(expected,c.state().tension,1e-9)
        c.apply(row(4,time,null).copy(c=.6,fulfills=promise.source));assertEquals(expected,c.state().tension,1e-9)
        c.apply(row(5,time,null).copy(c=.6,fulfills=SourceRef("other",2,1)));assertEquals(expected,c.state().tension,1e-9)
    }
    @Test fun noContactNoMissingAndYearIsFinite(){
        val c=cursor();c.apply(row());c.advance(365*RelationshipProjector.DAY)
        assertEquals(0.0,c.missing,1e-9);org.junit.Assert.assertTrue(c.state().tension in 0.0..1.0)
        val s=c.state();listOf(s.trust,s.closeness,s.tension,s.happy,s.disappointment,s.unhappy,s.hurt,s.low,s.missing).forEach{
            org.junit.Assert.assertTrue(it.isFinite()&&it in 0.0..1.0)
        }
    }
    @Test fun mergedSummaryBothBands(){
        for(g in listOf(.2,.4)){
            val s=cursor().state().copy(missing=.9,tension=g)
            val text=RelationshipSummary.render(s,0){null}
            org.junit.Assert.assertTrue(text.contains(if(g<=.3)"很想对方，但还有点在意那件事" else "很想对方，但心里还有点疙瘩"))
            org.junit.Assert.assertTrue(text.length<=250)
            org.junit.Assert.assertTrue(RelationshipSummary.render(s,0,"小星"){null}.contains("很想小星，但"))
        }
    }
    @Test fun missingExampleCExactTimes(){
        for(c in listOf(0.0,.4,1.0))for(target in listOf(.4,.6,.8)){
            val time=-ln(1-target)/RelationDecay.missingRate(c)
            assertEquals(target,1-exp(-ln(2.0)/24*(1+c)*time),1e-9)
        }
    }
    @Test fun proactiveCrossingAndCaps(){
        val c=cursor();c.contact(0);val p=RelationTemplate.STEADY.parameters
        val due=RelationshipProactive.next(p,0,emptyList(),0,0){t->c.copy().also{it.advance(t)}.state()}!!
        val exact=-ln(.4)/RelationDecay.missingRate(.4)*hour
        org.junit.Assert.assertTrue(due>=exact&&due-exact<=60_000)
        org.junit.Assert.assertNull(RelationshipProactive.next(p.copy(dailyMax=0),0,emptyList(),0,0))
        org.junit.Assert.assertNull(RelationshipProactive.next(p,null,emptyList(),0,0))
        org.junit.Assert.assertNull(RelationshipProactive.next(p,0,emptyList(),0,0){c.state().copy(missing=0.0)})
        c.apply(row())
        val withGrudge=RelationshipProactive.next(p,0,emptyList(),0,0){t->c.copy().also{it.advance(t)}.state()}!!
        // 心结不自己变淡，门槛按记下的心结固定抬高。
        val frozenCrossing=-ln(1-RelationDecay.theta(RelationDecay.thetaBase(p.silenceDays),.126))/RelationDecay.missingRate(.4)*hour
        org.junit.Assert.assertTrue(withGrudge>=frozenCrossing&&withGrudge-frozenCrossing<=60_000)
    }
    @Test fun lowTriggerIsBasedOnExistingGrudge(){
        for(g in listOf(RelationDecay.LOW_TRIGGER,RelationDecay.LOW_TRIGGER+.001)){
            val c=cursor();c.at=0;c.impacts[SourceRef("r",99,1)]=RelationImpact(tension=g)
            c.apply(row());assertEquals(if(g>RelationDecay.LOW_TRIGGER).21 else 0.0,c.state().low,1e-9)
        }
    }
    @Test fun expiredCareDoesNotAccelerate(){
        val c=cursor();c.at=24*hour;c.cares.add(0);c.impacts[row().source]=RelationImpact(disappointment=.4)
        c.advance(24*hour+14*RelationshipProjector.DAY);assertEquals(.2,c.state().disappointment,1e-9)
    }
    @Test fun withdrawnEventsLeaveNoRepeatOrCare(){
        val initial=row();val second=row(2,RelationshipProjector.DAY)
        val cache=RelationProjectionCache()
        val withdrawn=cache.project(RelationTemplate.DIRECT.parameters,listOf(initial.copy(status="disputed"),second),second.at,RelationStart.FRIENDS,emptyList())
        val absent=cache.project(RelationTemplate.DIRECT.parameters,listOf(second),second.at,RelationStart.FRIENDS,emptyList())
        assertEquals(absent,withdrawn)
        val restored=cache.project(RelationTemplate.DIRECT.parameters,listOf(initial,second),second.at,RelationStart.FRIENDS,emptyList())
        org.junit.Assert.assertTrue(restored.tension>absent.tension)
    }
    @Test fun templateDoesNotChangeExistingHalfLives(){
        val c=cursor();c.apply(row());c.parameters=RelationTemplate.RESERVED.parameters;c.advance(60*RelationshipProjector.DAY)
        assertEquals(.21,c.state().disappointment,1e-9)
    }
    @Test fun modelInstructionsDoNotRevealDecayNumbers(){
        val text=RelationInputContract.instruction(123)
        org.junit.Assert.assertTrue(text.contains("道歉和好只缓解那件事带来的不愉快"))
        org.junit.Assert.assertFalse(text.contains("0.15"));org.junit.Assert.assertFalse(text.contains("半衰期"))
    }
    @Test fun holdAndExampleA(){
        for(hours in listOf(0.0,1.99,2.0,9.0,24.0,48.0)){
            val c=cursor();c.apply(row());c.advance((hours*hour).toLong())
            assertEquals(.42*2.0.pow(-hours/24/60),c.state().disappointment,1e-9)
            assertEquals(.21*2.0.pow(-(hours-2).coerceAtLeast(0.0)/24),c.state().hurt,1e-9)
        }
    }
    @Test fun grudgeExampleA(){
        for(days in listOf(0,1,7,14,28)){
            val c=cursor();c.apply(row());c.advance(days*RelationshipProjector.DAY)
            assertEquals(.126,c.state().tension,1e-9)
        }
    }
    @Test fun apologyExampleD(){
        val c=cursor();val original=row();c.apply(original);c.advance(14*hour);val before=c.state()
        c.apply(row(2,14*hour,null).copy(previous=original.source,correction="道歉和好"))
        assertEquals(.42*2.0.pow(-14.0/24/60)*.6,c.state().disappointment,1e-9)
        assertEquals(.21*2.0.pow(-12.0/24)*.6,c.state().hurt,1e-9)
        assertEquals(before.tension,c.state().tension,1e-9)
        c.apply(row(3,14*hour,null).copy(previous=original.source,correction="道歉和好"))
        assertEquals(.42*2.0.pow(-14.0/24/60)*.6,c.state().disappointment,1e-9)
    }
    @Test fun segmentedEqualsSingleAdvance(){
        val c=cursor();c.contact(0);c.apply(row());c.apply(row(2,hour,null).copy(p=.6))
        val split=c.copy();for(h in 2..400)split.advance(h*hour)
        c.advance(400*hour)
        assertEquals(c.state().disappointment,split.state().disappointment,1e-9)
        assertEquals(c.state().tension,split.state().tension,1e-9)
        // 想念的增长按每段开头的亲近算，亲近现在会连续变淡，分段只差很小的数。
        assertEquals(c.state().missing,split.state().missing,1e-6)
        assertEquals(c.closeness,split.closeness,1e-9)
    }
    @Test fun contactAndReachOut(){
        val c=cursor();c.contact(0);c.advance(24*hour);val missing=1-2.0.pow(-1.4)
        assertEquals(missing,c.missing,1e-9);c.reachOut(24*hour)
        assertEquals(missing*.6,c.missing,1e-9);c.contact(24*hour)
        assertEquals(missing*.6*.2,c.missing,1e-9)
    }
    @Test fun rollbackDoesNotIncreaseState(){
        val c=cursor();c.apply(row());c.advance(24*hour);val before=c.state();c.advance(hour)
        assertEquals(before,c.state())
    }
    @Test fun repeatSlowsOnlySameKind(){
        val c=cursor();c.apply(row());c.apply(row(2,10*RelationshipProjector.DAY));c.apply(row(3,20*RelationshipProjector.DAY))
        assertEquals(1.0,c.impacts.getValue(row().source).grudgeSlow,1e-9)
        assertEquals(1.5,c.impacts.getValue(row(2).source).grudgeSlow,1e-9)
        assertEquals(2.0,c.impacts.getValue(row(3).source).grudgeSlow,1e-9)
        val at20=.126+.126*(1-.126)
        assertEquals(at20+.126*(1-at20),c.state().tension,1e-9)
        c.apply(row(4,20*RelationshipProjector.DAY,null).copy(b=.6))
        assertEquals(1.0,c.impacts.getValue(row(4).source).grudgeSlow,1e-9)
        val expired=cursor();expired.apply(row());expired.apply(row(2,29*RelationshipProjector.DAY))
        assertEquals(1.0,expired.impacts.getValue(row(2).source).grudgeSlow,1e-9)
    }
    @Test fun expectation() {
        listOf(0.0 to 0.5,0.4 to 0.7,1.0 to 1.0).forEach{(c,e)->
            assertEquals(e,RelationDecay.expectation(c),1e-9)
        }
    }
    @Test fun openingThreshold() {
        listOf(1.0 to 0.4,1.5 to 0.5,2.0 to 0.6,3.5 to 0.8,
            4.0 to (13.0/15),0.5 to 0.3,14.0 to 0.9).forEach{(s,e)->
            assertEquals(e,RelationDecay.thetaBase(s),1e-9)
        }
        assertEquals(0.8,RelationDecay.theta(0.6,0.4),1e-9)
        assertEquals(0.95,RelationDecay.theta(0.9,1.0),1e-9)
    }
    @Test fun careMultiplier() {
        listOf(0,1,3,10).forEach{n->
            assertEquals(2-exp(-n/3.0),RelationDecay.careMultiplier(n),1e-9)
        }
    }
    @Test fun grudgeHalfLife() {
        assertEquals(14.0,RelationDecay.grudgeHalfDays(0.0,1.0),1e-9)
        assertEquals(35.0/3,RelationDecay.grudgeHalfDays(1.0,1.0),1e-9)
    }
    @Test fun missingRate() {
        listOf(0.0,0.4,1.0).forEach{c->
            assertEquals(ln(2.0)/24*(1+c),RelationDecay.missingRate(c),1e-9)
        }
    }
}
