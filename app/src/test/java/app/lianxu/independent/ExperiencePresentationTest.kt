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
    @Test fun visibilityNeverBecomesReadOrDeliveryConfirmation(){
        val visible=ExperiencePresentation.visibility(now-1000,now,zone)
        assertTrue(visible.contains("消息曾出现在聊天页面"));assertTrue(visible.contains("不代表对方读过"))
        assertFalse(visible.contains((now-1000).toString()));assertFalse(visible.contains("已经看到"))
        val absent=ExperiencePresentation.visibility(null,now,zone)
        assertTrue(absent.contains("是否读过不确定"));assertFalse(absent.contains("还没看到"))
    }
    @Test fun receiptAddsActualElapsedTimeWithoutInventingReadOrReplyStatus(){
        for(readAt in listOf(null,now-1000)){
            val text=ExperiencePresentation.receipt(12,readAt,now,zone,now-3*86_400_000L)
            assertTrue(text.contains("距现在约 3 天"));assertTrue(text.endsWith("回复 12"))
            assertFalse(text.contains("已读但没回"));assertFalse(text.contains("实际未读"));assertFalse(text.contains("失望"))
            assertTrue(text.contains(if(readAt==null)"是否读过不确定"else"不代表对方读过"))
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
        assertTrue(body.contains("不代表对方读过"));assertTrue(refs.contains("消息 12，版本 7"));assertTrue(refs.contains("回复 56"))
        assertTrue(refs.contains("变化 habit-abc，版本 3"));assertEquals(before,Gson().toJson(context))
    }
    @Test fun replyNotesPutTheEventBeforeIdsAndDoNotCallVisibilityRead(){
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No model calls")}
        val config=ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,2048)
        val base=CompanionPrompt.build(Friend("a","甲","角色简介"),RolePrompts(),emptyList(),"今天有点累",capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val prepared=RolePlanner(client).prepareReply(base,config,"今天有点累",ExperienceProposal(memory="谈到今天的加班"),context,now,false,emptyMap(),0) as PromptBuild.Ready
        val notes=prepared.request.messages.first{it.content.startsWith("[这一轮的笔记")}.content
        val body=notes.substringBefore(ExperiencePresentation.REFS);val refs=notes.substringAfter(ExperiencePresentation.REFS)
        assertTrue(body.contains("谈到今天的加班"));assertFalse(body.contains("编号"));assertFalse(body.contains("已经看到"))
        assertTrue(body.contains("不代表对方读过"));assertTrue(refs.contains("编号是 99，版本 2"));assertTrue(refs.contains("回复 56"))
        assertTrue(refs.contains("habit-abc"));assertTrue(refs.contains("消息 12，版本 7"))
    }
}
