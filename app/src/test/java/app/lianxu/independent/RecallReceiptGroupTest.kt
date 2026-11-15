package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RecallReceiptGroupTest {
    private fun row(id:String,group:String?="response",state:String="success",detail:String="",attempt:Int=1,tool:String="agent_memory_read")=
        ChatToolActivity(id,attempt,"让我想一想",state,detail=detail,kind=ChatToolActivity.READ,tool=tool,reasoningAt=10,reasoningKnown=true,recallGroup=group)
    @Test fun oneResponseHasOneGroupWithBothActualReceipts(){
        val rows=listOf(row("a"),row("b",tool="agent_recall_search"))
        val map=recallReceiptGroups(rows)
        assertSame(map["a"],map["b"]);assertEquals(rows,map.getValue("a").items)
        assertEquals("想起来了",map.getValue("a").label());assertTrue(map.getValue("a").succeeded)
    }
    @Test fun separateResponsesAtTheSameThinkingOffsetNeverMerge(){
        val groups=recallReceiptGroups(listOf(row("a","one"),row("b","two")))
        assertNotEquals(groups["a"],groups["b"])
    }
    @Test fun attemptsAreIsolatedEvenIfAnImportedGroupMarkerRepeats(){
        val groups=recallReceiptGroups(listOf(row("a",attempt=1),row("b",attempt=2)))
        assertNotEquals(groups["a"],groups["b"])
    }
    @Test fun oldReceiptsWithoutAGroupAreNotGuessedTogether(){
        val groups=recallReceiptGroups(listOf(row("a",null),row("b",null)))
        assertNotEquals(groups["a"],groups["b"])
    }
    @Test fun otherToolsNeverJoinARecallGroup(){
        val groups=recallReceiptGroups(listOf(row("hidden",state=ChatToolActivity.SUPERSEDED),row("a"),row("write",tool="agent_memory_update"),row("history",tool="agent_history_read")))
        assertEquals(setOf("a"),groups.keys)
    }
    @Test fun actualResultMappingKeepsDeduplicatedHitsSuccessfulAndMissesSeparateFromErrors(){
        val pending=row("a",state="running")
        val found=ChatToolJournal.result(pending,mapOf("success" to true,"status" to "already_in_context","results" to listOf(mapOf("content" to "已在上方参考资料中"))))
        val miss=ChatToolJournal.result(pending,mapOf("success" to true,"results" to emptyList<Any>()))
        val error=ChatToolJournal.result(pending,mapOf("success" to false,"error" to "invalid_arguments"))
        assertEquals(RecallDisplayOutcome.FOUND,found.recallOutcome())
        assertEquals(RecallDisplayOutcome.NOT_FOUND,miss.recallOutcome())
        assertEquals(RecallDisplayOutcome.ERROR,error.recallOutcome())
        assertEquals("response",found.recallGroup)
    }
    @Test fun aCollapsedMixedGroupExplicitlyShowsFailureAndKeepsEachResult(){
        val rows=listOf(row("found"),row("miss",state="failed"),row("error",state="failed",detail="操作未完成"))
        val group=recallReceiptGroups(rows).getValue("found")
        assertEquals("想起来了 · 部分失败",group.label());assertTrue(group.hasFailure);assertFalse(group.succeeded)
        assertEquals(listOf("想起来了","没找到","回想出错"),group.items.map{it.recallOutcome().label})
        assertEquals(rows,group.items)
    }
    @Test fun unfinishedAndUnconfirmedResultsCannotClaimWholeGroupSuccess(){
        val running=RecallReceiptGroup(listOf(row("a"),row("b",state="running"),row("c",state="failed")))
        assertEquals("让我想一想 · 已有失败",running.label());assertFalse(running.succeeded)
        val unknown=RecallReceiptGroup(listOf(row("a"),row("b",state="unknown")))
        assertTrue(unknown.label().contains("未确认"));assertFalse(unknown.succeeded)
    }
    @Test fun groupMetadataSurvivesBackupAndOldReceiptsRemainValid(){
        val raw=Gson().toJson(listOf(row("a"),row("b",state="failed")))
        val backup=ChatToolJournal.forBackup(raw,emptySet())
        ChatToolJournal.validateBackup(backup,emptySet())
        val rows=JsonParser.parseString(backup).asJsonArray
        assertEquals("response",rows[0].asJsonObject["recallGroup"].asString)
        ChatToolJournal.validateBackup("""[{"id":"old","attempt":1,"title":"让我想一想","state":"failed"}]""",emptySet())
    }
    @Test fun startAddsOnlyDisplayMetadataToRecallTools(){
        val call=ChatToolCall("call","agent_memory_read","""{"query":"合成"}""")
        val before=call.copy()
        val item=ChatToolJournal.start(call,2,"原话","batch")
        assertEquals("batch",item.recallGroup);assertEquals("running",item.state)
        assertEquals(before,call)
        assertEquals("batch",ChatToolJournal.start(call.copy(name="agent_history_search"),2,"","batch").recallGroup)
        assertNull(ChatToolJournal.start(call.copy(name="agent_history_read"),2,"","batch").recallGroup)
    }
}
