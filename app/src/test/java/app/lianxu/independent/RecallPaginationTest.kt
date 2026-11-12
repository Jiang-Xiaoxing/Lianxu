package app.lianxu.independent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecallPaginationTest {
    private class Reader:AgentRecallReader {
        var available=true;var recallCalls=0;var historyCalls=0
        override suspend fun isAvailable(sessionId:String)=available&&sessionId=="a"
        override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int):List<AgentRecallCandidate>{
            recallCalls++;return (1..65).map{AgentRecallCandidate("m$it","companion_memory","面试","面试 $it",sourceSessionId="a")}.take(limit)
        }
        override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int):List<AgentHistoryMessage>{
            historyCalls++;return (1..65).map{AgentHistoryMessage("$it","user","面试 $it","2026-11-02")}.take(limit)
        }
        override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int)=emptyList<AgentHistoryMessage>()
    }
    @Test fun allSixtyFiveResultsCanBeVisitedWithoutRepeatingFirstPage()=runBlocking {
        for(tool in listOf("agent_recall_search","agent_history_search")){
            val reader=Reader();val handler=AgentRecallToolHandler("a",reader,true);var offset=0;val ids=mutableListOf<String>()
            do {
                val page=handler.execute(tool,mapOf("query" to "面试","limit" to 8,"offset" to offset))
                val rows=page["results"] as List<*>
                ids+=rows.map{r->val m=r as Map<*,*>;(m["id"]?:m["message_id"]).toString()}
                val next=(page["next_offset"] as Number).toInt();assertTrue(next>offset);offset=next
            }while(page["more_candidates"]==true)
            assertEquals(65,ids.size);assertEquals(65,ids.distinct().size);assertEquals(1,reader.recallCalls+reader.historyCalls)
        }
    }
    @Test fun pagePermissionIsRecheckedEvenForCachedRows()=runBlocking {
        val reader=Reader();val handler=AgentRecallToolHandler("a",reader,true)
        assertEquals(true,handler.execute("agent_recall_search",mapOf("query" to "面试"))["success"])
        reader.available=false
        assertEquals(false,handler.execute("agent_recall_search",mapOf("query" to "面试","offset" to 5))["success"])
    }
    @Test fun endOfSearchDoesNotClaimMoreCandidates()=runBlocking {
        val handler=AgentRecallToolHandler("a",Reader(),true)
        val page=handler.execute("agent_history_search",mapOf("query" to "面试","offset" to 65))
        assertEquals(false,page["more_candidates"]);assertTrue((page["results"] as List<*>).isEmpty())
    }
}
