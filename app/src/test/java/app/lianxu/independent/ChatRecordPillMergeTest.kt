package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 同一轮里记账只显示一个结果：有一份记上了，失败那份就不再挂「没记下」；整轮都没记上照旧「没记下」。 */
class ChatRecordPillMergeTest {
    private fun rec(id:String,state:String,attempt:Int=1,before:String="")=ChatToolActivity(id,attempt,"嗯…我记住了",state,before,kind=ChatToolActivity.WRITE,tool=CompanionRecord.NAME)
    private fun shown(items:List<ChatToolActivity>)=mergeRecordPills(items).filter{it.state!=ChatToolActivity.SUPERSEDED}.map{it.id to it.label()}

    @Test fun parallelCallsOneLandedShowOnlySuccess(){
        // MiniMax 同一步交两份：一份成功，一份缺 experience 报错。
        val items=listOf(rec("ok","success"),rec("bad","failed"))
        assertEquals(listOf("ok" to "嗯…我记住了"),shown(items))
        assertEquals("存档里失败那份原样保留，诊断照样看得到","failed",items[1].state)
    }
    @Test fun resendThatLandsHidesTheEarlierFailure(){
        // 要求补发那轮：先失败，补发后暂存（随回话一起保存），活动预览里也不再显示「没记下」。
        assertEquals(listOf("again" to "嗯…我记住了"),shown(listOf(rec("first","failed"),rec("again","staged"))))
        assertEquals(listOf("again" to "嗯…我记住了"),shown(listOf(rec("first","failed"),rec("again","success"))))
    }
    @Test fun wholeTurnFailedStillSaysNotSaved(){
        assertEquals(listOf("a" to "没记下","b" to "没记下"),shown(listOf(rec("a","failed"),rec("b","failed"))))
    }
    @Test fun stagedThatDoesNotLandBringsTheFailureBack(){
        val settled=ChatToolJournal.settleRows(listOf(rec("first","failed"),rec("again","staged")),1,committed=true,recordSaved=false,memorySaved=true)
        assertEquals("最后没保存成功，就都如实显示没记下",listOf("first" to "没记下","again" to "没记下"),shown(settled))
    }
    @Test fun onlySameAttemptCounts(){
        val items=listOf(rec("old","success",attempt=1),rec("new","failed",attempt=2))
        assertEquals(listOf("old" to "嗯…我记住了","new" to "没记下"),shown(items))
    }
    @Test fun otherToolsAreUntouched(){
        val memory=ChatToolActivity("m",1,"嗯…我记住了","failed",kind=ChatToolActivity.WRITE,tool="agent_memory_update")
        val read=ChatToolActivity("r",1,"让我查一下聊天记录","failed",kind=ChatToolActivity.READ,tool="agent_history_search")
        assertEquals(listOf("ok" to "嗯…我记住了","m" to "没记下","r" to "没想起来"),shown(listOf(rec("ok","success"),memory,read)))
    }
    @Test fun hiddenPillKeepsItsTextAndDoesNotCutThinking(){
        val merged=mergeRecordPills(listOf(rec("bad","failed",before="先说一句"),rec("ok","success")))
        assertEquals("模型在那次调用前说的话还在","先说一句",merged[0].before)
        val parts=chatThinkingParts("先想A再想B",listOf(ChatThinkingBreak(3,"bad"),ChatThinkingBreak(5,"ok")),merged)!!
        assertEquals(listOf("先想A再想","ok","B"),parts.map{when(it){is ChatThinkingPart.Text->it.text;is ChatThinkingPart.Tool->it.item.id}})
    }
}