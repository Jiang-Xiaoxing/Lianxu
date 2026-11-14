package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecallDisplayOriginTest {
    @Test fun cachedMemoryPagesKeepTheSelectedVersionEvenWhenPrefixesMatch()=runBlocking{
        var version=1
        val reader=object:AgentRecallReader{
            override suspend fun isAvailable(sessionId:String)=true
            override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int)=listOf(AgentRecallCandidate("m","companion_memory","合成","共同前缀".repeat(200)+version,displayVersion=version,displayMode="full"))
            override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int)=emptyList<AgentHistoryMessage>()
            override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int)=emptyList<AgentHistoryMessage>()
        }
        val handler=AgentRecallToolHandler("a",reader)
        val first=handler.execute("agent_recall_search",mapOf("query" to "first"));assertEquals(1,handler.displayOrigins.single().version)
        version=2
        val second=handler.execute("agent_recall_search",mapOf("query" to "second"));assertEquals(2,handler.displayOrigins.single().version)
        handler.execute("agent_recall_search",mapOf("query" to "first"));assertEquals(1,handler.displayOrigins.single().version)
        assertEquals(first["results"],second["results"])
        assertFalse(Gson().toJson(second).contains("displayVersion"));assertFalse(Gson().toJson(second).contains("displayMode"))
    }
    @Test fun cachedHistoryPagesKeepTheirSourceRevisionOutsideTheWirePayload()=runBlocking{
        var version=1
        val reader=object:AgentRecallReader{
            override suspend fun isAvailable(sessionId:String)=true
            override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int)=emptyList<AgentRecallCandidate>()
            override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int)=listOf(AgentHistoryMessage("12","user","旧原话","2026-11-15",version))
            override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int)=emptyList<AgentHistoryMessage>()
        }
        val handler=AgentRecallToolHandler("a",reader)
        handler.execute("agent_history_search",mapOf("query" to "first"));assertEquals(1,handler.displayOrigins.single().version)
        version=3
        handler.execute("agent_history_search",mapOf("query" to "second"));assertEquals(3,handler.displayOrigins.single().version)
        val cached=handler.execute("agent_history_search",mapOf("query" to "first"));assertEquals(1,handler.displayOrigins.single().version)
        assertFalse(Gson().toJson(cached).contains("displayVersion"))
    }
}
