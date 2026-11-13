package app.lianxu.independent

import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ExperiencePresentationTest {
    private val now=Instant.parse("2027-01-01T00:05:00Z").toEpochMilli()
    private val zone=ZoneId.of("Asia/Shanghai")
    private val source=SourceRef("a",12,7)
    private val current=SourceRef("a",99,2)
    private val evidence=listOf(RoleEvidence(source))
    private val action=RoleActualAction(source,"listen","chat",56,"你慢慢说",emptyList(),emptyList(),"current_request",now-1000,null)
    private val flow=RoleUnderstanding(appraisal=RoleAppraisal("谈到加班","先陪对方说说","reported","倾听","user_report","chat","new_event",emptyList(),evidence),
        feelings=listOf(RoleFeeling("care","mild","对方","对方累了",evidence)),goal=RoleGoal("先听完","current_request","waiting","不打断",evidence),
        choices=listOf(RoleChoice("one","listen","不急着建议",evidence)),selected="one",judgment=RoleJudgment("先别急着提建议","inference",evidence),
        feedback=emptyList(),learning=null,usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,dependencies=evidence)
    private val context=RoleContext("a",current,mapOf(12L to source,99L to current),listOf(RoleEntry(source,flow,action)),listOf(action),
        listOf(RoleLearningState("habit-abc","habit","累的时候","先听完再建议","paused",source,3)),listOf(CompanionExperience(source,"那天聊到加班","我今天有点累")))

    @Test fun timestampsUseExistingCalendarAcrossYearsAndZones(){
        val old=Instant.parse("2026-12-31T15:00:00Z").toEpochMilli()
        assertEquals("昨天 23:00",ExperiencePresentation.at(old,now,zone))
        assertEquals("今天 07:00",ExperiencePresentation.at(old,now,ZoneId.of("America/Los_Angeles")))
        assertFalse(ExperiencePresentation.at(old,now,zone).contains(old.toString()))
    }
    @Test fun futureClockIsNotSilentlyPresentedAsThePast(){
        assertTrue(ExperiencePresentation.at(now+1,now,zone).contains("晚于现在，需核对"))
    }
    @Test fun visibilityUsesExistingReadMarkerWithoutRepeatedDisclaimers(){
        val visible=ExperiencePresentation.visibility(now-1000,now,zone)
        assertTrue(visible.startsWith("已读（"));assertFalse(visible.contains("不代表"))
        assertFalse(visible.contains((now-1000).toString()));assertFalse(visible.contains("已经看到"))
        val absent=ExperiencePresentation.visibility(null,now,zone)
        assertEquals("未读",absent);assertFalse(absent.contains("还没看到"))
    }
    @Test fun receiptAddsActualElapsedTimeWithoutInventingReadOrReplyStatus(){
        for(readAt in listOf(null,now-1000)){
            val text=ExperiencePresentation.receipt(12,readAt,now,zone,now-3*86_400_000L)
            assertTrue(text.contains("距现在约 3 天"));assertTrue(text.startsWith("回复 12："))
            assertFalse(text.contains("已读但没回"));assertFalse(text.contains("实际未读"));assertFalse(text.contains("失望"))
            assertTrue(text.startsWith(if(readAt==null)"回复 12：未读"else"回复 12：已读"));assertFalse(text.contains("不代表"))
        }
    }
    @Test fun receiptDoesNotTurnFutureClockIntoElapsedTime(){
        val text=ExperiencePresentation.receipt(12,null,now,zone,now+60_000)
        assertTrue(text.contains("晚于现在"));assertFalse(text.contains("距现在约"))
    }
    @Test fun experienceKeepsOriginalWordsAndExactToolIdentity(){
        val original="原话里的来源12v1和500毫秒，以及明天18:05，都要原样保留。"
        val text=ExperiencePresentation.experience(source,now-1000,original,now,zone)
        assertTrue(text.contains(original));assertTrue(text.endsWith("这段经历：消息 12，版本 7"))
        assertTrue(text.indexOf(original)<text.indexOf(ExperiencePresentation.REFS))
    }
    @Test fun policyTextDistinguishesPausedDisabledAndUnknown(){
        assertEquals("对方允许主动联系",ExperiencePresentation.permission("enabled"))
        assertTrue(ExperiencePresentation.permission("paused_by_user").contains("暂停"))
        assertTrue(ExperiencePresentation.permission("awaiting_user_resume").contains("重新开启"))
        assertTrue(ExperiencePresentation.permission("disabled").contains("尚未开启"))
        assertTrue(ExperiencePresentation.permission("unexpected").contains("不能当作已获许可"))
    }
    @Test fun historicalContextHasReadableBodyAndSeparateAccurateReferences(){
        val before=Gson().toJson(context);val text=context.prompt(now,zone)
        val body=text.substringBefore(ExperiencePresentation.REFS);val refs=text.substringAfter(ExperiencePresentation.REFS)
        for(code in listOf("来源12v7","habit-abc","程序状态=","paused","reported",(now-1000).toString()))assertFalse("body contains $code",code in body)
        assertTrue(body.contains("先陪对方说说"));assertTrue(body.contains("有点关心"));assertTrue(body.contains("已经放下，不能再用"))
        assertFalse(body.contains("已读"));assertFalse(body.contains("未读"));assertTrue(refs.contains("消息 12，版本 7"));assertTrue(refs.contains("回复 56"))
        assertTrue(refs.contains("变化 habit-abc，版本 3"));assertEquals(before,Gson().toJson(context))
    }
    @Test fun replyNotesKeepActionsAndExactToolReferencesWithoutRepeatingReadState(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No model calls")}
        val config=ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,2048)
        val base=CompanionPrompt.build(Friend("a","甲","角色简介"),RolePrompts(),emptyList(),"今天有点累",capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val prepared=RolePlanner(client).prepareReply(base,config,"今天有点累",ExperienceProposal(memory="谈到今天的加班"),context,now,false,emptyMap(),0) as PromptBuild.Ready
        val notes=prepared.request.messages.first{it.content.startsWith("[这一轮的笔记")}.content
        assertFalse(notes.contains(ExperiencePresentation.REFS))
        assertTrue(notes.contains("谈到今天的加班"));assertFalse(notes.contains("已经看到"))
        assertFalse(notes.contains("已读"));assertFalse(notes.contains("未读"));assertTrue(notes.contains("编号是 99，版本 2"))
        assertTrue(notes.lines().single{it.startsWith("- [回复 56；")}.contains("来源消息 12，版本 7] 你选择倾听，回了「你慢慢说」"))
        assertTrue(notes.lines().single{it.startsWith("- [变化 habit-abc，版本 3；")}.contains("来源消息 12，版本 7] 习惯：先听完再建议"))
    }
    @Test fun differentMessageStatesKeepTheirOwnSourceAndReplyNumbers(){
        val unread=action.copy(source=SourceRef("a",31,4),replyId=78,reply="另一条回应",readAt=null)
        val view=context.copy(actions=listOf(action,unread),knownSources=context.knownSources+(31L to unread.source))
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No model calls")}
        val text=RolePlanner(client).replyNotes(ExperienceProposal(),view,UserPersona(),now)
        val lines=text.lines()
        assertTrue(lines.single{it.startsWith("- [回复 56；来源消息 12，版本 7]")}.contains("你慢慢说"))
        assertTrue(lines.single{it.startsWith("- [回复 78；来源消息 31，版本 4]")}.contains("另一条回应"))
        assertFalse(text.contains("已读"));assertFalse(text.contains("未读"))
        assertEquals(1,Regex("引用行动时，来源用消息编号，message 用回复编号").findAll(text).count())
        assertFalse(text.contains("不代表对方读过"))
    }

    @Test fun inlineReferencesKeepEntityTypesVersionsAndSourcesWithoutVisibleMaterial(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No model calls")}
        val orphan=SourceRef("a",81,6)
        val view=context.copy(
            knownSources=context.knownSources+(81L to orphan),
            actions=listOf(action.copy(replyId=12)),
            learning=listOf(context.learning.single().copy(id="12",version=4)),
            relationRecent=listOf(mapOf("消息" to 12L,"内容" to "说好下次继续聊","未兑现约定" to true)))
        val before=Gson().toJson(view)
        val text=RolePlanner(client).replyNotes(ExperienceProposal(),view,UserPersona(),now)
        val lines=text.lines()
        assertTrue(lines.single{it.startsWith("- [经历；")}.contains("来源消息 12，版本 7] 那天聊到加班"))
        assertTrue(lines.single{it.startsWith("- [回复 ")}.startsWith("- [回复 12；来源消息 12，版本 7]"))
        assertTrue(lines.single{it.startsWith("- [变化 ")}.startsWith("- [变化 12，版本 4；来源消息 12，版本 7]"))
        assertEquals("- [关系变化；消息 12] 说好下次继续聊（约好了还没兑现）",lines.single{it.startsWith("- [关系变化；")})
        assertEquals("还能引用的消息编号：消息 12，版本 7；消息 81，版本 6。",lines.single{it.startsWith("还能引用的消息编号：")})
        assertFalse("经历的来源消息不是记忆 ID",text.contains("[记忆 12]"))
        assertEquals("不再在编号表重复摘录回应",1,Regex("你慢慢说").findAll(text).count())
        assertEquals(before,Gson().toJson(view))
    }
}
