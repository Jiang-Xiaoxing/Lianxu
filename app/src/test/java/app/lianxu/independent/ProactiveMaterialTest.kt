package app.lianxu.independent

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 主动联系只给原始材料：最近聊天（带时间）、回访原话、上次聊天时间与间隔、现在时间；程序不写理由，不限时段。 */
class ProactiveMaterialTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun at(d:Int,h:Int,mi:Int)=LocalDateTime.of(2026,11,d,h,mi).atZone(zone).toInstant().toEpochMilli()
    private val now=at(11,2,30) // 深夜也照常交给她判断
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)
    private val chat=listOf(Message(1,"a","我后天面试",true,at(8,22,10)),Message(2,"a","加油，面完跟我说",false,at(8,22,11)),
        Message(3,"a","【已撤回】",true,at(9,9,0)),Message(4,"a","刚下班",true,at(10,20,0)),Message(5,"a","辛苦啦",false,at(10,20,1)))
    private val followUp=CompanionOpportunity("a:1:1","a",1,1,0,0,0,now+86_400_000,"等他面试完问问结果","running",1)
    private val relation=followUp.copy(id="relationship:a:4:0:0",source=4,reason="")

    private fun prompt(o:CompanionOpportunity,source:Message?,snapshot:CompanionSnapshot=CompanionSnapshot("a",0,0,"",emptyList(),"当前关系小结","enabled")):String {
        var sent="";val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            sent=request.messages.joinToString("\n"){it.content};return TextChatResult.Success("""{"action":"silent","reason":"先不打扰"}""")}}
        runBlocking{CompanionModel(client).reconsider(config,"synthetic",o,snapshot,"人设",now,"",source?.created,ProactiveMaterial.recent(chat),source,zone)}
        return sent
    }

    @Test fun followUpGetsHerOwnWordsAndRawChat(){
        val p=prompt(followUp,chat[0])
        assertTrue(p.contains("[现在] 2026年11月11日 星期三 02:30"))
        assertTrue(p.contains("[上次聊天] [昨天 20:01]，距现在约 6 小时"))
        assertTrue(p.contains("[11月8日 22:10] 对方：我后天面试"))
        assertTrue(p.contains("[11月8日 22:11] 你：加油，面完跟我说"))
        assertTrue(p.contains("（隔了 1 天）\n[昨天 20:00] 对方：刚下班"))
        assertFalse("撤回的不给",p.contains("【已撤回】"))
        assertTrue(p.contains("你当时留下的原话：等他面试完问问结果"))
        assertTrue(p.contains("当时对方那条消息（11月8日 22:10，距现在约 2 天）：我后天面试"))
        assertFalse(p.contains("再考虑理由"))
    }

    @Test fun relationshipChanceHasNoProgramReason(){
        val p=prompt(relation,null)
        assertFalse(p.contains("想你"));assertFalse(p.contains("疙瘩"))
        assertTrue(p.contains("没有约好的回访，只是一次可以主动联系的机会。"))
        assertTrue(p.contains("要不要联系、为什么联系、说什么，都由你看下面的原始材料自己决定"))
        val legacy=prompt(relation.copy(reason=ProactiveMaterial.LEGACY_RELATION_REASON+"语气轻松"),null)
        assertFalse("旧库里程序写的理由也不再给她",legacy.contains("想你"))
        assertTrue(prompt(relation.copy(reason="明早再问"),null).contains("[你上次推迟时留下的话]\n明早再问"))
    }

    @Test fun noProgramSummaryOrTemplateToneInProactivePrompt(){
        val chat=CompanionSnapshot("a",0,0,"",emptyList(),"[当前关系小结]\n挺想对方，但心里还有点疙瘩\n主动交流语气：关心一句，不抱怨。","enabled",proactivePrompt="[手机保存的经历]\n来源1v1: 后天面试")
        val p=prompt(followUp,null,chat)
        assertFalse(p.contains("当前关系小结"));assertFalse(p.contains("疙瘩"));assertFalse(p.contains("主动交流语气"))
        assertTrue(p.contains("来源1v1: 后天面试"))
        val refs=listOf(PromptReference(RELATION_SUMMARY_LABEL,"挺想对方",true),PromptReference(CompanionPrompt.TONE_LABEL,"主动交流语气：关心一句",true),PromptReference("当前权限与交流边界","主动联系许可=enabled",true))
        assertEquals(listOf("当前权限与交流边界"),proactiveReferences(refs).map{it.label})
    }

    @Test fun agoAndRecentWindow(){
        assertEquals("不到 1 分钟",ProactiveMaterial.ago(5_000));assertEquals("14 小时",ProactiveMaterial.ago(14*3_600_000L));assertEquals("3 天",ProactiveMaterial.ago(3*86_400_000L+1))
        val many=(1..30L).map{Message(it,"a","第$it",it%2==1L,it)}
        assertEquals((19..30L).toList(),ProactiveMaterial.recent(many).map{it.id})
    }
    @Test fun existingRequestGetsVisibilityTimeAndActualConversationWithOrWithoutLaterUserMessage(){
        for(replied in listOf(false,true)){
            val sentAt=now-3*86_400_000L
            val sent=Message(20,"a","面试怎么样了？",false,sentAt)
            val messages=listOf(sent)+if(replied)listOf(Message(21,"a","还要等通知",true,now-60_000))else emptyList()
            val facts=ExperiencePresentation.receipt(20,if(replied)now-60_000 else null,now,zone,sentAt)
            var calls=0;var text=""
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                calls++;text=request.messages.joinToString("\n"){it.content};return TextChatResult.Success("""{"action":"silent","reason":"先不打扰"}""")}}
            runBlocking{CompanionModel(client).reconsider(config,"synthetic",relation,CompanionSnapshot("a",0,0,"",emptyList(),"","enabled"),"成年角色",now,facts,recent=messages,zone=zone)}
            assertEquals(1,calls);assertTrue(text.contains("距现在约 3 天"));assertTrue(text.contains("面试怎么样了？"))
            assertEquals(replied,text.contains("还要等通知"));assertFalse(text.contains("已读但没回"))
            assertTrue(text.contains(if(replied)"不代表对方读过"else"是否读过不确定"))
        }
    }
}
