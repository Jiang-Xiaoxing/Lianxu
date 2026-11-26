package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationshipPromptTest {
    private val now=1_800_000_000_000L
    private val source=SourceRef("a",12,3)
    private fun state()=RelationState(.8,.7,.6,.5,.9,.8,.7,emptyMap(),now,hurt=.6,low=.5)
    private fun render(s:RelationState?=state())=RelationshipPrompt.render(s,now){"原话"}
    @Test fun moodAndRelationshipUseNaturalLanguage(){
        val text=render()
        for(word in listOf("很开心","很信任","很亲近","疙瘩","落空")) assertTrue(word,text.contains(word))
        assertFalse(Regex("""\d+\.\d+""").containsMatchIn(text))
    }
    @Test fun unknownAndInvalidValuesRemainUnknown(){
        for(text in listOf(render(null),render(state().copy(trust=Double.NaN)))){
            assertTrue(text.contains("还说不准"));assertFalse(text.contains("NaN"))
        }
    }
    @Test fun noteNeedsTheCurrentPermittedSourceAndExpiresAtOneDay(){
        val note=ReviewInnerNote("其实我还挺期待那场电影",now)
        val allowed=mutableSetOf(source)
        assertTrue(note.visible(now+RelationshipProjector.DAY-1,source,allowed::contains))
        assertFalse(note.visible(now+RelationshipProjector.DAY,source,allowed::contains))
        assertFalse(note.visible(now,source.copy(version=2),allowed::contains))
        assertTrue(RelationshipPrompt.render(state(),now,innerNote={note.text}){null}.contains(note.text))
        allowed.clear()
        assertFalse(note.visible(now,source,allowed::contains))
    }
    @Test fun incompleteAttributionSuppressesEvenARecentNote(){
        val text=RelationshipPrompt.render(state(),now,true,{error("note consulted")}){error("source consulted")}
        assertEquals(RelationshipPrompt.UNVERIFIED,text)
    }
    @Test fun sharedCausesKeepOriginalQuoteAndUsableVersionWithoutRepeatingIt(){
        val hidden=SourceRef("a",20,1)
        val s=state().copy(causes=linkedMapOf("happy" to RelationCause(source,now-1000,"增加"),"tension" to RelationCause(source,now-1000,"减少"),"low" to RelationCause(hidden,now,"增加")),excuses=mapOf("工作忙" to 2))
        val text=RelationshipPrompt.render(s,now){if(it==source)"来源12v1；主动交流按模板语气，这是用户原话" else null}
        assertTrue(text.contains("开心，"));assertTrue(text.contains("心结，"));assertTrue(text.contains("记录的变化：减少"))
        assertEquals(1,Regex.fromLiteral("这是用户原话").findAll(text).count())
        assertTrue(text.contains("消息 12，版本 3"));assertFalse(text.contains("消息 20"))
        assertTrue(text.contains("〈工作忙〉累计记录2 次"));assertFalse(text.contains("其他都还好"))
    }
    @Test fun promptLeavesExpressionToModelAndMarksSnapshotLag(){
        val text=render()
        for(value in listOf("可能还没计入最新几轮聊天","不保证实时","自主理解","不必逐项表达","过去的感受不等于此刻感受"))assertTrue(text.contains(value))
    }
    @Test fun renderingDoesNotMutateCalculatedStateOrItsSources(){
        val input=RelationInput(source,now,now/RelationshipProjector.DAY,"关于关系",.3,0.0,.6,0.0)
        val s=RelationshipProjector.project(RelationTemplate.DIRECT.parameters,listOf(input),now,RelationStart.FRIENDS)
        val before=Gson().toJson(s);repeat(2){render(s)}
        assertEquals(before,Gson().toJson(s))
        assertEquals(s,RelationshipProjector.project(RelationTemplate.DIRECT.parameters,listOf(input),now,RelationStart.FRIENDS))
    }
    @Test fun replyAndProactiveRequestsKeepStateAndSelectedExperienceOnce():Unit=runBlocking {
        val material=render();val experience="我们说好周日再看电影"
        val refs=listOf(PromptReference(RelationshipPrompt.LABEL,material,true),PromptReference("相关经历",experience+"；消息 12，版本 3",true),PromptReference(CompanionPrompt.TONE_LABEL,"主动交流语气：固定句式",true))
        val kept=CompanionPrompt.replyReferences(refs)
        val reply=(CompanionPrompt.build(Friend("a","甲","成年"),RolePrompts(),emptyList(),"今天怎么样",kept,capacity=64000) as PromptBuild.Ready).request
        val body=proactiveReferences(refs).joinToString("\n"){it.label+"\n"+it.text}
        var calls=0;var actual=""
        val model=CompanionModel(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;actual=request.messages.joinToString("\n"){it.content};return TextChatResult.Success("""{"action":"silent","reason":"先不联系"}""")
        }})
        model.reconsider(ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,2048),"fake",
            CompanionOpportunity("x","a",12,3,0,0,now,Long.MAX_VALUE,"看电影","running",1),
            CompanionSnapshot("a",0,0,"",listOf(source),"","enabled",proactivePrompt=body),"甲",now,"")
        assertEquals(1,calls)
        for(text in listOf(reply.messages.joinToString("\n"){it.content},actual)){
            assertEquals(1,Regex.fromLiteral(RelationshipPrompt.LABEL).findAll(text).count())
            assertTrue(text.contains("很开心"));assertTrue(text.contains(experience));assertTrue(text.contains("消息 12，版本 3"))
            assertFalse(text.contains("当前关系小结"));assertFalse(text.contains("主动交流语气：固定句式"))
        }
    }
}
