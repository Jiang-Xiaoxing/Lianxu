package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import kotlin.math.pow
import kotlin.math.ln

class RelationshipV3Test {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long,at:Long=0)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")
    private fun project(rows:List<RelationInput>,at:Long=rows.maxOfOrNull{it.at}?:0,changes:List<RelationChange> = emptyList())=
        RelationshipProjector.project(p,rows,at,RelationStart.FRIENDS,changes=changes)
    @Test fun explanationKeepsOriginalAndRefundsOnlyItsRemainingContribution(){
        val original=row(1).copy(v=.6,disappointment=.54)
        val explanation=row(2,RelationshipProjector.HOUR).copy(explains=original.source,excuse="身体不舒服")
        val before=project(listOf(original),explanation.at);val after=project(listOf(original,explanation))
        assertEquals(.6,original.v!!,0.0);assertEquals(.7,after.explanations.single().ratio,0.0)
        assertEquals(before.trust+(project(emptyList(),explanation.at).trust-before.trust)*.7,after.trust,1e-12)
        assertEquals(before.disappointment*.3,after.disappointment,1e-15);assertEquals(before.hurt*.3,after.hurt,1e-15)
        val later=project(listOf(original,explanation),explanation.at+8*RelationshipProjector.HOUR)
        assertEquals(after.disappointment*2.0.pow(-8.0/24/60),later.disappointment,1e-12)
    }
    @Test fun explanationAbsentPositiveAndAlreadyExplainedAreNoOps(){
        val negative=row(1).copy(v=.6);val positive=row(2).copy(p=.3)
        val first=row(3).copy(explains=negative.source)
        val a=project(listOf(negative,positive,first))
        for(target in listOf(SourceRef("r",99,1),positive.source,negative.source)){
            val b=project(listOf(negative,positive,first,row(4).copy(explains=target)))
            assertEquals(a.trust,b.trust,0.0);assertEquals(a.explanations,b.explanations)
        }
    }
    @Test fun explanationTimingAndRepeatedCategoriesFollowApprovedRates(){
        for((count,expected) in listOf(1 to .7,2 to .5,3 to .2,4 to .1,9 to .1))assertEquals(expected,RelationExcuses.ratio(count,day),0.0)
        assertEquals(.5,RelationExcuses.ratio(1,3*day),1e-15)
        assertEquals(.3,RelationExcuses.ratio(1,30*day),0.0)
        for(n in 2..4)assertEquals(0.0,RelationExcuses.ratio(n,30*day),0.0)
        assertEquals("其他",RelationExcuses.category("陌生类别"))
    }
    @Test fun threeRealFulfillmentsReduceAllExcuseCounts(){
        val rows=mutableListOf<RelationInput>();var id=0L
        for(category in listOf("工作忙","工作忙","忘了")){
            val negative=row(++id).copy(v=.3);rows+=negative;rows+=row(++id).copy(explains=negative.source,excuse=category)
        }
        assertEquals(2,project(rows).excuses["工作忙"])
        assertTrue(RelationshipSummary.render(project(rows),0){"工作太忙"}.contains("第二次说〈工作忙〉"))
        assertTrue(RelationshipSummary.render(project(rows),0){"工作太忙"}.contains("对方这已经是第二次说〈工作忙〉"))
        val named=RelationshipSummary.render(project(rows),0,"小星"){"工作太忙"}
        assertTrue(named.contains("小星这已经是第二次说〈工作忙〉"));assertFalse(named.replace("其他","").contains("他"))
        assertTrue(RelationshipSummary.render(project(rows),0,""){"工作太忙"}.contains("对方这已经是"))
        repeat(3){val promise=row(++id).copy(promise=true);rows+=promise;rows+=row(++id).copy(c=.3,fulfills=promise.source)}
        val end=project(rows);assertEquals(1,end.excuses["工作忙"]);assertEquals(0,end.excuses["忘了"])
        val removed=rows.map{if(it.explains!=null&&it.excuse=="忘了")it.copy(status="disputed")else it}
        assertNull(project(removed).excuses["忘了"])
    }
    @Test fun changingParametersPreservesAllCurrentValuesAndOnlyChangesFuture(){
        val old=row(1).copy(v=.6,disappointment=.54,p=.3)
        val at=day;val before=project(listOf(old),at)
        val change=RelationChange(at,p,RelationTemplate.RESERVED.parameters,"独立慢热嘴硬")
        val after=project(listOf(old),at,listOf(change))
        assertEquals(before,after)
        val later=project(listOf(old),at+day,listOf(change));assertEquals(after.disappointment*2.0.pow(-1.0/60),later.disappointment,1e-12)
        val added=row(2,at).copy(p=.3)
        assertEquals(after.trust+.15*.3*(1-after.trust),project(listOf(old,added),at,listOf(change)).trust,1e-15)
    }
    @Test fun sameMillisecondTemplateChangeFollowsAlreadySavedEvents(){
        val event=row(1).copy(p=.6);val change=RelationChange(0,p,RelationTemplate.RESERVED.parameters,"独立慢热嘴硬",afterMessage=1)
        assertEquals(project(listOf(event)),project(listOf(event),0,listOf(change)))
        val next=row(2).copy(p=.3);val before=project(listOf(event))
        assertEquals(before.trust+.15*.3*(1-before.trust),project(listOf(event,next),0,listOf(change)).trust,1e-15)
    }
    @Test fun aFullyCalmedSmallEventIsForgotten(){
        val event=row(1).copy(disappointment=.27);val explanation=row(2,1000*day).copy(explains=event.source,excuse="工作忙")
        val state=project(listOf(event,explanation));assertTrue(state.explanations.isEmpty());assertTrue(state.excuses.isEmpty());assertTrue(state.ledger.isEmpty())
    }
    @Test fun mixedHistoricalParametersSurviveVoidingAndRestoring(){
        val first=row(1).copy(p=.3);val second=row(2,2*day).copy(p=.3)
        val changes=listOf(RelationChange(day,p,RelationTemplate.RESERVED.parameters,"独立慢热嘴硬"))
        val cache=RelationProjectionCache()
        val a=cache.project(p,listOf(first,second),2*day,RelationStart.FRIENDS,listOf(0,2*day),changes)
        val b=cache.project(p,listOf(first.copy(status="disputed"),second),2*day,RelationStart.FRIENDS,listOf(0,2*day),changes)
        assertEquals(.5225,b.trust,1e-15);assertEquals(1,cache.rebuilds);assertEquals(1,cache.restoredPosition);assertEquals(3,cache.applied)
        assertEquals(a,cache.project(p,listOf(first,second),2*day,RelationStart.FRIENDS,listOf(0,2*day),changes))
    }
    @Test fun incrementalEqualsReplayAcrossRandomEventsQueriesCorrectionsAndChanges(){
        repeat(12){seed->
            val random=Random(seed);val cache=RelationProjectionCache();val rows=mutableListOf<RelationInput>();val changes=mutableListOf<RelationChange>()
            var at=0L;var current=p
            repeat(40){i->
                at+=random.nextLong(1,3*day);val id=i.toLong()+1
                var next=row(id,at)
                next=when(i%9){0->next.copy(p=.3,promise=true);1->next.copy(v=.6,disappointment=.54);2->next.copy(explains=rows.lastOrNull()?.source,excuse="工作忙");3->next.copy(c=.6,fulfills=rows.lastOrNull{it.promise}?.source);4->next.copy(b=.3);5->next.copy(disappointment=.05);6->next.copy(ignored=.08);7->next.copy(previous=rows.lastOrNull{(it.v?:0.0)>0}?.source,correction="道歉和好");else->next.copy(p=.9)}
                rows+=next
                if(i%11==0){val new=RelationTemplate.entries[random.nextInt(7)].parameters;changes+=RelationChange(at,current,new,"测试");current=new}
                val contacts=rows.map{it.at};val reachOuts=rows.filterIndexed{index,_->index%3==0}.map{it.at}
                assertEquals("seed=$seed event=$i",RelationshipProjector.project(p,rows,at,RelationStart.FRIENDS,at,contacts,changes,reachOuts),cache.project(p,rows,at,RelationStart.FRIENDS,contacts,changes,reachOuts))
                cache.project(p,rows,at+day,RelationStart.FRIENDS,contacts,changes,reachOuts)
                assertEquals(RelationshipProjector.project(p,rows,at+day,RelationStart.FRIENDS,at,contacts,changes,reachOuts),cache.project(p,rows,at+day,RelationStart.FRIENDS,contacts,changes,reachOuts))
            }
            val applications=cache.applied;cache.project(p,rows,at,RelationStart.FRIENDS,rows.map{it.at},changes,rows.filterIndexed{index,_->index%3==0}.map{it.at});assertEquals(applications,cache.applied);assertEquals(1,cache.rebuilds)
        }
    }
    @Test fun moodMappingSaturatesAndApologyRepairsAnger(){
        val a=row(1).copy(p=.6,v=.6,b=.6,disappointment=.54)
        val s=project(listOf(a));assertEquals(0.0,s.happy,1e-9);assertEquals(.54,s.disappointment,1e-9);assertEquals(.21,s.hurt,1e-9);assertEquals(.42,s.unhappy,1e-9)
        val b=row(2).copy(ignored=.08);val both=project(listOf(a,b))
        assertEquals(.54+.08*.7396*(1-.54),both.disappointment,1e-15);assertEquals(.21+.08*.7396*.79,both.hurt,1e-15);assertEquals(0.0,both.low,1e-9)
        val apology=row(3).copy(previous=a.source,correction="道歉和好")
        assertEquals(.252,project(listOf(a,b,apology)).unhappy,1e-15)
    }
    @Test fun mixedMoodThresholdIsInclusiveAndSharedByQueryAndSummary(){
        val base=project(emptyList()).copy(happy=.4,hurt=.2)
        assertNotNull(RelationshipSummary.mood(base).secondary)
        assertEquals(40,RelationshipSummary.mood(base).primary.score);assertEquals(20,RelationshipSummary.mood(base).secondary!!.score)
        assertNull(RelationshipSummary.mood(base.copy(hurt=.199999)).secondary)
        assertNull(RelationshipSummary.mood(base.copy(happy=.400001)).secondary)
        val text=RelationshipSummary.mood(base).sentence
        assertEquals("挺开心，但还有点委屈",text)
        assertEquals(text,RelationshipQuery.metrics(base){null}.last().degree)
        assertTrue(RelationshipSummary.render(base,0){null}.contains(text))
    }
    @Test fun sevenTemplatesAndCustomRangesAreComplete(){
        assertEquals(7,RelationTemplate.entries.size)
        RelationTemplate.entries.forEach{assertEquals(it.parameters,RelationParameterRules.clamp(it.parameters))}
        val c=RelationParameterRules.custom(RelationTemplate.DIRECT,p.copy(trustGain=9.0,disappointmentHours=-4.0,resendCount=99,dailyMax=-2,ignoredDisappointment=2.0))
        assertEquals("自定义（基于黏人直率）",c.label);assertEquals(.4,c.parameters.trustGain,0.0);assertEquals(1.0,c.parameters.disappointmentHours,0.0)
        assertEquals(3,c.parameters.resendCount);assertEquals(0,c.parameters.dailyMax);assertEquals(.2,c.parameters.ignoredDisappointment,0.0)
        assertTrue(RelationParameterRules.ranges.values.all{it.explanation.isNotBlank()})
        assertTrue("久别回落已不起作用，说明要写明不再使用",RelationParameterRules.ranges.getValue("cooling").explanation.startsWith("已不再使用"))
        assertFalse("久别每周回落" in RelationParameterRules.descriptions.values.joinToString())
    }
    @Test fun onlySteadyPromptHasNoPressureAndSummaryNeverContainsIt(){
        RelationTemplate.entries.forEach{assertEquals(it==RelationTemplate.STEADY,it.prompt().contains("不催促、不施压"))}
        assertFalse(RelationInputContract.instruction(1).contains("不催促"))
        assertFalse(RelationshipSummary.render(project(listOf(row(1)),20*day),20*day){"原话"}.contains("不催促"))
    }
    @Test fun summaryKeepsTwoItemsAndAggregateWhenQuotesAreLong(){
        val s=project(listOf(row(1).copy(v=.9,b=.9,p=.9,disappointment=.81)))
        val text=RelationshipSummary.render(s,0){"原话".repeat(500)}
        assertTrue(text.length<=250);assertEquals(2,Regex("因为").findAll(text).count());assertTrue(text.contains("其他都还好")||text.contains("别的小疙瘩")||text.contains("总体有点闷"))
        assertFalse(Regex("[0-9]").containsMatchIn(text))
    }
    @Test fun aggregateDoesNotHideAnUnmentionedDisappointmentOrLowMood(){
        val s=project(emptyList()).copy(trust=0.0,happy=.9,disappointment=.25)
        assertTrue(RelationshipSummary.render(s,0){null}.contains("心里还有点别的小疙瘩"))
        assertTrue(RelationshipSummary.render(s.copy(disappointment=0.0,low=.25),0){null}.contains("最近总体有点闷"))
        assertTrue(RelationshipSummary.render(project(emptyList()),0){null}.contains("其他都还好"))
    }
    @Test fun recommendationFailureIsVisibleAndRetryIsExplicit()=runBlocking{
        var calls=0;var raw="{}"
        val creation=RelationshipCreation(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{assertEquals(config.outputTokens,request.outputLimit);calls++;return TextChatResult.Success(raw)}})
        val config=ModelConfig("test","test","custom","https://example.invalid","test")
        val first=creation.recommend("甲",config,"synthetic");assertNull(first.template);assertEquals("这次没推荐出来",first.message)
        assertEquals(first,creation.recommend("甲",config,"synthetic"));assertEquals(1,calls)
        raw="""{"模板":"傲娇"}""";assertEquals(RelationTemplate.TSUNDERE,creation.recommend("甲",config,"synthetic",retry=true).template);assertEquals(2,calls)
        creation.recommend("乙",config,"synthetic");assertEquals(3,calls)
    }
    @Test fun askKeepsToneProposalAndRejectsRetiredFrequencyWithoutRequest()=runBlocking{
        var calls=0;var raw="""{"参数":{"tone":"温柔些"},"她的话":"说得温柔些。"}"""
        val ask=RelationshipAsk(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success(raw)}})
        val config=ModelConfig("test","test","custom","https://example.invalid","test")
        val good=ask.ask("人设",config,"synthetic","tone",0);assertEquals("温柔些",good.proposal!!.values["tone"]);assertEquals(1,calls)
        assertTrue(runCatching{ask.ask("人设",config,"synthetic","silenceDays",0)}.isFailure);assertEquals(1,calls)
        raw="{}";assertEquals("她没说清楚，再问一次",ask.ask("人设",config,"synthetic","tone",0).message);assertEquals(2,calls)
        assertEquals(1.0,p.silenceDays,0.0)
    }
    @Test fun chatParameterProposalRequiresCurrentRequestAndSafeAllowlist(){
        fun parse(input:String,key:String="tone",source:Long=1)=RelationParameterContract.parse(JsonParser.parseString("""{"消息":"$source","参数":{"$key":"温柔些"},"她的话":"可以，等你确认。"}"""),row(1).source,input,0)
        assertEquals("温柔些",parse("说话温柔些")!!.values["tone"])
        for(key in listOf("silenceDays","resendDays","resendCount","dailyMax"))assertNull(parse("三天找我一次",key))
        // Intent is the model's job; source and parameter allowlist remain program checks.
        assertNotNull(parse("今天看了电影"));assertNull(parse("三天找我一次",source=2));assertNull(parse("改失望参数，三天找我一次","sensitivity"))
        assertNotNull(parse("改人设，三天找我一次"));assertFalse("ignoredDisappointment" in RelationParameterRules.modelAllowed)
    }
    @Test fun proactiveOriginalTemplatesKeepTimingsAndCapsWithoutAutomaticFeelings(){
        for(t in listOf(RelationTemplate.DIRECT,RelationTemplate.RESERVED,RelationTemplate.STEADY)){
            val p=t.parameters;val exact=-ln(1-RelationDecay.thetaBase(p.silenceDays))/RelationDecay.missingRate(.2)*RelationshipProjector.HOUR;val first=RelationshipProactive.next(p,0,emptyList(),0,0)!!
            assertTrue(first>=exact&&first-exact<=60_000)
            val sent=RelationSent(row(1).source,first)
            val state=RelationCursor(RelationStart.FRIENDS,p).also{it.contact(0);it.reachOut(sent.at);it.advance(first+3*day)}.state()
            assertEquals(.5,state.trust,0.0);assertEquals(0.0,state.disappointment,0.0)
            assertEquals(0.0,state.hurt,0.0);assertEquals(0.0,state.low,0.0)
            val all=(0..p.resendCount).map{RelationSent(row(it+1L).source,first+it*day)}
            assertNull(RelationshipProactive.next(p,0,all,all.last().at,all.last().at/day*day))
        }
    }
    @Test fun proactiveHardCapsAndReplyStartANewSilenceWindow(){
        val sent=(1L..4).map{RelationSent(row(it).source,it*day)}
        assertNull(RelationshipProactive.next(p.copy(resendCount=99),0,sent,4*day,4*day))
        val due=RelationshipProactive.next(p,5*day,sent,5*day,5*day)!!
        val exact=5*day-ln(.6)/RelationDecay.missingRate(.2)*RelationshipProjector.HOUR
        assertTrue(due>=exact&&due-exact<=60_000)
        val five=(1L..5).map{RelationSent(row(it).source,day+it)}
        assertEquals(2*day,RelationshipProactive.next(p.copy(dailyMax=99,silenceDays=.5),day+6,five,day+6,day))
    }
    @Test fun historyRoundTripPreservesParametersAndCorrectionTimes(){
        val history=RelationHistory(changes=listOf(RelationChange(day,p,RelationTemplate.QUIET.parameters,"安静依赖")),corrections=listOf(RelationCorrection(row(1).source,day,true),RelationCorrection(row(1).source,day+1,false)))
        assertEquals(history,Gson().fromJson(Gson().toJson(history),RelationHistory::class.java))
    }
}
