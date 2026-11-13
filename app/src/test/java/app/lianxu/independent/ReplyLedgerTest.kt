package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/**
 * 平常回话的桌面上不摊旧账单：旧 entries 的评价/感受/目标全文不进提示词，附带的只是一段中文笔记，
 * 只留用得上的内容和工具要用的编号；真要翻旧事时靠 relation_event_search 查。规矩和人设放在开头，
 * 最后两条是用户原话和时间提醒。
 */
class ReplyLedgerTest {
    private val role="a"
    private val current=SourceRef(role,9,1)
    private val old=SourceRef(role,5,1)
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)

    private val flow=RoleUnderstanding(
        appraisal=RoleAppraisal("上次的约定","给个不施压的收尾","reported","回应","user_report","聊天","new_event",emptyList(),listOf(RoleEvidence(old))),
        feelings=listOf(RoleFeeling("care","mild","对方","不想逼太紧",listOf(RoleEvidence(old)))),
        goal=RoleGoal("把话说清楚","current_request","active","怕追问太急",listOf(RoleEvidence(old))),
        choices=listOf(RoleChoice("respond","acknowledge","先收住",listOf(RoleEvidence(old)))),
        selected="respond",
        judgment=RoleJudgment("别施压","inference",listOf(RoleEvidence(old))),
        feedback=emptyList(),learning=null,usedLearningIds=emptyList(),consideredLearningIds=emptyList(),future=null,
        dependencies=emptyList()
    )
    private val context=RoleContext(role,current,mapOf(5L to old,9L to current),listOf(RoleEntry(old,flow,null)),emptyList(),emptyList(),emptyList())

    private fun prepared(facts:ExperienceProposal=ExperienceProposal()):List<WireMessage>{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Prompt preparation must not call a model")}
        val base=CompanionPrompt.build(Friend("a","合成角色","成年"),RolePrompts(),emptyList(),"在吗？美女",emptyList(),capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val ready=RolePlanner(client).prepareReply(base,config,"在吗？美女",facts,context,0,false,emptyMap(),0) as PromptBuild.Ready
        return ready.request.messages
    }
    private fun replySystem():String=prepared().joinToString("\n"){it.content}
    /** The notes are the system message placed right before the user's words. */
    private fun notes(messages:List<WireMessage>):String=messages[messages.indexOfLast{it.role=="user"}-1].also{assertEquals("system",it.role)}.content

    @Test fun oldLedgerTextIsNotOnTheReplyBoard(){
        val system=replySystem()
        assertFalse("旧评价不该塞进回话","给个不施压的收尾" in system)
        assertFalse("旧目标不该塞进回话","把话说清楚" in system)
        assertFalse("旧感受不该塞进回话","不想逼太紧" in system)
        assertFalse("旧判断不该塞进回话","别施压" in system)
    }

    @Test fun rulesSitWithThePersonaAndTheUserSpeaksLast(){
        val messages=prepared()
        assertTrue("规矩和人设在同一条开头系统消息里",messages.first().role=="system"&&"[companion.reply.v1]" in messages.first().content)
        assertEquals("规矩只出现一次",1,messages.count{"[companion.reply.v1]" in it.content})
        assertEquals("倒数第二条是用户原话","在吗？美女",messages[messages.size-2].content)
        assertTrue("最后一条是时间提醒",messages.last().turnContext)
        val sent=CompanionPrompt.forSend(TextChatRequest(messages),emptyList()).messages
        assertTrue("发送时仍带中文思考提醒",sent[sent.size-2].content.endsWith(CompanionPrompt.THINKING_REMINDER))
        assertTrue(sent.last().turnContext)
    }

    @Test fun notesArePlainChineseWithoutJsonOrCodes(){
        val text=notes(prepared(ExperienceProposal(memory="下周三一起去看展",emotion="happy")))
        assertTrue("这一轮发生的事：" in text)
        assertTrue("下周三一起去看展" in text);assertTrue("看起来开心" in text)
        assertTrue("当前消息编号要用中文说清楚","编号是 9" in text)
        for(code in listOf("{\"","current_source","valid_context","knownSources","independent_facts","reported","inference","unknown","turnId","\"entries\""))
            assertFalse("笔记里不该有 $code",code in text)
        val empty=notes(prepared())
        assertFalse("没有内容的栏目不出现","这一轮发生的事" in empty)
        assertFalse("最近的关系变化" in empty)
    }

    @Test fun notesStillListTheOlderNumbersSheMayCite(){
        val text=notes(prepared())
        val line=text.lines().first{it.startsWith("还能引用的消息编号：")}
        assertTrue("上一轮理解的来源编号要在，不然引用会被判 UNKNOWN_SOURCE","消息 5，版本 1" in line)
        assertFalse("当前消息另有一行，不重复列","消息 9，" in line)
        assertFalse("摘要不带旧评价原文","给个不施压的收尾" in line||"别施压" in line)
    }

    @Test fun contractUsesChineseInsteadOfCodes(){
        val contract=prepared().first().content.substringAfter("[companion.reply.v1]")
        for(code in listOf("reported","inference","unknown","source","context","input","当前时间="))
            assertFalse("规矩里不该再有 $code",code in contract)
    }

    @Test fun contractSaysLookItUpBeforeRevisiting(){
        val system=replySystem()
        assertTrue("更早的旧事要先查工具","relation_event_search" in system)
        assertTrue("查不到就坦诚，不编编号","不编编号" in system)
        assertTrue("近期变化对应的工具编号可以直接用","最近关系变化旁的消息编号可以直接用" in system)
    }

    @Test fun recordGuideStillExplainsSeenSources(){
        @Suppress("UNCHECKED_CAST") val function=CompanionRecord.definition(9,false)["function"] as Map<String,Any>
        val guide=function["description"] as String
        assertTrue("工具说明仍要求引用已列出或查得的来源","已列出" in guide || "查得" in guide || "relation_event_search" in guide)
    }
}
