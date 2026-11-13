package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ControlPresentationTest {
    private fun applied(action:String)=ExperienceProposal(control=action,controlChecked=true,controlAuthorized=true,targets=listOf(12,23))
    @Test fun threeSuccessfulActionsHaveAccurateDifferentMeaning(){
        val forget=ControlPresentation.reference(applied("forget"))!!.text
        assertTrue(forget.contains("已停止使用指定经历"));assertTrue(forget.contains("原聊天仍保留"))
        val correct=ControlPresentation.reference(applied("correct"))!!.text
        assertTrue(correct.contains("已停用指定的旧来源"));assertTrue(correct.contains("以实际写入结果为准"));assertFalse(correct.contains("并保存这次更正"))
        val stop=ControlPresentation.reference(applied("stop_reminding"))!!.text
        assertTrue(stop.contains("已取消尚未发送的相关回访"));assertTrue(stop.contains("没有执行遗忘"))
        for(text in listOf(forget,correct,stop)){
            assertTrue(text.contains("12、23"));assertTrue(text.contains("不要重复执行"))
            assertFalse(text.contains(ControlPresentation.NOT_COMPLETED));assertFalse(text.contains("\"success\""));assertFalse(text.contains("\"targets\""))
        }
    }
    @Test fun noSuccessIsNeverInventedFromUncheckedOrUnresolvedProposal(){
        val cases=listOf(ExperienceProposal(controlChecked=true),applied("forget").copy(controlChecked=false),applied("forget").copy(targets=emptyList()),applied("unknown"))
        cases.forEach{assertEquals(ControlPresentation.NOT_COMPLETED,ControlPresentation.reference(it)!!.text)}
        assertTrue(ControlPresentation.NOT_COMPLETED.contains("若尚未找到对应记录"))
        assertTrue(ControlPresentation.NOT_COMPLETED.contains("若工具报告失败"))
        assertFalse(ControlPresentation.NOT_COMPLETED.contains("这项操作已经完成"))
    }
    @Test fun noOperationHasNoReference(){assertNull(ControlPresentation.reference(null))}
    @Test fun presentationDoesNotChangeToolReceiptOrControlFacts(){
        val p=applied("forget");val saved=Gson().toJson(p);val receipt=JointControl.receipt(p);val encoded=Gson().toJson(receipt)
        repeat(2){ControlPresentation.reference(p)}
        assertEquals(saved,Gson().toJson(p));assertEquals(encoded,Gson().toJson(JointControl.receipt(p)))
        assertEquals(setOf("success","action","targets","status","note"),receipt.keys)
        assertEquals(true,receipt["success"]);assertEquals("applied",receipt["status"])
    }
    @Test fun normalAndRebuiltRequestsContainOnlyTheirCurrentOutcomeAndKeepOriginal(){
        val original="请忘记我喜欢紫色风筝这件事，然后聊聊天气。"
        for(adaptive in listOf(false,true))for(p in listOf(null,applied("forget"),applied("correct"),applied("stop_reminding"))){
            val ref=ControlPresentation.reference(p);ref?.let{assertTrue(it.mandatory)}
            val ready=CompanionPrompt.build(Friend("a","甲","成年"),RolePrompts(),emptyList(),original,listOfNotNull(ref),adaptive=adaptive,capacity=64000) as PromptBuild.Ready
            val request=CompanionPrompt.forSend(ready.request,listOf(JointControl.definition))
            val system=request.messages.filter{it.role=="system"}.joinToString("\n"){it.content}
            assertEquals(if(p==null)0 else 1,Regex.fromLiteral(ControlPresentation.LABEL).findAll(system).count())
            ref?.let{assertTrue(system.contains(it.text))};assertFalse(system.contains(ControlPresentation.NOT_COMPLETED))
            assertFalse(system.contains("本轮已执行工具回执"));assertFalse(system.contains("\"status\":\"applied\""))
            assertEquals(original,request.messages.single{it.role=="user"}.content.removeSuffix("\n\n"+CompanionPrompt.THINKING_REMINDER))
        }
    }
}
