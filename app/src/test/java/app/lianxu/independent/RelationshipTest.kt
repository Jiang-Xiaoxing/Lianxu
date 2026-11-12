package app.lianxu.independent

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class RelationshipTest {
    private val h=RelationshipProjector.HOUR
    private val wed=20*h
    private val fri=66*h
    private val sun=118*h
    private fun input(id:Long,at:Long,p:Double=0.0,c:Double=0.0,v:Double=0.0,b:Double=0.0,d:Double?=null,previous:SourceRef?=null,mode:String?=null)=
        RelationInput(SourceRef("r",id,1),at,at/24/h,"关于关系",p,c,v,b,d,previous,mode)
    private val invitation=input(1,wed,p=.3).copy(promise=true)
    private val reschedule=input(2,fri,d=.9*.6*.5)
    private val fulfilled=input(3,sun,c=.6).copy(fulfills=invitation.source)
    private val missed=input(2,93*h,v=.6,d=.9*.6)
    private fun project(t:RelationTemplate,rows:List<RelationInput>,now:Long)=RelationshipProjector.project(t.parameters,rows,now,RelationStart.FRIENDS)
    private fun near(expected:Double,actual:Double)=assertEquals(expected,actual,.00051)
    // 新做法：信任/亲近几天里按14天半衰期轻微变淡，设计例子的数只在当天精确。
    private fun nearDays(expected:Double,actual:Double)=assertEquals(expected,actual,.015)
    @Test fun movieWednesdayAndSundayMatchDesignForBothTemplates(){
        for((t,expected) in listOf(RelationTemplate.DIRECT to listOf(.533,.440,.595,.477),RelationTemplate.RESERVED to listOf(.522,.418,.565,.435))){
            val first=project(t,listOf(invitation),wed);near(expected[0],first.trust);near(expected[1],first.closeness);near(.44*.3*.7,first.happy)
            val end=project(t,listOf(invitation,reschedule,fulfilled),sun);nearDays(expected[2],end.trust);nearDays(expected[3],end.closeness);nearDays(if(t==RelationTemplate.DIRECT).19031955 else .187468,end.happy)
        }
    }
    @Test fun rescheduleDoesNotReduceTrustAndDisappointmentDecays(){
        for((t,d) in listOf(RelationTemplate.DIRECT to .27,RelationTemplate.RESERVED to .081)){
            val before=project(t,listOf(invitation),wed)
            val after=project(t,listOf(invitation,reschedule),fri)
            assertEquals(project(t,listOf(invitation),fri).trust,after.trust,0.0);nearDays(before.trust,after.trust);near(d,after.disappointment);near(0.0,after.tension)
            near(d*2.0.pow(-26.0/24/14),project(t,listOf(invitation,reschedule),92*h).disappointment)
        }
    }
    @Test fun missedPromiseAndThreeDayTensionMatch(){
        for((t,trust,d,knot) in listOf(Values(RelationTemplate.DIRECT,.399,.54,.106),Values(RelationTemplate.RESERVED,.391,.162,.223))){
            val s=project(t,listOf(invitation,missed),missed.at);nearDays(trust,s.trust);near(d,s.disappointment);near(.3*.6*(.5+.5*project(t,listOf(invitation),missed.at).closeness),s.tension)
            near(s.tension,project(t,listOf(invitation,missed),missed.at+72*h).tension)
        }
    }
    private data class Values(val t:RelationTemplate,val trust:Double,val d:Double,val knot:Double)
    @Test fun recordCorrectionRestoresFullReplayAndLeavesSundayTrust(){
        for(t in listOf(RelationTemplate.DIRECT,RelationTemplate.RESERVED)){
            val removed=project(t,listOf(invitation,reschedule.copy(status="disputed"),fulfilled),sun)
            assertEquals(RelationshipProjector.project(t.parameters,listOf(invitation,fulfilled),sun,RelationStart.FRIENDS,contactTimes=listOf(wed,fri,sun)),removed);near(0.0,removed.disappointment)
            val correction=input(4,sun+1,previous=reschedule.source,mode="误会")
            val s=project(t,listOf(invitation,reschedule,fulfilled,correction),sun+1)
            near(removed.trust,s.trust);near(0.0,s.disappointment)
        }
    }
    @Test fun explanationReturnsSeventyPercentWithoutRewritingOriginal(){
        for(t in listOf(RelationTemplate.DIRECT,RelationTemplate.RESERVED)){
            val reason=input(3,missed.at+h).copy(explains=missed.source,excuse="身体不舒服")
            val before=project(t,listOf(invitation,missed),reason.at)
            val s=project(t,listOf(invitation,missed,reason),reason.at)
            assertEquals(before.trust+(project(t,listOf(invitation),reason.at).trust-before.trust)*.7,s.trust,1e-12)
            assertEquals(before.disappointment*.3,s.disappointment,1e-15)
            assertEquals(before.tension*.3,s.tension,1e-15)
        }
    }
    @Test fun deterministicReplayOrderDuplicatesAndTemplateChanges(){
        val rows=listOf(invitation,reschedule,fulfilled)
        for(t in RelationTemplate.entries){val a=project(t,rows,sun);assertEquals(a,project(t,rows.reversed()+rows,sun));assertEquals(a,project(t,rows,sun))}
        assertNotEquals(project(RelationTemplate.DIRECT,rows,sun),project(RelationTemplate.RESERVED,rows,sun))
    }
    @Test fun dailyPositiveCapAndRepeatedApologyCannotFarmState(){
        val rows=(1L..5L).map{input(it,wed+it,p=.3)}
        near(project(RelationTemplate.DIRECT,rows.take(2),wed+10).trust,project(RelationTemplate.DIRECT,rows,wed+10).trust)
        val apologies=(3L..12L).map{input(it,missed.at+h+it,previous=missed.source,mode="道歉和好")}
        val once=project(RelationTemplate.DIRECT,listOf(invitation,missed,apologies.first()),missed.at+2*h)
        val repeated=project(RelationTemplate.DIRECT,listOf(invitation,missed)+apologies,missed.at+2*h)
        assertEquals(once.tension,repeated.tension,1e-15);assertEquals(once.trust,repeated.trust,0.0)
        near(0.0,project(RelationTemplate.DIRECT,listOf(invitation)+apologies,missed.at+2*h).tension)
    }
    @Test fun fourteenDaysMissingDoesNotPunishTrust(){
        val ordinary=input(1,0).copy(category="普通信息")
        for((t,m) in listOf(RelationTemplate.DIRECT to .594,RelationTemplate.RESERVED to .151,RelationTemplate.STEADY to .376)){
            val s=project(t,listOf(ordinary),14*24*h);near(.5,s.trust);near(1-2.0.pow(-14*1.4),s.missing)
            near(.4,s.closeness) // No interaction has raised the peak above this relationship's start.
        }
    }
    @Test fun invalidRelationshipFieldsAreIndependentAndLogged(){
        val log=mutableListOf<Pair<String,String>>()
        fun parse(raw:String)=RelationInputContract.parse(JsonParser.parseString(raw),invitation.source,wed,0,emptyList()){f,c->log+=f to c}
        val row=parse("""{"消息":"1","信息类别":"关于关系","P":"错","C":"中","V":[],"B":"无","没达到预期":{"期待":"高"},"指向之前":{"消息":"999","方式":"误会"}}""")!!
        assertNull(row.p);assertNull(row.v);assertEquals(.6,row.c!!,0.0);assertNull(row.disappointment);assertNull(row.previous)
        assertEquals(setOf("P","V","没达到预期","指向之前"),log.map{it.first}.toSet())
        for(raw in listOf("null","[]","{}","""{"消息":"999","信息类别":"关于关系"}""","""{"消息":"1","信息类别":"错"}"""))assertNull(parse(raw))
    }
    @Test fun ordinaryInformationAndFutureInputsDoNotChangeScores(){
        val ordinary=missed.copy(category="普通信息")
        val s=project(RelationTemplate.DIRECT,listOf(ordinary,fulfilled.copy(at=sun+1)),sun)
        near(.5,s.trust);near(.4,s.closeness);near(0.0,s.disappointment)
    }
    @Test fun tensionIsClampedAtEventTimeBeforeDecay(){
        val a=input(1,0,b=.9);val b=input(2,1,b=.9)
        // 两笔各自在当时饱和：.189 + .189×(1-.189)；心结之后不自己变淡。
        near(.342279,project(RelationTemplate.DIRECT,listOf(a,b),48*h+1).tension)
    }
    @Test fun comingBackDoesNotUndoCoolingAndInvalidRelationIsStillContact(){
        val ordinary=input(1,0,p=.3)
        val returned=input(2,14*24*h).copy(category="普通信息")
        val state=project(RelationTemplate.RESERVED,listOf(ordinary,returned),returned.at)
        // 新做法：小事加的亲近14天淡一半，常来聊不会让旧事不淡。
        near(.4+(.418-.4)*.5,state.closeness);near(.19999978717781186,state.missing)
        val frequent=RelationshipProjector.project(RelationTemplate.RESERVED.parameters,listOf(ordinary),14*24*h,RelationStart.FRIENDS,14*24*h,listOf(0,6*24*h,12*24*h,14*24*h))
        near(.4+(.418-.4)*.5,frequent.closeness)
    }
    @Test fun queryHasRoundedScoresAndOnlyThreeCorrectableMetrics(){
        val state=project(RelationTemplate.DIRECT,listOf(invitation,reschedule,fulfilled),sun)
        val rows=RelationshipQuery.metrics(state){"真实测试原话"}
        // 失望是印象，周五那点失落到周日还剩大半；心情只看当下的开心等。
        assertEquals(listOf(59,24,47,0,19),rows.map{it.score})
        assertTrue(rows.take(3).all{it.canCorrect});assertTrue(rows.drop(3).none{it.canCorrect})
    }
    @Test fun summariesUseOnlyActualQuotesAndNoScoreFields(){
        val s=project(RelationTemplate.DIRECT,listOf(invitation,reschedule,fulfilled),sun)
        val text=RelationshipSummary.render(s,sun){if(it==fulfilled.source)"电影真好看，谢谢你陪我"else "周六晚上一起去看电影吧"}
        assertTrue(text.contains("比较信任↑"));assertTrue(text.contains("有一点开心"));assertTrue(text.contains("电影真好看"));assertFalse(text.contains("0.595"));assertTrue(text.length<=250)
    }
    @Test fun bothMovieScoresDirectionsAndOriginalQuotesAreExposedWithoutLeakingScores(){
        val quotes=mapOf(invitation.source to "周六晚上一起去看电影吧",reschedule.source to "周六要加班，改到周日晚上行吗？",fulfilled.source to "电影真好看，谢谢你陪我")
        for((template,trust,disappointment) in listOf(Triple(RelationTemplate.DIRECT,59,27),Triple(RelationTemplate.RESERVED,56,8))){
            val friday=RelationshipQuery.metrics(project(template,listOf(invitation,reschedule),fri),quotes::get)
            val loss=friday.single{it.key=="disappointment"}
            assertEquals(disappointment,loss.score);assertEquals("↑",loss.direction);assertEquals(quotes[reschedule.source],loss.quote)
            assertEquals(reschedule.source,loss.correctionSource)
            val sunday=project(template,listOf(invitation,reschedule,fulfilled),sun)
            val rows=RelationshipQuery.metrics(sunday,quotes::get)
            val faith=rows.single{it.key=="trust"}
            assertEquals(trust,faith.score);assertEquals("↑",faith.direction);assertEquals(fulfilled.source,faith.source)
            assertEquals(quotes[fulfilled.source],faith.quote);assertEquals(fulfilled.source,faith.correctionSource)
            assertTrue(rows.filter{it.key in setOf("tension","happy","unhappy")}.all{!it.canCorrect&&it.correctionSource==null})
            val prompt=RelationshipSummary.render(sunday,sun,quote=quotes::get)
            assertFalse(Regex("59|56|27|0\\.594|0\\.565|score|trustGain").containsMatchIn(prompt))
        }
    }
    @Test fun withdrawalRebuildsScoreDirectionAndCorrectionTarget(){
        val quotes=mapOf(invitation.source to "约电影",missed.source to "忘记了")
        for(t in listOf(RelationTemplate.DIRECT,RelationTemplate.RESERVED)){
            val rows=listOf(invitation,missed)
            val old=RelationshipQuery.metrics(project(t,rows,missed.at),quotes::get).single{it.key=="trust"}
            assertEquals("↓",old.direction);assertEquals(missed.source,old.correctionSource)
            val new=RelationshipQuery.metrics(project(t,listOf(invitation,missed.copy(status="disputed")),missed.at),quotes::get)
            val trust=new.single{it.key=="trust"};assertEquals(if(t==RelationTemplate.DIRECT)53 else 52,trust.score)
            assertEquals("↑",trust.direction);assertEquals(invitation.source,trust.correctionSource)
            val loss=new.single{it.key=="disappointment"};assertEquals(0,loss.score);assertEquals("不变",loss.direction)
            assertNull(loss.source);assertNull(loss.quote);assertNull(loss.correctionSource);assertFalse(loss.canCorrect)
        }
    }
    @Test fun dominantMoodAndMissingEvidenceNeverInventACause(){
        val state=project(RelationTemplate.DIRECT,listOf(input(1,0,p=.3,b=.6)),0)
        val mood=RelationshipQuery.metrics(state){"真实原话"}.last()
        assertEquals("mood",mood.key);assertEquals(42,mood.score);assertEquals("挺生气",mood.degree);assertFalse(mood.canCorrect)
        RelationshipQuery.metrics(state){null}.forEach{assertNull(it.source);assertNull(it.quote);assertNull(it.correctionSource);assertEquals("不变",it.direction)}
    }
    @Test fun weakerNewDisappointmentAddsToThePreviousDecayedValue(){
        val high=input(1,0,d=.54);val low=input(2,h,d=.27)
        val rows=RelationshipQuery.metrics(project(RelationTemplate.DIRECT,listOf(high,low),h)){"新的落空"}
        val loss=rows.single{it.key=="disappointment"};assertEquals(66,loss.score);assertEquals("↑",loss.direction);assertEquals(low.source,loss.source)
    }
    @Test fun earlierApologyCannotHideTheCauseOfALaterBoundaryViolation(){
        val first=input(1,0,b=.6);val apology=input(2,h,previous=first.source,mode="道歉和好");val latest=input(3,2*h,b=.6)
        val repaired=project(RelationTemplate.DIRECT,listOf(first,apology),3*h).causes.getValue("tension")
        assertEquals(first.source,repaired.source);assertEquals("↑",repaired.direction)
        val fresh=project(RelationTemplate.DIRECT,listOf(first,apology,latest),3*h).causes.getValue("tension")
        assertEquals(latest.source,fresh.source);assertEquals("↑",fresh.direction)
    }
    @Test fun aPromiseCanOnlyBeFulfilledOnceAndWithdrawalReopensIt(){
        val duplicate=fulfilled.copy(source=SourceRef("r",4,1),at=sun+h)
        val once=project(RelationTemplate.DIRECT,listOf(invitation,fulfilled),sun+h)
        val twice=project(RelationTemplate.DIRECT,listOf(invitation,fulfilled,duplicate),sun+h)
        assertEquals(once.trust,twice.trust,0.0);assertEquals(fulfilled.source,twice.promises.single().fulfilledBy)
        for(row in listOf(fulfilled.copy(fulfills=null),fulfilled.copy(fulfills=reschedule.source))){
            assertEquals(project(RelationTemplate.DIRECT,listOf(invitation,reschedule),sun).trust,project(RelationTemplate.DIRECT,listOf(invitation,reschedule,row),sun).trust,.000001)
        }
        val reopened=project(RelationTemplate.DIRECT,listOf(invitation,fulfilled.copy(status="disputed")),sun)
        assertNull(reopened.promises.single().fulfilledBy)
        val sourceGone=project(RelationTemplate.DIRECT,listOf(invitation.copy(status="disputed"),fulfilled),sun)
        assertEquals(.5,sourceGone.trust,0.0);assertTrue(sourceGone.promises.isEmpty())
        assertEquals(.5,project(RelationTemplate.DIRECT,listOf(fulfilled),sun).trust,0.0)
    }
    @Test fun recordingFailuresUseTheSameRecentWindowAsTheirDenominator(){
        val failure=RelationIssue("关系输入","OBJECT_REQUIRED")
        val rows=(1L..5L).map{RelationRecordingAttempt(SourceRef("r",it,1),it,if(it%2L==0L)listOf(failure)else emptyList(),it%2L==1L)}
        val report=RelationshipQuery.recording(rows,3)
        assertEquals(3,report.examined);assertEquals(1,report.failed);assertEquals(1.0/3,report.failureRate,0.0)
        assertEquals(4L,report.ignored.single().source.turnId);assertFalse(report.ignored.single().recorded)
        assertEquals(0.0,RelationshipQuery.recording(emptyList(),20).failureRate,0.0)
    }
    @Test fun saturatedMoodsPreserveEarlierEventsAndRepairOnlyTheirOwnContribution(){
        val first=input(1,0,p=.3,b=.6,d=.54);val second=input(2,0,p=.3,b=.6,d=.05)
        val s=project(RelationTemplate.DIRECT,listOf(first,second),0)
        assertEquals(.563,s.disappointment,.000001);assertEquals(.023906096,s.happy,1e-9);assertEquals(.6704904,s.unhappy,1e-9)
        val apology=input(3,0,previous=first.source,mode="道歉和好")
        val repaired=project(RelationTemplate.DIRECT,listOf(first,second,apology),0)
        // Equal timestamps still replay in source order: the later apology only repairs its own part.
        assertEquals(.252+.2504904,repaired.unhappy,1e-9)
        val withdrawn=project(RelationTemplate.DIRECT,listOf(first.copy(status="disputed"),second),0)
        assertEquals(.05,withdrawn.disappointment,.000001);assertEquals(0.0,withdrawn.happy,1e-9)
    }
    @Test fun peakFloorAndFasterRewarmingAreRebuiltFromValidInputs(){
        val rows=(1L..20L).map{input(it,it*RelationshipProjector.DAY,p=.9)}
        for(t in RelationTemplate.entries){
            val end=rows.last().at;val peak=project(t,rows,end).closeness
            val cooled=project(t,rows,end+14*RelationshipProjector.DAY)
            // 新做法：P重算大事，加的亲近60天淡一半，不再按周回落到地板。
            assertEquals(.4+(peak-.4)*2.0.pow(-14.0/60),cooled.closeness,.000001)
            assertEquals(peak,cooled.closenessPeak,0.0)
            val returned=input(21,end+14*RelationshipProjector.DAY,p=.3)
            val warm=project(t,rows+returned,returned.at)
            val gain=t.parameters.closenessGain*.3*(1-cooled.closeness)
            assertEquals(cooled.closeness+gain,warm.closeness,.000001)
            val cleared=project(t,rows.map{it.copy(status="disputed")},returned.at)
            assertEquals(.4,cleared.closeness,0.0);assertEquals(.4,cleared.closenessPeak,0.0)
        }
    }
    @Test fun recommendationFailureIsNotAPresetAndNeverRetries()=runBlocking{
        for(raw in listOf("{}","[]","""{"模板":"未知"}""","""{"模板":"黏人直率"}""")){
            var calls=0
            val service=RelationshipCreation(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success(raw)}})
            val config=ModelConfig("test","test","custom","https://example.invalid","test")
            val first=service.recommend("人设",config,"synthetic")
            assertEquals(if(raw.contains("黏人直率"))RelationTemplate.DIRECT else null,first.template)
            assertEquals(first,service.recommend("人设",config,"synthetic"));assertEquals(1,calls)
        }
    }
}
