package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallBudgetTest {
    private val gson = Gson()
    private var saved: String? = null
    private fun guard(rounds: Int = 3, tokens: Int = 3000) = CompanionRecallBudget(
        { CompanionRecallSettings(rounds, tokens) }, { saved }, { saved = it })
    private fun original(body: String, offset: Int = 0, text: String = body.substring(offset)) = mapOf<String, Any>(
        "message_id" to "m101", "source_revision" to stableDigest(body),
        "text_offset" to offset, "content" to text, "truncated" to (offset + text.length < body.length))
    private fun history(row: Map<String, Any>) = mapOf<String, Any>("success" to true, "messages" to listOf(row))
    private fun tool(result: Map<String, Any>) = mapOf<String, Any>("role" to "tool", "name" to "agent_history_read", "content" to gson.toJson(result))
    private suspend fun prepare(g: CompanionRecallBudget, messages: List<Map<String, Any>> = emptyList(), actor: String = "main") =
        g.prepare(actor, messages, buildAgentRecallToolDefinitions(), 32000, 4000)
    private fun state() = gson.fromJson(saved, CompanionRecallUsage::class.java)

    private fun systemMessage():Map<String,Any> = mapOf("role" to "system","content" to CompanionRecallBudget.QUERY_RULES)

    @Test fun requestKeepsRulesAtTheFrontAndLeavesPlayerMessageLast() = runBlocking {
        val messages = listOf(systemMessage(), mapOf("role" to "assistant", "content" to "你好"),
            mapOf("role" to "user", "content" to "今天一起散步吗"))
        val g = guard()
        val first = prepare(g, messages)
        val again = prepare(g, first.messages)
        assertEquals(messages, first.messages)
        assertEquals(messages, again.messages)
        assertEquals(1, again.messages.count { it["role"] == "system" })
        assertEquals("今天一起散步吗", again.messages.last()["content"])
        assertFalse(gson.toJson(again.messages).contains("剩余"))
        assertEquals(0, state().rounds)
        assertEquals(0, state().tokens)
    }

    @Test fun guardDoesNotCreateAMessageWhenNoSystemPromptIsPresent() = runBlocking {
        val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "你好"))
        assertEquals(messages, prepare(guard(), messages).messages)
        assertTrue(prepare(guard()).messages.isEmpty())
    }

    @Test fun multimodalPlayerContentIsPreservedWithoutInternalSuffix() = runBlocking {
        val player = mapOf<String, Any>("role" to "user", "content" to listOf(
            mapOf("type" to "text", "text" to "你看这张图"),
            mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/png;base64,c2FtcGxl"))))
        val result = prepare(guard(), listOf(systemMessage(), player))
        assertEquals(player, result.messages.last())
        assertEquals(2, result.messages.size)
    }

    @Test fun sourceMetadataIsRemovedWithoutChangingTheOriginalText() = runBlocking {
        val player = mapOf<String, Any>("role" to "user", "content" to "原话正文",
            CompanionRecallBudget.SOURCE_META to mapOf("id" to "m101",
                "revision" to stableDigest("原话正文")))
        val result = prepare(guard(), listOf(systemMessage(), player))
        assertEquals(player - CompanionRecallBudget.SOURCE_META, result.messages.last())
        assertFalse(gson.toJson(result.messages).contains(CompanionRecallBudget.SOURCE_META))
    }

    @Test fun exhaustedBudgetKeepsToolResultsLastAndOnlyRemovesRecallTools() = runBlocking {
        val g = guard(rounds = 1)
        prepare(g)
        val read = g.read("main") { history(original("散步的原话")) }
        val messages = listOf(systemMessage(), mapOf("role" to "user", "content" to "那天说过什么"),
            mapOf("role" to "assistant", "tool_calls" to listOf(
                mapOf("function" to mapOf("name" to "agent_history_read")),
                mapOf("function" to mapOf("name" to "web_search")))), tool(read))
        val otherTool = mapOf<String, Any>("type" to "function", "function" to mapOf("name" to "web_search"))
        val used = state().tokens
        val result = g.prepare("main", messages, buildAgentRecallToolDefinitions() + otherTool, 32000, 4000)
        assertEquals(messages, result.messages)
        assertEquals(listOf(otherTool), result.tools)
        assertFalse(result.mustAnswer)
        assertEquals(1, state().rounds)
        assertEquals(used, state().tokens)
        var fetched = false
        assertEquals(true, g.read("main") { fetched = true; history(original("不应再读")) }["recall_stopped"])
        assertFalse(fetched)
    }

    @Test fun sameBatchCountsOnceAndResumeDoesNotReset() = runBlocking {
        val g = guard(rounds = 1)
        prepare(g)
        repeat(2) { g.read("main") { history(original("同一段原话")) } }
        assertEquals(1, state().rounds)
        val resumed = guard(rounds = 100)
        prepare(resumed)
        var read = false
        val result = resumed.read("main") { read = true; history(original("其他原话")) }
        assertFalse(read)
        assertEquals(true, result["recall_stopped"])
    }

    @Test fun evictedBodyCanBeReadAgainButSpentTokensStay() = runBlocking {
        val g = guard()
        prepare(g)
        val first = g.read("main") { history(original("记住星灯小盒")) }
        val used = state().tokens
        prepare(g, listOf(tool(first)))
        val duplicate = g.read("main") { history(original("记住星灯小盒")) }
        assertTrue((duplicate["messages"] as List<*>).isEmpty())
        prepare(g) // 最终请求已移除工具正文，不能继续按“读过”去重。
        val reread = g.read("main") { history(original("记住星灯小盒")) }
        assertEquals(1, (reread["messages"] as List<*>).size)
        assertTrue(state().tokens > used)
    }

    @Test fun overlappingRawRangesReturnOnlyMissingText() = runBlocking {
        val g = guard()
        val full = "abcdefghij"
        prepare(g, listOf(tool(history(original(full, 2, "cdef")))))
        val answer = g.read("main") { history(original(full)) }
        val rows = answer["messages"] as List<*>
        assertEquals(listOf("ab", "ghij"), rows.map { (it as Map<*, *>)["content"] })
        assertEquals(listOf(0, 6), rows.map { ((it as Map<*, *>)["text_offset"] as Number).toInt() })
    }

    @Test fun editedSourceAndIdOnlyReferencesNeverSuppressBody() = runBlocking {
        val g = guard()
        prepare(g, listOf(tool(history(original("旧正文")))))
        assertEquals(1, (g.read("main") { history(original("已编辑正文")) }["messages"] as List<*>).size)
        prepare(g, listOf(tool(mapOf("messages" to listOf(mapOf("message_id" to "m101"))))))
        assertEquals(1, (g.read("main") { history(original("旧正文")) }["messages"] as List<*>).size)
    }

    @Test fun actualAutomaticOriginalIsVisibleWithoutChargingInjection() = runBlocking {
        val g = guard()
        prepare(g, listOf(mapOf("role" to "user", "content" to "原话正文",
            CompanionRecallBudget.SOURCE_META to mapOf("id" to "m101", "revision" to stableDigest("原话正文")))))
        assertEquals(0, state().tokens)
        assertEquals(0, state().rounds)
        assertTrue((g.read("main") { history(original("原话正文")) }["messages"] as List<*>).isEmpty())
    }

    @Test fun parallelActorsShareOnePersistentAllowance() = runBlocking {
        val first = guard(tokens = 600)
        val second = guard(tokens = 600)
        prepare(first, actor = "main"); prepare(second, actor = "child")
        val outputs = listOf(async { first.read("main") { history(original("原话".repeat(2000))) } },
            async { second.read("child") { history(original("另一原话".repeat(2000))) } }).awaitAll()
        assertTrue(state().tokens <= 600)
        assertEquals(1, outputs.count { it["recall_stopped"] == true })
        assertTrue(outputs.any { it["budget_limited"] == true })
    }

    @Test fun fullContextStopsAdditionalReadBeforeCallingDatabase() = runBlocking {
        val g = guard()
        g.prepare("main", emptyList(), emptyList(), 500, 400)
        val result = g.read("main") { error("不应访问原话") }
        assertEquals(true, result["recall_stopped"])
    }

    @Test fun settingsAreFrozenBeforeFirstRead() = runBlocking {
        val initial = guard(rounds = 1)
        prepare(initial)
        val changed = guard(rounds = 10)
        prepare(changed)
        assertEquals(1, state().settings.rounds)
        assertEquals(0, state().rounds)
    }

    @Test fun clippedMemoryOnlySuppressesTheVisiblePrefix() = runBlocking {
        val body = "前半段后半段"
        val visible = mapOf<String, Any>("id" to "fact1", "content" to "前半段",
            "content_revision" to stableDigest(body), "text_offset" to 0)
        val g = guard()
        prepare(g, listOf(tool(mapOf("memories" to listOf(visible)))))
        val result = g.read("main") { mapOf("memories" to listOf(mapOf("id" to "fact1", "content" to body))) }
        val row = (result["memories"] as List<*>).single() as Map<*, *>
        assertEquals("后半段", row["content"])
        assertEquals(3, row["text_offset"])
    }

    @Test fun pureRecallEndsWithANoToolAnswerAtTheLimit() = runBlocking {
        val g = guard(rounds = 1)
        prepare(g)
        g.read("main") { history(original("原话")) }
        val response = g.prepare("main", listOf(mapOf("role" to "assistant", "tool_calls" to
            listOf(mapOf("function" to mapOf("name" to "agent_history_read"))))), buildAgentRecallToolDefinitions(), 32000, 4000)
        assertTrue(response.mustAnswer)
        assertTrue(response.tools.isEmpty())
    }
}
