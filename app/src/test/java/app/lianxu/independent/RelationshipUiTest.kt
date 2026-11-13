package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RelationshipUiTest {
    @Test fun undoPreservesLaterChangesAndOldLabel(){
        val old=RelationTemplate.DIRECT.parameters
        val later=RelationTemplate.QUIET.parameters.copy(silenceDays=5.0,dailyMax=4,tone="后来语气")
        val source=SourceRef("r",9,1)
        val history=RelationHistory(receipts=listOf(ParameterReceipt(source,old,oldLabel="自定义旧名",keys=setOf("silenceDays"))))
        val change=history.reverseParameters(source,later,"当前名",10,10)!!.changes.single()
        assertEquals(later.copy(silenceDays=old.silenceDays),change.new)
        assertEquals("自定义旧名",change.label)
    }
    @Test fun legacyReceiptUsesCurrentLabelAndInfersChangedKeys(){
        val old=RelationTemplate.DIRECT.parameters;val next=old.copy(dailyMax=1);val source=SourceRef("r",9,1)
        val h=RelationHistory(changes=listOf(RelationChange(1,old,next,"旧",1)),receipts=listOf(ParameterReceipt(source,old)))
        val later=next.copy(tone="后来语气")
        val c=h.reverseParameters(source,later,"当前名",2,2)!!.changes.last()
        assertEquals(later.copy(dailyMax=old.dailyMax),c.new);assertEquals("当前名",c.label)
    }
    private val p=RelationTemplate.DIRECT.parameters
    private val ref=SourceRef("r",1,1)
    private fun state()=RelationshipProjector.project(p,listOf(RelationInput(ref,0,0,"关于关系",v=.9)),0,RelationStart.FRIENDS)
    private fun proposal(vararg values:Pair<String,String>)=RelationParameterProposal(ref,mapOf(*values),"合成",0)
    @Test fun knotTabListsTheRealKnot(){val s=state();assertEquals(listOf(ref),RelationshipQuery.knots(s){"原话"})}
    @Test fun missingQuoteIsOmitted(){assertTrue(RelationshipQuery.knots(state()){null}.isEmpty())}
    @Test fun explainedButUnrepairedKnotStays(){val s=state().copy(explanations=listOf(RelationExplanation(SourceRef("r",2,1),ref,"其他",.7,1)));assertEquals(listOf(ref),RelationshipQuery.knots(s){"原话"})}
    @Test fun releasedDebtIsOmitted(){val s=RelationshipProjector.project(p,listOf(RelationInput(ref,0,0,"关于关系",v=.9),RelationInput(SourceRef("r",2,1),1,0,"关于关系",explains=ref,correction="不介意了",formatVersion=2,releaseKind="真误会")),1);assertTrue(RelationshipQuery.knots(s){"原话"}.isEmpty())}
    @Test fun retiredFrequencyProposalsHaveNoDisplayLines(){
        for(key in listOf("silenceDays","resendDays","resendCount","dailyMax"))assertTrue(parameterLines(p,proposal(key to "3")).isEmpty())
        assertTrue(parameterLines(p,proposal("dailyMax" to "3","tone" to "温柔")).isEmpty())
    }
    @Test fun toneOnly(){assertEquals("晚星想换一种方式找你",parameterTitle("晚星"));assertEquals(listOf("主动找你的语气：${p.tone} → 温柔"),parameterLines(p,proposal("tone" to "温柔")))}
    @Test fun retiredReceiptsStayInHistoryButCannotClaimToBeEffective(){
        val frequency=ParameterReceipt(ref,p,keys=setOf("dailyMax"));val mixed=frequency.copy(source=ref.copy(turnId=2),keys=setOf("tone","resendCount"))
        val tone=frequency.copy(source=ref.copy(turnId=3),keys=setOf("tone"));val legacy=frequency.copy(source=ref.copy(turnId=4),keys=null)
        val h=RelationHistory(changes=listOf(RelationChange(0,p,p.copy(dailyMax=0),"旧",1)),receipts=listOf(frequency,mixed,tone,legacy))
        val original=Gson().toJson(h);assertEquals(listOf(tone),h.activeParameterReceipts());assertEquals(original,Gson().toJson(h))
    }
    @Test fun ambiguousLegacyReceiptCannotBorrowAnEarlierToneChange(){
        val tone=p.copy(tone="温柔")
        for(later in listOf(p.copy(dailyMax=0),p.copy(dailyMax=0,tone="轻快"))){
            val h=RelationHistory(changes=listOf(RelationChange(1,p,tone,"旧",1),RelationChange(2,tone,p,"撤销",2),RelationChange(3,p,later,"后一次",3)),receipts=listOf(ParameterReceipt(ref,p)))
            val original=Gson().toJson(h);assertTrue(h.activeParameterReceipts().isEmpty());assertEquals(original,Gson().toJson(h))
        }
        val clear=RelationHistory(changes=listOf(RelationChange(1,p,tone,"语气",1)),receipts=listOf(ParameterReceipt(ref,p)))
        assertEquals(clear.receipts,clear.activeParameterReceipts())
        assertTrue(RelationHistory(receipts=clear.receipts).activeParameterReceipts().isEmpty())
    }
    @Test fun receiptSurvivesSerialization(){val h=RelationHistory(receipts=listOf(ParameterReceipt(ref,p)));val restored=Gson().fromJson(Gson().toJson(h),RelationHistory::class.java);assertEquals(h,restored);assertFalse(restored.receipts.single().undone)}
    @Test fun reverseAppendsChangeAndCannotRepeat(){val next=p.copy(silenceDays=3.0);val original=RelationChange(1,p,next,"原模板",1);val h=RelationHistory(changes=listOf(original),receipts=listOf(ParameterReceipt(ref,p)));val reversed=h.reverseParameters(ref,next,"原模板",2,3)!!;assertEquals(original,reversed.changes.first());assertEquals(RelationChange(2,next,p,"原模板",3),reversed.changes.last());assertNull(reversed.reverseParameters(ref,p,"原模板",4,5));assertTrue(reversed.receipts.single().undone)}
    @Test fun queriesDoNotChangeScores(){val s=state();val before=s.copy();repeat(3){RelationshipQuery.metrics(s){"原话"};RelationshipQuery.knots(s){"原话"}};assertEquals(before,s);assertEquals(.5-.42*.9*.5,s.trust,1e-15)}
}
