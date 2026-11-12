package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ChatToolActivityTest {
    private fun start(name:String,args:String="{}")=ChatToolJournal.start(ChatToolCall("x",name,args),1,"")
    private fun label(name:String,args:String="{}",result:Map<String,Any>?=null):String{
        val item=start(name,args)
        return (if(result==null)item else ChatToolJournal.result(item,result)).label()
    }
    /** A staged accounting receipt is not a success until this turn really commits it. */
    @Test fun stagedWriteOnlySucceedsAfterTheTurnReallyCommitsIt(){
        val record=ChatToolJournal.result(start(CompanionRecord.NAME,"{}"),mapOf("success" to true,"recorded" to true,"status" to "staged_until_reply_commit"))
        assertEquals("staged",record.state)
        assertEquals("回话成功后保存",record.detail)
        assertEquals("success",ChatToolJournal.settleRows(listOf(record),1,true,true,false).single().state)
        assertEquals("failed",ChatToolJournal.settleRows(listOf(record),1,true,false,false).single().state)
        assertEquals("failed",ChatToolJournal.settleRows(listOf(record),1,false,true,false).single().state)
        val dropped=ChatToolJournal.settleRows(listOf(record),1,true,false,false).single()
        assertEquals("没记下",dropped.label());assertEquals("根据后续操作，这条没有保存",dropped.detail)
        val failedTurn=ChatToolJournal.settleRows(listOf(record),1,false,true,false).single()
        assertEquals("回话没完成，这条没存下",failedTurn.detail)
        val memory=ChatToolJournal.result(start("agent_memory_update","""{"op":"add"}"""),mapOf("success" to true,"status" to "staged_until_reply_commit"))
        assertEquals("success",ChatToolJournal.settleRows(listOf(memory.copy(memoryId="mem-1")),1,true,false,true).single().state)
        // 只留下待确认候选、没有正式记录时不能报已保存。
        val candidate=ChatToolJournal.settleRows(listOf(memory),1,true,false,true).single()
        assertEquals("failed",candidate.state);assertEquals("没记下",candidate.label())
        assertEquals("这条还在待确认，没有写成记忆",candidate.detail);assertFalse(candidate.canOpenMemory)
        // A control in the same turn discards the staged memory candidates; the capsule says so.
        assertEquals("failed",ChatToolJournal.settleRows(listOf(memory),1,true,false,false).single().state)
        // Cancelled or failed before commit: the same honest "not saved" wording, never a success.
        val memoryCancel=ChatToolJournal.settleRows(listOf(memory),1,false,false,false).single()
        assertEquals("failed",memoryCancel.state);assertEquals("没记下",memoryCancel.label())
        assertEquals("回话没完成，这条没存下",memoryCancel.detail)
        val running=start(CompanionRecord.NAME,"{}")
        assertEquals("unknown",ChatToolJournal.settleRows(listOf(running),1,false,false,false).single().state)
    }
    @Test fun jsonFieldsDoNotLeakAndBodyStreamsBeforeClosingObject(){
        val d=ChatDisplayDecoder()
        assertEquals("",d.append(" {\"experience\":{\"controlIntent\":\"none\"},\"reply\":\""))
        assertEquals("你好",d.append("你好"));assertEquals("呀",d.append("呀\"}"))
    }
    @Test fun naturalToolPreambleStreamsWithoutClaimingToolSuccess(){
        val d=ChatDisplayDecoder();assertEquals("删好",d.append("删好"));assertEquals("了",d.append("了"))
        val pending=start(JointControl.NAME,"""{"action":"forget"}""")
        assertEquals("running",pending.state);assertEquals("遗忘",pending.label())
        val failed=ChatToolJournal.result(pending,mapOf("success" to false,"error" to "invalid_arguments"))
        assertEquals("failed",failed.state);assertEquals("遗忘",failed.label())
        assertEquals("success",ChatToolJournal.result(pending,mapOf("success" to true)).state)
    }
    /** The four agreed phrases, verbatim: 2.1 in 工具胶囊与协议C-设计-20261106. */
    @Test fun humanPhrasesFollowTheAgreedTable(){
        assertEquals("让我想一想",label("agent_memory_read","""{"query":"生日"}"""))
        assertEquals("让我想一想",label("agent_recall_search","""{"query":"生日"}"""))
        // 回想出结果后换成“想起来了”；进行中仍是“让我想一想”。
        assertEquals("想起来了",label("agent_recall_search","""{"query":"生日"}""",mapOf("status" to "ok","results" to listOf(mapOf("id" to "1")))))
        // 搜聊天与读原话 share one phrase.
        assertEquals("让我查一下聊天记录",label("agent_history_search","""{"terms":["生日"]}"""))
        assertEquals("让我查一下聊天记录",label("agent_history_read","""{"message_id":"1"}"""))
        assertEquals("嗯…我记住了",label("agent_memory_update","""{"op":"add"}"""))
        assertEquals("让我想想以前",label(RelationshipRecall.NAME))
    }
    /** Failure replaces the whole text in the same pill; it is never glued onto the phrase. */
    @Test fun failureReplacesThePhraseInsteadOfAppendingToIt(){
        val readFailed=label("agent_recall_search","""{"query":"生日"}""",mapOf("status" to "error"))
        assertEquals("没想起来",readFailed);assertFalse(readFailed.contains("让我想一想"))
        assertEquals("没想起来",label("agent_history_read","{}",mapOf("status" to "context_full")))
        assertEquals("没想起来",label(RelationshipRecall.NAME,"{}",mapOf("status" to "quota_exhausted")))
        val writeFailed=label("agent_memory_update","""{"op":"add"}""",mapOf("success" to false))
        assertEquals("没记下",writeFailed);assertFalse(writeFailed.contains("嗯…我记住了"))
    }
    @Test fun runningAndSuccessKeepTheSamePhrase(){
        val running=start("agent_memory_update","""{"op":"add"}""")
        assertEquals("嗯…我记住了",running.label())
        assertEquals("嗯…我记住了",ChatToolJournal.result(running,mapOf("success" to true,"status" to "staged_until_reply_commit")).label())
        val read=start("agent_recall_search","""{"query":"生日"}""")
        assertEquals("让我想一想",read.label())
        assertEquals("想起来了",ChatToolJournal.result(read,mapOf("success" to true,"results" to listOf(mapOf("id" to "1")))).label())
    }
    /** 回想成功（含命中但已在本轮参考资料里被去重）说“想起来了”；真没命中只留一颗“没想起来”。 */
    @Test fun recallFoundSaysRememberedAndRealMissKeepsOneCleanCapsule(){
        val running=start("agent_memory_read","""{"query":"意面"}""")
        assertEquals("让我想一想",running.label())
        val found=ChatToolJournal.result(running,mapOf("status" to "already_in_context","results" to listOf(mapOf("content" to "tool-e6cb…：用户喜欢吃意大利面（已在上方参考资料中）"))))
        assertEquals("success",found.state);assertEquals("想起来了",found.label());assertEquals("",found.detail)
        val missing=ChatToolJournal.result(running,mapOf("status" to "empty","results" to emptyList<Any>()))
        assertEquals("failed",missing.state);assertEquals("没想起来",missing.label());assertEquals("没有额外小字","",missing.detail)
        // 其它读工具保持原来的说法。
        val history=ChatToolJournal.result(start("agent_history_search","""{"terms":["x"]}"""),mapOf("success" to true,"messages" to listOf(mapOf("message_id" to "1"))))
        assertEquals("让我查一下聊天记录",history.label())
    }
    @Test fun malformedJsonAndModelSuccessWordsCannotCreateAReceipt(){
        assertEquals("",ChatDisplayDecoder().append("{\"controlStatus\":\"applied\",bad"))
        val item=ChatToolActivity("x",1,"遗忘","unknown",before="删除成功")
        assertTrue(item.label().contains("未确认"))
    }
    @Test fun relationRecallErrorIsFailureNotSuccess(){
        val item=start(RelationshipRecall.NAME)
        for(status in listOf("error","quota_exhausted","context_full","disabled")){
            val result=ChatToolJournal.result(item,mapOf("status" to status))
            assertEquals(status,"failed",result.state);assertEquals("没想起来",result.label())
        }
        assertEquals("success",ChatToolJournal.result(item,mapOf("status" to "ok","events" to listOf(mapOf("消息" to 3)))).state)
    }
    @Test fun emptyReadsAreNotFoundNotSuccess(){
        val recall=start("agent_recall_search","""{"query":"生日"}""")
        val none=ChatToolJournal.result(recall,mapOf("success" to true,"results" to emptyList<Any>(),"more_candidates" to false))
        assertEquals("failed",none.state);assertEquals("没想起来",none.label());assertEquals("真没命中只留一颗，不挂解释小字","",none.detail)
        val history=ChatToolJournal.result(start("agent_history_read","""{"anchor_message_id":"1"}"""),mapOf("success" to true,"messages" to emptyList<Any>()))
        assertEquals("没想起来",history.label())
        // 其它读工具的空读仍保留原来的解释小字，本次只改回想类。
        assertEquals("没找到符合的内容",history.detail)
        val relation=ChatToolJournal.result(start(RelationshipRecall.NAME),mapOf("status" to "ok","events" to emptyList<Any>()))
        assertEquals("没想起来",relation.label())
        // A page with rows stays a success even when more candidates remain.
        val page=ChatToolJournal.result(recall,mapOf("success" to true,"results" to listOf(mapOf("id" to "1")),"more_candidates" to true))
        assertEquals("success",page.state);assertEquals("想起来了",page.label())
        // A page blocked by the output budget is a capacity boundary, not a match.
        val blocked=ChatToolJournal.result(recall,mapOf("success" to true,"results" to emptyList<Any>(),"page_blocked_by_capacity" to true))
        assertEquals("failed",blocked.state);assertEquals("没想起来",blocked.label());assertEquals("这一页超出容量，没取到内容",blocked.detail)
        // Write receipts never turn into "not found" because of an unrelated list field.
        val write=ChatToolJournal.result(start("agent_memory_update","""{"op":"add"}"""),mapOf("success" to true,"results" to emptyList<Any>()))
        assertEquals("success",write.state);assertEquals("嗯…我记住了",write.label())
    }
    /** The stage digest is what the reply transaction later maps to the row that was really written. */
    @Test fun aStagedWriteKeepsTheOperationItsCommitWillLookUp(){
        val item=start("agent_memory_update","""{"op":"add"}""")
        val staged=ChatToolJournal.result(item,mapOf("success" to true,"status" to "staged_until_reply_commit","operation" to "digest-1"))
        assertEquals("staged",staged.state);assertEquals("digest-1",staged.operation)
        assertNull("暂存时还没有可读的记录",staged.memoryId)
        val settled=ChatToolJournal.settleRows(listOf(staged.copy(memoryId="mem-1")),1,true,false,true).single()
        assertEquals("success",settled.state);assertEquals("mem-1",settled.memoryId);assertEquals("digest-1",settled.operation)
    }
    /** Opening is allowed only for a committed write that points at a stored row. */
    @Test fun onlyACommittedLinkedWriteCanBeOpened(){
        val base=ChatToolActivity("x",1,"嗯…我记住了","success",kind=ChatToolActivity.WRITE,tool="agent_memory_update",memoryId="mem-1")
        assertTrue(base.canOpenMemory)
        assertFalse("暂存不能点",base.copy(state="staged").canOpenMemory)
        assertFalse("失败不能点",base.copy(state="failed").canOpenMemory)
        assertFalse("取消结算后不能点",base.copy(state="unknown").canOpenMemory)
        assertFalse("没有关联记录不能点",base.copy(memoryId=null).canOpenMemory)
        // A failed receipt that somehow kept a link still must not open.
        assertFalse(base.copy(state="failed").canOpenMemory)
        // A settled failure for a staged write drops the link so the pill stays shut and shows 没记下.
        val staged=ChatToolJournal.result(start("agent_memory_update","""{"op":"add"}"""),mapOf("success" to true,"status" to "staged_until_reply_commit","operation" to "d"))
        assertEquals("没记下",ChatToolJournal.settleRows(listOf(staged),1,false,false,false).single().label())
    }
    /** What the opened card shows: the current row, or an honest "deleted" when it is gone. */
    @Test fun theOpenedCardShowsTheCurrentRowOrSaysItWasDeleted(){
        val current=StoredMemory("mem-1","a",MemoryCategory.STATE_FACT,"用户叫小周。",manual=true,version=3)
        assertEquals("用户叫小周。",peekText(current))
        assertEquals("这条记忆已删除",peekText(null))
        // An edited row is read as it is now, not as the tool arguments once said.
        assertEquals("用户改名叫小周了。",peekText(current.copy(text="用户改名叫小周了。",version=4)))
    }
    private fun peekText(saved:StoredMemory?)=when(val peek=memoryPeekOf(saved)){is ChatMemoryPeek.Saved->peek.text;ChatMemoryPeek.Deleted->"这条记忆已删除";else->"?"}
}
