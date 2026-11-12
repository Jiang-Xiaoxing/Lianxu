package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/**
 * 记账触发措辞：回话规矩只轻轻提一句「值得记住的就顺手记下来」，具体类别放在工具说明里；不每轮扫描一遍
 * 「评价、感受、学习或关系变化」。收窄的同时不能把真实关系事件漏掉，也不能整类禁记。
 */
class RecordTriggerWordingTest {
    private val source=SourceRef("a",2,1)
    private val context=RoleContext("a",source,mapOf(2L to source),emptyList(),emptyList(),emptyList(),emptyList())
    private val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)

    private fun replySystem(recordEnabled:Boolean=true):String{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Prompt preparation must not call a model")}
        val base=CompanionPrompt.build(Friend("a","合成角色","成年"),RolePrompts(),emptyList(),"问候",emptyList(),capacity=64000,outputLimit=2048) as PromptBuild.Ready
        val ready=RolePlanner(client).prepareReply(base,config,"问候",ExperienceProposal(),context,0,false,emptyMap(),0,recordEnabled) as PromptBuild.Ready
        return ready.request.messages.first().content.let{assertTrue("回话规矩要和人设放在开头",it.contains("[companion.reply.v1]"));"[companion.reply.v1]"+it.substringAfter("[companion.reply.v1]")}
    }

    private fun toolDescription():String{
        @Suppress("UNCHECKED_CAST") val function=CompanionRecord.definition(2,true)["function"] as Map<String,Any>
        return function["description"] as String
    }

    @Test fun broadScanWordingIsGoneFromBothPlaces(){
        for(text in listOf(replySystem(),toolDescription())){
            assertFalse("不再用「评价、感受、学习或关系变化」这种每轮扫描的写法","评价、感受、学习或关系变化" in text)
        }
    }

    @Test fun replyRulesOnlyCarryOneSoftLine(){
        val system=replySystem()
        assertTrue("回话规矩只轻轻提一句","聊到值得记住的事就顺手记下来" in system)
        assertTrue("不强制调用","记不记由你判断" in system)
        assertTrue("没记成也照常回话","没记成也照样自然回话" in system)
        assertFalse("规矩里不点英文工具名","调用 companion_record 记下" in system)
        for(hint in listOf("新约定或改期","重要的新情况","伤到你或越过边界","明确的感谢","不是禁区","颜文字")){
            assertFalse("类别清单只留在工具说明里：$hint",hint in system)
        }
        assertFalse("不加禁止想工具的要求","禁止想工具" in system)
    }

    @Test fun toolDescriptionStillCoversRelationshipAccounting(){
        val description=toolDescription()
        for(hint in listOf("约定","改期","新情况","兑现","边界","落差","解释","道歉","弥补","明确的感谢","问候","玩笑","颜文字","照样可以记","不调用也能正常回话")){
            assertTrue("工具说明应覆盖：$hint",hint in description)
        }
    }
    @Test fun toolDescriptionCarriesTheSameNarrowTrigger(){
        val description=toolDescription()
        for(hint in listOf("约定","兑现","边界","不用记","明确的感谢","不是禁区")){
            assertTrue("工具描述也应有：$hint",hint in description)
        }
    }
}
