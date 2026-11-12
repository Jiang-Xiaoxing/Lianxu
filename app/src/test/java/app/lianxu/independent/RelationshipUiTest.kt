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
    @Test fun lessDirection(){assertEquals("晚星想少主动找你一点",parameterTitle("晚星",p,proposal("silenceDays" to "3","dailyMax" to "1")))}
    @Test fun moreDirection(){assertEquals("晚星想多主动找你一点",parameterTitle("晚星",p,proposal("silenceDays" to "0.5")))}
    @Test fun mixedDirection(){assertEquals("晚星想换一种方式找你",parameterTitle("晚星",p,proposal("silenceDays" to "3","dailyMax" to "4")))}
    @Test fun toneOnly(){assertEquals("晚星想换一种方式找你",parameterTitle("晚星",p,proposal("tone" to "温柔")))}
    @Test fun plainLanguageAndUnits(){assertEquals(listOf("想你时主动找你：1 天 → 3 天","每天最多主动发：3 条 → 1 条"),parameterLines(p,proposal("silenceDays" to "3","dailyMax" to "1")))}
    @Test fun receiptSurvivesSerialization(){val h=RelationHistory(receipts=listOf(ParameterReceipt(ref,p)));val restored=Gson().fromJson(Gson().toJson(h),RelationHistory::class.java);assertEquals(h,restored);assertFalse(restored.receipts.single().undone)}
    @Test fun reverseAppendsChangeAndCannotRepeat(){val next=p.copy(silenceDays=3.0);val original=RelationChange(1,p,next,"原模板",1);val h=RelationHistory(changes=listOf(original),receipts=listOf(ParameterReceipt(ref,p)));val reversed=h.reverseParameters(ref,next,"原模板",2,3)!!;assertEquals(original,reversed.changes.first());assertEquals(RelationChange(2,next,p,"原模板",3),reversed.changes.last());assertNull(reversed.reverseParameters(ref,p,"原模板",4,5));assertTrue(reversed.receipts.single().undone)}
    @Test fun queriesDoNotChangeScores(){val s=state();val before=s.copy();repeat(3){RelationshipQuery.metrics(s){"原话"};RelationshipQuery.knots(s){"原话"}};assertEquals(before,s);assertEquals(.5-.42*.9*.5,s.trust,1e-15)}
}
