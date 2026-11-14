package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** The incident's raw wire shape, with synthetic values only; no rewritten tool result. */
class HistoryDedupReceiptTest {
    @Suppress("UNCHECKED_CAST")
    private fun payload()=Gson().fromJson("""{"success":true,"status":"found","visible_duplicates":8,"results":[]}""",Map::class.java) as Map<String,Any>
    private fun pending(tool:String="agent_history_search",group:String="response")=
        ChatToolJournal.start(ChatToolCall("synthetic",tool,"""{"query":"合成查询"}"""),1,"",group)
    @Test fun originalFoundEightDuplicatesAndEmptyResultsIsSuccessfulWithoutMutatingThePayload(){
        val raw=payload();val before=Gson().toJson(raw)
        val shown=ChatToolJournal.result(pending(),raw)
        assertEquals("success",shown.state);assertEquals("想起来了",shown.label())
        assertEquals(RecallDisplayOutcome.FOUND,shown.recallOutcome());assertEquals("",shown.detail)
        assertEquals(before,Gson().toJson(raw));assertTrue((raw["results"] as List<*>).isEmpty())
    }
    @Test fun historyAndMemorySearchesInOneResponseShareOneSuccessfulGroup(){
        val rows=listOf(
            ChatToolJournal.result(pending(),payload()),
            ChatToolJournal.result(pending("agent_memory_read"),mapOf("success" to true,"results" to listOf(mapOf("id" to "synthetic-memory")))),
            ChatToolJournal.result(pending("agent_recall_search"),mapOf("success" to true,"status" to "already_in_context","results" to listOf(mapOf("in_reference" to true))))
        )
        val groups=recallReceiptGroups(rows)
        assertEquals(1,groups.values.distinct().size)
        val group=groups.getValue(rows.first().id)
        assertEquals(rows,group.items);assertTrue(group.succeeded);assertFalse(group.hasFailure)
        assertEquals("想起来了",group.label())
    }
    @Test fun anActualEmptyResultAndAZeroDuplicateCountRemainNotFound(){
        for(raw in listOf(mapOf("success" to true,"status" to "empty","results" to emptyList<Any>()),payload()+("visible_duplicates" to 0))){
            val shown=ChatToolJournal.result(pending(),raw)
            assertEquals("failed",shown.state);assertEquals(RecallDisplayOutcome.NOT_FOUND,shown.recallOutcome())
        }
    }
    @Test fun explicitErrorsAndCapacityBoundariesOverrideDuplicateHits(){
        for(extra in listOf(mapOf("success" to false),mapOf("error" to "invalid_arguments"),mapOf("status" to "error"),mapOf("page_blocked_by_capacity" to true))){
            val shown=ChatToolJournal.result(pending(),payload()+extra)
            assertEquals("failed",shown.state);assertEquals(RecallDisplayOutcome.ERROR,shown.recallOutcome())
        }
    }
    @Test fun mixedHistoryHitMemoryMissAndErrorCannotHideFailure(){
        val rows=listOf(ChatToolJournal.result(pending(),payload()),
            ChatToolJournal.result(pending("agent_memory_read"),mapOf("success" to true,"results" to emptyList<Any>())),
            ChatToolJournal.result(pending("agent_recall_search"),mapOf("success" to false,"error" to "invalid_arguments")))
        val group=recallReceiptGroups(rows).getValue(rows.first().id)
        assertTrue(group.hasFailure);assertFalse(group.succeeded)
        assertEquals("想起来了 · 部分失败",group.label())
        assertEquals(listOf("想起来了","没找到","回想出错"),group.items.map{it.recallOutcome().label})
    }
    @Test fun laterResponsesAndUnrelatedToolsDoNotJoinThisHistoryGroup(){
        val first=ChatToolJournal.result(pending(),payload());val later=ChatToolJournal.result(pending(group="later-response"),payload())
        val unrelated=listOf("agent_history_read",RelationshipRecall.NAME,"agent_memory_update").map{pending(it)}
        val groups=recallReceiptGroups(listOf(first,later)+unrelated)
        assertNotEquals(groups[first.id],groups[later.id]);assertEquals(setOf(first.id,later.id),groups.keys)
        assertTrue(unrelated.all{it.recallGroup==null})
    }
    @Test fun oldUngroupedHistoryMissKeepsItsRecordedMeaningWithoutInventingAHit(){
        val old=ChatToolActivity("legacy",1,"让我查一下聊天记录","failed",detail="没找到符合的内容",kind=ChatToolActivity.READ,tool="agent_history_search")
        assertEquals(RecallDisplayOutcome.NOT_FOUND,old.recallOutcome())
        val group=recallReceiptGroups(listOf(old)).getValue(old.id)
        assertEquals("没找到",group.label());assertFalse(group.succeeded);assertEquals(old,group.items.single())
        assertEquals(RecallDisplayOutcome.ERROR,old.copy(detail="操作未完成，角色会根据结果继续回应").recallOutcome())
    }
}
