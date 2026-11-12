package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** 旧轮次工具胶囊进备份：导出时落定、格式校验、恢复时跟着记忆换编号。 */
class ChatToolBackupTest {
    private fun row(id:String,state:String,memoryId:String?=null,tool:String="agent_memory_update")=
        ChatToolActivity(id,1,"嗯…我记住了",state,"先写的一句",kind=ChatToolActivity.WRITE,tool=tool,reasoningAt=12,reasoningKnown=true,operation="op-$id",memoryId=memoryId)
    private fun json(vararg rows:ChatToolActivity)=Gson().toJson(rows.toList())
    private fun rejected(raw:String,memoryIds:Set<String> = setOf("memory-a"))=
        assertTrue(raw,runCatching{ChatToolJournal.validateBackup(raw,memoryIds)}.isFailure)

    @Test fun exportSettlesUnfinishedReceiptsAndDropsLinksOutsideTheExport(){
        val raw=json(row("ok","success","memory-a"),row("gone","success","memory-deleted"),row("run","running"),row("stage","staged"))
        val out=ChatToolJournal.forBackup(raw,setOf("memory-a"))
        val rows=JsonParser.parseString(out).asJsonArray.map{it.asJsonObject}
        assertEquals(listOf("success","success","unknown","failed"),rows.map{it["state"].asString})
        assertEquals("memory-a",rows[0]["memoryId"].asString)
        assertFalse("指向导出里没有的记忆就不再能点开",rows[1].has("memoryId"))
        assertEquals("先写的一句",rows[0]["before"].asString);assertEquals(12,rows[0]["reasoningAt"].asInt);assertTrue(rows[0]["reasoningKnown"].asBoolean)
        ChatToolJournal.validateBackup(out,setOf("memory-a"))
    }
    @Test fun emptyAndOrdinaryReceiptsPass(){
        ChatToolJournal.validateBackup("[]",emptySet())
        ChatToolJournal.validateBackup(json(row("a","failed"),row("b","unknown",tool=RelationshipRecall.NAME)),emptySet())
        ChatToolJournal.validateBackup("""[{"id":"x","attempt":2,"title":"让我想一想","state":"success"}]""",emptySet())
    }
    @Test fun malformedReceiptsAreRejected(){
        rejected("{}");rejected("not json");rejected("[1]")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"success","extra":1}]""")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"running"}]""")
        rejected("""[{"attempt":1,"title":"t","state":"success"}]""")
        rejected("""[{"id":" ","attempt":1,"title":"t","state":"success"}]""")
        rejected("""[{"id":"x","attempt":"1","title":"t","state":"success"}]""")
        rejected("""[{"id":"x","attempt":1.5,"title":"t","state":"success"}]""")
        rejected("""[{"id":"x","attempt":-1,"title":"t","state":"success"}]""")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"success","kind":"delete"}]""")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"success","tool":"bad tool"}]""")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"success","reasoningKnown":"yes"}]""")
        rejected("""[{"id":"x","attempt":1,"title":"t","state":"success","before":"${"长".repeat(16001)}"}]""")
        rejected(json(row("x","success"),row("x","failed")))
        rejected(json(row("x","success","memory-other")))
        rejected(Gson().toJson((1..201).map{row("r$it","failed")}),emptySet())
    }
    @Test fun restoreFollowsTheNewMemoryIdsAndDropsLinksWithoutARow(){
        val raw=json(row("a","success","memory-a"),row("b","success","memory-b"),row("c","failed"))
        val out=JsonParser.parseString(ChatToolJournal.remapBackup(raw,mapOf("memory-a" to "new-a"))).asJsonArray.map{it.asJsonObject}
        assertEquals("new-a",out[0]["memoryId"].asString);assertFalse(out[1].has("memoryId"));assertFalse(out[2].has("memoryId"))
        assertEquals(listOf("a","b","c"),out.map{it["id"].asString});assertEquals("op-a",out[0]["operation"].asString)
    }
}